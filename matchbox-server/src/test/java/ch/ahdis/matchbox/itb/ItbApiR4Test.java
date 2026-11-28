package ch.ahdis.matchbox.itb;

import ca.uhn.fhir.jpa.starter.Application;
import ch.ahdis.matchbox.test.CompareUtil;
import com.gitb.model.core.AnyContent;
import com.gitb.model.ps.ProcessResponse;
import com.gitb.model.tr.ReportItem;
import com.gitb.model.tr.SeverityLevel;
import com.gitb.model.tr.TAR;
import com.gitb.model.tr.TestResultType;
import com.gitb.model.core.TypedParameter;
import com.gitb.model.core.UsageEnumeration;
import com.gitb.model.vs.ValidateRequest;
import com.gitb.model.core.ValueEmbeddingEnumeration;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the ITB (GITB REST) services: the validation service (https://github.com/ahdis/matchbox/issues/589), and
 * the FHIRPath and IG manager processing services (https://github.com/ahdis/matchbox/issues/681).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(classes = {Application.class})
@ActiveProfiles({"tests", "test-r4"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ItbApiR4Test {

	private static final String PATIENT = """
		<Patient xmlns="http://hl7.org/fhir">
			<id value="example"/>
			<text>
				<status value="generated"/>
				<div xmlns="http://www.w3.org/1999/xhtml">42 </div>
			</text>
		</Patient>""";

	private static final String PRACTITIONER = """
		<Practitioner xmlns="http://hl7.org/fhir">
			<identifier>
				<system value="urn:oid:2.51.1.3"/>
				<value value="7610000050719"/>
			</identifier>
		</Practitioner>""";

	private static final String PATIENT_PROFILE = "http://hl7.org/fhir/StructureDefinition/Patient";
	private static final String BUNDLE_PROFILE = "http://hl7.org/fhir/StructureDefinition/Bundle";

	private final ItbClient client = new ItbClient("http://localhost:8081/matchboxv3/itb/fhir/");
	private final ItbClient fhirPathClient = new ItbClient("http://localhost:8081/matchboxv3/itb/fhirPath/");
	private final ItbClient igManagerClient = new ItbClient("http://localhost:8081/matchboxv3/itb/igManager/");

	@Value("${hapi.fhir.implementationguides.matchbox_health_test_ig_r4.version}")
	private String matchboxTestIgVersion;

	@BeforeAll
	void waitUntilStartup() throws Exception {
		Thread.sleep(10000); // give the server some time to start up
		CompareUtil.logMemory();
	}

	@Test
	void testModuleDefinition() throws Exception {
		final var module = this.client.getModuleDefinition().getModule();
		assertEquals("FHIRValidator", module.getId());
		assertEquals("matchbox", module.getMetadata().getName());
		assertTrue(module.getServiceLocation().endsWith("/matchboxv3/itb/fhir"));

		final List<String> names = module.getInputs().stream().map(TypedParameter::getName).toList();
		assertEquals("contentToValidate", names.getFirst());
		assertEquals(UsageEnumeration.R, module.getInputs().getFirst().getUse());
		for (final String name : List.of("contentType", "profiles", "failOn", "includeContentInReport", "bpWarnings",
													"resourceIdRule", "displayWarnings", "txServer", "suppressErrors", "ig")) {
			assertTrue(names.contains(name), "Missing input " + name);
		}
		assertEquals(names.size(), Set.copyOf(names).size(), "Duplicate inputs");
		module.getInputs().stream().skip(1).forEach(input -> assertEquals(UsageEnumeration.O, input.getUse()));
	}

	@Test
	void validatePatient() throws Exception {
		final TAR tar = this.client.validate(PATIENT, Map.of("profiles", PATIENT_PROFILE));
		assertEquals(TestResultType.SUCCESS, tar.getResult());
		assertEquals(0, tar.getCounters().getNrOfErrors());
		assertEquals(0, tar.getCounters().getNrOfWarnings());
		assertEquals(PATIENT_PROFILE + "|4.0.1", tar.getOverview().getProfileID());
		assertEquals("matchbox", tar.getOverview().getValidationServiceName());
		assertNotNull(tar.getId());
		assertNotNull(tar.getDate());

		assertEquals("0", getContext(tar, "errorCount").getValue());
		assertEquals("0", getContext(tar, "warningCount").getValue());
		assertEquals("0", getContext(tar, "informationCount").getValue());
		assertEquals("information", getContext(tar, "severity").getValue());
		assertFalse(getContext(tar, "errorCount").isForDisplay());

		// How the validation was done, shown in the ITB step report
		final var validation = getContext(tar, "validation");
		assertEquals("map", validation.getType());
		assertEquals(PATIENT_PROFILE + "|4.0.1", getItem(validation, "profile").getValue());
		assertTrue(getItem(validation, "packages").getItem().stream()
						  .anyMatch(item -> item.getValue().startsWith("hl7.fhir.r4.core#4.0.1")));
		assertNotNull(getItem(getItem(validation, "parameters"), "txServer").getValue());

		final var operationOutcome = getContext(tar, "operationOutcome");
		assertEquals("application/fhir+json", operationOutcome.getMimeType());
		final var oo = this.client.readTree(operationOutcome.getValue());
		assertEquals("OperationOutcome", oo.get("resourceType").asText());
		assertTrue(operationOutcome.getValue().contains("No fatal or error issues detected"));

		final var content = getContext(tar, "content");
		assertEquals(PATIENT, content.getValue());
		assertEquals("application/fhir+xml", content.getMimeType());
	}

	@Test
	void validateWithoutProfileUsesBaseProfile() throws Exception {
		TAR tar = this.client.validate(PATIENT, Map.of());
		assertEquals(TestResultType.SUCCESS, tar.getResult());
		assertEquals(PATIENT_PROFILE + "|4.0.1", tar.getOverview().getProfileID());

		tar = this.client.validate("{\"resourceType\": \"Patient\", \"active\": true}", Map.of());
		assertEquals(TestResultType.SUCCESS, tar.getResult());
		assertEquals(PATIENT_PROFILE + "|4.0.1", tar.getOverview().getProfileID());
		assertEquals("application/fhir+json", getContext(tar, "content").getMimeType());
	}

	/**
	 * Without the input 'profiles', the first meta.profile of the resource is the profile, as for the type-level
	 * $validate.
	 */
	@Test
	void validateWithoutProfileUsesMetaProfile() throws Exception {
		final String profile = "http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required";
		final String practitioner = """
			<Practitioner xmlns="http://hl7.org/fhir">
				<meta>
					<profile value="%s"/>
				</meta>
				<identifier>
					<system value="urn:oid:2.51.1.3"/>
					<value value="7610000050719"/>
				</identifier>
			</Practitioner>""".formatted(profile);

		TAR tar = this.client.validate(practitioner, Map.of());
		assertEquals(TestResultType.SUCCESS, tar.getResult());
		assertTrue(tar.getOverview().getProfileID().startsWith(profile + "|"), tar.getOverview().getProfileID());
		assertTrue(getContext(tar, "operationOutcome").getValue()
					  .contains("matchbox.health.test.ig.r4#" + this.matchboxTestIgVersion));

		// The input 'profiles' takes precedence over meta.profile
		tar = this.client.validate(practitioner, Map.of("profiles", "http://hl7.org/fhir/StructureDefinition/Practitioner"));
		assertEquals("http://hl7.org/fhir/StructureDefinition/Practitioner|4.0.1", tar.getOverview().getProfileID());
	}

	@Test
	void validatePatientAgainstBundle() throws Exception {
		TAR tar = this.client.validate(PATIENT, Map.of("profiles", BUNDLE_PROFILE));
		assertEquals(TestResultType.FAILURE, tar.getResult());
		assertEquals(1, tar.getCounters().getNrOfErrors());
		assertEquals("1", getContext(tar, "errorCount").getValue());
		assertEquals("error", getContext(tar, "severity").getValue());
		final ReportItem item = getFirstError(tar);
		assertNotNull(item.getAssertionID());
		assertNotNull(item.getType());
		assertTrue(item.getLocation().matches("content:\\d+:\\d+\\|Patient.*"), item.getLocation());

		// Without the content in the report, the location is the FHIRPath
		tar = this.client.validate(PATIENT, Map.of("profiles", BUNDLE_PROFILE, "includeContentInReport", "false"));
		assertEquals(TestResultType.FAILURE, tar.getResult());
		assertNull(getContextOrNull(tar, "content"));
		assertFalse(getFirstError(tar).getLocation().startsWith("content:"));
	}

	@Test
	// https://gazelle.ihe.net/jira/browse/EHS-431
	void validateEhs431() throws Exception {
		final TAR tar = this.client.validate(getContent("ehs-431.json"), Map.of("profiles", BUNDLE_PROFILE));
		assertEquals(TestResultType.FAILURE, tar.getResult());
		assertEquals(1, tar.getCounters().getNrOfErrors());
	}

	@Test
	// https://gazelle.ihe.net/jira/browse/EHS-419, the content has a UTF-8 BOM that gives a warning
	void validateEhs419AndFailOn() throws Exception {
		final String content = getContent("ehs-419.json");
		TAR tar = this.client.validate(content, Map.of("profiles", PATIENT_PROFILE));
		assertEquals(0, tar.getCounters().getNrOfErrors());
		assertTrue(tar.getCounters().getNrOfWarnings() > 0);
		assertEquals(TestResultType.WARNING, tar.getResult());
		assertEquals("warning", getContext(tar, "severity").getValue());

		tar = this.client.validate(content, Map.of("profiles", PATIENT_PROFILE, "failOn", "warning"));
		assertEquals(TestResultType.FAILURE, tar.getResult());

		tar = this.client.validate(content, Map.of("profiles", PATIENT_PROFILE, "failOn", "information"));
		assertEquals(TestResultType.FAILURE, tar.getResult());
	}

	@Test
	void validateWithIgVersion() throws Exception {
		final String profile = "http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required";

		TAR tar = this.client.validate(PRACTITIONER, Map.of("profiles", profile));
		assertEquals(TestResultType.SUCCESS, tar.getResult());
		assertTrue(getContext(tar, "operationOutcome").getValue()
					  .contains("matchbox.health.test.ig.r4#" + this.matchboxTestIgVersion));

		tar = this.client.validate(PRACTITIONER, Map.of("profiles", profile + "|" + this.matchboxTestIgVersion));
		assertEquals(TestResultType.SUCCESS, tar.getResult());
		assertTrue(getContext(tar, "operationOutcome").getValue()
					  .contains("matchbox.health.test.ig.r4#" + this.matchboxTestIgVersion));

		tar = this.client.validate(PRACTITIONER, Map.of("profiles", profile + "|9.9.9"));
		assertEquals(TestResultType.FAILURE, tar.getResult());
	}

	@Test
	void validateUnknownProfile() throws Exception {
		final TAR tar = this.client.validate(PATIENT, Map.of("profiles", "http://example.org/StructureDefinition/unknown"));
		assertEquals(TestResultType.FAILURE, tar.getResult());
		assertEquals(1, tar.getCounters().getNrOfErrors());
		assertEquals("1", getContext(tar, "errorCount").getValue());
	}

	@Test
	void validateBase64() throws Exception {
		final var request = ItbClient.request(AnyContent.builder()
							 .withName("contentToValidate")
							 .withValue(Base64.getEncoder().encodeToString(PATIENT.getBytes(StandardCharsets.UTF_8)))
							 .withEmbeddingMethod(ValueEmbeddingEnumeration.BASE_64).build(),
			ItbClient.input("profiles", PATIENT_PROFILE));
		final TAR tar = this.client.validate(request);
		assertEquals(TestResultType.SUCCESS, tar.getResult());
		assertEquals(PATIENT, getContext(tar, "content").getValue());
	}

	@Test
	void sessionIdInNote() throws Exception {
		final var request = ItbClient.request(ItbClient.input("contentToValidate", PATIENT));
		final var response = this.client.post(request, Map.of("Gitb-Test-Session-Identifier", "session-42"));
		assertEquals(200, response.status());
		assertEquals("session-42", this.client.readTree(response.body()).get("report").get("overview").get("note").asText());
	}

	@Test
	void badRequests() throws Exception {
		assertBadRequest(ItbClient.request(ItbClient.input("profiles", PATIENT_PROFILE)),
							  "Missing required input: contentToValidate");
		assertBadRequest(ItbClient.request(ItbClient.input("contentToValidate", "")),
							  "Required input 'contentToValidate' is present but empty");
		assertBadRequest(ItbClient.request(AnyContent.builder()
																			.withName("contentToValidate")
																			.withValue("http://example.org/Patient/1")
																			.withEmbeddingMethod(ValueEmbeddingEnumeration.URI).build()),
							  "embeddingMethod URI is not supported");
		assertBadRequest(ItbClient.request(AnyContent.builder()
																			.withName("contentToValidate")
																			.withValue("%%% not base64 %%%")
																			.withEmbeddingMethod(ValueEmbeddingEnumeration.BASE_64).build()),
							  "Invalid base64 value");
		assertBadRequest(ItbClient.request(ItbClient.input("contentToValidate", PATIENT),
			ItbClient.input("profiles", PATIENT_PROFILE + "," + BUNDLE_PROFILE)),
							  "Only one profile");
		assertBadRequest(ItbClient.request(ItbClient.input("contentToValidate", PATIENT),
			ItbClient.input("failOn", "fatal")),
							  "Invalid value 'fatal' for input 'failOn'");
		assertBadRequest(ItbClient.request(ItbClient.input("contentToValidate", PATIENT),
			ItbClient.input("contentType", "text/turtle")),
							  "Unsupported contentType");
		assertBadRequest(ItbClient.request(ItbClient.input("contentToValidate", PATIENT),
			ItbClient.input("bpWarnings", "Sometimes")),
							  "Invalid bpWarnings");

		final var response = this.client.post("{not json", Map.of());
		assertEquals(400, response.status());
		assertTrue(this.client.readTree(response.body()).get("error").asText().startsWith("Malformed JSON"));
	}

	@Test
	void fhirPathModuleDefinition() throws Exception {
		final var response = this.fhirPathClient.get("getModuleDefinition");
		assertEquals(200, response.status(), response.body());
		final var module = this.fhirPathClient.readTree(response.body()).get("module");
		assertEquals("FHIRPathProcessor", module.get("id").asText());
		assertTrue(module.get("serviceLocation").asText().endsWith("/matchboxv3/itb/fhirPath"));
		final var operation = module.get("operation").get(0);
		assertEquals("evaluate", operation.get("name").asText());
		assertEquals("content", operation.get("inputs").get(0).get("name").asText());
		assertEquals("expression", operation.get("inputs").get(1).get("name").asText());
		assertEquals("result", operation.get("outputs").get(0).get("name").asText());
	}

	@Test
	void fhirPathEvaluate() throws Exception {
		final String json = """
			{"resourceType": "Patient", "id": "example", "active": true,
			 "name": [{"family": "Muster", "given": ["Anna", "Maria"]}]}""";

		assertEquals("true", this.evaluate("evaluate", json, "Patient.active"));
		assertEquals("true", this.evaluate("evaluate", json, "name.given.count() = 2"));
		assertEquals("false", this.evaluate("evaluate", json, "name.family = 'Other'"));
		assertEquals("Muster", this.evaluate("evaluate", json, "Patient.name.family"));
		assertEquals("2", this.evaluate("evaluate", json, "name.given.count()"));
		// A collection, and an empty one
		assertEquals("Anna,Maria", this.evaluate("evaluate", json, "name.given"));
		assertEquals("", this.evaluate("evaluate", json, "Patient.birthDate"));
		// The operation can be left out, and the content can be XML
		assertEquals("example", this.evaluate(null, PATIENT, "Patient.id"));

		// As the test cases read it, with a JSON pointer
		final var response = this.fhirPathClient.process("evaluate", Map.of("content", json, "expression", "active"));
		final var tree = this.fhirPathClient.readTree(response.body());
		assertEquals("true", tree.at("/output/0/value").asText());
		assertEquals("result", tree.at("/output/0/name").asText());
		assertFalse(tree.has("report"));
	}

	@Test
	void fhirPathEvaluateOperationOutcome() throws Exception {
		// The checks of a test case on the OperationOutcome of a validation
		final TAR tar = this.client.validate(PATIENT, Map.of("profiles", BUNDLE_PROFILE));
		final String operationOutcome = getContext(tar, "operationOutcome").getValue();
		assertEquals("1", this.evaluate("evaluate", operationOutcome,
												  "OperationOutcome.issue.where(severity = 'error' or severity = 'fatal').count()"));
	}

	@Test
	void fhirPathBadRequests() throws Exception {
		assertBadProcessRequest(this.fhirPathClient, "evaluate", Map.of("expression", "Patient.id"),
										"Missing required input: content");
		assertBadProcessRequest(this.fhirPathClient, "evaluate", Map.of("content", PATIENT),
										"Missing required input: expression");
		assertBadProcessRequest(this.fhirPathClient, "evaluate", Map.of("content", PATIENT, "expression", "Patient.id.("),
										"FHIRPath evaluation failed");
		assertBadProcessRequest(this.fhirPathClient, "evaluate", Map.of("content", "{not json", "expression", "id"),
										"FHIRPath evaluation failed");
		assertBadProcessRequest(this.fhirPathClient, "transform", Map.of("content", PATIENT, "expression", "Patient.id"),
										"Unknown operation: transform. Supported: evaluate");

		final var response = this.fhirPathClient.post("process", "{not json", Map.of());
		assertEquals(400, response.status());
		assertTrue(this.fhirPathClient.readTree(response.body()).get("error").asText().startsWith("Malformed JSON"));
	}

	@Test
	void processingTransaction() throws Exception {
		final var begin = this.fhirPathClient.post("beginTransaction", "{}", Map.of());
		assertEquals(200, begin.status(), begin.body());
		assertFalse(this.fhirPathClient.readTree(begin.body()).get("sessionId").asText().isEmpty());
		assertEquals(204, this.fhirPathClient.post("endTransaction", "{}", Map.of()).status());
	}

	@Test
	void igManagerModuleDefinition() throws Exception {
		final var response = this.igManagerClient.get("getModuleDefinition");
		assertEquals(200, response.status(), response.body());
		final var module = this.igManagerClient.readTree(response.body()).get("module");
		assertEquals("IGManager", module.get("id").asText());
		final var operation = module.get("operation").get(0);
		assertEquals("loadIG", operation.get("name").asText());
		assertEquals("ig", operation.get("inputs").get(0).get("name").asText());
		assertEquals("loaded", operation.get("outputs").get(0).get("name").asText());
	}

	@Test
	void igManagerLoadIg() throws Exception {
		// An installed package is confirmed
		final String ig = "matchbox.health.test.ig.r4#" + this.matchboxTestIgVersion;
		final var response = this.igManagerClient.process("loadIG", Map.of("ig", ig));
		final ProcessResponse loaded = this.igManagerClient.readProcessResponse(response);
		assertNull(loaded.getReport());
		assertEquals(1, loaded.getOutput().size());
		assertEquals("loaded", loaded.getOutput().getFirst().getName());
		assertEquals(ig, loaded.getOutput().getFirst().getValue());
		assertEquals(ig, this.igManagerClient.readTree(response.body()).at("/output/0/value").asText());

		// The server of the tests is read-only: a package that is not installed is not installed, the step fails
		final ProcessResponse missing = this.igManagerClient.readProcessResponse(
			this.igManagerClient.process("loadIG", Map.of("ig", "matchbox.health.test.ig.r4#9.9.9")));
		assertTrue(missing.getOutput().isEmpty());
		assertEquals(TestResultType.FAILURE, missing.getReport().getResult());
		assertTrue(getFirstError(missing.getReport()).getDescription().contains("is not installed"));

		assertBadProcessRequest(this.igManagerClient, "loadIG", Map.of(), "Missing required input: ig");
		assertBadProcessRequest(this.igManagerClient, "loadIG", Map.of("ig", "matchbox.health.test.ig.r4"),
										"expected 'id#version'");
		assertBadProcessRequest(this.igManagerClient, "loadIG", Map.of("ig", "../../x#1.0.0"), "expected 'id#version'");
		assertBadProcessRequest(this.igManagerClient, "listIGs", Map.of("ig", ig), "Unknown operation: listIGs");
	}

	private String evaluate(final String operation, final String content, final String expression) throws Exception {
		final ProcessResponse response = this.fhirPathClient.readProcessResponse(
			this.fhirPathClient.process(operation, Map.of("content", content, "expression", expression)));
		assertEquals(1, response.getOutput().size());
		assertEquals("result", response.getOutput().getFirst().getName());
		return response.getOutput().getFirst().getValue();
	}

	private static void assertBadProcessRequest(final ItbClient client,
															  final String operation,
															  final Map<String, String> inputs,
															  final String expectedError) throws Exception {
		final var response = client.process(operation, inputs);
		assertEquals(400, response.status(), response.body());
		final String error = client.readTree(response.body()).get("error").asText();
		assertTrue(error.contains(expectedError), error);
	}

	private void assertBadRequest(final ValidateRequest request, final String expectedError) throws Exception {
		final var response = this.client.post(request);
		assertEquals(400, response.status(), response.body());
		final String error = this.client.readTree(response.body()).get("error").asText();
		assertTrue(error.contains(expectedError), error);
	}

	private static ReportItem getFirstError(final TAR tar) {
		return tar.getItems().stream()
			.filter(item -> item.getLevel() == SeverityLevel.ERROR)
			.findFirst()
			.orElseThrow();
	}

	private static AnyContent getContext(final TAR tar, final String name) {
		final AnyContent item = getContextOrNull(tar, name);
		assertNotNull(item, "Missing context item " + name);
		return item;
	}

	private static AnyContent getContextOrNull(final TAR tar, final String name) {
		assertEquals("map", tar.getContext().getType());
		return tar.getContext().getItem().stream().filter(item -> name.equals(item.getName())).findFirst().orElse(null);
	}

	private static AnyContent getItem(final AnyContent map, final String name) {
		return map.getItem().stream().filter(item -> name.equals(item.getName())).findFirst()
			.orElseThrow(() -> new AssertionError("Missing item " + name));
	}

	private static String getContent(final String resourceName) throws IOException {
		return FileUtils.readFileToString(new ClassPathResource(resourceName).getFile(), StandardCharsets.UTF_8);
	}
}
