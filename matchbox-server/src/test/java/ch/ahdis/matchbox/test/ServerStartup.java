package ch.ahdis.matchbox.test;

import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Waits until the matchbox server started by a {@code @SpringBootTest} is ready to be used by the tests.
 * <p>
 * The whole startup is synchronous: Spring Boot starts the embedded web server during the context refresh, and the
 * {@code ApplicationReadyEvent} listener that installs the configured IGs and creates the main validation engine
 * ({@code MatchboxEventListener}) runs on the same thread before {@code SpringApplication.run()} returns, i.e. before
 * the test instance is created. The server is therefore normally ready on the first check; these checks replace the
 * former blind 10 s sleep with a deterministic safety net that fails with a clear message instead.
 */
public final class ServerStartup {
	private static final Logger log = LoggerFactory.getLogger(ServerStartup.class);

	/**
	 * Generous upper bound, so that slow CI runners never fail; the checks return as soon as the server is ready.
	 */
	private static final Duration TIMEOUT = Duration.ofMinutes(2);
	private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

	private ServerStartup() {
	}

	/**
	 * Waits until the main validation engine has been created from the configured IGs.
	 */
	public static void awaitEngineInitialized(final MatchboxEngineSupport engineSupport) throws InterruptedException {
		await("the validation engine to be initialized", engineSupport::isInitialized);
	}

	/**
	 * Waits until the main validation engine has been created and the FHIR endpoint answers the capabilities request.
	 *
	 * @param serverBase the server base, e.g. {@code http://localhost:8081/matchboxv3}
	 */
	public static void awaitServerReady(final String serverBase,
													final MatchboxEngineSupport engineSupport) throws InterruptedException {
		awaitEngineInitialized(engineSupport);
		final var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		final var request = HttpRequest.newBuilder(URI.create(serverBase + "/fhir/metadata"))
			.header("Accept", "application/fhir+json")
			.timeout(Duration.ofSeconds(60))
			.GET()
			.build();
		await("GET " + request.uri() + " to succeed", () -> {
			try {
				return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
			} catch (final IOException e) {
				log.debug("Server not reachable yet: {}", e.toString());
				return false;
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		});
	}

	private static void await(final String description,
									  final BooleanSupplier condition) throws InterruptedException {
		final long start = System.nanoTime();
		final long deadline = start + TIMEOUT.toNanos();
		while (!condition.getAsBoolean()) {
			if (Thread.currentThread().isInterrupted()) {
				throw new InterruptedException("Interrupted while waiting for " + description);
			}
			if (System.nanoTime() - deadline > 0) {
				throw new IllegalStateException("Timed out after " + TIMEOUT.toSeconds() + " s waiting for " + description);
			}
			Thread.sleep(POLL_INTERVAL.toMillis());
		}
		log.info("Waited {} ms for {}", Duration.ofNanos(System.nanoTime() - start).toMillis(), description);
	}
}
