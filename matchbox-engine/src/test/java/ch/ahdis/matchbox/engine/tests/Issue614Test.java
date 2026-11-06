package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;

import java.nio.charset.StandardCharsets;

import org.hl7.fhir.convertors.loaders.loaderRN.BaseLoaderRN;
import org.hl7.fhir.model.core.CodeSystem;
import org.hl7.fhir.model.core.Resource;
import org.hl7.fhir.model.core.StructureDefinition;
import org.hl7.fhir.model.core.ValueSet;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.validation.ValidatorUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.packages.PackageResourceParser;

/**
 * Tests for #614: the narrative of package resources isn't needed for the validation.
 * <p>
 * {@link PackageResourceParser}, which the server uses for the resources of the IG packages, and the core loaders with
 * the {@code skipNarrative} option (matchbox patch of {@link BaseLoaderRN}), which the engine uses, don't parse it.
 * The resources that are parsed when a package is loaded drop it in {@code BaseWorkerContext.cacheResourceFromPackage()}
 * (#566), and the lazily loaded ones (#599) in core's {@code CanonicalResourceProxy.getResource()}.
 *
 * @see <a href="https://github.com/ahdis/matchbox/issues/614">Don't parse the narrative of package resources</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Issue614Test {

	// A CodeSystem of hl7.terminology.r4#7.3.0 with 55 kB of narrative in the package
	private static final String CODE_SYSTEM_URL = "http://terminology.hl7.org/CodeSystem/v2-0203";
	private static final String CODE_SYSTEM_VERSION = "5.0.0";

	private static final String NARRATIVE =
		"{ \"status\": \"generated\", \"div\": \"<div xmlns=\\\"http://www.w3.org/1999/xhtml\\\"><p>Narrative</p></div>\" }";

	/**
	 * A profile and a contained ValueSet with a narrative.
	 */
	private static final String PROFILE = """
		{
		  "resourceType": "StructureDefinition",
		  "id": "composition-narrative",
		  "text": %1$s,
		  "contained": [
		    { "resourceType": "ValueSet", "id": "vs", "text": %1$s, "status": "active" }
		  ],
		  "url": "http://matchbox.health/test/StructureDefinition/composition-narrative",
		  "name": "CompositionNarrative",
		  "status": "active",
		  "kind": "resource",
		  "abstract": false,
		  "type": "Composition",
		  "baseDefinition": "http://hl7.org/fhir/StructureDefinition/Composition",
		  "derivation": "constraint",
		  "differential": {
		    "element": [
		      { "id": "Composition", "path": "Composition" },
		      { "id": "Composition.title", "path": "Composition.title", "min": 1 }
		    ]
		  }
		}
		""".formatted(NARRATIVE);

	private final MatchboxEngine engine;

	Issue614Test() throws Exception {
		this.engine = new MatchboxEngine.MatchboxEngineBuilder().getEngineR4();
	}

	@ParameterizedTest
	@ValueSource(strings = {"4.0.1", "4.3.0", "5.0.0"})
	void packageResourceParserSkipsTheNarrative(final String fhirVersion) throws Exception {
		final StructureDefinition sd = assertInstanceOf(StructureDefinition.class,
			PackageResourceParser.parseJson(fhirVersion, PROFILE.getBytes(StandardCharsets.UTF_8)));

		assertFalse(sd.hasText(), "The narrative of the resource should not have been parsed");
		final ValueSet contained = assertInstanceOf(ValueSet.class, sd.getContained().get(0));
		assertFalse(contained.hasText(), "The narrative of the contained resource should not have been parsed");

		assertEquals("active", contained.getStatus().toCode());
		assertEquals(1, sd.getDifferential().getElementList().get(1).getMin());
	}

	@ParameterizedTest
	@ValueSource(strings = {"3.0.2", "4.0.1", "4.3.0", "5.0.0"})
	void loaderSkipsTheNarrative(final String fhirVersion) throws Exception {
		final BaseLoaderRN loader = ValidatorUtils.loaderForVersion(org.hl7.fhir.model.ModelContext.fullCoreContext(), fhirVersion).setSkipNarrative(true);
		final Resource fromBundle = loader.loadBundle(profile(), true).getEntryFirstRep().getResource();
		for (final Resource resource : new Resource[]{fromBundle, loader.loadResource(profile(), true)}) {
			final StructureDefinition sd = assertInstanceOf(StructureDefinition.class, resource);
			assertFalse(sd.hasText(), "The narrative of the resource should not have been parsed");
			assertFalse(sd.getContained().get(0) instanceof final ValueSet vs && vs.hasText(),
							"The narrative of the contained resource should not have been parsed");
			assertEquals(1, sd.getDifferential().getElementList().get(1).getMin());
		}
	}

	@Test
	void loaderKeepsTheNarrativeByDefault() throws Exception {
		final BaseLoaderRN loader = ValidatorUtils.loaderForVersion(org.hl7.fhir.model.ModelContext.fullCoreContext(), "4.0.1");
		final StructureDefinition sd = assertInstanceOf(StructureDefinition.class,
																		loader.loadBundle(profile(), true).getEntryFirstRep().getResource());
		assertTrue(sd.hasText());
	}

	@Test
	void newLoaderSkipsTheNarrativeToo() throws Exception {
		final BaseLoaderRN loader = ValidatorUtils.loaderForVersion(org.hl7.fhir.model.ModelContext.fullCoreContext(), "4.0.1").setSkipNarrative(true);
		final NpmPackage r5Package = NpmPackage.fromPackage(Issue614Test.class.getResourceAsStream("/hl7.fhir.r5.core.tgz"));
		assertTrue(((BaseLoaderRN) loader.getNewLoader(r5Package)).isSkipNarrative());
	}

	@Test
	void lazilyLoadedResourceHasNoNarrative() {
		final CodeSystem cs = this.engine.getContext().fetchResource(CodeSystem.class, CODE_SYSTEM_URL,
																						 CODE_SYSTEM_VERSION, null);
		assertNotNull(cs);
		assertEquals("hl7.terminology.r4", cs.getSourcePackage().getId());
		assertFalse(cs.hasText(), "The narrative should have been dropped");
		assertFalse(cs.getConceptList().isEmpty());
	}

	private static ByteArrayInputStream profile() {
		return new ByteArrayInputStream(PROFILE.getBytes(StandardCharsets.UTF_8));
	}
}
