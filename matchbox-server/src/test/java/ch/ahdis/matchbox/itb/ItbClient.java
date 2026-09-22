package ch.ahdis.matchbox.itb;

import ch.ahdis.matchbox.validation.itb.models.AnyContent;
import ch.ahdis.matchbox.validation.itb.models.GetModuleDefinitionResponse;
import ch.ahdis.matchbox.validation.itb.models.TAR;
import ch.ahdis.matchbox.validation.itb.models.ValidateRequest;
import ch.ahdis.matchbox.validation.itb.models.ValidationResponse;
import ch.ahdis.matchbox.validation.itb.models.ValueEmbeddingEnumeration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * A client for the ITB (GITB REST) validation service of matchbox.
 */
public class ItbClient {

	private final URI serviceUri;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final HttpClient httpClient;

	/**
	 * @param serviceUrl the root of the validation service, ending with a slash.
	 */
	public ItbClient(final String serviceUrl) {
		this.serviceUri = URI.create(serviceUrl);
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	/**
	 * The raw answer of the service.
	 */
	public record Response(int status, String body) {
	}

	public GetModuleDefinitionResponse getModuleDefinition() throws IOException, InterruptedException {
		final HttpRequest request = HttpRequest.newBuilder(this.serviceUri.resolve("getModuleDefinition")).GET().build();
		final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return this.objectMapper.readValue(response.body(), GetModuleDefinitionResponse.class);
	}

	/**
	 * Validates the content with the given inputs, and returns the report.
	 *
	 * @param inputs the inputs besides {@code contentToValidate}, as name and value (embedded as STRING).
	 */
	public TAR validate(final String content, final Map<String, String> inputs) throws IOException, InterruptedException {
		final var request = new ValidateRequest().addInput(input("contentToValidate", content));
		inputs.forEach((name, value) -> request.addInput(input(name, value)));
		return this.validate(request);
	}

	public TAR validate(final ValidateRequest request) throws IOException, InterruptedException {
		final Response response = this.post(this.objectMapper.writeValueAsString(request), Map.of());
		if (response.status() != 200) {
			throw new IllegalStateException("HTTP %d: %s".formatted(response.status(), response.body()));
		}
		return this.objectMapper.readValue(response.body(), ValidationResponse.class).getReport();
	}

	public Response post(final String body, final Map<String, String> headers) throws IOException, InterruptedException {
		final var builder = HttpRequest.newBuilder(this.serviceUri.resolve("validate"))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
		headers.forEach(builder::header);
		final HttpResponse<String> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		return new Response(response.statusCode(), response.body());
	}

	public Response post(final ValidateRequest request) throws IOException, InterruptedException {
		return this.post(request, Map.of());
	}

	public Response post(final ValidateRequest request,
								final Map<String, String> headers) throws IOException, InterruptedException {
		return this.post(this.objectMapper.writeValueAsString(request), headers);
	}

	public JsonNode readTree(final String json) throws IOException {
		return this.objectMapper.readTree(json);
	}

	public static AnyContent input(final String name, final String value) {
		return new AnyContent().setName(name).setValue(value).setEmbeddingMethod(ValueEmbeddingEnumeration.STRING);
	}
}
