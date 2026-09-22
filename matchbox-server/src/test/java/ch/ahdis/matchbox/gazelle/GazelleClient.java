package ch.ahdis.matchbox.gazelle;

import ch.ahdis.matchbox.validation.gazelle.models.validation.Input;
import ch.ahdis.matchbox.validation.gazelle.models.validation.ValidationProfile;
import ch.ahdis.matchbox.validation.gazelle.models.validation.ValidationReport;
import ch.ahdis.matchbox.validation.gazelle.models.validation.ValidationRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * matchbox
 *
 * @author Quentin Ligier
 **/
public class GazelleClient {

	private final URI serverUri;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final HttpClient httpClient;

	public GazelleClient(final String serverUrl) {
		this.serverUri = URI.create(serverUrl);
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	public List<ValidationProfile> getProfiles() throws IOException, InterruptedException {
		final HttpRequest request = HttpRequest.newBuilder()
			.uri(this.serverUri.resolve("validation/v2/profiles"))
			.GET()
			.build();

		final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return this.objectMapper.readValue(response.body(), new TypeReference<>() {});
	}

	public ValidationReport validate(final String content, final String profileId) throws IOException, InterruptedException {
		final var validationRequest = new ValidationRequest();
		validationRequest.setValidationProfileId(profileId);
		validationRequest.addInput(new Input()
													.setItemId("first")
													.setContent(content.getBytes(StandardCharsets.UTF_8))
													.setLocation("localhost"));

		final var dest = this.serverUri.resolve("validation/v2/validate");
		System.out.printf("Destination: %s%n", dest);

		final HttpRequest request = HttpRequest.newBuilder(dest)
			.uri(dest)
			.POST(HttpRequest.BodyPublishers.ofString(this.objectMapper.writeValueAsString(validationRequest)))
			.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();

		final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return this.objectMapper.readValue(response.body(), new TypeReference<>() {});
	}

	/**
	 * Fetches the v2 profile list and returns the raw HTTP response, optionally revalidating with the given ETag.
	 */
	public HttpResponse<String> getProfilesRaw(final String ifNoneMatch) throws IOException, InterruptedException {
		return this.conditionalGet("validation/v2/profiles", ifNoneMatch);
	}

	/**
	 * Fetches the v1 profile list and returns the raw HTTP response, optionally revalidating with the given ETag.
	 */
	public HttpResponse<String> getProfilesV1Raw(final String ifNoneMatch) throws IOException, InterruptedException {
		return this.conditionalGet("validation/profiles", ifNoneMatch);
	}

	private HttpResponse<String> conditionalGet(final String path,
															  final String ifNoneMatch) throws IOException, InterruptedException {
		final var builder = HttpRequest.newBuilder().uri(this.serverUri.resolve(path)).GET();
		if (ifNoneMatch != null) {
			builder.header("If-None-Match", ifNoneMatch);
		}
		return this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Sends a raw v2 validation request and returns the raw HTTP response.
	 */
	public HttpResponse<String> validateRaw(final String requestJson) throws IOException, InterruptedException {
		final var dest = this.serverUri.resolve("validation/v2/validate");
		final HttpRequest request = HttpRequest.newBuilder(dest)
			.POST(HttpRequest.BodyPublishers.ofString(requestJson))
			.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
		return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Sends a raw v1 validation request and returns the raw HTTP response.
	 */
	public HttpResponse<String> validateV1Raw(final String requestJson) throws IOException, InterruptedException {
		final var dest = this.serverUri.resolve("validation/validate");
		final HttpRequest request = HttpRequest.newBuilder(dest)
			.POST(HttpRequest.BodyPublishers.ofString(requestJson))
			.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
		return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Returns the raw JSON of the v1 profile list.
	 */
	public JsonNode getProfilesV1() throws IOException, InterruptedException {
		final HttpRequest request = HttpRequest.newBuilder()
			.uri(this.serverUri.resolve("validation/profiles"))
			.GET()
			.build();
		final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return this.objectMapper.readTree(response.body());
	}

	/**
	 * Sends a v1 validation request and returns the raw JSON of the v1 report.
	 */
	public JsonNode validateV1(final String content, final String profileId) throws IOException, InterruptedException {
		final var validationRequest = this.objectMapper.createObjectNode()
			.put("apiVersion", "0.1")
			.put("validationServiceName", "Matchbox")
			.put("validationProfileId", profileId);
		validationRequest.putArray("validationItems").addObject()
			.put("itemId", "first")
			.put("role", "request")
			.put("content", content.getBytes(StandardCharsets.UTF_8))
			.put("location", "localhost");
		final var dest = this.serverUri.resolve("validation/validate");
		final HttpRequest request = HttpRequest.newBuilder(dest)
			.POST(HttpRequest.BodyPublishers.ofString(this.objectMapper.writeValueAsString(validationRequest)))
			.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
		final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return this.objectMapper.readTree(response.body());
	}
}
