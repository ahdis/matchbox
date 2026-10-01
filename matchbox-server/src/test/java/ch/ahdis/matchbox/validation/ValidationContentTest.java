package ch.ahdis.matchbox.validation;

import ca.uhn.fhir.rest.api.EncodingEnum;
import ch.ahdis.matchbox.validation.ValidationContent.ValidationContentException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resolution of the content of a type-level $validate request
 * (<a href="https://github.com/ahdis/matchbox/issues/629">#629</a>).
 */
class ValidationContentTest {

	private static final String PROFILE = "http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required";

	// Formatting, number and duplicate property have to reach the validator as they were sent
	private static final String JSON_RESOURCE = """
		{
		        "resourceType" : "Observation",
		        "meta": { "profile": ["%s", "http://example.org/other"] },
		        "status": "final", "status": "final",
		        "valueQuantity": { "value": 1.50e0 },
		        "note": [ { "text": "a } in a \\"string\\" ]" } ]
		      }""".formatted(PROFILE);

	@Test
	void jsonResourceIsValidatedAsIs() throws Exception {
		final var content = ValidationContent.resolve(JSON_RESOURCE, EncodingEnum.JSON, "Observation");
		assertSame(JSON_RESOURCE, content.content());
		assertNull(content.envelopeProfile());
		assertEquals(List.of(PROFILE, "http://example.org/other"), content.metaProfiles());
	}

	@Test
	void jsonEnvelopeIsUnwrappedVerbatim() throws Exception {
		final String body = """
			{
			  "resourceType": "Parameters",
			  "parameter": [
			    { "name": "mode", "valueCode": "create" },
			    { "name": "other", "resource": { "resourceType": "Patient" } },
			    { "resource": %s, "name": "resource" },
			    { "name": "profile", "valueUri": "http://example.org/envelope" }
			  ]
			}""".formatted(JSON_RESOURCE);
		final var content = ValidationContent.resolve(body, EncodingEnum.JSON, "Observation");
		assertEquals(JSON_RESOURCE, content.content());
		assertEquals("http://example.org/envelope", content.envelopeProfile());
		assertEquals(List.of(PROFILE, "http://example.org/other"), content.metaProfiles());
	}

	@Test
	void jsonEnvelopeWithBom() throws Exception {
		final String body = "﻿{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"resource\",\"resource\":{\"resourceType\":\"Patient\"}}]}";
		final var content = ValidationContent.resolve(body, EncodingEnum.JSON, "Patient");
		assertEquals("{\"resourceType\":\"Patient\"}", content.content());
	}

	@Test
	void parametersOnParametersIsNotAnEnvelope() throws Exception {
		final String body = """
			{"resourceType":"Parameters","parameter":[{"name":"resource","resource":{"resourceType":"Parameters"}}]}""";
		final var content = ValidationContent.resolve(body, EncodingEnum.JSON, "Parameters");
		assertSame(body, content.content());
		assertTrue(content.metaProfiles().isEmpty());
	}

	@Test
	void jsonWrongTypes() {
		final var notTheType = assertThrows(ValidationContentException.class, () -> ValidationContent.resolve(
			"{\"resourceType\":\"Patient\"}", EncodingEnum.JSON, "Observation"));
		assertTrue(notTheType.getMessage().contains("'Observation'"));
		assertTrue(notTheType.getMessage().contains("found a 'Patient'"));

		final var notTheEnvelopedType = assertThrows(ValidationContentException.class, () -> ValidationContent.resolve(
			"{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"resource\",\"resource\":{\"resourceType\":\"Patient\"}}]}",
			EncodingEnum.JSON,
			"Observation"));
		assertTrue(notTheEnvelopedType.getMessage().contains("of type 'Observation'"));
		assertTrue(notTheEnvelopedType.getMessage().contains("found type 'Patient'"));

		final var noResource = assertThrows(ValidationContentException.class, () -> ValidationContent.resolve(
			"{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"mode\",\"valueCode\":\"create\"}]}",
			EncodingEnum.JSON,
			"Observation"));
		assertTrue(noResource.getMessage().contains("no 'resource' parameter"));
	}

	@Test
	void unreadableBodyIsLeftToTheValidator() throws Exception {
		final String malformedJson = "{\"resourceType\":\"Patient\",";
		assertSame(malformedJson, ValidationContent.resolve(malformedJson, EncodingEnum.JSON, "Patient").content());

		final String noResourceType = "{\"status\":\"final\"}";
		assertSame(noResourceType, ValidationContent.resolve(noResourceType, EncodingEnum.JSON, "Patient").content());

		final String malformedXml = "<Patient xmlns=\"http://hl7.org/fhir\"><id value=\"1\"/>";
		assertSame(malformedXml, ValidationContent.resolve(malformedXml, EncodingEnum.XML, "Patient").content());
	}

	@Test
	void xmlResourceIsValidatedAsIs() throws Exception {
		final String body = """
			<Practitioner xmlns="http://hl7.org/fhir">
			  <meta><profile value="%s"/></meta>
			</Practitioner>""".formatted(PROFILE);
		final var content = ValidationContent.resolve(body, EncodingEnum.XML, "Practitioner");
		assertSame(body, content.content());
		assertEquals(List.of(PROFILE), content.metaProfiles());
	}

	@Test
	void xmlEnvelopeIsUnwrappedWithItsNamespaces() throws Exception {
		final String body = """
			<?xml version="1.0" encoding="UTF-8"?>
			<Parameters xmlns="http://hl7.org/fhir">
			  <parameter>
			    <name value="profile"/>
			    <valueCanonical value="http://example.org/envelope"/>
			  </parameter>
			  <parameter>
			    <name value="resource"/>
			    <resource>
			      <Practitioner>
			        <meta><profile value="%s"/></meta>
			        <text>
			          <status value="generated"/>
			          <div xmlns="http://www.w3.org/1999/xhtml">42</div>
			        </text>
			      </Practitioner>
			    </resource>
			  </parameter>
			</Parameters>""".formatted(PROFILE);
		final var content = ValidationContent.resolve(body, EncodingEnum.XML, "Practitioner");
		assertTrue(content.content().startsWith("<Practitioner xmlns=\"http://hl7.org/fhir\">"), content.content());
		assertTrue(content.content().contains("<div xmlns=\"http://www.w3.org/1999/xhtml\">42</div>"), content.content());
		assertTrue(content.content().endsWith("</Practitioner>"), content.content());
		assertEquals("http://example.org/envelope", content.envelopeProfile());
		assertEquals(List.of(PROFILE), content.metaProfiles());
	}

	@Test
	void xmlWithDoctypeIsNotParsed() throws Exception {
		// No external entity is resolved (XXE): the body is left to the validator, which rejects the DOCTYPE
		final String body = """
			<?xml version="1.0"?>
			<!DOCTYPE Parameters [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
			<Parameters xmlns="http://hl7.org/fhir">
			  <parameter>
			    <name value="resource"/>
			    <resource><Patient><id value="&xxe;"/></Patient></resource>
			  </parameter>
			</Parameters>""";
		final var content = ValidationContent.resolve(body, EncodingEnum.XML, "Patient");
		assertSame(body, content.content());
		assertTrue(content.metaProfiles().isEmpty());
	}

	@Test
	void xmlWrongTypes() {
		final var notTheType = assertThrows(ValidationContentException.class, () -> ValidationContent.resolve(
			"<Patient xmlns=\"http://hl7.org/fhir\"/>", EncodingEnum.XML, "Practitioner"));
		assertTrue(notTheType.getMessage().contains("found a 'Patient'"));

		final var notTheEnvelopedType = assertThrows(ValidationContentException.class, () -> ValidationContent.resolve(
			"<Parameters xmlns=\"http://hl7.org/fhir\"><parameter><name value=\"resource\"/><resource><Patient/></resource></parameter></Parameters>",
			EncodingEnum.XML,
			"Practitioner"));
		assertTrue(notTheEnvelopedType.getMessage().contains("found type 'Patient'"));
	}
}
