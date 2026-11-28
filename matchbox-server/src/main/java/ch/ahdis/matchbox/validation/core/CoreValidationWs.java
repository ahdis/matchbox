package ch.ahdis.matchbox.validation.core;

import ca.uhn.fhir.rest.api.EncodingEnum;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.engine.exception.MatchboxEngineCreationException;
import ch.ahdis.matchbox.validation.IgSelection;
import ch.ahdis.matchbox.validation.MatchboxValidationService;
import ch.ahdis.matchbox.validation.PreparedValidation;
import ch.ahdis.matchbox.validation.ProfileSelection;
import ch.ahdis.matchbox.validation.ValidationContent;
import ch.ahdis.matchbox.validation.ValidationResult;
import ch.ahdis.matchbox.validation.core.CoreValidationRequest.CoreBadRequestException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.annotation.Nullable;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.validation.service.model.FileInfo;
import org.hl7.fhir.validation.service.model.ValidationOutcome;
import org.hl7.fhir.validation.service.model.ValidationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The validation API of the HL7 validator, as validator.fhir.org has it ({@code POST /validate} of the
 * org.hl7.fhir.validator-wrapper, with the {@code ValidationRequest} and {@code ValidationResponse} of
 * org.hl7.fhir.core), so that a client of the HL7 validator can use matchbox with only the address changed.
 * <p>
 * It is under {@code /core}, as the path {@code /validate} is the validation page of the web interface.
 * <p>
 * See https://github.com/ahdis/matchbox/issues/702.
 */
@RestController
@RequestMapping(path = "/core")
public class CoreValidationWs {
	private static final Logger log = LoggerFactory.getLogger(CoreValidationWs.class);

	/**
	 * The response is written with Gson from the classes of the core library, as the HL7 validator does: the issues
	 * are its {@link ValidationMessage}, with all their fields.
	 */
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final MatchboxValidationService validationService;

	public CoreValidationWs(final MatchboxValidationService validationService) {
		this.validationService = Objects.requireNonNull(validationService);
	}

	/**
	 * Validates the resources of the request, and returns the issues per resource.
	 * <p>
	 * An invalid request gives HTTP 400 with the error as text, as the HL7 validator does. A resource that cannot be
	 * validated (e.g. an unknown profile) gets a {@code FATAL} issue in its outcome.
	 */
	@PostMapping(path = "/validate")
	public ResponseEntity<String> validate(@RequestBody(required = false) final String body) {
		try {
			final CoreValidationRequest request = CoreValidationRequest.parse(body);
			log.info("Core validate: {} file(s), IGs {}, profiles {}, session {}",
						request.files().size(), request.igs(), request.profiles(), request.sessionId());
			return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.body(GSON.toJson(this.doValidate(request)));
		} catch (final CoreBadRequestException | IgSelection.IgNotInstalledException e) {
			return error(HttpStatus.BAD_REQUEST, e.getMessage());
		} catch (final MatchboxValidationService.EngineNotInitializedException e) {
			return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
		} catch (final Exception e) {
			log.error("Internal error during the core validation", e);
			return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error: " + e.getMessage());
		}
	}

	ValidationResponse doValidate(final CoreValidationRequest request) {
		if (request.profiles().size() > 1) {
			throw new CoreBadRequestException(
				"Only one profile can be given, matchbox validates against one profile per call.");
		}
		final String requestedProfile = request.profiles().isEmpty() ? null : request.profiles().getFirst();

		// The validation parameters: the ones of the server, overridden by the ones of the request
		final CliContext cliContext = this.validationService.newContext(request.parameters()::get);
		try {
			cliContext.getBestPracticeWarningLevel();
			cliContext.getResourceIdStatus();
		} catch (final IllegalArgumentException e) {
			throw new CoreBadRequestException(e.getMessage());
		}

		// The requested implementation guides select the engine. Without them, the profile does, as for $validate
		final IgSelection igSelection = this.validationService.selectIgs(request.igs());
		igSelection.applyTo(cliContext);
		if (request.fhirVersion() != null) {
			cliContext.setFhirVersion(request.fhirVersion());
		} else if (igSelection.fhirVersion() != null) {
			cliContext.setFhirVersion(igSelection.fhirVersion());
		}

		final List<ValidationOutcome> outcomes = new ArrayList<>();
		String sessionId = null;
		for (final FileInfo file : request.files()) {
			final String content = file.getFileContent();
			final EncodingEnum encoding = getEncoding(file.getFileType(), content);

			final ProfileSelection selection;
			if (requestedProfile != null) {
				selection = ProfileSelection.select(requestedProfile, null, List.of(), null);
			} else {
				final var inspection = ValidationContent.inspect(content, encoding);
				selection = ProfileSelection.select(null, null, inspection.metaProfiles(), inspection.resourceType());
			}
			if (selection == null) {
				outcomes.add(failure(file, "The resource type could not be determined, set the parameter 'profiles'"));
				continue;
			}

			final PreparedValidation prepared;
			try {
				// The engine selection completes the parameters (e.g. the implementation guide of the profile): every
				// resource gets its own
				prepared = this.validationService.prepare(selection, new CliContext(cliContext), false);
			} catch (final MatchboxValidationService.EngineNotInitializedException e) {
				throw e;
			} catch (final MatchboxEngineCreationException e) {
				outcomes.add(failure(file, e.getMessage()));
				continue;
			}
			if (sessionId == null) {
				sessionId = prepared.sessionId();
			}

			final ValidationResult result = this.validationService.validate(prepared, content, encoding);
			if (!result.isValidated()) {
				outcomes.add(failure(file, result.failure()));
				continue;
			}
			this.validationService.saveForStatistics(
				this.validationService.toOperationOutcome(UUID.randomUUID().toString(), result));
			outcomes.add(new ValidationOutcome(file, result.messages()));
		}
		return new ValidationResponse(outcomes, sessionId, null);
	}

	/**
	 * Returns the encoding of a file: from its type if it is {@code json} or {@code xml}, otherwise detected from the
	 * content.
	 */
	static EncodingEnum getEncoding(final @Nullable String fileType, final String content) {
		if (fileType != null) {
			final String type = fileType.strip().toLowerCase(Locale.ROOT);
			if (type.equals("json") || type.endsWith("+json") || type.endsWith("/json")) {
				return EncodingEnum.JSON;
			}
			if (type.equals("xml") || type.endsWith("+xml") || type.endsWith("/xml")) {
				return EncodingEnum.XML;
			}
		}
		return EncodingEnum.detectEncoding(content);
	}

	/**
	 * The outcome of a resource that could not be validated.
	 */
	private static ValidationOutcome failure(final FileInfo file, final String message) {
		final var issue = new ValidationMessage();
		issue.setLevel(ValidationMessage.IssueSeverity.FATAL);
		issue.setType(ValidationMessage.IssueType.EXCEPTION);
		issue.setMessage(message);
		final List<ValidationMessage> issues = new ArrayList<>();
		issues.add(issue);
		return new ValidationOutcome(file, issues);
	}

	private static ResponseEntity<String> error(final HttpStatus status, final String message) {
		return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(message);
	}
}
