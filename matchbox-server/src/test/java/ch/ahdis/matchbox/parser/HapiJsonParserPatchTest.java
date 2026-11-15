package ch.ahdis.matchbox.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

/**
 * The HAPI JSON parser fixes matchbox carries in ca.uhn.fhir.parser.BaseParser and ca.uhn.fhir.parser.JsonParser,
 * see https://github.com/ahdis/matchbox/issues/625.
 */
class HapiJsonParserPatchTest {

	private static final FhirContext CONTEXT = FhirContext.forR4Cached();

	/**
	 * hapifhir/hapi-fhir#8238: encoding a contained Bundle with an extension on Bundle.timestamp threw an NPE, which
	 * made the installation of the CH EKM IG (SDC $extract templates in the Questionnaires) fail.
	 */
	@Test
	void encodesContainedBundleWithExtensionOnRootPrimitive() {
		final String input = """
			{
			  "resourceType": "Questionnaire",
			  "id": "q",
			  "url": "http://example.org/fhir/Questionnaire/q",
			  "status": "draft",
			  "contained": [ {
			    "resourceType": "Bundle",
			    "id": "tpl",
			    "type": "collection",
			    "timestamp": "1900-01-01T00:00:00Z",
			    "_timestamp": { "extension": [ { "url": "http://example.org/any-extension", "valueString": "anything" } ] }
			  } ]
			}""";

		final IParser parser = CONTEXT.newJsonParser();
		final String encoded = parser.encodeResourceToString(parser.parseResource(Questionnaire.class, input));
		assertTrue(encoded.contains("http://example.org/any-extension"));

		final Questionnaire reparsed = parser.parseResource(Questionnaire.class, encoded);
		final Bundle bundle = (Bundle) reparsed.getContained().get(0);
		assertEquals(1, bundle.getTimestampElement().getExtension().size());
		assertEquals("anything", bundle.getTimestampElement().getExtensionFirstRep().getValue().primitiveValue());
	}

	/**
	 * hapifhir/hapi-fhir#8370: extensions on a repeating primitive without a value ("_line" without "line") were
	 * silently dropped.
	 */
	@Test
	void keepsExtensionsOnValuelessRepeatingPrimitive() {
		final String input = """
			{
			  "resourceType": "Patient",
			  "address": [ {
			    "_line": [ {
			      "extension": [ { "url": "http://example.org/line", "valueString": "line-0" } ]
			    }, {
			      "extension": [ { "url": "http://example.org/line", "valueString": "line-1" } ]
			    } ]
			  } ]
			}""";

		final IParser parser = CONTEXT.newJsonParser();
		final String encoded = parser.encodeResourceToString(parser.parseResource(Patient.class, input));
		assertTrue(encoded.contains("_line"));

		final List<StringType> line = parser.parseResource(Patient.class, encoded).getAddressFirstRep().getLine();
		assertEquals(2, line.size());
		assertNull(line.get(0).getValue());
		assertEquals("line-0", line.get(0).getExtensionFirstRep().getValue().primitiveValue());
		assertEquals("line-1", line.get(1).getExtensionFirstRep().getValue().primitiveValue());
	}
}
