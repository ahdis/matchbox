package ch.ahdis.matchbox.validation.gazelle;

import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionResourceEntity;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.util.StopWatch;
import ch.ahdis.matchbox.validation.ValidationProvider;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import ch.ahdis.matchbox.providers.StructureDefinitionResourceProvider;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.cli.VersionUtil;
import ch.ahdis.matchbox.engine.exception.MatchboxEngineCreationException;
import ch.ahdis.matchbox.validation.gazelle.models.metadata.Interface;
import ch.ahdis.matchbox.validation.gazelle.models.metadata.RestBinding;
import ch.ahdis.matchbox.validation.gazelle.models.metadata.Service;
import ch.ahdis.matchbox.validation.gazelle.models.validation.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static ch.ahdis.matchbox.packages.MatchboxJpaPackageCache.structureDefinitionIsValidatable;

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

	private static final List<SupportedInput> SUPPORTED_INPUTS = List.of(
		new SupportedInput().setId(INPUT_ID).setLabel("FHIR resource (JSON or XML)").setRequired(true));

	private final MatchboxEngineSupport matchboxEngineSupport;

	private final StructureDefinitionResourceProvider structureDefinitionProvider;

	// The base CLI context, with the default parameters
	private final CliContext baseCliContext;

	private final GazelleApiV1Mapper v1Mapper;

	public GazelleValidationWs(final MatchboxEngineSupport matchboxEngineSupport,
										final CliContext baseCliContext,
										final StructureDefinitionResourceProvider structureDefinitionProvider,
										final ObjectMapper objectMapper) {
		this.matchboxEngineSupport = Objects.requireNonNull(matchboxEngineSupport);
		this.baseCliContext = Objects.requireNonNull(baseCliContext);
		this.structureDefinitionProvider = Objects.requireNonNull(structureDefinitionProvider);
		this.v1Mapper = new GazelleApiV1Mapper(Objects.requireNonNull(objectMapper));
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
	public ResponseEntity<String> getProfilesV1() throws JsonProcessingException {
		return jsonResponse(HttpStatus.OK, this.v1Mapper.write(this.getProfiles()));
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
		return jsonResponse(HttpStatus.OK, this.v1Mapper.write(this.postValidate(validationRequest)));
	}

	private static ResponseEntity<String> jsonResponse(final HttpStatus status, final String json) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(json);
	}

	/**
	 * Returns the list of profiles supported by this server (v2).
	 */
	@GetMapping(path = V2_PROFILES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ValidationProfile> getProfiles() {
		// Filter the extensions, because they won't be validated directly
		final List<NpmPackageVersionResourceEntity> entities =
			this.structureDefinitionProvider.getPackageResources().stream()
			.filter(packageVersionResource -> structureDefinitionIsValidatable(packageVersionResource.getFilename()))
			.toList();

		final var profiles = new ArrayList<ValidationProfile>(entities.size()*2);
		entities.forEach(packageVersionResource -> {
				final var profile = new ValidationProfile();
				final var version = packageVersionResource.getCanonicalVersion();
				profile.setProfileID("%s|%s".formatted(packageVersionResource.getCanonicalUrl(), version));
				// PATCHed: filename contains the StructureDefinition title.
				profile.setProfileName("%s (%s)".formatted(packageVersionResource.getFilename(), version));
				profile.setDomain(packageVersionResource.getPackageVersion().getPackageId());
				profile.setVersion(version);
				profile.setSupportedInputs(SUPPORTED_INPUTS);
				profiles.add(profile);

				// If the package is current, we also add it version-less
				if (packageVersionResource.getPackageVersion().isCurrentVersion()) {
					final var profile2 = new ValidationProfile();
					profile2.setProfileID(packageVersionResource.getCanonicalUrl());
					// PATCHed: filename contains the StructureDefinition title.
					profile2.setProfileName(packageVersionResource.getFilename());
					profile2.setDomain(packageVersionResource.getPackageVersion().getPackageId());
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
	public ValidationReport postValidate(@RequestBody final ValidationRequest validationRequest) {
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
		} catch (final Exception exception) {
			report.addValidationSubReport(unexpectedError(exception.getMessage()));
			return updateReportFields(report);
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

		return updateReportFields(report);
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
			throw new MatchboxEngineCreationException(
				"Validation for profile '%s' not supported by this validator instance".formatted(canonicalWithVersion));
		}
		if (!this.matchboxEngineSupport.isInitialized()) {
			throw new RuntimeException("Validation engine not initialized, please try again");
		}
		return engine;
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
		if (subReport.getAssertionReports() == null || subReport.getAssertionReports().isEmpty()) {
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
		report.setSubReportResult(ValidationTestResult.FAILED);
		report.addUnexpectedError(new UnexpectedError().setMessage(message));
		report.getSubCounters().incrementUnexpectedErrors();
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
