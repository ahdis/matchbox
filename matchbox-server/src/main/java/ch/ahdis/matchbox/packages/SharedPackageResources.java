package ch.ahdis.matchbox.packages;

import java.util.ArrayList;
import java.util.List;

import ch.ahdis.matchbox.engine.packages.LazyTerminologyLoader;
import org.hl7.fhir.r5.context.CanonicalResourceManager.CanonicalResourceProxy;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.model.PackageInformation;
import org.hl7.fhir.r5.model.Resource;

/**
 * The conformance resources of a package (id#version) as they were registered in the worker context of a validation
 * engine: the parsed resources and the proxies of the lazily loaded resources. The same objects are registered in the
 * context of every engine that loads the package, see {@link SharedPackageResourcesCache}.
 * <p>
 * The contexts keep this object alive ({@link org.hl7.fhir.r5.context.BaseWorkerContext#retain(Object)}), so it's
 * garbage collected when the last engine that loaded the package is dropped.
 */
public class SharedPackageResources {

	private final String packageId;
	private final PackageInformation packageInformation;
	private final List<Resource> resources = new ArrayList<>();
	private final List<CanonicalResourceProxy> proxies = new ArrayList<>();
	private final List<LazyTerminologyLoader.OidRegistration> oids = new ArrayList<>();

	public SharedPackageResources(final String packageId, final PackageInformation packageInformation) {
		this.packageId = packageId;
		this.packageInformation = packageInformation;
	}

	public String getPackageId() {
		return this.packageId;
	}

	public void addResource(final Resource resource) {
		this.resources.add(resource);
	}

	public void addProxy(final CanonicalResourceProxy proxy) {
		this.proxies.add(proxy);
	}

	public void addOids(final LazyTerminologyLoader.OidRegistration oids) {
		this.oids.add(oids);
	}

	public int size() {
		return this.resources.size() + this.proxies.size();
	}

	/**
	 * Registers the resources in a worker context, like they were registered when the package was loaded.
	 */
	public void registerIn(final SimpleWorkerContext context) {
		context.retain(this);
		for (final LazyTerminologyLoader.OidRegistration o : this.oids) {
			o.registerIn(context);
		}
		for (final Resource resource : this.resources) {
			context.cacheResourceFromPackage(resource, this.packageInformation);
		}
		for (final CanonicalResourceProxy proxy : this.proxies) {
			context.registerResourceFromPackage(proxy, this.packageInformation);
		}
		context.getLoadedPackages().add(this.packageId);
	}
}
