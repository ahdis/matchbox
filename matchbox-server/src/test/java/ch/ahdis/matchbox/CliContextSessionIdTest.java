package ch.ahdis.matchbox;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The session id of a CLI context is the key of its validation engine in the engine cache: equal parameters must
 * give the same engine, different parameters a different one.
 */
class CliContextSessionIdTest {

	private static CliContext newContext() {
		return new CliContext(new MockEnvironment().withProperty("matchbox.fhir.context.txServer", "n/a"));
	}

	@Test
	void equalContextsHaveTheSameSessionId() {
		final CliContext a = newContext();
		final CliContext b = newContext();
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertEquals(a.sessionId(), b.sessionId());
	}

	/**
	 * The arrays are compared by content: two requests with the same values get the same engine.
	 */
	@Test
	void arraysAreComparedByContent() {
		final CliContext a = newContext();
		final CliContext b = newContext();
		a.setCheckReferencesTo(new String[]{"http://example.org/fhir"});
		b.setCheckReferencesTo(new String[]{"http://example.org/fhir"});
		a.setIgs(new String[]{"hl7.fhir.uv.ips#2.0.0"});
		b.setIgs(new String[]{"hl7.fhir.uv.ips#2.0.0"});
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertEquals(a.sessionId(), b.sessionId());
	}

	@Test
	void differentParametersHaveDifferentSessionIds() {
		final CliContext base = newContext();

		final CliContext ig = newContext();
		ig.setIg("hl7.fhir.uv.ips#2.0.0");
		assertNotEquals(base.sessionId(), ig.sessionId());

		final CliContext igs = newContext();
		igs.setIgs(new String[]{"hl7.fhir.uv.ips#2.0.0"});
		assertNotEquals(base.sessionId(), igs.sessionId());
		assertNotEquals(ig.sessionId(), igs.sessionId());

		final CliContext references = newContext();
		references.setCheckReferencesTo(new String[]{"http://example.org/fhir"});
		assertNotEquals(base.sessionId(), references.sessionId());
	}
}
