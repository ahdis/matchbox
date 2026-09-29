package ch.ahdis.matchbox.engine.packages;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.hl7.fhir.convertors.factory.VersionConvertorFactory_30_50;
import org.hl7.fhir.convertors.factory.VersionConvertorFactory_40_50;
import org.hl7.fhir.convertors.factory.VersionConvertorFactory_43_50;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r5.model.Resource;

/**
 * Parses a resource of a package, i.e. a JSON file of the package index, into an R5 resource.
 * <p>
 * Like core's {@code IgLoader.loadResourceByVersion()}, but without its file name checks: it rejects the files ending
 * with {@code template.json}, which are IG Publisher templates in the IG sources it's meant for, but ordinary
 * resources in a package, e.g. the profile {@code sdc-questionnaire-extr-template} of {@code hl7.fhir.uv.sdc} (#610).
 * The core validator doesn't use it for packages either, see {@code SimpleWorkerContext.loadFromPackage()}.
 *
 * @see <a href="https://github.com/ahdis/matchbox/issues/610">#610</a>
 */
public final class PackageResourceParser {

	private PackageResourceParser() {
	}

	/**
	 * Parses the JSON content of a package resource with the parser of the FHIR version of the package, and converts it
	 * to R5.
	 *
	 * @param fhirVersion the FHIR version of the package, e.g. 4.0.1
	 * @param content     the JSON file content
	 */
	public static Resource parseJson(final String fhirVersion, final byte[] content) throws IOException, FHIRException {
		if (fhirVersion == null) {
			throw new FHIRException("Unknown FHIR version of the package");
		}
		if (fhirVersion.startsWith("3.0")) {
			return VersionConvertorFactory_30_50.convertResource(
				new org.hl7.fhir.dstu3.formats.JsonParser().parse(new ByteArrayInputStream(content)));
		}
		if (fhirVersion.startsWith("4.0")) {
			return VersionConvertorFactory_40_50.convertResource(
				new org.hl7.fhir.r4.formats.JsonParser().parse(new ByteArrayInputStream(content)));
		}
		if (fhirVersion.startsWith("4.3")) {
			return VersionConvertorFactory_43_50.convertResource(
				new org.hl7.fhir.r4b.formats.JsonParser().parse(new ByteArrayInputStream(content)));
		}
		if (fhirVersion.startsWith("5.0")) {
			return new org.hl7.fhir.r5.formats.JsonParser().parse(new ByteArrayInputStream(content));
		}
		throw new FHIRException("Unsupported FHIR version " + fhirVersion + " of the package");
	}
}
