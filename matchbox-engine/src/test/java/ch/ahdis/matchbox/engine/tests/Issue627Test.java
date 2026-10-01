package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;

import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.ValueSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import ch.ahdis.matchbox.engine.MatchboxEngine;

/**
 * Regression test for #627: the CodeSystems and ValueSets of the core package are loaded lazily (#599) and pinned to
 * the core versions when they're parsed (MetadataCoreVersionPinner), like core's CoreVersionPinner pins them when the
 * core package is loaded (SimpleWorkerContext.finishLoading()).
 * <p>
 * CoreVersionPinner only sees the core package, but the proxies are parsed when the context also contains the other
 * packages. The include of http://hl7.org/fhir/ValueSet/units-of-time was pinned to http://unitsofmeasure.org|3.0.1,
 * the not-present UCUM CodeSystem of hl7.fhir.uv.xver-r5.r4#0.1.0, and the terminology server, which only knows UCUM
 * 2.2, rejected every Timing.repeat.periodUnit: "The value provided ('h') was not found in the value set 'UnitsOfTime'
 * (...) A definition for CodeSystem 'http://unitsofmeasure.org' version '3.0.1' could not be found".
 *
 * @see <a href="https://github.com/ahdis/matchbox/issues/627">UCUM codes in core value sets rejected since 4.1.18</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Issue627Test {

	private static final String TEST_IG = "/matchbox.health.test.ig.r4-0.3.0.tgz";
	private static final String UCUM = "http://unitsofmeasure.org";

	private final MatchboxEngine main;

	Issue627Test() throws Exception {
		this.main = new MatchboxEngine.MatchboxEngineBuilder().getEngineR4();
	}

	@Test
	void coreValueSetIsNotPinnedToTheUcumCodeSystemOfAnotherPackage() {
		// The context has a UCUM CodeSystem, but not from the core package
		final CodeSystem ucum = this.main.getContext().fetchCodeSystem(UCUM);
		assertNotNull(ucum);
		assertEquals("3.0.1", ucum.getVersion());

		assertNull(this.includedVersion(this.main, "http://hl7.org/fhir/ValueSet/units-of-time", UCUM));
	}

	@Test
	void coreValueSetIsPinnedToTheCoreCodeSystem() {
		assertEquals("4.0.1", this.includedVersion(this.main, "http://hl7.org/fhir/ValueSet/administrative-gender",
																	"http://hl7.org/fhir/administrative-gender"));
	}

	@Test
	void coreValueSetOfAnIgEngineIsNotPinnedToTheUcumCodeSystemOfAnotherPackage() throws Exception {
		final MatchboxEngine igEngine = new MatchboxEngine(this.main);
		try (final InputStream in = Issue627Test.class.getResourceAsStream(TEST_IG)) {
			igEngine.loadPackage(in);
		}
		assertNull(this.includedVersion(igEngine, "http://hl7.org/fhir/ValueSet/ucum-units", UCUM));
		assertNull(this.includedVersion(igEngine, "http://hl7.org/fhir/ValueSet/units-of-time", UCUM));
	}

	private String includedVersion(final MatchboxEngine engine, final String valueSetUrl, final String system) {
		final ValueSet vs = engine.getContext().fetchResource(ValueSet.class, valueSetUrl);
		assertNotNull(vs, valueSetUrl);
		return vs.getCompose().getInclude().stream()
			.filter(include -> system.equals(include.getSystem()))
			.findFirst()
			.orElseThrow()
			.getVersion();
	}
}
