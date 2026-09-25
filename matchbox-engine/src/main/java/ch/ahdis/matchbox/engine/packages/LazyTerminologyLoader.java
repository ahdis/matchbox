package ch.ahdis.matchbox.engine.packages;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r5.context.IContextResourceLoader;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.context.SimpleWorkerContext.PackageResourceLoader;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.PackageInformation;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.r5.model.ValueSet;
import org.hl7.fhir.r5.terminologies.client.ITerminologyClientFactory;
import org.hl7.fhir.utilities.FileUtilities;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.NpmPackage.PackageResourceInformation;

/**
 * Registers the terminology resources (CodeSystem, ValueSet, NamingSystem, ConceptMap) of a package as
 * {@link CompressedPackageResourceProxy} objects, which are parsed when they're first used (lazy loading).
 * <p>
 * The other resource types, above all the StructureDefinitions, are parsed when the package is loaded: the validator
 * iterates over all StructureDefinitions in many places (e.g. FHIRPathEngine, ContextUtilities.getStructures()), so
 * they would be parsed by the first validation anyway, like the core validator parses them at startup.
 */
public final class LazyTerminologyLoader {

	/**
	 * The resource types that are loaded lazily.
	 */
	public static final Set<String> LAZY_LOADED_RESOURCE_TYPES = Utilities.stringSet("CodeSystem", "ValueSet",
		"NamingSystem", "ConceptMap");

	private static final String PACKAGE_FOLDER_PREFIX = "@package/";

	private LazyTerminologyLoader() {
	}

	/**
	 * Returns the filename of a resource in the 'package' folder of an in-memory package (the folder that
	 * NpmPackage.listResources() lists), or null for a resource in another folder.
	 */
	public static String getPackageFolderFilename(final PackageResourceInformation pri) {
		final String filename = pri.getFilename();
		if (filename == null || !filename.startsWith(PACKAGE_FOLDER_PREFIX)
			|| filename.indexOf('/', PACKAGE_FOLDER_PREFIX.length()) >= 0) {
			return null;
		}
		return filename.substring(PACKAGE_FOLDER_PREFIX.length());
	}

	/**
	 * Returns whether a resource of the package index can be registered as a proxy.
	 */
	public static boolean canLoadLazily(final PackageResourceInformation pri) {
		return LAZY_LOADED_RESOURCE_TYPES.contains(pri.getResourceType()) && pri.hasId() && pri.getUrl() != null;
	}

	/**
	 * Registers a terminology resource as a proxy, and its OIDs.
	 */
	public static void registerProxy(final SimpleWorkerContext context,
												final PackageResourceInformation pri,
												final String url,
												final String filename,
												final byte[] content,
												final CompressedPackageResourceProxy.ResourceParser parser,
												final PackageInformation packageInfo) throws IOException {
		if ("CodeSystem".equals(pri.getResourceType()) || "NamingSystem".equals(pri.getResourceType())) {
			registerOids(context, pri, content);
		}
		context.registerResourceFromPackage(
			new CompressedPackageResourceProxy(pri, url, filename, content, parser, packageInfo), packageInfo);
	}

	/**
	 * Registers the terminology resources of an in-memory package as proxies, the resources are parsed with the given
	 * loader like SimpleWorkerContext does. The package must have been loaded without these resource types, see
	 * {@link #withoutLazyLoadedTypes(IContextResourceLoader)}.
	 *
	 * @param pinner a pinner for the resources of a core package (see CoreVersionPinner), or null
	 * @return the number of registered proxies
	 */
	public static int registerProxies(final SimpleWorkerContext context,
												 final NpmPackage pi,
												 final PackageInformation packageInfo,
												 final IContextResourceLoader loader,
												 final MetadataCoreVersionPinner pinner) throws IOException {
		final CompressedPackageResourceProxy.ResourceParser parser = (bytes, filename) -> {
			final Resource resource = parse(loader, bytes);
			if (pinner != null) {
				if (resource instanceof final CodeSystem cs) {
					pinner.pinCoreVersions(List.of(cs), List.of(), List.of());
				} else if (resource instanceof final ValueSet vs) {
					pinner.pinCoreVersions(List.of(), List.of(vs), List.of());
				}
			}
			return resource;
		};
		int count = 0;
		for (final PackageResourceInformation pri : pi.listIndexedResources(LAZY_LOADED_RESOURCE_TYPES)) {
			final String filename = getPackageFolderFilename(pri);
			if (filename == null || !loader.wantLoad(pi, pri)) {
				continue;
			}
			final byte[] content = FileUtilities.streamToBytes(pi.load("package", filename));
			if (!canLoadLazily(pri)) {
				final Resource resource = parse(loader, content);
				if (resource != null) {
					context.cacheResourceFromPackage(resource, packageInfo);
				}
				continue;
			}
			registerProxy(context, pri, loader.patchUrl(pri.getUrl(), pri.getResourceType()), filename, content, parser,
							  packageInfo);
			++count;
		}
		return count;
	}

