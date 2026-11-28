package ch.ahdis.matchbox.validation.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.annotation.Nullable;
import org.hl7.fhir.validation.service.model.FileInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A request of the validation API of the HL7 validator ({@code POST /validate} of validator.fhir.org, the
 * {@code ValidationRequest} of org.hl7.fhir.core), read for matchbox.
 * <p>
 * The request is not read into the {@code ValidationContext} of the core library: that class gives every parameter
 * its default value, so the parameters that the client has sent could not be told from the ones it has not, and the
 * defaults of the HL7 validator would replace the configuration of the server. Only the parameters that are in the
 * request are read, and they override the ones of the server, as for the other validation APIs.
 *
 * @param files       the resources to validate.
 * @param parameters  the validation parameters of the request, by matchbox name (the name of the {@code $validate}
 *                    parameter).
 * @param igs         the requested implementation guides ({@code igs} and {@code ig}), as sent.
 * @param profiles    the requested profiles ({@code profiles} and {@code profile}).
 * @param fhirVersion the requested FHIR version ({@code sv}) as {@code 4.0.1}, {@code 4.3.0} or {@code 5.0.0}, or
 *                    {@code null} if the request has none.
 * @param sessionId   the session id of the request, if any.
 */
record CoreValidationRequest(List<FileInfo> files,
									  Map<String, String[]> parameters,
									  List<String> igs,
									  List<String> profiles,
									  @Nullable String fhirVersion,
									  @Nullable String sessionId) {

	static final String NO_FILES_PROVIDED_MESSAGE = "No files for validation provided in request.";
	static final String INVALID_FILE_MESSAGE = "Improperly formatted file content!";

	/**
	 * The objects of the request that hold validation parameters. {@code cliContext} is the former name of
	 * {@code validationContext}; newer clients split the parameters into {@code validationEngineParameters} and
	 * {@code instanceValidatorParameters}. If a parameter is in several of them, the last one wins.
	 */
	private static final List<String> PARAMETER_OBJECTS = List.of("cliContext",
																					  "validationContext",
																					  "validationEngineParameters",
																					  "instanceValidatorParameters");

	/**
	 * The parameters of the HL7 validator whose matchbox name is different.
	 */
	private static final Map<String, String> MATCHBOX_NAMES = Map.of("displayWarnings", "displayIssuesAreWarnings",
																						  "checkIPSCodes", "checkIpsCodes",
																						  "bestPracticeLevel", "bpWarnings");

	/**
	 * Reads a request.
	 *
	 * @throws CoreBadRequestException if the request is not valid.
	 */
	static CoreValidationRequest parse(final @Nullable String body) {
		final JsonObject request;
		try {
			final JsonElement json = (body == null || body.isBlank()) ? null : JsonParser.parseString(body);
			if (json == null || !json.isJsonObject()) {
				throw new CoreBadRequestException("The request is not a JSON object.");
			}
			request = json.getAsJsonObject();
		} catch (final JsonParseException e) {
			throw new CoreBadRequestException("Malformed JSON: " + e.getMessage());
		}

		final Map<String, String[]> parameters = new LinkedHashMap<>();
		final Set<String> igs = new LinkedHashSet<>();
		final Set<String> profiles = new LinkedHashSet<>();
		String fhirVersion = null;
		for (final String name : PARAMETER_OBJECTS) {
			final JsonElement object = request.get(name);
			if (object == null || !object.isJsonObject()) {
				continue;
			}
			for (final Map.Entry<String, JsonElement> entry : object.getAsJsonObject().entrySet()) {
				final String[] values = getValues(entry.getValue());
				if (values.length == 0) {
					continue;
				}
				switch (entry.getKey()) {
					case "igs", "ig" -> igs.addAll(List.of(values));
					case "profiles", "profile" -> profiles.addAll(List.of(values));
					case "sv" -> fhirVersion = getFhirVersion(values[0]);
					// matchbox has the opposite parameter
					case "noEcosystem" ->
						parameters.put("txUseEcosystem", new String[]{String.valueOf(!Boolean.parseBoolean(values[0]))});
					// an enumeration in the HL7 validator, sent by its name: the codes are in lower case
					case "r5BundleRelativeReferencePolicy" ->
						parameters.put(entry.getKey(), new String[]{values[0].toLowerCase(Locale.ROOT)});
					default -> parameters.put(MATCHBOX_NAMES.getOrDefault(entry.getKey(), entry.getKey()), values);
				}
			}
		}

		return new CoreValidationRequest(getFiles(request.get("filesToValidate")),
													parameters,
													List.copyOf(igs),
													List.copyOf(profiles),
													fhirVersion,
													getString(request.get("sessionId")));
	}

	private static List<FileInfo> getFiles(final @Nullable JsonElement filesToValidate) {
		if (filesToValidate == null || !filesToValidate.isJsonArray() || filesToValidate.getAsJsonArray().isEmpty()) {
			throw new CoreBadRequestException(NO_FILES_PROVIDED_MESSAGE);
		}
		final List<FileInfo> files = new ArrayList<>();
		for (final JsonElement file : filesToValidate.getAsJsonArray()) {
			if (!file.isJsonObject()) {
				throw new CoreBadRequestException(INVALID_FILE_MESSAGE);
			}
			final String content = getString(file.getAsJsonObject().get("fileContent"));
			if (content == null || content.isBlank()) {
				throw new CoreBadRequestException(INVALID_FILE_MESSAGE);
			}
			files.add(new FileInfo(getString(file.getAsJsonObject().get("fileName")),
										  content,
										  getString(file.getAsJsonObject().get("fileType"))));
		}
		return files;
	}

	/**
	 * Returns the values of a parameter: the value, or the values of a list. Empty for {@code null}, an object or an
	 * empty list, which are not parameter values.
	 */
	private static String[] getValues(final @Nullable JsonElement element) {
		if (element == null) {
			return new String[0];
		}
		if (element.isJsonPrimitive()) {
			return new String[]{element.getAsString()};
		}
		if (element.isJsonArray()) {
			final JsonArray array = element.getAsJsonArray();
			final List<String> values = new ArrayList<>();
			for (final JsonElement item : array) {
				if (item.isJsonPrimitive()) {
					values.add(item.getAsString());
				}
			}
			return values.toArray(String[]::new);
		}
		return new String[0];
	}

	private static @Nullable String getString(final @Nullable JsonElement element) {
		return (element != null && element.isJsonPrimitive()) ? element.getAsString() : null;
	}

	/**
	 * Returns the FHIR version of the parameter {@code sv}, as the validation parameters have it.
	 *
	 * @throws CoreBadRequestException if matchbox does not support the version.
	 */
	static String getFhirVersion(final String sv) {
		final String version = sv.strip().toLowerCase(Locale.ROOT);
		if (version.equals("r4") || version.equals("4.0") || version.startsWith("4.0.")) {
			return "4.0.1";
		}
		if (version.equals("r4b") || version.equals("4.3") || version.startsWith("4.3.")) {
			return "4.3.0";
		}
		if (version.equals("r5") || version.equals("5.0") || version.startsWith("5.0.")) {
			return "5.0.0";
		}
		throw new CoreBadRequestException(
			"FHIR version '%s' is not supported, expected 4.0.1 (R4), 4.3.0 (R4B) or 5.0.0 (R5).".formatted(sv));
	}

	/**
	 * Thrown when a request is not valid, answered with HTTP 400.
	 */
	static class CoreBadRequestException extends RuntimeException {
		CoreBadRequestException(final String message) {
			super(message);
		}
	}
}
