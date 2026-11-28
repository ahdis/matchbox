package ch.ahdis.matchbox.test;

import ca.uhn.fhir.jpa.starter.Application;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of a server that is not read-only: an implementation guide that a validation request names and that is not
 * installed is installed from the package registry, for {@code $validate} as for {@code POST /core/validate}
 * (https://github.com/ahdis/matchbox/issues/702).
 * <p>
 * {@code CoreApiR4Test}, {@code MatchboxApiR4Test} and {@code ItbApiR4Test} have the read-only server;
 * {@code OnDemandIgInstallerTest} the installation itself, without the package registry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
					 properties = "matchbox.fhir.context.httpReadOnly=false")
@ContextConfiguration(classes = {Application.class})
@ActiveProfiles({"tests", "test-r4"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class LoadIgOnDemandR4Test {

	private static final String VALIDATE_URL = "http://localhost:8081/matchboxv3/core/validate";
	private static final String FHIR_VALIDATE_URL = "http://localhost:8081/matchboxv3/fhir/$validate";
	private static final String PATIENT_PROFILE = "http://hl7.org/fhir/StructureDefinition/Patient";

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	@BeforeAll
	void waitUntilStartup() throws Exception {
		Thread.sleep(10000); // give the server some time to start up
		CompareUtil.logMemory();
	}

	/**
	 * The installation is tried, and fails: the package registry doesn't have this version of the test IG (or cannot
	 * be reached).
	 */
	@Test
	void igThatCannotBeInstalled() throws Exception {
		final String ig = "matchbox.health.test.ig.r4#9.9.9";
		final HttpResponse<String> response = this.validate(ig);
		assertEquals(400, response.statusCode(), response.body());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
		assertTrue(response.body().startsWith("The package %s could not be installed: ".formatted(ig)), response.body());
	}

	/**
	 * Without its version, a package that is not installed cannot be installed.
	 */
	@Test
	void igWithoutVersion() throws Exception {
		final HttpResponse<String> response = this.validate("matchbox.health.unknown.ig");
		assertEquals(400, response.statusCode(), response.body());
		assertEquals("The package matchbox.health.unknown.ig is not installed, and it can only be installed with its "
							 + "version: 'id#version'", response.body());
	}

	/**
	 * An installed implementation guide is used as it is.
	 */
	@Test
	void installedIg() throws Exception {
		final HttpResponse<String> response = this.validate("matchbox.health.test.ig.r4");
		assertEquals(200, response.statusCode(), response.body());
	}

	/**
	 * $validate with the parameter 'ig': the installation is tried as well, and the error is in the OperationOutcome.
	 */
	@Test
	void fhirValidateWithIg() throws Exception {
		HttpResponse<String> response = this.fhirValidate("matchbox.health.test.ig.r4%239.9.9");
		assertEquals(200, response.statusCode(), response.body());
		assertTrue(response.body().contains("The package matchbox.health.test.ig.r4#9.9.9 could not be installed: "),
					  response.body());

		response = this.fhirValidate("matchbox.health.unknown.ig");
		assertTrue(response.body().contains("it can only be installed with its version"), response.body());

		// installed, with or without the version
		response = this.fhirValidate("matchbox.health.test.ig.r4");
		assertEquals(200, response.statusCode(), response.body());
		assertFalse(response.body().contains("\"severity\":\"error\""), response.body());
		assertFalse(response.body().contains("\"severity\":\"fatal\""), response.body());
	}

	private HttpResponse<String> fhirValidate(final String ig) throws Exception {
		final HttpRequest request = HttpRequest.newBuilder(
				URI.create("%s?profile=%s&ig=%s".formatted(FHIR_VALIDATE_URL, PATIENT_PROFILE, ig)))
			.header("Content-Type", "application/fhir+json")
			.header("Accept", "application/fhir+json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"resourceType\": \"Patient\"}"))
			.build();
		return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> validate(final String ig) throws Exception {
		final String body = """
			{
				"validationContext": {"igs": ["%s"]},
				"filesToValidate": [
					{"fileName": "patient.json", "fileContent": "{\\"resourceType\\": \\"Patient\\"}", "fileType": "json"}
				]
			}""".formatted(ig);
		final HttpRequest request = HttpRequest.newBuilder(URI.create(VALIDATE_URL))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
