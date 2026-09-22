package ch.ahdis.matchbox.itb;

import ca.uhn.fhir.jpa.starter.Application;
import ch.ahdis.matchbox.test.CompareUtil;
import ch.ahdis.matchbox.validation.itb.models.AnyContent;
import ch.ahdis.matchbox.validation.itb.models.ReportItem;
import ch.ahdis.matchbox.validation.itb.models.SeverityLevel;
import ch.ahdis.matchbox.validation.itb.models.TAR;
import ch.ahdis.matchbox.validation.itb.models.TestResultType;
import ch.ahdis.matchbox.validation.itb.models.TypedParameter;
import ch.ahdis.matchbox.validation.itb.models.UsageEnumeration;
import ch.ahdis.matchbox.validation.itb.models.ValidateRequest;
import ch.ahdis.matchbox.validation.itb.models.ValueEmbeddingEnumeration;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * Tests of the ITB (GITB REST) validation service, see https://github.com/ahdis/matchbox/issues/589.
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
		assertFalse(getContext(tar, "errorCount").getForDisplay());

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
		assertTrue(getContext(tar, "operationOutcome").getValue().contains("matchbox.health.test.ig.r4#0.3.0"));

		tar = this.client.validate(PRACTITIONER, Map.of("profiles", profile + "|0.3.0"));
		assertEquals(TestResultType.SUCCESS, tar.getResult());
		assertTrue(getContext(tar, "operationOutcome").getValue().contains("matchbox.health.test.ig.r4#0.3.0"));

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
		final var request = new ValidateRequest()
			.addInput(new AnyContent()
							 .setName("contentToValidate")
							 .setValue(Base64.getEncoder().encodeToString(PATIENT.getBytes(StandardCharsets.UTF_8)))
							 .setEmbeddingMethod(ValueEmbeddingEnumeration.BASE_64))
			.addInput(ItbClient.input("profiles", PATIENT_PROFILE));
		final TAR tar = this.client.validate(request);
		assertEquals(TestResultType.SUCCESS, tar.getResult());
		assertEquals(PATIENT, getContext(tar, "content").getValue());
	}

	@Test
	void sessionIdInNote() throws Exception {
		final var request = new ValidateRequest().addInput(ItbClient.input("contentToValidate", PATIENT));
		final var response = this.client.post(request, Map.of("Gitb-Test-Session-Identifier", "session-42"));
		assertEquals(200, response.status());
		assertEquals("session-42", this.client.readTree(response.body()).get("report").get("overview").get("note").asText());
	}

	@Test
	void badRequests() throws Exception {
		assertBadRequest(new ValidateRequest().addInput(ItbClient.input("profiles", PATIENT_PROFILE)),
							  "Missing required input: contentToValidate");
		assertBadRequest(new ValidateRequest().addInput(ItbClient.input("contentToValidate", "")),
							  "Required input 'contentToValidate' is present but empty");
		assertBadRequest(new ValidateRequest().addInput(new AnyContent()
																			.setName("contentToValidate")
																			.setValue("http://example.org/Patient/1")
																			.setEmbeddingMethod(ValueEmbeddingEnumeration.URI)),
							  "embeddingMethod URI is not supported");
		assertBadRequest(new ValidateRequest().addInput(new AnyContent()
																			.setName("contentToValidate")
																			.setValue("%%% not base64 %%%")
																			.setEmbeddingMethod(ValueEmbeddingEnumeration.BASE_64)),
							  "Invalid base64 value");
		assertBadRequest(new ValidateRequest()
								  .addInput(ItbClient.input("contentToValidate", PATIENT))
								  .addInput(ItbClient.input("profiles", PATIENT_PROFILE + "," + BUNDLE_PROFILE)),
							  "Only one profile");
		assertBadRequest(new ValidateRequest()
								  .addInput(ItbClient.input("contentToValidate", PATIENT))
								  .addInput(ItbClient.input("failOn", "fatal")),
							  "Invalid value 'fatal' for input 'failOn'");
		assertBadRequest(new ValidateRequest()
								  .addInput(ItbClient.input("contentToValidate", PATIENT))
								  .addInput(ItbClient.input("contentType", "text/turtle")),
							  "Unsupported contentType");
		assertBadRequest(new ValidateRequest()
								  .addInput(ItbClient.input("contentToValidate", PATIENT))
								  .addInput(ItbClient.input("bpWarnings", "Sometimes")),
							  "Invalid bpWarnings");

		final var response = this.client.post("{not json", Map.of());
		assertEquals(400, response.status());
		assertTrue(this.client.readTree(response.body()).get("error").asText().startsWith("Malformed JSON"));
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
