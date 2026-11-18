package ch.ahdis.matchbox.validation.itb;

import ch.ahdis.matchbox.engine.cli.VersionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.gitb.model.core.ConfigurationType;
import com.gitb.model.core.Metadata;
import com.gitb.model.core.TypedParameter;
import com.gitb.model.core.UsageEnumeration;
import com.gitb.model.ps.BeginTransactionResponse;
import com.gitb.model.ps.GetModuleDefinitionResponse;
import com.gitb.model.ps.ProcessRequest;
import com.gitb.model.ps.ProcessResponse;
import com.gitb.model.ps.ProcessingModule;
import com.gitb.model.ps.ProcessingOperation;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The base of an ITB (GITB REST) processing service with one operation.
 * <p>
 * ITB calls it with {@code handlerApiType="REST"}: {@code GET {root}/getModuleDefinition},
 * {@code POST {root}/process}, and {@code POST {root}/beginTransaction} and {@code POST {root}/endTransaction} for a
 * transaction. The services are stateless, so a transaction only gets an id. The contract is the one of the HL7
 * validator's ITB services (hapifhir/org.hl7.fhir.core#2615, {@code itb-rest-spec.md} §4).
 * <p>
 * See https://github.com/ahdis/matchbox/issues/681.
 */
abstract class ItbProcessingWs {
	private static final Logger log = LoggerFactory.getLogger(ItbProcessingWs.class);

	private final String serviceId;
	private final String serviceName;
	private final String description;
	private final String operation;

	protected ItbProcessingWs(final String serviceId,
									  final String serviceName,
									  final String description,
									  final String operation) {
		this.serviceId = serviceId;
		this.serviceName = serviceName;
		this.description = description;
		this.operation = operation;
	}

	/**
	 * Returns the inputs of the operation.
	 */
	protected abstract List<TypedParameter> getInputs();

	/**
	 * Returns the outputs of the operation.
	 */
	protected abstract List<TypedParameter> getOutputs();

	/**
	 * Executes the operation.
	 *
	 * @throws ItbBadRequestException if the request is invalid, answered with HTTP 400.
	 */
	protected abstract ProcessResponse doProcess(final ItbInputs inputs);

	/**
	 * Returns the definition of the processing service, with its operation.
	 */
	@GetMapping(path = "/getModuleDefinition", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> getModuleDefinition(final HttpServletRequest request) throws JsonProcessingException {
		final var module = ProcessingModule.builder()
			.withId(this.serviceId)
			.withServiceLocation(request.getRequestURL().toString().replace("/getModuleDefinition", ""))
			.withMetadata(Metadata.builder()
								  .withName(this.serviceName)
								  .withVersion(VersionUtil.getVersion())
								  .withDescription(this.description)
								  .build())
			.withOperation(ProcessingOperation.builder()
									.withName(this.operation)
									.withInput(this.getInputs().toArray(TypedParameter[]::new))
									.withOutput(this.getOutputs().toArray(TypedParameter[]::new))
									.build())
			.build();
		return json(GetModuleDefinitionResponse.builder().withModule(module).build());
	}

	/**
	 * Executes the operation of the service. The operation name can be left out, as the service has only one.
	 * <p>
	 * An invalid request gives HTTP 400 with {@code {"error": ...}}.
	 */
	@PostMapping(path = "/process", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> process(@RequestBody(required = false) final String body,
												@RequestHeader(name = "Gitb-Test-Session-Identifier", required = false) final String testSessionId,
												@RequestHeader(name = "Gitb-Test-Case-Identifier", required = false) final String testCaseId,
												@RequestHeader(name = "Gitb-Test-Step-Identifier", required = false) final String testStepId) {
		log.info("ITB {} {}: session {}, test case {}, step {}",
					this.serviceId, this.operation, testSessionId, testCaseId, testStepId);

		final ProcessRequest request;
		try {
			request = (body == null || body.isBlank())
				? new ProcessRequest()
				: ItbJson.mapper().readValue(body, ProcessRequest.class);
		} catch (final JsonProcessingException e) {
			return error(HttpStatus.BAD_REQUEST, "Malformed JSON: " + e.getOriginalMessage());
		}

		try {
			final String requested = request.getOperation();
			if (requested != null && !requested.isBlank() && !this.operation.equals(requested.strip())) {
				throw new ItbBadRequestException(
					"Unknown operation: %s. Supported: %s".formatted(requested, this.operation));
			}
			return json(this.doProcess(new ItbInputs(request.getInput())));
		} catch (final ItbBadRequestException e) {
			return error(HttpStatus.BAD_REQUEST, e.getMessage());
		} catch (final Exception e) {
			log.error("Internal error in the ITB service {}", this.serviceId, e);
			return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error: " + e.getMessage());
		}
	}

	/**
	 * Begins a transaction. The service is stateless, the session id is only an identifier.
	 */
	@PostMapping(path = "/beginTransaction", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> beginTransaction() throws JsonProcessingException {
		return json(BeginTransactionResponse.builder().withSessionId(UUID.randomUUID().toString()).build());
	}

	/**
	 * Ends a transaction, there is nothing to release.
	 */
	@PostMapping(path = "/endTransaction")
	public ResponseEntity<Void> endTransaction() {
		return ResponseEntity.noContent().build();
	}

	protected static TypedParameter parameter(final String name, final boolean required, final String description) {
		return TypedParameter.builder()
			.withName(name)
			.withType("string")
			.withUse(required ? UsageEnumeration.R : UsageEnumeration.O)
			.withKind(ConfigurationType.SIMPLE)
			.withDesc(description)
			.build();
	}

	/**
	 * Answers with a GITB type as JSON, written with the mapper of the GITB types ({@link ItbJson}).
	 */
	private static ResponseEntity<byte[]> json(final Object body) throws JsonProcessingException {
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(ItbJson.mapper().writeValueAsBytes(body));
	}

	private static ResponseEntity<Map<String, String>> error(final HttpStatus status, final String message) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
	}
}
