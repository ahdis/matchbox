package ch.ahdis.matchbox.validation;

import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.util.MatchboxEngineSupport.InstalledPackage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The implementation guides of a request are normalised, so that the same set gives the same validation engine.
 */
class IgSelectionTest {

	private static final Map<String, InstalledPackage> INSTALLED = Map.of(
		"hl7.fhir.r4.core#4.0.1", new InstalledPackage("hl7.fhir.r4.core", "4.0.1", "4.0.1"),
		"a.ig#1.0.0", new InstalledPackage("a.ig", "1.0.0", "4.0.1"),
		"a.ig", new InstalledPackage("a.ig", "1.0.0", "4.0.1"),
		"b.ig#2.0.0", new InstalledPackage("b.ig", "2.0.0", "4.0.1"),
		"r5.ig#1.0.0", new InstalledPackage("r5.ig", "1.0.0", "5.0.0"));

	private static IgSelection select(final String... igs) {
		return IgSelection.of(List.of(igs), ig -> Optional.ofNullable(INSTALLED.get(ig)));
	}

	private static CliContext newContext() {
		return new CliContext(new MockEnvironment());
	}

	@Test
	void noIgLeavesTheChoiceToTheProfile() {
		final IgSelection selection = select();
		assertTrue(selection.igs().isEmpty());
		assertNull(selection.fhirVersion());

		final CliContext cliContext = newContext();
		final String sessionId = cliContext.sessionId();
		selection.applyTo(cliContext);
		assertNull(cliContext.getIg());
		assertEquals(sessionId, cliContext.sessionId());
	}

	@Test
	void theOrderAndDuplicatesDoNotMatter() {
		final IgSelection selection = select("b.ig#2.0.0", " a.ig#1.0.0 ", "", "b.ig#2.0.0");
		assertEquals(List.of("a.ig#1.0.0", "b.ig#2.0.0"), selection.igs());
		assertEquals(selection, select("a.ig#1.0.0", "b.ig#2.0.0"));

		final CliContext first = newContext();
		selection.applyTo(first);
		assertEquals("a.ig#1.0.0", first.getIg());
		assertArrayEquals(new String[]{"b.ig#2.0.0"}, first.getIgs());

		final CliContext second = newContext();
		select("a.ig#1.0.0", "b.ig#2.0.0").applyTo(second);
		assertEquals(first.sessionId(), second.sessionId());
	}

	@Test
	void anIgWithoutVersionIsTheInstalledVersion() {
		assertEquals(select("a.ig#1.0.0"), select("a.ig"));
	}

	/**
	 * One implementation guide gives the parameters that the engine selection sets for a profile of this
	 * implementation guide: the engine is shared with the other validation APIs.
	 */
	@Test
	void oneIgGivesTheEngineOfItsProfiles() {
		final CliContext byProfile = newContext();
		byProfile.setIg("a.ig#1.0.0");

		final CliContext byIg = newContext();
		select("a.ig").applyTo(byIg);
		assertNull(byIg.getIgs());
		assertEquals(byProfile.sessionId(), byIg.sessionId());
	}

	@Test
	void theCorePackageIsNotAnIg() {
		final IgSelection selection = select("hl7.fhir.r4.core#4.0.1", "a.ig#1.0.0");
		assertEquals(List.of("a.ig#1.0.0"), selection.igs());
		assertEquals("4.0.1", selection.fhirVersion());
		assertTrue(select("hl7.fhir.r4.core#4.0.1").igs().isEmpty());
	}

	@Test
	void theFhirVersionIsTheOneOfTheFirstRequestedIg() {
		assertEquals("5.0.0", select("r5.ig#1.0.0", "a.ig#1.0.0").fhirVersion());
		assertEquals("4.0.1", select("a.ig#1.0.0", "r5.ig#1.0.0").fhirVersion());
	}

	@Test
	void anIgThatIsNotInstalledIsAnError() {
		final var exception = assertThrows(IgSelection.IgNotInstalledException.class,
													  () -> select("a.ig#1.0.0", "c.ig#3.0.0", "a.ig#9.9.9"));
		assertEquals(List.of("c.ig#3.0.0", "a.ig#9.9.9"), exception.getIgs());
		assertTrue(exception.getMessage().contains("c.ig#3.0.0"));
	}
}
