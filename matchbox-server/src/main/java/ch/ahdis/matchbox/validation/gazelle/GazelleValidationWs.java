package ch.ahdis.matchbox.validation.gazelle;

import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.util.StopWatch;
import ch.ahdis.matchbox.util.metrics.MatchboxMetrics;
import ch.ahdis.matchbox.validation.ValidationProvider;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.cli.VersionUtil;
import ch.ahdis.matchbox.engine.exception.MatchboxEngineCreationException;
import ch.ahdis.matchbox.validation.gazelle.models.metadata.Interface;
import ch.ahdis.matchbox.validation.gazelle.models.metadata.RestBinding;
import ch.ahdis.matchbox.validation.gazelle.models.metadata.Service;
import ch.ahdis.matchbox.validation.gazelle.models.validation.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The WebService for validation with the Gazelle Validation Service API.
 * <p>
 * Both versions of the API are served: v2 under {@code /validation/v2/}, v1 under {@code /validation/}. The models
 * follow v2; {@link GazelleApiV1Mapper} reads and writes the v1 JSON.
 *
 * @author Quentin Ligier
 **/
@RestController
@RequestMapping(path = "/gazelle")
public class GazelleValidationWs {
	private static final Logger log = LoggerFactory.getLogger(GazelleValidationWs.class);

	/**
	 * HTTP paths.
	 */
	private static final String METADATA_PATH = "/metadata";
	private static final String V1_PROFILES_PATH = "/validation/profiles";
	private static final String V1_VALIDATE_PATH = "/validation/validate";
	private static final String V2_PROFILES_PATH = "/validation/v2/profiles";
	private static final String V2_VALIDATE_PATH = "/validation/v2/validate";

	/**
	 * The single input declared by every profile: matchbox validates one FHIR resource against one profile. The id is
	 * the one Maestro uses as fallback when a profile declares no input, so test definitions work either way.
	 */
	static final String INPUT_ID = "contentToValidate";

	/**
	 * The value of the {@code Retry-After} header sent when the validation engine is not yet initialized, in seconds.
	 */
	static final int RETRY_AFTER_SECONDS = 5;

	private static final List<SupportedInput> SUPPORTED_INPUTS = List.of(
		new SupportedInput().setId(INPUT_ID).setLabel("FHIR resource (JSON or XML)").setRequired(true));

	private final MatchboxEngineSupport matchboxEngineSupport;

	private final Optional<MatchboxMetrics> matchboxMetrics;;

	private final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository;

	// The base CLI context, with the default parameters
	private final CliContext baseCliContext;

	private final GazelleApiV1Mapper v1Mapper;

	// The mapper used by Spring for the v2 responses, to serialize the profile list exactly as it is sent
	private final ObjectMapper objectMapper;

	public GazelleValidationWs(final MatchboxEngineSupport matchboxEngineSupport,
										final CliContext baseCliContext,
										final Optional<MatchboxMetrics> matchboxMetrics,
										final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository,
										final ObjectMapper objectMapper) {
		this.matchboxEngineSupport = Objects.requireNonNull(matchboxEngineSupport);
		this.baseCliContext = Objects.requireNonNull(baseCliContext);
		this.matchboxMetrics = Objects.requireNonNull(matchboxMetrics);
		this.installedStructureDefinitionRepository = Objects.requireNonNull(installedStructureDefinitionRepository);
		this.objectMapper = Objects.requireNonNull(objectMapper);
		this.v1Mapper = new GazelleApiV1Mapper(objectMapper);
	}

