package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.hl7.fhir.utilities.settings.FhirSettings;
import org.junit.jupiter.api.Test;

import ch.ahdis.matchbox.engine.MatchboxEngine;

/**
 * The terminology ecosystem (tx-reg) routes the requests for a code system to the server that knows it, e.g. the
 * Swiss SNOMED CT edition to tx.fhir.ch. Since FHIR Core 7.0.4 it is only used with https://tx.fhir.org, the matchbox
 * configurations use http://tx.fhir.org.
 */
class TxEcosystemTest {

	@Test
	void ecosystemForTxFhirOrg() {
		final String ecosystem = FhirSettings.getTxEcosystem();
		assertEquals(ecosystem, MatchboxEngine.getTxEcosystem("http://tx.fhir.org", true));
		assertEquals(ecosystem, MatchboxEngine.getTxEcosystem("http://tx.fhir.org/r4", true));
		assertEquals(ecosystem, MatchboxEngine.getTxEcosystem("https://tx.fhir.org", true));
		assertEquals(ecosystem, MatchboxEngine.getTxEcosystem("https://tx.fhir.org/r4/", true));
	}

	@Test
	void noEcosystem() {
		assertNull(MatchboxEngine.getTxEcosystem("http://tx.fhir.org", false));
		assertNull(MatchboxEngine.getTxEcosystem(null, true));
		assertNull(MatchboxEngine.getTxEcosystem("http://localhost:8080/matchboxv3/tx", true));
		assertNull(MatchboxEngine.getTxEcosystem("http://tx.fhir.org.example.com", true));
		assertNull(MatchboxEngine.getTxEcosystem("https://tx.fhir.ch/r4", true));
	}
}