	/**
	 * Parses a resource of a package like SimpleWorkerContext.loadFromFileJson() does.
	 */
	private static Resource parse(final IContextResourceLoader loader, final byte[] content) throws IOException {
		final Bundle bundle = loader.loadBundle(new ByteArrayInputStream(content), true);
		if (bundle == null || bundle.getEntry().isEmpty()) {
			return null;
		}
		final Resource resource = bundle.getEntryFirstRep().getResource();
		final String path = loader.getResourcePath(resource);
		if (path != null) {
			resource.setWebPath(path);
		}
		return resource;
	}

	/**
	 * Registers the OIDs of a CodeSystem or NamingSystem that is registered as a proxy. cacheResourceFromPackage() finds
	 * them in the parsed resource, here they're read from the JSON content without parsing the resource.
	 */
	private static void registerOids(final SimpleWorkerContext context,
												final PackageResourceInformation pri,
												final byte[] content) throws IOException {
		final JsonObject json = JsonParser.parseObject(content);
		String url = null;
		final Set<String> oids = new HashSet<>();
		if ("CodeSystem".equals(pri.getResourceType())) {
			url = json.asString("url");
			for (final JsonObject identifier : json.getJsonObjects("identifier")) {
				final String value = identifier.asString("value");
				if (value != null && value.startsWith("urn:oid:")) {
					oids.add(value.substring(8));
				}
			}
		} else if ("codesystem".equals(json.asString("kind"))) {
			for (final JsonObject uniqueId : json.getJsonObjects("uniqueId")) {
				if ("uri".equals(uniqueId.asString("type"))) {
					url = uniqueId.asString("value");
				} else if ("oid".equals(uniqueId.asString("type"))) {
					oids.add(uniqueId.asString("value"));
				}
			}
		}
		if (url != null && !oids.isEmpty()) {
			context.registerOids(pri.getResourceType(), url, json.asString("version"), oids);
		}
	}

	/**
	 * Returns a loader that loads the same resource types as the given one, except the lazily loaded types.
	 */
	public static IContextResourceLoader withoutLazyLoadedTypes(final IContextResourceLoader loader) {
		return new WithoutLazyLoadedTypes(loader);
	}

	private record WithoutLazyLoadedTypes(IContextResourceLoader delegate) implements IContextResourceLoader {

		private static Set<String> withoutLazyLoadedTypes(final Set<String> types) {
			final Set<String> result = new HashSet<>(types);
			result.removeAll(LAZY_LOADED_RESOURCE_TYPES);
			return result;
		}

		@Override
		public Set<String> getTypes() {
			return withoutLazyLoadedTypes(this.delegate.getTypes());
		}

		@Override
		public Set<String> reviewActualTypes(final Set<String> types) {
			return withoutLazyLoadedTypes(this.delegate.reviewActualTypes(types));
		}

		@Override
		public Bundle loadBundle(final InputStream stream, final boolean isJson) throws FHIRException, IOException {
			return this.delegate.loadBundle(stream, isJson);
		}

		@Override
		public Resource loadResource(final InputStream stream, final boolean isJson) throws FHIRException, IOException {
			return this.delegate.loadResource(stream, isJson);
		}

		@Override
		public String getResourcePath(final Resource resource) {
			return this.delegate.getResourcePath(resource);
		}

		@Override
		public IContextResourceLoader getNewLoader(final NpmPackage npm) throws IOException {
			return new WithoutLazyLoadedTypes(this.delegate.getNewLoader(npm));
		}

		@Override
		public List<CodeSystem> getCodeSystems() {
			return this.delegate.getCodeSystems();
		}

		@Override
		public void setPatchUrls(final boolean value) {
			this.delegate.setPatchUrls(value);
		}

		@Override
		public String patchUrl(final String url, final String resourceType) {
			return this.delegate.patchUrl(url, resourceType);
		}

		@Override
		public IContextResourceLoader setLoadProfiles(final boolean value) {
			this.delegate.setLoadProfiles(value);
			return this;
		}

		@Override
		public ITerminologyClientFactory txFactory() {
			return this.delegate.txFactory();
		}

		@Override
		public boolean wantLoad(final NpmPackage pi, final PackageResourceInformation pri) {
			return this.delegate.wantLoad(pi, pri);
		}

		@Override
		public PackageResourceLoader editInfo(final PackageResourceLoader pri) {
			return this.delegate.editInfo(pri);
		}
	}
}