	/**
	 * Returns the metadata of the validation service.
	 */
	@GetMapping(path = METADATA_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> getMetadata(final HttpServletRequest request) throws JsonProcessingException {
		final var service = new Service();
		service.setName("Matchbox");
		service.setVersion(VersionUtil.getVersion());
		service.setInstanceId("NOT_SET");
		service.setReplicaId("NOT_SET");

		final var theInterface = new Interface();
		theInterface.setType("validationInterface");
		theInterface.setInterfaceName("ValidationInterface");
		theInterface.setInterfaceVersion("1.0.0");
		theInterface.setRequired(true);

		final var binding = new RestBinding();
		binding.setServiceUrl(request.getRequestURL().toString().replace(METADATA_PATH, V1_VALIDATE_PATH));
		binding.setType("restBinding");

		theInterface.setValidationProfiles(this.getProfiles());

		theInterface.addBinding(binding);

		// v2: the binding is the base URL, the profiles are listed at /validation/v2/profiles
		final var v2Interface = new Interface();
		v2Interface.setType("validationInterface");
		v2Interface.setInterfaceName("Validation Service API");
		v2Interface.setInterfaceVersion("2.0.0");
		v2Interface.setRequired(true);
		final var v2Binding = new RestBinding();
		v2Binding.setServiceUrl(request.getRequestURL().toString().replace(METADATA_PATH, ""));
		v2Binding.setType("restBinding");
		v2Interface.addBinding(v2Binding);

		service.setProvidedInterfaces(List.of(theInterface, v2Interface));

		return jsonResponse(HttpStatus.OK, this.v1Mapper.write(service));
	}

	/**
	 * Returns the list of profiles supported by this server (v1).
	 */
	@GetMapping(path = V1_PROFILES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> getProfilesV1(
		@RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) final @Nullable String ifNoneMatch)
		throws JsonProcessingException {
		return profileListResponse(this.v1Mapper.write(this.getProfiles()), ifNoneMatch);
	}

	/**
	 * Performs the validation of the given items with the given profile (v1).
	 */
	@PostMapping(path = V1_VALIDATE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces =
		MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> postValidateV1(@RequestBody final String body) throws JsonProcessingException {
		final ValidationRequest validationRequest;
		try {
			validationRequest = this.v1Mapper.readRequest(body);
		} catch (final JsonProcessingException exception) {
			return jsonResponse(HttpStatus.BAD_REQUEST, "{\"error\":\"Invalid validation request\"}");
		}
		final String requestError = checkRequest(validationRequest, "validationItems");
		if (requestError != null) {
			return jsonResponse(HttpStatus.BAD_REQUEST, "{\"error\":\"%s\"}".formatted(requestError));
		}
		final ValidationOutcome outcome = this.validate(validationRequest);
		return outcome.toResponse(this.v1Mapper.write(outcome.report()));
	}

	/**
	 * Checks that the validation request contains everything needed to perform a validation.
	 *
	 * @param validationRequest the request to check, may be {@code null}.
	 * @param inputsFieldName   the name of the inputs field in the request, which differs between v1 and v2.
	 * @return the error message if the request is invalid, {@code null} if it is valid.
	 */
	static String checkRequest(final ValidationRequest validationRequest, final String inputsFieldName) {
		if (validationRequest == null) {
			return "The validation request is missing";
		}
		if (!validationRequest.isValidationProfileIdValid()) {
			return "The field 'validationProfileId' is missing or empty";
		}
		if (!validationRequest.isInputsValid()) {
			return "The field '%s' is missing or empty".formatted(inputsFieldName);
		}
		return null;
	}

	private static ResponseEntity<String> jsonResponse(final HttpStatus status, final String json) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(json);
	}

	/**
	 * Returns the list of profiles supported by this server (v2).
	 */
	@GetMapping(path = V2_PROFILES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> getProfilesV2(
		@RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) final @Nullable String ifNoneMatch)
		throws JsonProcessingException {
		return profileListResponse(this.objectMapper.writeValueAsString(this.getProfiles()), ifNoneMatch);
	}

	/**
	 * Writes the profile list as an HTTP response, with an {@code ETag} derived from the serialized list.
	 * <p>
	 * The list is fetched and serialized on every request: nothing is cached server-side, because the table it is
	 * built from is written to often enough that invalidating a cache reliably would be harder than the query it
	 * saves. The ETag is a hash of the bytes that would be sent, so it changes exactly when the list changes, and a
	 * client that revalidates with {@code If-None-Match} is spared the transfer (~1 MB for a few thousand profiles).
	 * https://github.com/ahdis/matchbox/issues/591
	 */
	static ResponseEntity<String> profileListResponse(final String json, final @Nullable String ifNoneMatch) {
		final String etag = "\"%s\"".formatted(DigestUtils.sha256Hex(json));
		if (matchesEtag(ifNoneMatch, etag)) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
				.eTag(etag)
				.cacheControl(CacheControl.noCache())
				.build();
		}
		return ResponseEntity.ok()
			.eTag(etag)
			.cacheControl(CacheControl.noCache())
			.contentType(MediaType.APPLICATION_JSON)
			.body(json);
	}

