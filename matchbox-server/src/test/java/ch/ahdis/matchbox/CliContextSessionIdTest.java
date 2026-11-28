package ch.ahdis.matchbox;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
	 * The copy of a context, which every request starts from, has all its parameters: 'extensions' and
	 * 'check-references-to' were dropped, so the setting of the server didn't reach the engines of the requests.
	 */
	@Test
	void copyKeepsTheArrayParameters() {
		final CliContext a = new CliContext(new MockEnvironment()
															.withProperty("matchbox.fhir.context.txServer", "n/a")
															.withProperty("matchbox.fhir.context.extensions", "http://example.org/a,http://example.org/b")
															.withProperty("matchbox.fhir.context.checkReferencesTo", "http://example.org/fhir"));
		a.setSuppressErrors(new String[]{"a", "b"});
		a.setIgs(new String[]{"hl7.fhir.uv.ips#2.0.0", "hl7.fhir.eu.base#0.1.0"});
		assertArrayEquals(new String[]{"http://example.org/a", "http://example.org/b"}, a.getExtensions());
		assertArrayEquals(new String[]{"http://example.org/fhir"}, a.getCheckReferencesTo());
		assertArrayEquals(new String[]{"a", "b"}, a.getSuppressErrors());

		final CliContext b = new CliContext(a);
		assertArrayEquals(a.getExtensions(), b.getExtensions());
		assertArrayEquals(a.getCheckReferencesTo(), b.getCheckReferencesTo());
		assertArrayEquals(a.getSuppressErrors(), b.getSuppressErrors());
		assertArrayEquals(a.getIgs(), b.getIgs());
		assertEquals(a, b);
		assertEquals(a.sessionId(), b.sessionId());

		// The copy doesn't share its arrays with the original
		b.getExtensions()[0] = "changed";
		assertEquals("http://example.org/a", a.getExtensions()[0]);
	}

	@Test
	void defaultExtensionsAreCopied() {
		final CliContext a = newContext();
		assertArrayEquals(new String[]{"any"}, a.getExtensions());
		assertArrayEquals(new String[]{"any"}, new CliContext(a).getExtensions());
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
