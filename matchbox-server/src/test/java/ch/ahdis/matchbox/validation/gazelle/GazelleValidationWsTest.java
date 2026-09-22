package ch.ahdis.matchbox.validation.gazelle;

import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.engine.exception.MatchboxEngineCreationException;
import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import ch.ahdis.matchbox.validation.gazelle.models.validation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the report computation of the Gazelle Validation Service API.
 * <p>
 * See <a href="https://github.com/ahdis/matchbox/issues/590">#590</a>: a validation that never ran must not be
 * reported as PASSED, and an incomplete request must be rejected.
 *
 * @author Oliver Egger
 **/
class GazelleValidationWsTest {

	@Test
	void unexpectedErrorSubReportIsUndefined() {
		final var subReport = GazelleValidationWs.unexpectedError("Validation for profile 'http://example.org/nope' "
																					 + "not supported by this validator instance");
		assertEquals(ValidationTestResult.UNDEFINED, subReport.getSubReportResult());
		assertNull(subReport.getAssertionReports());
		assertEquals(1, subReport.getUnexpectedErrors().size());
	}

	@Test
	void unexpectedErrorIsNotRecomputedToPassed() {
		final var report = new ValidationReport();
		report.setDisclaimer("Matchbox disclaims");
		report.addValidationSubReport(GazelleValidationWs.unexpectedError("Validation engine not initialized, please "
																							  + "try again"));

		GazelleValidationWs.updateReportFields(report);

		// Nothing was validated: neither PASSED nor FAILED is true
		assertEquals(ValidationTestResult.UNDEFINED, report.getOverallResult());
		assertEquals(ValidationTestResult.UNDEFINED, report.getReports().getFirst().getSubReportResult());
		assertEquals(0, report.getCounters().getNumberOfAssertions());
		assertEquals(1, report.getCounters().getNumberOfUnexpectedErrors());
		assertEquals(1, report.getReports().getFirst().getSubCounters().getNumberOfUnexpectedErrors());
	}

	@Test
	void unexpectedErrorWeightsMoreThanFailedAndPassed() {
		final var report = new ValidationReport();
		report.setDisclaimer("Matchbox disclaims");
		report.addValidationSubReport(new ValidationSubReport()
												  .setName("Validation of item #first")
												  .addAssertionReport(new AssertionReport()
																				 .setResult(ValidationTestResult.FAILED)
																				 .setSeverity(SeverityLevel.ERROR)
																				 .setPriority(RequirementPriority.MANDATORY)
																				 .setDescription("An error")));
		report.addValidationSubReport(GazelleValidationWs.unexpectedError("Error during validation"));

		GazelleValidationWs.updateReportFields(report);

		assertEquals(ValidationTestResult.UNDEFINED, report.getOverallResult());
		assertEquals(ValidationTestResult.FAILED, report.getReports().getFirst().getSubReportResult());
		assertEquals(1, report.getCounters().getNumberOfFailedWithErrors());
		assertEquals(1, report.getCounters().getNumberOfUnexpectedErrors());
	}

	@Test
	void subReportWithUnexpectedErrorAndAssertionsIsUndefined() {
		// An exception caught inside validateItem(): the sub-report may carry both assertions and an unexpected error
		final var subReport = new ValidationSubReport()
			.setName("Validation of item #first")
			.addAssertionReport(new AssertionReport()
										  .setResult(ValidationTestResult.PASSED)
										  .setSeverity(SeverityLevel.INFO)
										  .setPriority(RequirementPriority.MANDATORY)
										  .setDescription("An information"))
			.addUnexpectedError(new UnexpectedError().setMessage("Error during validation: boom"));

		final var report = new ValidationReport();
		report.setDisclaimer("Matchbox disclaims");
		report.addValidationSubReport(subReport);

		GazelleValidationWs.updateReportFields(report);

		assertEquals(ValidationTestResult.UNDEFINED, report.getOverallResult());
		assertEquals(1, report.getCounters().getNumberOfUnexpectedErrors());
	}

	@Test
	void reportWithoutUnexpectedErrorIsUnchanged() {
		final var report = new ValidationReport();
		report.setDisclaimer("Matchbox disclaims");
		report.addValidationSubReport(new ValidationSubReport()
												  .setName("Validation of item #first")
												  .addAssertionReport(new AssertionReport()
																				 .setResult(ValidationTestResult.PASSED)
																				 .setSeverity(SeverityLevel.INFO)
																				 .setPriority(RequirementPriority.MANDATORY)
																				 .setDescription("No fatal or error issues detected, the validation has passed")));

		GazelleValidationWs.updateReportFields(report);

		assertEquals(ValidationTestResult.PASSED, report.getOverallResult());
		assertEquals(1, report.getCounters().getNumberOfAssertions());
		assertEquals(0, report.getCounters().getNumberOfUnexpectedErrors());
	}

