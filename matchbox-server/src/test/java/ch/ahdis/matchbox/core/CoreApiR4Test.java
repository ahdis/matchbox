package ch.ahdis.matchbox.core;

import ca.uhn.fhir.jpa.starter.Application;
import ch.ahdis.matchbox.test.CompareUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the validation API of the HL7 validator, {@code POST /core/validate}
 * (https://github.com/ahdis/matchbox/issues/702).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(classes = {Application.class})
@ActiveProfiles({"tests", "test-r4"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class CoreApiR4Test {

	private static final String VALIDATE_URL = "http://localhost:8081/matchboxv3/core/validate";

	private static final String PATIENT = """
		{
			"resourceType": "Patient",
			"id": "example",
			"text": {
				"status": "generated",
				"div": "<div xmlns=\\"http://www.w3.org/1999/xhtml\\">42 </div>"
			}
		}""";

	private static final String PRACTITIONER = """
		<Practitioner xmlns="http://hl7.org/fhir">
			<identifier>
				<system value="urn:oid:2.51.1.3"/>
				<value value="7610000050719"/>
			</identifier>
		</Practitioner>""";

	private static final String PRACTITIONER_WITHOUT_IDENTIFIER = """
		<Practitioner xmlns="http://hl7.org/fhir">
			<active value="true"/>
		</Practitioner>""";

	private static final String TEST_IG = "matchbox.health.test.ig.r4";
	private static final String TEST_IG_PROFILE =
		"http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required";

	private final Gson gson = new Gson();
	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	@Value("${hapi.fhir.implementationguides.matchbox_health_test_ig_r4.version}")
	private String matchboxTestIgVersion;

	@BeforeAll
	void waitUntilStartup() throws Exception {
		Thread.sleep(10000); // give the server some time to start up
		CompareUtil.logMemory();
	}

	@Test
	void validatePatient() throws Exception {
		final JsonObject response = this.validate(null, file("patient.json", PATIENT, "json"));
		assertNotNull(response.get("sessionId").getAsString());

		final JsonArray outcomes = response.getAsJsonArray("outcomes");
		assertEquals(1, outcomes.size());
		final JsonObject fileInfo = outcomes.get(0).getAsJsonObject().getAsJsonObject("fileInfo");
		assertEquals("patient.json", fileInfo.get("fileName").getAsString());
		assertEquals(PATIENT, fileInfo.get("fileContent").getAsString());
		assertEquals("json", fileInfo.get("fileType").getAsString());
		assertTrue(errors(response, 0).isEmpty(), errors(response, 0).toString());
	}

	/**
	 * The request is the one of the HL7 validator: the implementation guide and the profile in 'cliContext'.
	 */
	@Test
	void validateAgainstProfileOfIg() throws Exception {
		final var context = Map.<String, Object>of("sv", "4.0.1",
																 "igs", List.of(this.testIg()),
																 "profiles", List.of(TEST_IG_PROFILE),
																 "locale", "en");
		JsonObject response = this.validate(Map.of("cliContext", context), file("practitioner.xml", PRACTITIONER, "xml"));
		assertTrue(errors(response, 0).isEmpty(), errors(response, 0).toString());

		response = this.validate(Map.of("cliContext", context),
										 file("practitioner.xml", PRACTITIONER_WITHOUT_IDENTIFIER, "xml"));
		final List<JsonObject> errors = errors(response, 0);
		assertEquals(1, errors.size(), errors.toString());
		assertEquals("ERROR", errors.getFirst().get("level").getAsString());
		assertTrue(errors.getFirst().get("message").getAsString().contains("Practitioner.identifier"),
					  errors.getFirst().toString());
		assertTrue(errors.getFirst().get("location").getAsString().startsWith("Practitioner"));
	}

	/**
	 * The implementation guide can be given as 'ig' or 'igs', with or without the version, in 'cliContext' or
	 * 'validationContext': it is always the same validation engine, and it is the one that is selected for a profile
	 * of the implementation guide when no implementation guide is given, as for $validate.
	 */
	@Test
	void sameIgSameSession() throws Exception {
		final Map<String, Object> file = file("practitioner.xml", PRACTITIONER, null);
		final String sessionId = this.validate(
			Map.of("validationContext", Map.of("profiles", List.of(TEST_IG_PROFILE))), file).get("sessionId").getAsString();

		final List<Map<String, Object>> contexts = List.of(
			Map.of("validationContext", Map.of("igs", List.of(this.testIg()), "profiles", List.of(TEST_IG_PROFILE))),
			Map.of("validationContext", Map.of("ig", this.testIg(), "profiles", List.of(TEST_IG_PROFILE))),
			Map.of("validationContext", Map.of("igs", List.of(TEST_IG), "profile", TEST_IG_PROFILE)),
			Map.of("cliContext", Map.of("ig", List.of(TEST_IG), "igs", List.of(this.testIg(), "hl7.fhir.r4.core#4.0.1"))),
			Map.of("validationEngineParameters", Map.of("igs", List.of(this.testIg())),
					 "instanceValidatorParameters", Map.of("profiles", List.of(TEST_IG_PROFILE))),
			// a parameter that matchbox doesn't have and the session id of the request don't change the engine
			Map.of("validationContext", Map.of("igs", List.of(this.testIg()), "showTimes", true),
					 "sessionId", "32fc25cf-020e-4492-ace5-03fe904d22e0"));
		for (final Map<String, Object> context : contexts) {
			final JsonObject response = this.validate(context, file);
			assertEquals(sessionId, response.get("sessionId").getAsString(), context.toString());
			assertTrue(errors(response, 0).isEmpty(), errors(response, 0).toString());
		}

		// another validation parameter is another engine
		final JsonObject response = this.validate(
			Map.of("validationContext", Map.of("igs", List.of(this.testIg()), "hintAboutNonMustSupport", true)), file);
		assertNotEquals(sessionId, response.get("sessionId").getAsString());
	}

	/**
	 * Without a profile in the request, the first meta.profile of the resource is the profile, then the base profile
	 * of its resource type, as for the other validation APIs.
	 */
	@Test
	void validateSeveralFiles() throws Exception {
		final String withMetaProfile = """
			<Practitioner xmlns="http://hl7.org/fhir">
				<meta>
					<profile value="%s"/>
				</meta>
				<active value="true"/>
			</Practitioner>""".formatted(TEST_IG_PROFILE);

		final JsonObject response = this.validate(null,
																file("patient.json", PATIENT, "json"),
																file("practitioner.xml", PRACTITIONER_WITHOUT_IDENTIFIER, "xml"),
																file("practitioner-meta.xml", withMetaProfile, null),
																file("text.txt", "not a resource", null));
		assertEquals(4, response.getAsJsonArray("outcomes").size());
		assertTrue(errors(response, 0).isEmpty(), errors(response, 0).toString());
		assertTrue(errors(response, 1).isEmpty(), errors(response, 1).toString());
		assertEquals(1, errors(response, 2).size(), errors(response, 2).toString());
		assertEquals("ERROR", errors(response, 2).getFirst().get("level").getAsString());
		assertEquals(1, errors(response, 3).size(), errors(response, 3).toString());
		assertEquals("FATAL", errors(response, 3).getFirst().get("level").getAsString());
	}

	@Test
	void unknownProfileIsAFatalIssue() throws Exception {
		final String profile = "http://example.org/StructureDefinition/unknown";
		final JsonObject response = this.validate(Map.of("validationContext", Map.of("profiles", List.of(profile))),
																file("patient.json", PATIENT, "json"));
		final List<JsonObject> errors = errors(response, 0);
		assertEquals(1, errors.size(), errors.toString());
		assertEquals("FATAL", errors.getFirst().get("level").getAsString());
		assertTrue(errors.getFirst().get("message").getAsString().contains(profile));
	}

	/**
	 * An implementation guide that is not installed is an error, and is not installed: the server of the tests is
	 * read-only. See {@code LoadIgOnDemandR4Test} for a server that is not.
	 */
	@Test
	void igNotInstalled() throws Exception {
		for (final String ig : List.of("hl7.fhir.us.core#6.1.0", TEST_IG + "#0.0.1", "hl7.fhir.us.core")) {
			final HttpResponse<String> response = this.post(this.gson.toJson(
				request(Map.of("validationContext", Map.of("igs", List.of(this.testIg(), ig))),
						  file("patient.json", PATIENT, "json"))));
			assertEquals(400, response.statusCode());
			assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
			assertEquals("The package %s is not installed and the server is read-only, add it to the configuration of matchbox"
								 .formatted(ig), response.body());
		}
	}

	@Test
	void invalidRequests() throws Exception {
		HttpResponse<String> response = this.post("{\"cliContext\": {}}");
		assertEquals(400, response.statusCode());
		assertEquals("No files for validation provided in request.", response.body());

		response = this.post("{\"filesToValidate\": [{\"fileName\": \"patient.json\"}]}");
		assertEquals(400, response.statusCode());
		assertEquals("Improperly formatted file content!", response.body());

		response = this.post("{\"filesToValidate\": ");
		assertEquals(400, response.statusCode());
		assertTrue(response.body().startsWith("Malformed JSON"), response.body());

		final Map<String, Object> file = file("patient.json", PATIENT, "json");
		response = this.post(this.gson.toJson(request(Map.of("validationContext", Map.of("sv", "3.0.2")), file)));
		assertEquals(400, response.statusCode());
		assertTrue(response.body().contains("3.0.2"), response.body());

		response = this.post(this.gson.toJson(request(
			Map.of("validationContext", Map.of("profiles", List.of(TEST_IG_PROFILE, "http://example.org/other"))), file)));
		assertEquals(400, response.statusCode());
		assertTrue(response.body().contains("one profile"), response.body());

		response = this.post(this.gson.toJson(request(
			Map.of("validationContext", Map.of("bestPracticeLevel", "Sometimes")), file)));
		assertEquals(400, response.statusCode());
		assertTrue(response.body().contains("Sometimes"), response.body());
	}

	private String testIg() {
		return TEST_IG + "#" + this.matchboxTestIgVersion;
	}

	private static Map<String, Object> file(final String name, final String content, final String type) {
		final Map<String, Object> file = new LinkedHashMap<>();
		file.put("fileName", name);
		file.put("fileContent", content);
		if (type != null) {
			file.put("fileType", type);
		}
		return file;
	}

	@SafeVarargs
	private static Map<String, Object> request(final Map<String, Object> properties, final Map<String, Object>... files) {
		final Map<String, Object> request = new LinkedHashMap<>();
		if (properties != null) {
			request.putAll(properties);
		}
		request.put("filesToValidate", List.of(files));
		return request;
	}

	/**
	 * Validates the files, and returns the response.
	 *
	 * @param properties the properties of the request besides 'filesToValidate', or {@code null}.
	 */
	@SafeVarargs
	private JsonObject validate(final Map<String, Object> properties, final Map<String, Object>... files)
		throws IOException, InterruptedException {
		final HttpResponse<String> response = this.post(this.gson.toJson(request(properties, files)));
		assertEquals(200, response.statusCode(), response.body());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private HttpResponse<String> post(final String body) throws IOException, InterruptedException {
		final HttpRequest request = HttpRequest.newBuilder(URI.create(VALIDATE_URL))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Returns the issues of an outcome that are errors or fatal.
	 */
	private static List<JsonObject> errors(final JsonObject response, final int outcome) {
		final List<JsonObject> errors = new ArrayList<>();
		final JsonObject validationOutcome = response.getAsJsonArray("outcomes").get(outcome).getAsJsonObject();
		assertFalse(validationOutcome.get("issues").isJsonNull());
		validationOutcome.getAsJsonArray("issues").forEach(issue -> {
			final String level = issue.getAsJsonObject().get("level").getAsString();
			if (level.equals("ERROR") || level.equals("FATAL")) {
				errors.add(issue.getAsJsonObject());
			}
		});
		return errors;
	}
}
