package org.hl7.fhir.utilities.npm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Matchbox serves the FHIR core packages from the classpath instead of going to a package server. These tests
 * pin down which package identifiers that applies to.
 */
class FilesystemPackageCacheManagerTest {

	/** The message of the exception raised when a package is expected on the classpath but is not there. */
	private static final String CLASSPATH_FAILURE = "we don't want go to the package server";

	private static FilesystemPackageCacheManager manager;

	@BeforeAll
	static void setUp() throws IOException {
		manager = new FilesystemPackageCacheManager.Builder().withTestingCacheFolder().build();
	}

	@Test
	void fhirCorePackagesAreServedFromTheClasspath() throws IOException {
		for (final String id : List.of("hl7.fhir.r4.core", "hl7.fhir.r4b.core", "hl7.fhir.r5.core")) {
			final var loaded = manager.loadFromPackageServer(id, "4.0.1");
			assertNotNull(loaded, id + " should be served from the classpath");
			assertNotNull(loaded.stream, id + " should provide a stream");
			loaded.stream.close();
		}
	}

	/**
	 * National core IGs such as hl7.fhir.fr.core, hl7.fhir.us.core or hl7.fhir.be.core are ordinary packages:
	 * they are not shipped in the matchbox-engine jar and must be resolved through the package server. They
	 * used to be captured by a `startsWith("hl7.fhir") && endsWith("core")` test and failed to resolve.
	 */
	@Test
	void nationalCoreIgsAreNotServedFromTheClasspath() {
		for (final String id : List.of("hl7.fhir.fr.core", "hl7.fhir.us.core", "hl7.fhir.be.core")) {
			String message = null;
			try {
				final var loaded = manager.loadFromPackageServer(id, "0.0.1-this-version-does-not-exist");
				if (loaded != null && loaded.stream != null) {
					loaded.stream.close();
				}
			} catch (final Exception e) {
				message = String.valueOf(e.getMessage());
			}
			// Whether the package server can be reached or not is irrelevant here: what matters is that the
			// package was looked for on the package server and not on the classpath.
			assertFalse(message != null && message.contains(CLASSPATH_FAILURE),
							"%s must be resolved through the package server, but was looked up on the classpath: %s"
								.formatted(id, message));
		}
	}
}
