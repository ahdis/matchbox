package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.hl7.fhir.r5.elementmodel.Manager.FhirFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import ch.ahdis.matchbox.engine.MatchboxEngine;

/**
 * Regression test for #543.
 * <p>
 * A bundle entry slice that allows several profiles is validated against each of them in
 * {@code InstanceValidator.validateContains()}; if one of them fails without reporting an error, the validator throws
 * {@code java.lang.Error: failed to validate, but no errors}.
 * <p>
 * That state was reached through {@code checkReference()}: with {@code showMessagesFromReferences} enabled (the
 * matchbox default), the messages of a resource referenced from within the bundle were copied to the referring
 * resource and made it fail, whatever their severity. Here the referenced Organization only raises the {@code dom-6}
 * best practice warning (it has no narrative), which must not fail the Device.
 *
 * @see <a href="https://github.com/ahdis/matchbox/issues/543">Internal validation failure</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Issue543Test {

	private static final String BUNDLE_PROFILE = "http://matchbox.health/test/StructureDefinition/bundle-issue543";

	/**
	 * A Device referencing an Organization of the same bundle; neither of them has a narrative.
	 */
	private static final String BUNDLE = """
		{
		  "resourceType": "Bundle",
		  "type": "collection",
		  "entry": [
		    {
		      "fullUrl": "urn:uuid:3202a55b-9b23-4836-a503-bd897e599077",
		      "resource": {
		        "resourceType": "Device",
		        "id": "3202a55b-9b23-4836-a503-bd897e599077",
		        "owner": { "reference": "urn:uuid:9fac2d9f-89c1-40e2-8c35-cad1d1a56f1a", "type": "Organization" }
		      }
		    },
		    {
		      "fullUrl": "urn:uuid:9fac2d9f-89c1-40e2-8c35-cad1d1a56f1a",
		      "resource": {
		        "resourceType": "Organization",
		        "id": "9fac2d9f-89c1-40e2-8c35-cad1d1a56f1a",
		        "name": "Zentrallabor"
		      }
		    }
		  ]
		}
		""";

	private final MatchboxEngine engine;

	Issue543Test() throws Exception {
		this.engine = new MatchboxEngine.MatchboxEngineBuilder().getEngineR4();
		this.loadProfile("StructureDefinition-device-a-issue543.json");
		this.loadProfile("StructureDefinition-device-b-issue543.json");
		this.loadProfile("StructureDefinition-bundle-issue543.json");
	}

	@Test
	void warningFromReferencedResourceDoesNotFailTheReferringResource() throws Exception {
		this.engine.getDefaultInstanceValidatorParameters().setShowMessagesFromReferences(true);

		final OperationOutcome outcome = this.engine.validate(
			new ByteArrayInputStream(BUNDLE.getBytes(StandardCharsets.UTF_8)),
			FhirFormat.JSON,
			BUNDLE_PROFILE);

		final var failures = outcome.getIssue().stream()
			.filter(issue -> OperationOutcome.IssueSeverity.FATAL == issue.getSeverity()
				|| OperationOutcome.IssueSeverity.ERROR == issue.getSeverity())
			.map(issue -> issue.getDetails().getText())
			.collect(Collectors.toList());
		assertEquals(0, failures.size(), "Unexpected validation errors: " + failures);

		// the dom-6 warnings are the only messages of the referenced Organization, and they must not fail the Device
		assertTrue(outcome.getIssue().stream()
					  .anyMatch(issue -> OperationOutcome.IssueSeverity.WARNING == issue.getSeverity()
						  && issue.getDetails().getText().contains("dom-6")),
					  "Expected the dom-6 best practice warning");
	}

	private void loadProfile(final String filename) throws IOException {
		try (final var stream = Issue543Test.class.getResourceAsStream("/issue543/" + filename)) {
			final var sd = (StructureDefinition) new org.hl7.fhir.r4.formats.JsonParser().parse(stream);
			this.engine.addCanonicalResource(this.engine.createSnapshot(sd));
		}
	}
}
