package ch.ahdis.matchbox.engine.tests;

import ch.ahdis.matchbox.engine.MatchboxEngine;
import org.hl7.fhir.model.Base;
import org.hl7.fhir.model.core.CodeSystem;
import org.hl7.fhir.model.core.VersionResolutionRules;
import org.hl7.fhir.model.core.formats.JsonParser;
import org.hl7.fhir.model.utilities.formats.FhirFormat;
import org.hl7.fhir.model.utilities.formats.OutputStyle;
import org.hl7.fhir.services.elementmodel.Element;
import org.hl7.fhir.services.elementmodel.Manager;
import org.hl7.fhir.services.elementmodel.ObjectConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MatchboxEngine#fetch} keeps the resources of the context it converted to the element model and returns a
 * copy on each call.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FetchCacheTest {
	private static final String URL = "http://hl7.org/fhir/administrative-gender";

	/**
	 * A CodeSystem with more than 10 concepts: NamedItemList looks children up in a name map, built lazily, from 10
	 * children on
	 */
	private static final String TEST_URL = "http://example.org/fhir/CodeSystem/fetch-test";
	private static final int TEST_CONCEPTS = 25;

	private final MatchboxEngine engine = new MatchboxEngine.MatchboxEngineBuilder().getEngineR4();

	FetchCacheTest() {
		final String concepts = IntStream.range(0, TEST_CONCEPTS)
			.mapToObj(i -> "{\"code\":\"c%d\",\"display\":\"Concept %d\"}".formatted(i, i))
			.collect(Collectors.joining(","));
		final String codeSystem = ("{\"resourceType\":\"CodeSystem\",\"url\":\"%s\",\"status\":\"active\","
			+ "\"content\":\"complete\",\"concept\":[%s]}").formatted(TEST_URL, concepts);
		try {
			this.engine.addCanonicalResource((CodeSystem) new JsonParser(this.engine.getContext().getModelContext())
				.parse(new ByteArrayInputStream(codeSystem.getBytes(StandardCharsets.UTF_8))));
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	void testFetchReturnsACopyOfTheConvertedResource() throws Exception {
		final Element first = this.engine.fetch(null, null, URL);
		final Element second = this.engine.fetch(null, null, URL);
		assertNotNull(first);
		assertNotSame(first, second);

		// the same content as the conversion without the cache
		final var codeSystem = this.engine.getContext().fetchResource(CodeSystem.class, URL,
																						  VersionResolutionRules.defaultRule());
		final String expected = this.compose(new ObjectConverter(this.engine.getContext()).convert(codeSystem));
		assertEquals(expected, this.compose(first));
		assertEquals(expected, this.compose(second));

		// the list items are numbered, as by the parser
		final List<Element> concepts = second.getChildrenByName("concept");
		assertEquals(4, concepts.size());
		for (int i = 0; i < concepts.size(); i++) {
			assertEquals(i, concepts.get(i).getIndex());
		}
	}

	@Test
	void testChangesToACopyDontChangeTheNextOne() throws Exception {
		final Element first = this.engine.fetch(null, null, URL);
		final String before = this.compose(first);
		first.getChildrenByName("concept").get(0).getNamedChild("code").setValue("changed");
		first.removeChild("concept");

		assertEquals(before, this.compose(this.engine.fetch(null, null, URL)));
	}

	/**
	 * The copies don't share the narrative (the context removes the narratives of its resources, but contained
	 * resources may have one).
	 */
	@Test
	void testTheNarrativeIsNotShared() throws Exception {
		final String patient = "{\"resourceType\":\"Patient\",\"text\":{\"status\":\"generated\",\"div\":\"<div "
			+ "xmlns=\\\"http://www.w3.org/1999/xhtml\\\"><p>Narrative</p></div>\"}}";
		final Element original = Manager.parseSingle(this.engine.getContext(),
																	new ByteArrayInputStream(patient.getBytes(StandardCharsets.UTF_8)),
																	FhirFormat.JSON);
		final Element copy = original.copy(EnumSet.allOf(Base.CopyObjectOptions.class));
		final var div = original.getNamedChild("text").getNamedChild("div").getXhtml();
		final var copiedDiv = copy.getNamedChild("text").getNamedChild("div").getXhtml();
		assertNotSame(div, copiedDiv);
		final String text = div.allText();
		assertEquals(text, copiedDiv.allText());

		copiedDiv.addText(" changed");
		assertEquals(text, div.allText());
	}

	/**
	 * Concurrent validations fetch the same resource and navigate their copies by name (in the name map of
	 * NamedItemList), and change them: each one must see all children.
	 */
	@Test
	void testConcurrentFetches() throws Exception {
		final var codeSystem = this.engine.getContext().fetchResource(CodeSystem.class, TEST_URL,
																						  VersionResolutionRules.defaultRule());
		final Element converted = new ObjectConverter(this.engine.getContext()).convert(codeSystem);
		final List<String> expectedCodes = codes(converted);
		assertEquals(TEST_CONCEPTS, expectedCodes.size());
		final String expected = this.compose(converted);

		final int threads = 8;
		final int iterations = 500;
		final Queue<String> failures = new ConcurrentLinkedQueue<>();
		final CountDownLatch start = new CountDownLatch(1);
		final ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			final List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				futures.add(executor.submit(() -> {
					start.await();
					for (int i = 0; i < iterations; i++) {
						final Element copy = this.engine.fetch(null, null, TEST_URL);
						final List<String> codes = codes(copy);
						if (!expectedCodes.equals(codes)) {
							failures.add("codes: " + codes);
						}
						if (i % 50 == 0 && !expected.equals(this.compose(copy))) {
							failures.add("content differs");
						}
						// the validator decorates its copy, e.g. with the parents; and changes to a copy must not be seen
						// by the others
						copy.getChildrenByName("concept").forEach(c -> c.setParentForValidator(copy));
						copy.getChildrenByName("concept").get(0).getChildrenByName("code").get(0).setValue("changed");
						copy.removeChild("concept");
					}
					return null;
				}));
			}
			start.countDown();
			for (final Future<?> future : futures) {
				future.get();
			}
		} finally {
			executor.shutdownNow();
		}
		assertTrue(failures.isEmpty(), () -> failures.size() + " failures, e.g. " + failures.peek());
		assertEquals(expected, this.compose(this.engine.fetch(null, null, TEST_URL)));
	}

	private static List<String> codes(final Element codeSystem) {
		return codeSystem.getChildrenByName("concept").stream()
			.map(concept -> concept.getChildrenByName("code").get(0).primitiveValue())
			.toList();
	}

	@Test
	void testUnknownUrl() throws Exception {
		assertNull(this.engine.fetch(null, null, "http://example.org/fhir/CodeSystem/unknown"));
	}

	private String compose(final Element element) throws IOException {
		final var out = new ByteArrayOutputStream();
		Manager.compose(this.engine.getContext(), element, out, FhirFormat.JSON, OutputStyle.PRETTY, null);
		return out.toString(StandardCharsets.UTF_8);
	}
}
