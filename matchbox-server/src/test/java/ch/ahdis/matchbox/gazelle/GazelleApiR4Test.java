package ch.ahdis.matchbox.gazelle;

import ca.uhn.fhir.jpa.starter.Application;
import ch.ahdis.matchbox.validation.gazelle.models.validation.SeverityLevel;
import ch.ahdis.matchbox.validation.gazelle.models.validation.ValidationProfile;
import ch.ahdis.matchbox.validation.gazelle.models.validation.ValidationReport;
import ch.ahdis.matchbox.validation.gazelle.models.validation.ValidationTestResult;
import ch.ahdis.matchbox.test.CompareUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * matchbox
 *
 * @author Quentin Ligier
 **/
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(classes = { Application.class })
@ActiveProfiles({"tests", "test-r4"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class GazelleApiR4Test extends AbstractGazelleTest {

	private final GazelleClient client = new GazelleClient("http://localhost:8081/matchboxv3/gazelle/");

	@BeforeAll
	void waitUntilStartup() throws Exception {
		Thread.sleep(10000); // give the server some time to start up
		CompareUtil.logMemory();
	}

	@Test
	void testProfiles() throws Exception {
		final var profiles = this.client.getProfiles();
		assertTrue(profiles.size() > 300);
		final var inputs = profiles.getFirst().getSupportedInputs();
		assertEquals(1, inputs.size());
		assertEquals("contentToValidate", inputs.getFirst().getId());
		assertTrue(inputs.getFirst().isRequired());

		// Every returned profile must have complete, non-blank metadata
		profiles.forEach(profile -> assertTrue(profile.isValid(), "Invalid profile: " + profile.getProfileID()));

		// The list must not contain duplicate profile IDs
		final List<String> profileIds = profiles.stream().map(ValidationProfile::getProfileID).toList();
		assertEquals(profileIds.size(), Set.copyOf(profileIds).size(), "Duplicate profile IDs found");

		// A base FHIR core resource profile must be listed, both version-less (current) and versioned
		assertTrue(profileIds.contains("http://hl7.org/fhir/StructureDefinition/Patient"));
		assertTrue(profileIds.contains("http://hl7.org/fhir/StructureDefinition/Patient|4.0.1"));
		final ValidationProfile patientProfile = profiles.stream()
			.filter(profile -> "http://hl7.org/fhir/StructureDefinition/Patient".equals(profile.getProfileID()))
			.findFirst()
			.orElseThrow();
		assertEquals("hl7.fhir.r4.core", patientProfile.getDomain());

		// A profile from the custom test IG must also be listed, both version-less and versioned
		final String testIgProfile = "http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required";
		assertTrue(profileIds.contains(testIgProfile));
		assertTrue(profileIds.contains(testIgProfile + "|0.3.0"));
		final ValidationProfile testIgValidationProfile = profiles.stream()
			.filter(profile -> testIgProfile.equals(profile.getProfileID()))
			.findFirst()
			.orElseThrow();
		assertEquals("matchbox.health.test.ig.r4", testIgValidationProfile.getDomain());

		// Extensions, primitive types and complex types are not validatable and must not be listed as profiles
		assertFalse(profileIds.contains("http://hl7.org/fhir/StructureDefinition/patient-citizenship"));
		assertFalse(profileIds.contains("http://hl7.org/fhir/StructureDefinition/string"));
		assertFalse(profileIds.contains("http://hl7.org/fhir/StructureDefinition/Address"));
	}

	/**
	 * The profile list carries an ETag and answers a revalidation with a 304, so a client does not transfer the whole
	 * list (~1 MB) again when nothing changed. https://github.com/ahdis/matchbox/issues/591
	 */
	@Test
	void testProfilesEtag() throws Exception {
		final var response = this.client.getProfilesRaw(null);
		assertEquals(200, response.statusCode());
		final String etag = response.headers().firstValue("ETag").orElseThrow();
		assertTrue(etag.startsWith("\"") && etag.endsWith("\""), "Unexpected ETag: " + etag);
		assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElseThrow());

		// The ETag is deterministic: an unchanged list yields the same one
		assertEquals(etag, this.client.getProfilesRaw(null).headers().firstValue("ETag").orElseThrow());

		// Revalidating with it: no body, same ETag
		final var notModified = this.client.getProfilesRaw(etag);
		assertEquals(304, notModified.statusCode());
		assertEquals("", notModified.body());
		assertEquals(etag, notModified.headers().firstValue("ETag").orElseThrow());

		// '*' matches any existing representation, and the comparison is weak
		assertEquals(304, this.client.getProfilesRaw("*").statusCode());
		assertEquals(304, this.client.getProfilesRaw("W/" + etag).statusCode());
		assertEquals(304, this.client.getProfilesRaw("\"0badcafe\", " + etag).statusCode());

		// An ETag that does not match gets the full list back
		final var stale = this.client.getProfilesRaw("\"0badcafe\"");
		assertEquals(200, stale.statusCode());
		assertEquals(etag, stale.headers().firstValue("ETag").orElseThrow());
		assertFalse(stale.body().isBlank());
	}

	/**
	 * Same as {@link #testProfilesEtag()} for v1, which is a different (smaller) representation and therefore has its
	 * own ETag.
	 */
	@Test
	void testProfilesEtagV1() throws Exception {
		final var response = this.client.getProfilesV1Raw(null);
		assertEquals(200, response.statusCode());
		final String etag = response.headers().firstValue("ETag").orElseThrow();

		final var notModified = this.client.getProfilesV1Raw(etag);
		assertEquals(304, notModified.statusCode());
		assertEquals("", notModified.body());

		// The v1 ETag must not be accepted on the v2 list, and vice versa
		final String etagV2 = this.client.getProfilesRaw(null).headers().firstValue("ETag").orElseThrow();
		assertNotEquals(etag, etagV2);
		assertEquals(200, this.client.getProfilesRaw(etag).statusCode());
		assertEquals(200, this.client.getProfilesV1Raw(etagV2).statusCode());
	}

	@Test
	void testProfilesV1() throws Exception {
		final var profiles = this.client.getProfilesV1();
		assertTrue(profiles.size() > 300);
		final var profile = profiles.get(0);
		assertTrue(profile.has("profileID"));
		assertFalse(profile.has("inputs"));
		assertFalse(profile.has("version"));
	}

	@Test
	void validatePatientV1() throws Exception {
		final String patient = """
				<Patient xmlns="http://hl7.org/fhir">
					<id value="example"/>
					<text>
						<status value="generated"/>
						<div xmlns="http://www.w3.org/1999/xhtml">42 </div>
					</text>
				</Patient>""";

		var report = this.client.validateV1(patient, "http://hl7.org/fhir/StructureDefinition/Patient");
		assertEquals("0.1", report.get("modelVersion").asText());
		assertEquals("PASSED", report.get("overallResult").asText());
		assertFalse(report.has("inputs"));
		assertEquals("first", report.get("validationItems").get(0).get("itemId").asText());
		assertEquals("request", report.get("validationItems").get(0).get("role").asText());
		assertFalse(report.get("counters").has("numberOfUndefined"));

		report = this.client.validateV1(patient, "http://hl7.org/fhir/StructureDefinition/Bundle");
		assertEquals("FAILED", report.get("overallResult").asText());
		final var assertion = report.get("reports").get(0).get("assertionReports").get(0);
		assertTrue(assertion.get("subjectLocation").asText().startsWith("line "));
		assertFalse(assertion.has("subjectLocations"));
	}

	@Test
	void validatePatientRawR4() throws Exception {
		final String patient = """
				<Patient xmlns="http://hl7.org/fhir">
					<id value="example"/>
					<text>
						<status value="generated"/>
						<div xmlns="http://www.w3.org/1999/xhtml">42 </div>
					</text>
				</Patient>""";

		ValidationReport report = this.client.validate(patient, "http://hl7.org/fhir/StructureDefinition/Patient");
		assertEquals(0, countValidationFailures(report));
		assertEquals(ValidationTestResult.PASSED, report.getOverallResult());
		assertEquals("first", report.getInputs().getFirst().getItemId());
		assertTrue(report.getReports().getFirst().getName().contains("first"));
		assertEquals(1, report.getReports().getFirst().getAssertionReports().size());
		assertEquals(ValidationTestResult.PASSED,
				report.getReports().getFirst().getAssertionReports().getFirst().getResult());
		assertEquals(SeverityLevel.INFO,
				report.getReports().getFirst().getAssertionReports().getFirst().getSeverity());
		// TODO: why no "first" in the report? No link between the validation item and
		// the assertion report?

		report = this.client.validate(patient, "http://hl7.org/fhir/StructureDefinition/Bundle");
		assertEquals(1, countValidationFailures(report));
		assertEquals(ValidationTestResult.FAILED, report.getOverallResult());
	}

	@Test
	void testSameSessionIdsForSameIg() throws Exception {
		final String patient = """
				<Patient xmlns="http://hl7.org/fhir">
					<id value="example"/>
					<text>
						<status value="generated"/>
						<div xmlns="http://www.w3.org/1999/xhtml">42 </div>
					</text>
				</Patient>""";

		ValidationReport report = this.client.validate(patient, "http://hl7.org/fhir/StructureDefinition/Patient");
		final String sessionId1 = getSessionId(report);

		report = this.client.validate(patient, "http://hl7.org/fhir/StructureDefinition/Bundle");
		final String sessionId2 = getSessionId(report);

		assertEquals(sessionId1, sessionId2);
	}

	@Test
	void verifyIgVersioningGazelle() throws Exception {
		final String resource = """
				<Practitioner xmlns="http://hl7.org/fhir">
					<identifier>
						<system value="urn:oid:2.51.1.3"/>
						<value value="7610000050719"/>
					</identifier>
				</Practitioner>""";

		String profileMatchbox = "http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required";

		// validate just with the profile, profile has no business version
		ValidationReport report = this.client.validate(resource, profileMatchbox);
		String sessionIdFirst = getSessionId(report);

		assertEquals(0, countValidationFailures(report));
		assertEquals("matchbox.health.test.ig.r4#0.3.0", getIg(report));

		report = this.client.validate(resource, profileMatchbox + "|0.3.0");
		String sessionIdThird = getSessionId(report);
		assertEquals(0, countValidationFailures(report));
		assertEquals("matchbox.health.test.ig.r4#0.3.0", getIg(report));
		assertEquals(sessionIdFirst, sessionIdThird);

		// validate with the profile and the ig version, has an internal business
		// version 9.9.9
		profileMatchbox = "http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-version-different-then-ig";
		report = this.client.validate(resource, profileMatchbox);
		String sessionIdForth = getSessionId(report);
		assertEquals(0, countValidationFailures(report));
		assertEquals("matchbox.health.test.ig.r4#0.3.0", getIg(report));
		assertEquals(sessionIdFirst, sessionIdForth);

		report = this.client.validate(resource, profileMatchbox + "|0.3.0");
		String sessionIdFifth = getSessionId(report);
		assertEquals(0, countValidationFailures(report));
		assertEquals("matchbox.health.test.ig.r4#0.3.0", getIg(report));
		assertEquals(sessionIdFirst, sessionIdFifth);
	}

	@Test
	// https://gazelle.ihe.net/jira/browse/EHS-431
	void validateEhs431() throws Exception {
		ValidationReport report = this.client.validate(getContent("ehs-431.json"),
				"http://hl7.org/fhir/StructureDefinition/Bundle");
		assertEquals(1, countValidationFailures(report));
	}

	@Test
	// https://gazelle.ihe.net/jira/browse/EHS-419
	void validateEhs419() throws Exception {
		ValidationReport report = this.client.validate(getContent("ehs-419.json"),
				"http://hl7.org/fhir/StructureDefinition/Patient");
		assertEquals(0, countValidationFailures(report));
	}

	@Test
	// https://gazelle.ihe.net/jira/browse/EHS-831
	void validateEhs831() throws Exception {
		final ValidationReport report = this.client.validate(getContent("ehs-831.json"),
				"http://hl7.org/fhir/StructureDefinition/Parameters");
		assertEquals(ValidationTestResult.PASSED, report.getOverallResult());
		assertEquals(0, countValidationFailures(report));
	}

	@Test
	void checkSuppressError() throws Exception {
		final String patient = "<RelatedPerson xmlns=\"http://hl7.org/fhir\">\n" + //
				"  <extension url=\"http://hl7.org/fhir/StructureDefinition/patient-citizenship\">\n" + //
				"    <extension url=\"code\">\n" + //
				"      <valueCodeableConcept>\n" + //
				"        <coding>\n" + //
				"          <system value=\"urn:iso:std:iso:3166\"/>\n" + //
				"          <code value=\"CH\"/>\n" + //
				"          <display value=\"Switzerland\"/>\n" + //
				"        </coding>\n" + //
				"      </valueCodeableConcept>\n" + //
				"    </extension>\n" + //
				"  </extension>\n" + //
				"  <patient>\n" + //
				"    <display value=\"none\"/>\n" + //
				"  </patient>\n" + //
				"</RelatedPerson>";

		ValidationReport report = this.client.validate(patient,
				"http://hl7.org/fhir/StructureDefinition/RelatedPerson");
		assertEquals(0, countValidationFailures(report));
		assertEquals(ValidationTestResult.PASSED, report.getOverallResult());
		assertEquals("first", report.getInputs().getFirst().getItemId());
		assertTrue(report.getReports().getFirst().getName().contains("first"));
		assertEquals(1, report.getReports().getFirst().getAssertionReports().size());
	}

	@Test
	void validateIgnoreErrorMatchboxTest() throws Exception {
		String practitioner = "<Practitioner xmlns=\"http://hl7.org/fhir\">\n" + //
				"    <extension url=\"http://hl7.org/fhir/StructureDefinition/unknown\">\n" + //
				"        <valueCodeableConcept>\n" + //
				"            <coding>\n" + //
				"                <system value=\"urn:iso:std:iso:3166\" />\n" + //
				"                <code value=\"CH\" />\n" + //
				"                <display value=\"Switzerland\" />\n" + //
				"            </coding>\n" + //
				"        </valueCodeableConcept>\n" + //
				"    </extension>\n" + //
				"    <identifier>\n" + //
				"        <system value=\"urn:oid:2.51.1.3\" />\n" + //
				"        <value value=\"7610000050719\" />\n" + //
				"    </identifier>\n" + //
				"</Practitioner>";

		ValidationReport report = this.client.validate(practitioner,
				"http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required");
		assertEquals(0, countValidationFailures(report));
		assertEquals(ValidationTestResult.PASSED, report.getOverallResult());
		assertEquals("first", report.getInputs().getFirst().getItemId());
		assertTrue(report.getReports().getFirst().getName().contains("first"));
		assertEquals(1, report.getReports().getFirst().getAssertionReports().size());
	}

	/**
	 * A profile that is unknown to this instance means that nothing could be validated: the result must be UNDEFINED,
	 * not PASSED. https://github.com/ahdis/matchbox/issues/590
	 */
	@Test
	void validateUnknownProfile() throws Exception {
		final ValidationReport report = this.client.validate(PATIENT, "http://example.org/nope");

		assertEquals(ValidationTestResult.UNDEFINED, report.getOverallResult());
		final var subReport = report.getReports().getFirst();
		assertEquals(ValidationTestResult.UNDEFINED, subReport.getSubReportResult());
		assertEquals(1, subReport.getUnexpectedErrors().size());
		assertTrue(subReport.getUnexpectedErrors().getFirst().getMessage()
						  .contains("not supported by this validator instance"),
					  "Unexpected message: " + subReport.getUnexpectedErrors().getFirst().getMessage());
		// No 'the validation has passed' assertion shall be added to a sub-report that failed with an unexpected error
		assertNull(subReport.getAssertionReports());
		assertEquals(0, report.getCounters().getNumberOfAssertions());
		assertEquals(1, report.getCounters().getNumberOfUnexpectedErrors());
	}

	/**
	 * Same as {@link #validateUnknownProfile()}, over the v1 API.
	 */
	@Test
	void validateUnknownProfileV1() throws Exception {
		final var report = this.client.validateV1(PATIENT, "http://example.org/nope");

		assertEquals("UNDEFINED", report.get("overallResult").asText());
		final var subReport = report.get("reports").get(0);
		assertEquals("UNDEFINED", subReport.get("subReportResult").asText());
		assertEquals(1, subReport.get("unexpectedErrors").size());
		assertFalse(subReport.has("assertionReports"));
		assertEquals(1, report.get("counters").get("numberOfUnexpectedErrors").asInt());
	}

	/**
	 * A request without a profile ID (or without inputs) cannot be validated and must be rejected with a 400, not
	 * crash with a 500. https://github.com/ahdis/matchbox/issues/590
	 */
	@Test
	void validateIncompleteRequest() throws Exception {
		var response = this.client.validateRaw(
			"{\"inputs\":[{\"id\":\"contentToValidate\",\"content\":\"%s\"}]}".formatted(PATIENT_BASE64));
		assertEquals(400, response.statusCode());
		assertTrue(response.body().contains("validationProfileId"), "Unexpected body: " + response.body());

		// A misspelled field name deserializes to null and must be treated the same way
		response = this.client.validateRaw(
			("{\"validationProfileID\":\"http://hl7.org/fhir/StructureDefinition/Patient\","
				+ "\"inputs\":[{\"id\":\"contentToValidate\",\"content\":\"%s\"}]}").formatted(PATIENT_BASE64));
		assertEquals(400, response.statusCode());

		// A blank profile ID is not usable either
		response = this.client.validateRaw(
			("{\"validationProfileId\":\" \","
				+ "\"inputs\":[{\"id\":\"contentToValidate\",\"content\":\"%s\"}]}").formatted(PATIENT_BASE64));
		assertEquals(400, response.statusCode());

		// No inputs at all
		response = this.client.validateRaw(
			"{\"validationProfileId\":\"http://hl7.org/fhir/StructureDefinition/Patient\"}");
		assertEquals(400, response.statusCode());
		assertTrue(response.body().contains("inputs"), "Unexpected body: " + response.body());

		response = this.client.validateRaw(
			"{\"validationProfileId\":\"http://hl7.org/fhir/StructureDefinition/Patient\",\"inputs\":[]}");
		assertEquals(400, response.statusCode());
	}

	/**
	 * Same as {@link #validateIncompleteRequest()}, over the v1 API.
	 */
	@Test
	void validateIncompleteRequestV1() throws Exception {
		var response = this.client.validateV1Raw(
			("{\"apiVersion\":\"0.1\",\"validationItems\":"
				+ "[{\"itemId\":\"first\",\"role\":\"request\",\"content\":\"%s\"}]}").formatted(PATIENT_BASE64));
		assertEquals(400, response.statusCode());
		assertTrue(response.body().contains("validationProfileId"), "Unexpected body: " + response.body());

		response = this.client.validateV1Raw(
			"{\"apiVersion\":\"0.1\",\"validationProfileId\":\"http://hl7.org/fhir/StructureDefinition/Patient\"}");
		assertEquals(400, response.statusCode());
		assertTrue(response.body().contains("validationItems"), "Unexpected body: " + response.body());

		// An unparseable body is still a 400
		response = this.client.validateV1Raw("not json");
		assertEquals(400, response.statusCode());
	}

	private static final String PATIENT = """
			<Patient xmlns="http://hl7.org/fhir">
				<id value="example"/>
				<text>
					<status value="generated"/>
					<div xmlns="http://www.w3.org/1999/xhtml">42 </div>
				</text>
			</Patient>""";

	private static final String PATIENT_BASE64 =
		Base64.getEncoder().encodeToString(PATIENT.getBytes(StandardCharsets.UTF_8));
}
