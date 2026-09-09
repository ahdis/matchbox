package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r5.context.ContextUtilities;
import org.hl7.fhir.r5.context.IWorkerContext;
import org.hl7.fhir.r5.context.IWorkerContext.VersionResolutionRules;
import org.hl7.fhir.r5.elementmodel.Manager.FhirFormat;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.PackageInformation;
import org.hl7.fhir.r5.model.ValueSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import ch.ahdis.matchbox.engine.MatchboxEngine;

/**
 * Regression test for #538: the resolution of an unversioned canonical must be the same in a copied engine as in the
 * original one.
 * <p>
 * The R4 engine loads hl7.fhir.uv.xver-r5.r4, which contains the R5 version (5.0.0) of the CodeSystem
 * http://hl7.org/fhir/encounter-status next to the R4 version (4.0.1) of hl7.fhir.r4.core. An unversioned reference to
 * that CodeSystem is resolved with the dependencies of the package of the referencing resource, or with the definition
 * of the core package (the 'master' package) if there is no such package. The copy of the context did not keep the
 * package information and the master definitions, so the copied engine (as used by the server for each validation
 * request, see MatchboxEngineSupport) fell back to the latest version, 5.0.0, which does not contain the R4 code
 * 'finished'.
 *
 * @see <a href="https://github.com/ahdis/matchbox/issues/538">Problem with multiple inheritance of the same valueset/terminology</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Issue538Test {

	private static final String CS_ENCOUNTER_STATUS = "http://hl7.org/fhir/encounter-status";
	private static final String VS_URL = "http://matchbox.health/test/ValueSet/encounter-status-issue538";
	private static final String PROFILE_URL = "http://matchbox.health/test/StructureDefinition/encounter-issue538";

	private static final String VALUE_SET = """
		{
		  "resourceType": "ValueSet",
		  "url": "%s",
		  "version": "1.0.0",
		  "name": "EncounterStatusIssue538",
		  "status": "active",
		  "compose": {
		    "include": [
		      {
		        "system": "%s",
		        "concept": [ { "code": "in-progress" }, { "code": "finished" } ]
		      }
		    ]
		  }
		}
		""".formatted(VS_URL, CS_ENCOUNTER_STATUS);

	private static final String PROFILE = """
		{
		  "resourceType": "StructureDefinition",
		  "url": "%s",
		  "version": "1.0.0",
		  "name": "EncounterIssue538",
		  "status": "active",
		  "fhirVersion": "4.0.1",
		  "kind": "resource",
		  "abstract": false,
		  "type": "Encounter",
		  "baseDefinition": "http://hl7.org/fhir/StructureDefinition/Encounter",
		  "derivation": "constraint",
		  "differential": {
		    "element": [
		      { "id": "Encounter", "path": "Encounter" },
		      {
		        "id": "Encounter.status",
		        "path": "Encounter.status",
		        "binding": { "strength": "required", "valueSet": "%s" }
		      }
		    ]
		  }
		}
		""".formatted(PROFILE_URL, VS_URL);

	private static final String ENCOUNTER = """
		{
		  "resourceType": "Encounter",
		  "status": "finished",
		  "class": { "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "IMP" }
		}
		""";

	private final MatchboxEngine engine;

	Issue538Test() throws Exception {
		this.engine = new MatchboxEngine.MatchboxEngineBuilder().getEngineR4();
		// Resources without a package, as when they are uploaded to the server
		this.engine.addCanonicalResource(this.parseR4(VALUE_SET));
		this.engine.addCanonicalResource(this.engine.createSnapshot(
			(org.hl7.fhir.r4.model.StructureDefinition) this.parseR4(PROFILE)));
	}

	/**
	 * The precondition of this test: the R4 engine knows two versions of the CodeSystem.
	 */
	@Test
	void engineContainsBothVersionsOfTheCodeSystem() {
		final List<String> versions = new ContextUtilities(this.engine.getContext()).fetchCodeSystemVersions(CS_ENCOUNTER_STATUS);
		assertTrue(versions.contains("4.0.1"), "Expected the R4 CodeSystem, got " + versions);
		assertTrue(versions.contains("5.0.0"), "Expected the R5 CodeSystem from hl7.fhir.uv.xver-r5.r4, got " + versions);
	}

	@Test
	void codeSystemResolutionIsTheSameInTheCopiedContext() throws Exception {
		// A ValueSet from a package that depends on the R4 core package
		final ValueSet vsFromR4Package = this.cacheValueSetFromPackage("test.issue538.r4", "hl7.fhir.r4.core#4.0.1");
		// A ValueSet from a package that depends on the R5 cross-version package
		final ValueSet vsFromR5Package = this.cacheValueSetFromPackage("test.issue538.r5", "hl7.fhir.uv.xver-r5.r4#0.1.0");

		final IWorkerContext original = this.engine.getContext();
		final IWorkerContext copy = new MatchboxEngine(this.engine).getContext();
		for (final IWorkerContext context : List.of(original, copy)) {
			// Without a referencing resource, the definition of the core (master) package is expected
			assertEquals("4.0.1", this.resolve(context, null));
			// With a referencing resource, the version from the package dependencies is expected
			assertEquals("4.0.1", this.resolve(context, vsFromR4Package));
			assertEquals("5.0.0", this.resolve(context, vsFromR5Package));
		}
	}

	/**
	 * The server validates with a copy of the main engine.
	 */
	@Test
	void encounterStatusFinishedIsValidWithTheCopiedEngine() throws Exception {
		this.expectValid(new MatchboxEngine(this.engine));
		this.expectValid(this.engine);
	}

	private String resolve(final IWorkerContext context, final ValueSet sourceOfReference) {
		final CodeSystem cs = context.fetchCodeSystem(CS_ENCOUNTER_STATUS, VersionResolutionRules.defaultRule(), null, sourceOfReference);
		return cs != null ? cs.getVersion() : null;
	}

	private ValueSet cacheValueSetFromPackage(final String packageId, final String dependency) {
		final PackageInformation packageInfo = new PackageInformation(packageId, "1.0.0", "4.0.1", new Date());
		packageInfo.getDependencies().add(dependency);
		final ValueSet vs = new ValueSet();
		vs.setId(packageId.replace('.', '-'));
		vs.setUrl("http://matchbox.health/test/" + packageId + "/ValueSet/encounter-status");
		vs.setVersion("1.0.0");
		vs.getCompose().addInclude().setSystem(CS_ENCOUNTER_STATUS);
		this.engine.getContext().cacheResourceFromPackage(vs, packageInfo);
		return vs;
	}

	private void expectValid(final MatchboxEngine engine) throws Exception {
		final OperationOutcome outcome = engine.validate(
			new ByteArrayInputStream(ENCOUNTER.getBytes(StandardCharsets.UTF_8)),
			FhirFormat.JSON,
			PROFILE_URL);
		final var errors = outcome.getIssue().stream()
			.filter(issue -> OperationOutcome.IssueSeverity.FATAL == issue.getSeverity()
				|| OperationOutcome.IssueSeverity.ERROR == issue.getSeverity())
			.map(issue -> issue.getDetails().getText())
			.collect(Collectors.toList());
		assertEquals(0, errors.size(), "Unexpected validation errors: " + errors);
	}

	private org.hl7.fhir.r4.model.Resource parseR4(final String json) throws Exception {
		return new org.hl7.fhir.r4.formats.JsonParser().parse(json);
	}
}