	@Test
	void checkRequestRejectsIncompleteRequests() {
		assertEquals("The validation request is missing", GazelleValidationWs.checkRequest(null, "inputs"));

		final var noProfile = new ValidationRequest()
			.addInput(new Input().setId(GazelleValidationWs.INPUT_ID).setContent("{}".getBytes()));
		assertEquals("The field 'validationProfileId' is missing or empty",
						 GazelleValidationWs.checkRequest(noProfile, "inputs"));

		final var blankProfile = new ValidationRequest()
			.setValidationProfileId("  ")
			.addInput(new Input().setId(GazelleValidationWs.INPUT_ID).setContent("{}".getBytes()));
		assertEquals("The field 'validationProfileId' is missing or empty",
						 GazelleValidationWs.checkRequest(blankProfile, "inputs"));

		final var noInputs = new ValidationRequest()
			.setValidationProfileId("http://hl7.org/fhir/StructureDefinition/Patient");
		assertEquals("The field 'inputs' is missing or empty", GazelleValidationWs.checkRequest(noInputs, "inputs"));
		// v1 spells the field differently
		assertEquals("The field 'validationItems' is missing or empty",
						 GazelleValidationWs.checkRequest(noInputs, "validationItems"));
	}

	@Test
	void checkRequestAcceptsCompleteRequest() {
		final var request = new ValidationRequest()
			.setValidationProfileId("http://hl7.org/fhir/StructureDefinition/Patient")
			.addInput(new Input().setId(GazelleValidationWs.INPUT_ID).setContent("{}".getBytes()));
		assertNull(GazelleValidationWs.checkRequest(request, "inputs"));
	}

	@Test
	void matchesEtagFollowsRfc9110() {
		final String etag = "\"abcdef\"";

		assertFalse(GazelleValidationWs.matchesEtag(null, etag));
		assertFalse(GazelleValidationWs.matchesEtag("", etag));
		assertFalse(GazelleValidationWs.matchesEtag("   ", etag));

		// '*' matches any existing representation
		assertTrue(GazelleValidationWs.matchesEtag("*", etag));
		assertTrue(GazelleValidationWs.matchesEtag(" * ", etag));

		assertTrue(GazelleValidationWs.matchesEtag(etag, etag));
		// If-None-Match uses the weak comparison function, so the W/ prefix is ignored
		assertTrue(GazelleValidationWs.matchesEtag("W/" + etag, etag));
		// A comma-separated list, with the optional whitespace RFC 9110 allows
		assertTrue(GazelleValidationWs.matchesEtag("\"other\", " + etag, etag));
		assertTrue(GazelleValidationWs.matchesEtag("\"other\"," + etag + ",\"third\"", etag));
		assertTrue(GazelleValidationWs.matchesEtag("W/\"other\", W/" + etag, etag));

		assertFalse(GazelleValidationWs.matchesEtag("\"other\"", etag));
		assertFalse(GazelleValidationWs.matchesEtag("\"other\", \"third\"", etag));
		// The quotes are part of the entity tag
		assertFalse(GazelleValidationWs.matchesEtag("abcdef", etag));
	}

	@Test
	void profileListEtagIsDeterministic() {
		final String json = "[{\"profileID\":\"http://hl7.org/fhir/StructureDefinition/Patient\"}]";

		final ResponseEntity<String> response = GazelleValidationWs.profileListResponse(json, null);
		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(json, response.getBody());
		final String etag = response.getHeaders().getETag();
		assertNotNull(etag);
		assertEquals("no-cache", response.getHeaders().getCacheControl());

		// The same list yields the same ETag, a different one does not
		assertEquals(etag, GazelleValidationWs.profileListResponse(json, null).getHeaders().getETag());
		assertNotEquals(etag, GazelleValidationWs.profileListResponse(json + " ", null).getHeaders().getETag());

		// Revalidating with it: 304, the same ETag, and no body
		final ResponseEntity<String> notModified = GazelleValidationWs.profileListResponse(json, etag);
		assertEquals(HttpStatus.NOT_MODIFIED, notModified.getStatusCode());
		assertEquals(etag, notModified.getHeaders().getETag());
		assertNull(notModified.getBody());

		// A stale ETag gets the list back
		assertEquals(HttpStatus.OK, GazelleValidationWs.profileListResponse(json, "\"stale\"").getStatusCode());
	}

