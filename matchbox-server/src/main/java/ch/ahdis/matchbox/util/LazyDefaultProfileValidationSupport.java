package ch.ahdis.matchbox.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.SearchParameter;
import org.hl7.fhir.utilities.npm.NpmPackage;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import ca.uhn.fhir.util.ClasspathUtil;

/**
 * Replaces the HAPI DefaultProfileValidationSupport, and only creates it when it's first used.
 * <p>
 * For R5, the DefaultProfileValidationSupport constructor loads the complete hl7.fhir.r5.core,
 * hl7.fhir.uv.extensions.r5 and hl7.terminology packages (about 7000 resources, 500 MB) into a static map, where they
 * are kept for the lifetime of the JVM, although matchbox validates with its own engine. The JPA search parameter
 * registry only needs the SearchParameters of the core package, which are read like HAPI does without creating it.
 */
public class LazyDefaultProfileValidationSupport implements IValidationSupport {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LazyDefaultProfileValidationSupport.class);

	/**
	 * The package from which DefaultProfileValidationSupportNpmStrategy takes the SearchParameters for R5 (it skips those
	 * of the extensions and terminology packages).
	 */
	private static final String R5_CORE_PACKAGE = "org/hl7/fhir/r5/packages/hl7.fhir.r5.core-5.0.0.tgz";

	private static volatile List<IBaseResource> r5SearchParameters;

	private final FhirContext fhirContext;

	private volatile DefaultProfileValidationSupport delegate;

	public LazyDefaultProfileValidationSupport(final FhirContext fhirContext) {
		this.fhirContext = fhirContext;
	}

	private DefaultProfileValidationSupport delegate() {
		if (this.delegate == null) {
			synchronized (this) {
				if (this.delegate == null) {
					log.info("Creating the HAPI DefaultProfileValidationSupport for {}", this.fhirContext.getVersion().getVersion());
					this.delegate = new DefaultProfileValidationSupport(this.fhirContext);
				}
			}
		}
		return this.delegate;
	}

	@Override
	public FhirContext getFhirContext() {
		return this.fhirContext;
	}

	@Override
	public String getName() {
		return "LazyDefaultProfileValidationSupport";
	}

	@Override
	public List<IBaseResource> fetchAllConformanceResources() {
		return this.delegate().fetchAllConformanceResources();
	}

	@Override
	public <T extends IBaseResource> List<T> fetchAllStructureDefinitions() {
		return this.delegate().fetchAllStructureDefinitions();
	}

	@Override
	public <T extends IBaseResource> List<T> fetchAllNonBaseStructureDefinitions() {
		return this.delegate().fetchAllNonBaseStructureDefinitions();
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends IBaseResource> List<T> fetchAllSearchParameters() {
		if (this.fhirContext.getVersion().getVersion() != FhirVersionEnum.R5) {
			return this.delegate().fetchAllSearchParameters();
		}
		if (this.delegate != null) {
			return this.delegate.fetchAllSearchParameters();
		}
		if (r5SearchParameters == null) {
			synchronized (LazyDefaultProfileValidationSupport.class) {
				if (r5SearchParameters == null) {
					r5SearchParameters = this.loadR5SearchParameters();
				}
			}
		}
		return (List<T>) new ArrayList<>(r5SearchParameters);
	}

	/**
	 * Parses the SearchParameters of the R5 core package like DefaultProfileValidationSupportNpmStrategy does:
	 * leniently, without the '_in' SearchParameter, and with the package as source.
	 */
	private List<IBaseResource> loadR5SearchParameters() {
		final IParser parser = this.fhirContext.newJsonParser().setParserErrorHandler(new LenientErrorHandler(false));
		final Map<String, IBaseResource> byUrl = new LinkedHashMap<>();
		try (final InputStream stream = ClasspathUtil.loadResourceAsStream(R5_CORE_PACKAGE)) {
			final NpmPackage npm = NpmPackage.fromPackage(stream);
			for (final String file : npm.listResources("SearchParameter")) {
				try (final InputStream resource = npm.loadResource(file)) {
					final SearchParameter searchParameter = parser.parseResource(SearchParameter.class, resource);
					if (!"_in".equals(searchParameter.getCode())) {
						searchParameter.setUserData(DefaultProfileValidationSupport.SOURCE_PACKAGE_ID, "hl7.fhir.r5.core");
						byUrl.put(searchParameter.getUrl(), searchParameter);
					}
				}
			}
		} catch (final IOException e) {
			throw new IllegalStateException("Unable to load the SearchParameters of the R5 core package", e);
		}
		log.info("Loaded {} SearchParameters of the R5 core package", byUrl.size());
		return List.copyOf(byUrl.values());
	}

	@Override
	public IBaseResource fetchCodeSystem(final String theSystem) {
		return this.delegate().fetchCodeSystem(theSystem);
	}

	@Override
	public IBaseResource fetchStructureDefinition(final String theUrl) {
		return this.delegate().fetchStructureDefinition(theUrl);
	}

	@Override
	public IBaseResource fetchValueSet(final String theUrl) {
		return this.delegate().fetchValueSet(theUrl);
	}

	@Override
	public void invalidateCaches() {
		if (this.delegate != null) {
			this.delegate.invalidateCaches();
		}
	}
}
