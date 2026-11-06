package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.model.utilities.formats.FhirFormat;
import org.hl7.fhir.model.core.Questionnaire;
import org.hl7.fhir.model.core.Resource;
import org.hl7.fhir.model.core.StructureDefinition;
import org.hl7.fhir.utilities.FileUtilities;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.NpmPackage.PackageResourceInformation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.packages.LazyTerminologyLoader;
import ch.ahdis.matchbox.engine.packages.PackageResourceParser;

/**
 * Regression test for #610: package resources with a file name ending in {@code template.json} weren't loaded.
 * <p>
 * The server (IgLoaderFromJpaPackageCache) parsed the resources of a package with core's
 * {@code IgLoader.loadResourceByVersion()}, which rejects these file names ("Unsupported format"), because they are IG
 * Publisher templates in the IG sources it's meant for. In a package they're ordinary resources, e.g. the profile
 * {@code sdc-questionnaire-extr-template} of {@code hl7.fhir.uv.sdc#4.0.0} in
 * {@code StructureDefinition-sdc-questionnaire-extr-template.json}. The package resources are now parsed with
 * {@link PackageResourceParser}.
 * <p>
 * The test uses a synthetic package with a profile in such a file, like the SDC one.
 *
 * @see <a href="https://github.com/ahdis/matchbox/issues/610">Package resources with a file name ending in
 * template.json aren't loaded (SDC template extraction profile)</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Issue610Test {

	private static final String PROFILE_ID = "questionnaire-extr-template";
	private static final String PROFILE_URL = "http://matchbox.health/test/StructureDefinition/" + PROFILE_ID;
	private static final String PROFILE_FILENAME = "StructureDefinition-" + PROFILE_ID + ".json";

	/**
	 * A Questionnaire profile that requires a title.
	 */
	private static final String PROFILE = """
		{
		  "resourceType": "StructureDefinition",
		  "id": "questionnaire-extr-template",
		  "url": "http://matchbox.health/test/StructureDefinition/questionnaire-extr-template",
		  "version": "0.1.0",
		  "name": "QuestionnaireExtrTemplate",
		  "status": "active",
		  "fhirVersion": "4.0.1",
		  "kind": "resource",
		  "abstract": false,
		  "type": "Questionnaire",
		  "baseDefinition": "http://hl7.org/fhir/StructureDefinition/Questionnaire",
		  "derivation": "constraint",
		  "differential": {
		    "element": [
		      { "id": "Questionnaire", "path": "Questionnaire" },
		      { "id": "Questionnaire.title", "path": "Questionnaire.title", "min": 1 }
		    ]
		  }
		}
		""";

	private static final String PACKAGE_JSON = """
		{
		  "name": "matchbox.health.test.issue610",
		  "version": "0.1.0",
		  "fhirVersions": ["4.0.1"],
		  "dependencies": { "hl7.fhir.r4.core": "4.0.1" }
		}
		""";

	private final byte[] tgz;
	private final MatchboxEngine main;

	Issue610Test() throws Exception {
		this.tgz = createPackage();
		this.main = new MatchboxEngine.MatchboxEngineBuilder().getEngineR4();
	}

	/**
	 * Parses the resources of the package index like IgLoaderFromJpaPackageCache.loadIg() does.
	 */
	@Test
	void packageResourceWithTemplateFileNameIsParsed() throws Exception {
		final NpmPackage npm = NpmPackage.fromPackage(new ByteArrayInputStream(this.tgz));
		final List<PackageResourceInformation> indexed = npm.listIndexedResources(Set.of("StructureDefinition"));
		assertEquals(1, indexed.size());
		final String filename = LazyTerminologyLoader.getPackageFolderFilename(indexed.get(0));
		assertEquals(PROFILE_FILENAME, filename);
		assertTrue(filename.endsWith("template.json"));

		final byte[] content = FileUtilities.streamToBytes(npm.load("package", filename));
		final Resource resource = PackageResourceParser.parseJson(npm.fhirVersion(), content);
		final StructureDefinition sd = assertInstanceOf(StructureDefinition.class, resource);
		assertEquals(PROFILE_URL, sd.getUrl());
	}

	@Test
	void validatesAgainstProfileWithTemplateFileName() throws Exception {
		final MatchboxEngine engine = new MatchboxEngine(this.main);
		engine.loadPackage(new ByteArrayInputStream(this.tgz));
		assertNotNull(engine.getContext().fetchResource(StructureDefinition.class, PROFILE_URL));

		final OperationOutcome withTitle = validate(engine, """
			{ "resourceType": "Questionnaire", "title": "Extraction", "status": "draft" }
			""");
		assertEquals(0, errors(withTitle), () -> "Unexpected errors: " + messages(withTitle));

		final OperationOutcome withoutTitle = validate(engine, """
			{ "resourceType": "Questionnaire", "status": "draft" }
			""");
		assertEquals(1, errors(withoutTitle), () -> "Expected the missing title only: " + messages(withoutTitle));
		assertTrue(messages(withoutTitle).contains("Questionnaire.title"), messages(withoutTitle));
	}

	@ParameterizedTest
	@ValueSource(strings = {"3.0.2", "4.0.1", "4.3.0", "5.0.0"})
	void parsesThePackageFhirVersions(final String fhirVersion) throws Exception {
		final byte[] content = """
			{ "resourceType": "Questionnaire", "url": "http://matchbox.health/test/Questionnaire/q", "status": "draft" }
			""".getBytes(StandardCharsets.UTF_8);
		final Questionnaire questionnaire = assertInstanceOf(Questionnaire.class,
																			  PackageResourceParser.parseJson(fhirVersion, content));
		assertEquals("http://matchbox.health/test/Questionnaire/q", questionnaire.getUrl());
	}

	@Test
	void rejectsUnsupportedFhirVersions() {
		final byte[] content = "{ \"resourceType\": \"Questionnaire\" }".getBytes(StandardCharsets.UTF_8);
		assertThrows(FHIRException.class, () -> PackageResourceParser.parseJson("1.0.2", content));
		assertThrows(FHIRException.class, () -> PackageResourceParser.parseJson(null, content));
	}

	private static OperationOutcome validate(final MatchboxEngine engine, final String questionnaire) throws Exception {
		return engine.validate(new ByteArrayInputStream(questionnaire.getBytes(StandardCharsets.UTF_8)),
									  FhirFormat.JSON, PROFILE_URL);
	}

	private static long errors(final OperationOutcome outcome) {
		return outcome.getIssue().stream()
			.filter(issue -> issue.getSeverity() == OperationOutcome.IssueSeverity.ERROR
				|| issue.getSeverity() == OperationOutcome.IssueSeverity.FATAL)
			.count();
	}

	private static String messages(final OperationOutcome outcome) {
		return outcome.getIssue().stream()
			.map(issue -> issue.getSeverity() + " " + issue.getExpression() + ": " + issue.getDetails().getText())
			.toList()
			.toString();
	}

	/**
	 * A package with the profile in a file whose name ends with template.json; the package index is built by
	 * NpmPackage when it's read.
	 */
	private static byte[] createPackage() throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (final TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
			addEntry(tar, "package/package.json", PACKAGE_JSON);
			addEntry(tar, "package/" + PROFILE_FILENAME, PROFILE);
		}
		return out.toByteArray();
	}

	private static void addEntry(final TarArchiveOutputStream tar, final String name, final String content)
		throws IOException {
		final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		final TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(bytes.length);
		tar.putArchiveEntry(entry);
		tar.write(bytes);
		tar.closeArchiveEntry();
	}
}