	/**
	 * While the validation engine is not initialized, the same request may succeed later: it is answered with a 503
	 * and a Retry-After header, but still with a well-formed report.
	 */
	@Test
	void notInitializedEngineIsRetryable() {
		final var ws = newWs(false);

		final ResponseEntity<?> response = ws.postValidate(patientRequest());

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
		assertEquals(String.valueOf(GazelleValidationWs.RETRY_AFTER_SECONDS),
						 response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
		assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());

		// The body is still a well-formed report, so clients that only parse the report keep working
		final var report = assertInstanceOf(ValidationReport.class, response.getBody());
		assertEquals(ValidationTestResult.UNDEFINED, report.getOverallResult());
		assertEquals(ValidationTestResult.UNDEFINED, report.getReports().getFirst().getSubReportResult());
		assertEquals("Validation engine not initialized, please try again",
						 report.getReports().getFirst().getUnexpectedErrors().getFirst().getMessage());
		assertEquals(1, report.getCounters().getNumberOfUnexpectedErrors());
		assertTrue(report.isDisclaimerValid());
		assertTrue(report.isUuidValid());
		assertTrue(report.isDateTimeValid());
	}

	/**
	 * An unknown profile is not retryable: it stays a 200 with an UNDEFINED report, and carries no Retry-After.
	 */
	@Test
	void unknownProfileIsNotRetryable() {
		final var ws = newWs(true);

		final ResponseEntity<?> response = ws.postValidate(patientRequest());

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
		final var report = assertInstanceOf(ValidationReport.class, response.getBody());
		assertEquals(ValidationTestResult.UNDEFINED, report.getOverallResult());
		assertTrue(report.getReports().getFirst().getUnexpectedErrors().getFirst().getMessage()
						  .contains("not supported by this validator instance"));
	}

	/**
	 * The v1 endpoint answers the same way, with the v1 JSON of the report as body.
	 */
	@Test
	void notInitializedEngineIsRetryableV1() throws Exception {
		final var ws = newWs(false);

		final ResponseEntity<String> response = ws.postValidateV1(
			"{\"apiVersion\":\"0.1\",\"validationProfileId\":\"http://hl7.org/fhir/StructureDefinition/Patient\","
				+ "\"validationItems\":[{\"itemId\":\"first\",\"role\":\"request\",\"content\":\"e30=\"}]}");

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
		assertEquals(String.valueOf(GazelleValidationWs.RETRY_AFTER_SECONDS),
						 response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));

		final var report = new ObjectMapper().readTree(response.getBody());
		assertEquals(GazelleApiV1Mapper.MODEL_VERSION, report.get("modelVersion").asText());
		assertEquals("UNDEFINED", report.get("overallResult").asText());
		assertEquals(1, report.get("counters").get("numberOfUnexpectedErrors").asInt());
	}

	/**
	 * An incomplete request is rejected before the engine is even looked up, so it is a 400 whatever the state of the
	 * engine is.
	 */
	@Test
	void incompleteRequestIsRejectedEvenWhenNotInitialized() {
		final var ws = newWs(false);

		final ResponseEntity<?> response = ws.postValidate(new ValidationRequest()
																			  .addInput(new Input().setId(GazelleValidationWs.INPUT_ID)
																											.setContent("{}".getBytes(StandardCharsets.UTF_8))));

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
	}

	/**
	 * Builds a web service whose engine support resolves no engine, with the given initialization state.
	 */
	private static GazelleValidationWs newWs(final boolean initialized) {
		final var engineSupport = mock(MatchboxEngineSupport.class);
		try {
			when(engineSupport.getMatchboxEngine(any(), any(), anyBoolean(), anyBoolean())).thenReturn(null);
		} catch (final MatchboxEngineCreationException e) {
			throw new AssertionError(e);
		}
		when(engineSupport.isInitialized()).thenReturn(initialized);
		return new GazelleValidationWs(engineSupport,
												 new CliContext(new MockEnvironment()),
												 Optional.empty(),
												 mock(MbInstalledStructureDefinitionRepository.class),
												 new ObjectMapper());
	}

	private static ValidationRequest patientRequest() {
		return new ValidationRequest()
			.setValidationProfileId("http://hl7.org/fhir/StructureDefinition/Patient")
			.addInput(new Input().setId(GazelleValidationWs.INPUT_ID)
										.setContent("{}".getBytes(StandardCharsets.UTF_8)));
	}
}
