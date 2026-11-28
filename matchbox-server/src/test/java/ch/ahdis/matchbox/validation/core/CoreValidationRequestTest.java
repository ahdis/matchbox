package ch.ahdis.matchbox.validation.core;

import ch.ahdis.matchbox.validation.core.CoreValidationRequest.CoreBadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreValidationRequestTest {

	private static final String FILES = """
		"filesToValidate": [{"fileName": "patient.json", "fileContent": "{\\"resourceType\\": \\"Patient\\"}", "fileType": "json"}]""";

	private static CoreValidationRequest parse(final String properties) {
		return CoreValidationRequest.parse("{" + properties + (properties.isEmpty() ? "" : ",") + FILES + "}");
	}

	@Test
	void theExampleOfTheHl7Validator() {
		final CoreValidationRequest request = parse("""
			"cliContext": {
				"sv": "4.0.1",
				"igs": ["hl7.fhir.us.core#4.0.0"],
				"profiles": ["http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"],
				"locale": "en"
			},
			"sessionId": "32fc25cf-020e-4492-ace5-03fe904d22e0\"""");
		assertEquals("4.0.1", request.fhirVersion());
		assertEquals(List.of("hl7.fhir.us.core#4.0.0"), request.igs());
		assertEquals(List.of("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"), request.profiles());
		assertEquals("32fc25cf-020e-4492-ace5-03fe904d22e0", request.sessionId());
		assertEquals(1, request.parameters().size());
		assertArrayEquals(new String[]{"en"}, request.parameters().get("locale"));

		assertEquals(1, request.files().size());
		assertEquals("patient.json", request.files().getFirst().getFileName());
		assertEquals("{\"resourceType\": \"Patient\"}", request.files().getFirst().getFileContent());
		assertEquals("json", request.files().getFirst().getFileType());
	}

	@Test
	void onlyTheParametersOfTheRequestAreRead() {
		final CoreValidationRequest request = parse("");
		assertTrue(request.parameters().isEmpty());
		assertTrue(request.igs().isEmpty());
		assertTrue(request.profiles().isEmpty());
		assertNull(request.fhirVersion());
		assertNull(request.sessionId());
	}

	@Test
	void igAndIgsAreBothAccepted() {
		final List<String> expected = List.of("a.ig#1.0.0", "b.ig#2.0.0");
		assertEquals(expected, parse("""
			"validationContext": {"igs": ["a.ig#1.0.0", "b.ig#2.0.0"]}""").igs());
		assertEquals(expected, parse("""
			"validationContext": {"ig": ["a.ig#1.0.0", "b.ig#2.0.0"]}""").igs());
		assertEquals(expected, parse("""
			"validationContext": {"ig": "a.ig#1.0.0", "igs": ["b.ig#2.0.0", "a.ig#1.0.0"]}""").igs());
		assertEquals(expected, parse("""
			"cliContext": {"ig": "a.ig#1.0.0"}, "validationEngineParameters": {"igs": ["b.ig#2.0.0"]}""").igs());
		assertEquals(List.of("p"), parse("""
			"validationContext": {"profile": "p"}""").profiles());
	}

	@Test
	void theParametersGetTheirMatchboxName() {
		final CoreValidationRequest request = parse("""
			"validationContext": {
				"displayWarnings": false,
				"checkIPSCodes": true,
				"bestPracticeLevel": "Error",
				"noEcosystem": true,
				"r5BundleRelativeReferencePolicy": "NEVER",
				"extensions": ["any"],
				"txServer": "http://tx.example.org",
				"hintAboutNonMustSupport": true,
				"txLog": null,
				"locations": {"a": "b"},
				"checkReferencesTo": []
			}""");
		assertArrayEquals(new String[]{"false"}, request.parameters().get("displayIssuesAreWarnings"));
		assertArrayEquals(new String[]{"true"}, request.parameters().get("checkIpsCodes"));
		assertArrayEquals(new String[]{"Error"}, request.parameters().get("bpWarnings"));
		assertArrayEquals(new String[]{"false"}, request.parameters().get("txUseEcosystem"));
		assertArrayEquals(new String[]{"never"}, request.parameters().get("r5BundleRelativeReferencePolicy"));
		assertArrayEquals(new String[]{"any"}, request.parameters().get("extensions"));
		assertArrayEquals(new String[]{"http://tx.example.org"}, request.parameters().get("txServer"));
		assertArrayEquals(new String[]{"true"}, request.parameters().get("hintAboutNonMustSupport"));
		// null, objects and empty lists are not parameter values
		assertFalse(request.parameters().containsKey("txLog"));
		assertFalse(request.parameters().containsKey("locations"));
		assertFalse(request.parameters().containsKey("checkReferencesTo"));
		assertEquals(8, request.parameters().size());
	}

	@Test
	void theLastParameterObjectWins() {
		final CoreValidationRequest request = parse("""
			"instanceValidatorParameters": {"noInvariants": true},
			"validationContext": {"noInvariants": false, "lang": "de"}""");
		assertArrayEquals(new String[]{"true"}, request.parameters().get("noInvariants"));
		assertArrayEquals(new String[]{"de"}, request.parameters().get("lang"));
	}

	@Test
	void theFhirVersions() {
		assertEquals("4.0.1", CoreValidationRequest.getFhirVersion("4.0"));
		assertEquals("4.0.1", CoreValidationRequest.getFhirVersion("R4"));
		assertEquals("4.3.0", CoreValidationRequest.getFhirVersion("4.3.0"));
		assertEquals("4.3.0", CoreValidationRequest.getFhirVersion("r4b"));
		assertEquals("5.0.0", CoreValidationRequest.getFhirVersion(" 5.0 "));
		assertThrows(CoreBadRequestException.class, () -> CoreValidationRequest.getFhirVersion("3.0.2"));
		assertThrows(CoreBadRequestException.class, () -> CoreValidationRequest.getFhirVersion("4.1.0"));
	}

	@Test
	void invalidRequests() {
		assertThrows(CoreBadRequestException.class, () -> CoreValidationRequest.parse(null));
		assertThrows(CoreBadRequestException.class, () -> CoreValidationRequest.parse(" "));
		assertThrows(CoreBadRequestException.class, () -> CoreValidationRequest.parse("[]"));
		assertTrue(assertThrows(CoreBadRequestException.class, () -> CoreValidationRequest.parse("{\"cliContext\": "))
						  .getMessage().startsWith("Malformed JSON"));

		assertEquals(CoreValidationRequest.NO_FILES_PROVIDED_MESSAGE,
						 assertThrows(CoreBadRequestException.class, () -> CoreValidationRequest.parse("{}")).getMessage());
		assertEquals(CoreValidationRequest.NO_FILES_PROVIDED_MESSAGE,
						 assertThrows(CoreBadRequestException.class,
										  () -> CoreValidationRequest.parse("{\"filesToValidate\": []}")).getMessage());
		assertEquals(CoreValidationRequest.INVALID_FILE_MESSAGE,
						 assertThrows(CoreBadRequestException.class,
										  () -> CoreValidationRequest.parse("{\"filesToValidate\": [{\"fileName\": \"a\"}]}"))
							 .getMessage());
	}
}
