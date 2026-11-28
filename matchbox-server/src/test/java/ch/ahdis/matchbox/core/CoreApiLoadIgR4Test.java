package ch.ahdis.matchbox.core;

import ca.uhn.fhir.jpa.starter.Application;
import ch.ahdis.matchbox.test.CompareUtil;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@code POST /core/validate} on a server that is not read-only: an implementation guide of the request
 * that is not installed is installed from the package registry (https://github.com/ahdis/matchbox/issues/702).
 * <p>
 * {@link CoreApiR4Test} has the read-only server; {@code OnDemandIgInstallerTest} the installation itself, without
 * the package registry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
					 properties = "matchbox.fhir.context.httpReadOnly=false")
@ContextConfiguration(classes = {Application.class})
@ActiveProfiles({"tests", "test-r4"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class CoreApiLoadIgR4Test {

	private static final String VALIDATE_URL = "http://localhost:8081/matchboxv3/core/validate";

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
