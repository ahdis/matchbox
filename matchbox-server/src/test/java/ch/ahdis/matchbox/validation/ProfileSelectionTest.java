package ch.ahdis.matchbox.validation;

import ca.uhn.fhir.rest.api.EncodingEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The profile to validate against is chosen the same way by all validation APIs.
 */
class ProfileSelectionTest {

	private static final String REQUESTED = "http://example.org/StructureDefinition/requested|1.0.0";
	private static final String ENVELOPE = "http://example.org/StructureDefinition/envelope";
	private static final String META_1 = "http://example.org/StructureDefinition/meta1";
	private static final String META_2 = "http://example.org/StructureDefinition/meta2";

	@Test
	void requestedProfileWins() {
		final var selection = ProfileSelection.select(REQUESTED, ENVELOPE, List.of(META_1, META_2), "Patient");
		assertEquals(REQUESTED, selection.profile());
		assertEquals(List.of(), selection.otherMetaProfiles());
	}

	@Test
	void envelopeProfileBeforeMetaProfile() {
		final var selection = ProfileSelection.select(null, ENVELOPE, List.of(META_1), "Patient");
		assertEquals(ENVELOPE, selection.profile());
		assertEquals(List.of(), selection.otherMetaProfiles());
	}

	@Test
	void firstMetaProfileWithTheOtherOnesNoted() {
		final var selection = ProfileSelection.select(null, null, List.of(META_1, META_2), "Patient");
		assertEquals(META_1, selection.profile());
		assertEquals(List.of(META_2), selection.otherMetaProfiles());
	}

	@Test
	void baseProfileOfTheResourceType() {
		final var selection = ProfileSelection.select(null, null, List.of(), "Patient");
		assertEquals("http://hl7.org/fhir/StructureDefinition/Patient", selection.profile());
	}

	@Test
	void noProfileWithoutAResourceType() {
		assertNull(ProfileSelection.select(null, null, List.of(), null));
		assertNull(ProfileSelection.select(null, null, List.of(), "not a type"));
	}

	@Test
	void profileReferenceSplitsTheVersion() {
		assertEquals(new ProfileReference("http://example.org/StructureDefinition/requested", "1.0.0"),
						 ProfileReference.parse(REQUESTED));
		assertEquals(new ProfileReference(ENVELOPE, null), ProfileReference.parse(ENVELOPE));
	}

	@Test
	void inspectJson() {
		final var inspection = ValidationContent.inspect(
			"﻿{\"resourceType\":\"Patient\",\"meta\":{\"profile\":[\"%s\",\"%s\"]}}".formatted(META_1, META_2),
			EncodingEnum.JSON);
		assertEquals("Patient", inspection.resourceType());
		assertEquals(List.of(META_1, META_2), inspection.metaProfiles());

		assertEquals(List.of(), ValidationContent.inspect("{\"resourceType\":\"Patient\"}", EncodingEnum.JSON).metaProfiles());
	}

	@Test
	void inspectXml() {
		final var inspection = ValidationContent.inspect(
			"<Patient xmlns=\"http://hl7.org/fhir\"><meta><profile value=\"%s\"/></meta></Patient>".formatted(META_1),
			EncodingEnum.XML);
		assertEquals("Patient", inspection.resourceType());
		assertEquals(List.of(META_1), inspection.metaProfiles());
	}

	@Test
	void unreadableContentHasNoResourceType() {
		assertNull(ValidationContent.inspect("{not json", EncodingEnum.JSON).resourceType());
		assertNull(ValidationContent.inspect("{\"id\":\"1\"}", EncodingEnum.JSON).resourceType());
		assertNull(ValidationContent.inspect("<Patient", EncodingEnum.XML).resourceType());
		// A DOCTYPE is not read
		assertNull(ValidationContent.inspect("<!DOCTYPE Patient []><Patient xmlns=\"http://hl7.org/fhir\"/>",
														 EncodingEnum.XML).resourceType());
	}
}