	/**
	 * Returns whether an {@code If-None-Match} header matches the given ETag, as per RFC 9110 §13.1.2: the header is
	 * either {@code *}, which matches any existing representation, or a comma-separated list of entity tags compared
	 * with the weak comparison function (so the {@code W/} prefix is ignored).
	 *
	 * @param ifNoneMatch the value of the {@code If-None-Match} header, may be {@code null}.
	 * @param etag        the ETag of the current representation, in its quoted form.
	 */
	static boolean matchesEtag(final @Nullable String ifNoneMatch, final String etag) {
		if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
			return false;
		}
		if ("*".equals(ifNoneMatch.trim())) {
			return true;
		}
		for (final String candidate : ifNoneMatch.split(",")) {
			final String trimmed = candidate.trim();
			if (etag.equals(trimmed.startsWith("W/") ? trimmed.substring(2) : trimmed)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Builds the list of profiles supported by this server.
	 */
	public List<ValidationProfile> getProfiles() {
		final List<MbInstalledStructureDefinitionEntity> entities =
			this.installedStructureDefinitionRepository.findAllValidatable();

		final var profiles = new ArrayList<ValidationProfile>(entities.size()*2);
		entities.forEach(installedStructDef  -> {
			final var profile = new ValidationProfile();
			final var version = installedStructDef.getPackageVersion();
			profile.setProfileID("%s|%s".formatted(installedStructDef.getCanonicalUrl(), version));
			profile.setProfileName("%s: %s (%s)".formatted(installedStructDef.getType(),
																		  installedStructDef.getTitle(),
																		  version));
			profile.setDomain(installedStructDef.getPackageId());
			profile.setVersion(version);
			profile.setSupportedInputs(SUPPORTED_INPUTS);
			profiles.add(profile);

			// If the package is current, we also add it version-less
			if (installedStructDef.isCurrent()) {
				final var profile2 = new ValidationProfile();
				profile2.setProfileID(installedStructDef.getCanonicalUrl());
				profile2.setProfileName(installedStructDef.getTitle());
				profile2.setDomain(installedStructDef.getPackageId());
				profile2.setVersion(version);
				profile2.setSupportedInputs(SUPPORTED_INPUTS);
				profiles.add(profile2);
			}
		});
		return profiles;
	}

	/**
	 * Performs the validation of the given items with the given profile (v2).
	 */
	@PostMapping(path = V2_VALIDATE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces =
		MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> postValidate(@RequestBody final ValidationRequest validationRequest) {
		final String requestError = checkRequest(validationRequest, "inputs");
		if (requestError != null) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("error", requestError));
		}
		final ValidationOutcome outcome = this.validate(validationRequest);
		return outcome.toResponse(outcome.report());
	}

	/**
	 * Performs the validation of the given items with the given profile. The request must have been checked with
	 * {@link #checkRequest(ValidationRequest, String)} beforehand.
	 */
	ValidationOutcome validate(final ValidationRequest validationRequest) {
		this.matchboxMetrics.ifPresent(MatchboxMetrics::addValidation);
		final var sw = new StopWatch();
		sw.startTask("Total");

		// Use a dedicated instance of the CLI context for this request, to avoid reusing wrong information (as the IGs)
		final CliContext cliContext = new CliContext(this.baseCliContext);

		final var report = new ValidationReport();
		report.setInputs(new ArrayList<>(validationRequest.getInputs().size()));
		report.setReports(new ArrayList<>(validationRequest.getInputs().size()));
		report.setDisclaimer("Matchbox disclaims");

		String profileCanonical = validationRequest.getValidationProfileId();

		// Response: create the validation method now, with the info we already have
		final var method = new ValidationMethod();
		method.setValidationProfileID(validationRequest.getValidationProfileId());
		method.setValidationProfileName("FHIR " + validationRequest.getValidationProfileId());
		method.setValidationServiceName("Matchbox");
		method.setValidationServiceVersion(VersionUtil.getVersion());
		report.setValidationMethod(method);

		// Split the profile ID to get the specified version, if any
		final int versionSeparator = profileCanonical.lastIndexOf('|');
		if (versionSeparator != -1) {
			final String version = profileCanonical.substring(versionSeparator + 1);
			profileCanonical = profileCanonical.substring(0, versionSeparator);
			method.setValidationProfileVersion(version);
		} else {
			method.setValidationProfileVersion("not determined yet");
		}

		// Get the Matchbox engine for the requested profile
		final MatchboxEngine engine;
		try {
			engine = this.getEngine(validationRequest.getValidationProfileId(), profileCanonical, cliContext);
		} catch (final EngineNotInitializedException exception) {
			// The engine may be ready later: answer with a well-formed report and a retryable status
			report.addValidationSubReport(unexpectedError(exception.getMessage()));
			return ValidationOutcome.unavailable(updateReportFields(report));
		} catch (final Exception exception) {
			report.addValidationSubReport(unexpectedError(exception.getMessage()));
			return ValidationOutcome.ok(updateReportFields(report));
		}
		final StructureDefinition structDef = engine.getStructureDefinitionR5(profileCanonical);

		// Response: update the validation method
		method.setValidationProfileVersion(structDef.getVersion());

		// Response: add validation info
		report.setAdditionalMetadata(new ArrayList<>(cliContext.getValidateEngineParameters().size() + engine.getContext().getLoadedPackages().size() + 6));
		final var sessionId = this.matchboxEngineSupport.getSessionId(engine);
		if (sessionId != null) {
			report.addAdditionalMetadata(new Metadata().setName("sessionId").setValue(sessionId));
		}
		report.addAdditionalMetadata(new Metadata().setName("validatorVersion").setValue(VersionUtil.getPoweredBy()));
		for (final var pkg : engine.getContext().getLoadedPackages()) {
			report.addAdditionalMetadata(new Metadata().setName("package").setValue(pkg));
		}
		for (final String suppressedWarning : engine.getSuppressedWarnInfoPatterns()) {
			report.addAdditionalMetadata(new Metadata().setName("suppressedWarning").setValue(suppressedWarning));
		}		
		for (final String suppressedError : engine.getSuppressedErrors()) {
			report.addAdditionalMetadata(new Metadata().setName("suppressedError").setValue(suppressedError));
		}		
		report.addAdditionalMetadata(new Metadata().setName("profile").setValue(structDef.getUrl()));
		report.addAdditionalMetadata(new Metadata().setName("profileVersion").setValue(structDef.getVersion()));
		report.addAdditionalMetadata(new Metadata().setName("profileDate").setValue(structDef.getDateElement().getValueAsString()));

		// Response: add the validation parameters as additional metadata
		for (final Field field : cliContext.getValidateEngineParameters()) {
			field.setAccessible(true);
			final var metadata = new Metadata();
			metadata.setName(field.getName());
			try {
				if (field.get(cliContext)!=null) {
					metadata.setValue(String.valueOf(field.get(cliContext)));
					report.addAdditionalMetadata(metadata);
				}
			} catch (final IllegalAccessException exception) {
				continue;
			}
		}

		// Response: add the validation items (requests) to the response
		report.getInputs().addAll(validationRequest.getInputs());

		// Perform the validation of all items with the given engine
		for (final var item : validationRequest.getInputs()) {
			try {
				report.addValidationSubReport(this.validateItem(engine, item, profileCanonical));
			} catch (final Exception exception) {
				report.addValidationSubReport(unexpectedError(exception.getMessage()));
			}
		}

		// Response: add the validation duration
		sw.endCurrentTask();
		report.addAdditionalMetadata(new Metadata().setName("total").setValue(sw.getMillis() + "ms"));

		return ValidationOutcome.ok(updateReportFields(report));
	}

	/**
	 * The outcome of a validation: the report to send back, and the HTTP status to send it with.
	 * <p>
	 * The status is {@code 200} in all cases but one: if the validation engine is not yet initialized, the same
	 * request may succeed later, so it is answered with a {@code 503} and a {@code Retry-After} header. The report is
	 * well-formed in both cases, with an {@code UNDEFINED} overall result when nothing could be validated.
	 * https://github.com/ahdis/matchbox/issues/590
	 */
	record ValidationOutcome(HttpStatus status, ValidationReport report) {

		static ValidationOutcome ok(final ValidationReport report) {
			return new ValidationOutcome(HttpStatus.OK, report);
		}

		static ValidationOutcome unavailable(final ValidationReport report) {
			return new ValidationOutcome(HttpStatus.SERVICE_UNAVAILABLE, report);
		}

		/**
		 * Writes the outcome as an HTTP response, with the given body (the report as a v2 object or as v1 JSON).
		 */
		<T> ResponseEntity<T> toResponse(final T body) {
			final var builder = ResponseEntity.status(this.status).contentType(MediaType.APPLICATION_JSON);
			if (this.status == HttpStatus.SERVICE_UNAVAILABLE) {
				// MatchboxEngineSupport polls the initialization flag every 2 seconds
				builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
			}
			return builder.body(body);
		}
	}

	/**
	 * Retrieves the Matchbox engine for the given profile.
	 */
	MatchboxEngine getEngine(final String canonicalWithVersion,
									 final String canonical,
									 final CliContext cliContext) {
		final MatchboxEngine engine;
		try {
			engine = this.matchboxEngineSupport.getMatchboxEngine(canonicalWithVersion, cliContext, true, false);
		} catch (final Exception e) {
			log.error("Error while initializing the validation engine", e);
			throw new MatchboxEngineCreationException("Error while initializing the validation engine: %s".formatted(e.getMessage()), e);
		}
		if (engine == null || engine.getStructureDefinitionR5(canonical) == null) {
			// A reload may have been started in the meantime: the profile is then not unknown, the engine is only not
			// ready yet, and the same request may succeed later.
			this.requireInitializedEngine();
			throw new MatchboxEngineCreationException(
				"Validation for profile '%s' not supported by this validator instance".formatted(canonicalWithVersion));
		}
		this.requireInitializedEngine();
		return engine;
	}

	/**
	 * Throws if the validation engine is not (yet) initialized.
	 *
	 * @throws EngineNotInitializedException if the engine is not initialized.
	 */
	private void requireInitializedEngine() {
		if (!this.matchboxEngineSupport.isInitialized()) {
			throw new EngineNotInitializedException("Validation engine not initialized, please try again");
		}
	}

	/**
	 * Thrown when the validation engine is not (yet) initialized, i.e. during startup or while an implementation guide
	 * is being (re)loaded. Unlike the other failures of {@link #getEngine(String, String, CliContext)}, the same
	 * request may succeed later, so it is answered with a {@code 503} and a {@code Retry-After} header.
	 */
	static class EngineNotInitializedException extends RuntimeException {
		EngineNotInitializedException(final String message) {
			super(message);
		}
	}

	/**
	 * Performs the validation of the given item with the given engine.
	 */
	ValidationSubReport validateItem(final MatchboxEngine engine,
									         final Input item,
												final String profile) {
		final String content = new String(item.getContent(), StandardCharsets.UTF_8);
		final var encoding = EncodingEnum.detectEncoding(content);

		final var subReport = new ValidationSubReport();
		subReport.setName("Validation of item #%s".formatted(item.getItemId() != null ? item.getItemId() : item.getId()));
		try {
			final var messages = ValidationProvider.doValidate(engine, content, encoding, profile);
			messages.stream()
				.map(message -> this.convertMessageToReport(message, engine, item.getId()))
				.forEach(subReport::addAssertionReport);
		} catch (final Exception e) {
			log.error("Error during validation", e);
			subReport.addUnexpectedError(new UnexpectedError().setMessage("Error during validation: %s".formatted(e.getMessage())));
		}

		// The EVSClient expects at least one assertion report, otherwise it will show it as DONE_UNDEFINED
		// https://github.com/ahdis/matchbox/issues/274
		// But if the validation failed with an unexpected error, DONE_UNDEFINED is exactly what shall be shown, so no
		// 'has passed' assertion is added in that case (https://github.com/ahdis/matchbox/issues/590)
		if ((subReport.getUnexpectedErrors() == null || subReport.getUnexpectedErrors().isEmpty())
			&& (subReport.getAssertionReports() == null || subReport.getAssertionReports().isEmpty())) {
			subReport.addAssertionReport(
				new AssertionReport()
					.setResult(ValidationTestResult.PASSED)
					.setSeverity(SeverityLevel.INFO)
					.setPriority(RequirementPriority.MANDATORY)
					.setDescription("No fatal or error issues detected, the validation has passed")
			);
		}

		return subReport;
	}

	/**
	 * Converts a validation message (HAPI) to an assertion report (Gazelle).
	 */
	AssertionReport convertMessageToReport(final ValidationMessage message,
														final MatchboxEngine engine,
														final String inputId) {
		final var assertionReport = new AssertionReport();
		switch (message.getLevel()) {
			case FATAL, ERROR:
				assertionReport.setPriority(RequirementPriority.MANDATORY);
				assertionReport.setResult(ValidationTestResult.FAILED);
				assertionReport.setSeverity(SeverityLevel.ERROR);
				break;
			case WARNING:
				assertionReport.setPriority(RequirementPriority.RECOMMENDED);
				assertionReport.setResult(ValidationTestResult.FAILED);
				assertionReport.setSeverity(SeverityLevel.WARNING);
				break;
			case INFORMATION:
			default:
				assertionReport.setResult(ValidationTestResult.PASSED); // Can't use UNDEFINED here, because it weights
				// more than FAILED, so the overall result would be UNDEFINED instead of PASSED/FAILED
				assertionReport.setSeverity(SeverityLevel.INFO);
				break;
		}

		// See AssertionReport#LINE_COL_PATT for the expected format
		assertionReport.setSubjectLocation("line %d, column %d, FHIRPath: %s".formatted(message.getLine(),
																											     message.getCol(),
																												  message.getLocation()));
		assertionReport.setSubjectLocations(List.of(
			new SubjectLocation().setInputId(inputId).setType(SubjectLocation.LINE_COLUMN_TYPE)
				.setValue("line %d, column %d".formatted(message.getLine(), message.getCol())),
			new SubjectLocation().setInputId(inputId).setType(SubjectLocation.FHIR_PATH_TYPE)
				.setValue(message.getLocation())));

		if (message.getInvId() != null) {
			assertionReport.setAssertionID(message.getInvId());
		} else if (message.getMessageId() != null) {
			assertionReport.setAssertionID(message.getMessageId());
		} else if (message.getType() != null) {
			assertionReport.setAssertionID(message.getType().name());
		}
		if (message.getSource() != null) {
			assertionReport.setAssertionType(message.getSource().name());
		}

		// Description, with slice info if available
		var description = new StringBuilder();
		description.append(message.getMessage());
		
		if (message.hasSliceInfo() && message.sliceHtml != null) {
			var slices = engine.filterSlicingMessages(message.sliceHtml);
			if (!slices.isEmpty()) {
				description.append("<br/><br/>Slice information:<br/><ul>");
				for (final var slice : slices) {
					description.append("<li>").append(slice).append("</li>");
				}
				description.append("</ul>");
			}
		}
		assertionReport.setDescription(description.toString());
		return assertionReport;
	}

	/**
	 * Creates a validation subreport that only contains an unexpected error.
	 */
	static ValidationSubReport unexpectedError(final String message) {
		final var report = new ValidationSubReport();
		report.setName("Unexpected error");
		// Nothing could be validated, so the result is neither PASSED nor FAILED but UNDEFINED. The counters are
		// computed from the unexpected errors by ValidationSubReport#computeCountersSubReport().
		report.setSubReportResult(ValidationTestResult.UNDEFINED);
		report.addUnexpectedError(new UnexpectedError().setMessage(message));
		return report;
	}

	/**
	 * Updates the counters and overall result of the given report.
	 */
	static ValidationReport updateReportFields(final ValidationReport report) {
		report.getReports().forEach(ValidationSubReport::computeCountersSubReport);
		report.getReports().forEach(ValidationSubReport::computeResultSubReport);
		report.computeCounters();
		report.computeOverallResult();
		return report;
	}
}
