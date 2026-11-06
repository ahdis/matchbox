package ch.ahdis.matchbox.engine.packages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.hl7.fhir.convertors.factory.VersionConvertorFactory_30_N;
import org.hl7.fhir.convertors.factory.VersionConvertorFactory_40_N;
import org.hl7.fhir.convertors.factory.VersionConvertorFactory_43_N;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.model.core.Resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parses a resource of a package, i.e. a JSON file of the package index, into an R5 resource.
 * <p>
 * Like core's {@code IgLoader.loadResourceByVersion()}, but without its file name checks: it rejects the files ending
 * with {@code template.json}, which are IG Publisher templates in the IG sources it's meant for, but ordinary
 * resources in a package, e.g. the profile {@code sdc-questionnaire-extr-template} of {@code hl7.fhir.uv.sdc} (#610).
 * The core validator doesn't use it for packages either, see {@code SimpleWorkerContext.loadFromPackage()}.
 * <p>
 * The narrative ({@code text}) of the resource and of its contained resources isn't parsed: the validation doesn't
 * need it, and the context drops it anyway (#566). Its XHTML is a large part of the parse time of IG profiles and
 * terminology resources (#614).
 *
 * @see <a href="https://github.com/ahdis/matchbox/issues/610">#610</a>
 * @see <a href="https://github.com/ahdis/matchbox/issues/614">#614</a>
 */
public final class PackageResourceParser {

	private PackageResourceParser() {
	}

	/**
	 * Parses the JSON content of a package resource with the parser of the FHIR version of the package, without its
	 * narrative, and converts it to R5.
	 *
	 * @param fhirVersion the FHIR version of the package, e.g. 4.0.1
	 * @param content     the JSON file content
	 */
	public static Resource parseJson(final String fhirVersion, final byte[] content) throws IOException, FHIRException {
		if (fhirVersion == null) {
			throw new FHIRException("Unknown FHIR version of the package");
		}
		if (fhirVersion.startsWith("3.0")) {
			return VersionConvertorFactory_30_N.convertResource(
				new org.hl7.fhir.dstu3.formats.JsonParser().parse(parseWithoutNarrative(content)));
		}
		if (fhirVersion.startsWith("4.0")) {
			return VersionConvertorFactory_40_N.convertResource(
				new org.hl7.fhir.r4.formats.JsonParser().parse(parseWithoutNarrative(content)));
		}
		if (fhirVersion.startsWith("4.3")) {
			return VersionConvertorFactory_43_N.convertResource(
				new org.hl7.fhir.r4b.formats.JsonParser().parse(parseWithoutNarrative(content)));
		}
		if (fhirVersion.startsWith("5.0")) {
			return new org.hl7.fhir.model.core.formats.JsonParser(org.hl7.fhir.model.ModelContext.fullCoreContext()).parse(parseWithoutNarrative(content));
		}
		throw new FHIRException("Unsupported FHIR version " + fhirVersion + " of the package");
	}

	/**
	 * Parses the JSON with Gson, like the R5 parser does by default (the R4 parser uses the slower JsonTrackingParser),
	 * and removes the narrative of the resource and of its contained resources.
	 */
	static JsonObject parseWithoutNarrative(final byte[] content) throws FHIRException {
		final JsonElement json = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
		if (!json.isJsonObject()) {
			throw new FHIRException("The package resource is not a JSON object");
		}
		final JsonObject resource = json.getAsJsonObject();
		removeNarrative(resource);
		return resource;
	}

	private static void removeNarrative(final JsonObject resource) {
		resource.remove("text");
		final JsonElement contained = resource.get("contained");
		if (contained != null && contained.isJsonArray()) {
			for (final JsonElement c : contained.getAsJsonArray()) {
				if (c.isJsonObject()) {
					removeNarrative(c.getAsJsonObject());
				}
			}
		}
	}
}
