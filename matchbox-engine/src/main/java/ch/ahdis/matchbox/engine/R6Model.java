package ch.ahdis.matchbox.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hl7.fhir.convertors.factory.VersionConvertorFactory_50_N;
import org.hl7.fhir.exceptions.FHIRException;

/**
 * Helpers between the HAPI FHIR R5 model (org.hl7.fhir.r5.model), which the server uses for its R5 endpoints, and the
 * versionless R6 model (org.hl7.fhir.model) that the validator and the worker context use since org.hl7.fhir.core 7.
 */
public final class R6Model {

	/**
	 * The packages of the versionless model: the core resources, and the add-on resources (e.g. StructureMap is defined
	 * by the FHIR mapping language IG, org.hl7.fhir.model.fml).
	 */
	private static final List<String> PACKAGES = List.of("org.hl7.fhir.model.core", "org.hl7.fhir.model.fml",
																			"org.hl7.fhir.model.tools", "org.hl7.fhir.model.testing",
																			"org.hl7.fhir.model.api");

	private static final Map<String, Class<? extends org.hl7.fhir.model.core.Resource>> CLASSES = new ConcurrentHashMap<>();

	private R6Model() {
	}

	/**
	 * Returns the class of the versionless model for the resource type of a HAPI R5 resource class, e.g.
	 * org.hl7.fhir.model.fml.StructureMap for org.hl7.fhir.r5.model.StructureMap.
	 */
	public static Class<? extends org.hl7.fhir.model.core.Resource> classFor(final Class<? extends org.hl7.fhir.r5.model.Resource> r5Class) {
		return classFor(r5Class.getSimpleName());
	}

	/**
	 * Returns the class of the versionless model for a resource type, e.g. org.hl7.fhir.model.core.ValueSet for
	 * ValueSet.
	 */
	public static Class<? extends org.hl7.fhir.model.core.Resource> classFor(final String resourceType) {
		return CLASSES.computeIfAbsent(resourceType, type -> {
			for (final String pkg : PACKAGES) {
				try {
					final Class<?> c = Class.forName(pkg + "." + type);
					if (org.hl7.fhir.model.core.Resource.class.isAssignableFrom(c)) {
						return c.asSubclass(org.hl7.fhir.model.core.Resource.class);
					}
				} catch (final ClassNotFoundException e) {
					// try the next package
				}
			}
			throw new FHIRException("No versionless model class for the resource type " + type);
		});
	}

	/**
	 * Converts a resource of the versionless model to the HAPI R5 model.
	 */
	public static org.hl7.fhir.r5.model.Resource toR5(final org.hl7.fhir.model.core.Resource resource) {
		return VersionConvertorFactory_50_N.convertResource(resource);
	}

	/**
	 * Converts a resource of the HAPI R5 model to the versionless model.
	 */
	public static org.hl7.fhir.model.core.Resource fromR5(final org.hl7.fhir.r5.model.Resource resource) {
		return VersionConvertorFactory_50_N.convertResource(resource);
	}
}
