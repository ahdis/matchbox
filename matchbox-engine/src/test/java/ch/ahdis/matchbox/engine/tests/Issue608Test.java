package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import org.hl7.fhir.standalone.context.SimpleWorkerContext;
import org.hl7.fhir.model.core.CodeSystem;
import org.hl7.fhir.model.core.Enumerations;
import org.hl7.fhir.model.core.Resource;
import org.hl7.fhir.model.core.StructureDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import ch.ahdis.matchbox.engine.MatchboxEngine;

/**
 * Regression test for #608: an engine for an IG is a copy of the main engine (see MatchboxEngineSupport in the server),
 * loading the IG in the copy must not change the main engine or the other copies.
 * <p>
 * The copy of the context shared the maps of the resource index per type (allResourcesById) and the sets of the OID
 * index (oidCacheManual) with the original context, so the resources of an IG loaded in a copy were also found by the
 * untyped lookups of the main engine and of all other copies. The validator uses such a lookup to resolve canonicals
 * (InstanceValidator.validateReference, StandAloneValidatorFetcher.resolveURL), so the validation result of the main
 * engine depended on the IG engines that had been created since the start of the server.
 *
 * @see <a href="https://github.com/ahdis/matchbox/issues/608">IG engines share resources with the main engine and each
 * other (BaseWorkerContext.copy)</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Issue608Test {

	private static final String TEST_IG = "/matchbox.health.test.ig.r4-0.3.0.tgz";
	private static final String IG_PROFILE_ID = "practitioner-identifier-required";
	private static final String IG_PROFILE_URL = "http://matchbox.health/ig/test/r4/StructureDefinition/" + IG_PROFILE_ID;
	// The OID of http://hl7.org/fhir/administrative-gender, known by the main engine
	private static final String OID = "2.16.840.1.113883.4.642.4.2";

	private final MatchboxEngine main;

	Issue608Test() throws Exception {
		this.main = new MatchboxEngine.MatchboxEngineBuilder().getEngineR4();
	}

	@Test
	void igResourcesAreOnlyFoundByTheIgEngine() throws Exception {
		final MatchboxEngine igEngine = new MatchboxEngine(this.main);
		try (final InputStream in = Issue608Test.class.getResourceAsStream(TEST_IG)) {
			igEngine.loadPackage(in);
		}
		final MatchboxEngine otherEngine = new MatchboxEngine(this.main);

		assertNotNull(igEngine.getContext().fetchResource(StructureDefinition.class, IG_PROFILE_URL));
		assertNotNull(igEngine.getContext().fetchResource(Resource.class, IG_PROFILE_URL));
		assertNotNull(igEngine.getContext().fetchResourceById("StructureDefinition", IG_PROFILE_ID));

		for (final MatchboxEngine engine : new MatchboxEngine[]{this.main, otherEngine}) {
			assertNull(engine.getContext().fetchResource(StructureDefinition.class, IG_PROFILE_URL));
			assertNull(engine.getContext().fetchResource(Resource.class, IG_PROFILE_URL));
			assertNull(engine.getContext().fetchResourceById("StructureDefinition", IG_PROFILE_ID));
		}
	}

	@Test
	void oidsOfAnIgEngineAreOnlyFoundByTheIgEngine() throws Exception {
		final int knownInMain = this.urlsForOid(this.main);
		assertTrue(knownInMain > 0, "Expected the main engine to know the OID " + OID);

		// A CodeSystem with an OID that the main engine knows already: the OID index of the copy starts with the same
		// entry for it
		final MatchboxEngine igEngine = new MatchboxEngine(this.main);
		final CodeSystem cs = new CodeSystem();
		cs.setId("issue608");
		cs.setUrl("http://matchbox.health/test/CodeSystem/issue608");
		cs.setVersion("1.0.0");
		cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
		cs.addIdentifier().setSystem("urn:ietf:rfc:3986").setValue("urn:oid:" + OID);
		igEngine.getContext().cacheResource(cs);

		assertEquals(knownInMain + 1, this.urlsForOid(igEngine));
		assertEquals(knownInMain, this.urlsForOid(this.main));
	}

	private int urlsForOid(final MatchboxEngine engine) {
		return ((SimpleWorkerContext) engine.getContext()).urlsForOid(OID, null, true).getDefinitions().size();
	}
}
