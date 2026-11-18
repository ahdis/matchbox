package ch.ahdis.matchbox.itb;

import ch.ahdis.matchbox.validation.itb.ItbJson;
import com.gitb.model.core.AnyContent;
import com.gitb.model.ps.ProcessRequest;
import com.gitb.model.ps.ProcessResponse;
import com.gitb.model.vs.GetModuleDefinitionResponse;
import com.gitb.model.tr.TAR;
import com.gitb.model.vs.ValidateRequest;
import com.gitb.model.vs.ValidationResponse;
import com.gitb.model.core.ValueEmbeddingEnumeration;
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
 * A client for an ITB (GITB REST) service of matchbox, a validation or a processing service.
 */
public class ItbClient {

	private final URI serviceUri;
	private final ObjectMapper objectMapper = ItbJson.mapper();
	private final HttpClient httpClient;

	/**
	 * @param serviceUrl the root of the service, ending with a slash.
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
		final var request = request(input("contentToValidate", content));
		inputs.forEach((name, value) -> request.getInput().add(input(name, value)));
		return this.validate(request);
	}

	public TAR validate(final ValidateRequest request) throws IOException, InterruptedException {
		final Response response = this.post(this.objectMapper.writeValueAsString(request), Map.of());
		if (response.status() != 200) {
			throw new IllegalStateException("HTTP %d: %s".formatted(response.status(), response.body()));
		}
		return this.objectMapper.readValue(response.body(), ValidationResponse.class).getReport();
	}

	/**
	 * Calls the operation of a processing service with the given inputs (embedded as STRING).
	 *
	 * @param operation the operation, or {@code null} to leave it out.
	 */
	public Response process(final String operation, final Map<String, String> inputs) throws IOException, InterruptedException {
		final var request = ProcessRequest.builder().withOperation(operation).build();
		inputs.forEach((name, value) -> request.getInput().add(input(name, value)));
		return this.post("process", this.objectMapper.writeValueAsString(request), Map.of());
	}

	public ProcessResponse readProcessResponse(final Response response) throws IOException {
		if (response.status() != 200) {
			throw new IllegalStateException("HTTP %d: %s".formatted(response.status(), response.body()));
		}
		return this.objectMapper.readValue(response.body(), ProcessResponse.class);
	}

	public Response get(final String path) throws IOException, InterruptedException {
		final HttpRequest request = HttpRequest.newBuilder(this.serviceUri.resolve(path)).GET().build();
		final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return new Response(response.statusCode(), response.body());
	}

	public Response post(final String body, final Map<String, String> headers) throws IOException, InterruptedException {
		return this.post("validate", body, headers);
	}

	public Response post(final String path,
								final String body,
								final Map<String, String> headers) throws IOException, InterruptedException {
		final var builder = HttpRequest.newBuilder(this.serviceUri.resolve(path))
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

	public static ValidateRequest request(final AnyContent... inputs) {
		return ValidateRequest.builder().withInput(inputs).build();
	}

	public static AnyContent input(final String name, final String value) {
		return AnyContent.builder().withName(name).withValue(value).withEmbeddingMethod(ValueEmbeddingEnumeration.STRING).build();
	}
}
