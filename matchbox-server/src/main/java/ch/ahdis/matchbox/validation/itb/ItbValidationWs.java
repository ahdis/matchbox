package ch.ahdis.matchbox.validation.itb;

import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.util.StopWatch;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.config.MatchboxFhirVersion;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.cli.VersionUtil;
import ch.ahdis.matchbox.engine.exception.MatchboxEngineCreationException;
import ch.ahdis.matchbox.util.metrics.MatchboxMetrics;
import ch.ahdis.matchbox.validation.ValidationHelper;
import ch.ahdis.matchbox.validation.itb.models.ConfigurationType;
import ch.ahdis.matchbox.validation.itb.models.GetModuleDefinitionResponse;
import ch.ahdis.matchbox.validation.itb.models.Metadata;
import ch.ahdis.matchbox.validation.itb.models.TAR;
import ch.ahdis.matchbox.validation.itb.models.TypedParameter;
import ch.ahdis.matchbox.validation.itb.models.UsageEnumeration;
import ch.ahdis.matchbox.validation.itb.models.ValidateRequest;
import ch.ahdis.matchbox.validation.itb.models.ValidationModule;
import ch.ahdis.matchbox.validation.itb.models.ValidationOverview;
import ch.ahdis.matchbox.validation.itb.models.ValidationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The ITB (GITB REST) validation service {@code FHIRValidator}.
 * <p>
 * ITB 1.30.0 calls it with {@code handlerApiType="REST"}: {@code GET {root}/getModuleDefinition} and
 * {@code POST {root}/validate}. The contract is the one of the HL7 validator's ITB services
 * (hapifhir/org.hl7.fhir.core#2615, {@code itb-rest-spec.md}), so a test case written for the HL7 validator runs
 * against matchbox with only the address changed. The matchbox validation parameters are accepted as extra inputs.
 * <p>
 * See https://github.com/ahdis/matchbox/issues/589.
 */
@RestController
@RequestMapping(path = "/itb/fhir")
public class ItbValidationWs {
	private static final Logger log = LoggerFactory.getLogger(ItbValidationWs.class);

	static final String SERVICE_ID = "FHIRValidator";
	static final String SERVICE_NAME = "matchbox";

	static final String INPUT_CONTENT = "contentToValidate";
	static final String INPUT_CONTENT_TYPE = "contentType";
	static final String INPUT_PROFILES = "profiles";
	static final String INPUT_FAIL_ON = "failOn";
	static final String INPUT_INCLUDE_CONTENT = "includeContentInReport";
	static final String INPUT_BP_WARNINGS = "bpWarnings";
	static final String INPUT_RESOURCE_ID_RULE = "resourceIdRule";
	static final String INPUT_DISPLAY_WARNINGS = "displayWarnings";

	private static final String BASE_PROFILE_PREFIX = "http://hl7.org/fhir/StructureDefinition/";

	private final ValidationHelper validationHelper;
	private final MatchboxFhirVersion matchboxFhirVersion;
	private final Optional<MatchboxMetrics> matchboxMetrics;
	private final ObjectMapper objectMapper;

	// The base CLI context, with the default parameters
	private final CliContext baseCliContext;

	public ItbValidationWs(final ValidationHelper validationHelper,
								  final CliContext baseCliContext,
								  final MatchboxFhirVersion matchboxFhirVersion,
								  final Optional<MatchboxMetrics> matchboxMetrics,
								  final ObjectMapper objectMapper) {
		this.validationHelper = Objects.requireNonNull(validationHelper);
		this.baseCliContext = Objects.requireNonNull(baseCliContext);
		this.matchboxFhirVersion = Objects.requireNonNull(matchboxFhirVersion);
		this.matchboxMetrics = Objects.requireNonNull(matchboxMetrics);
		this.objectMapper = Objects.requireNonNull(objectMapper);
	}

	/**
	 * Returns the definition of the validation service, with the inputs it accepts.
	 */
	@GetMapping(path = "/getModuleDefinition", produces = MediaType.APPLICATION_JSON_VALUE)
	public GetModuleDefinitionResponse getModuleDefinition(final HttpServletRequest request) {
		final var module = new ValidationModule()
			.setId(SERVICE_ID)
			.setOperation("validate")
			.setServiceLocation(request.getRequestURL().toString().replace("/getModuleDefinition", ""))
			.setMetadata(new Metadata()
								 .setName(SERVICE_NAME)
								 .setVersion(VersionUtil.getVersion())
								 .setDescription("Validates a FHIR resource (JSON or XML) against a profile of the " +
														 "implementation guides installed in matchbox, and returns a TAR report."));

		module.addInput(input(INPUT_CONTENT, "string", true,
									 "The FHIR resource to validate, JSON or XML."));
		module.addInput(input(INPUT_CONTENT_TYPE, "string", false,
									 "application/fhir+json or application/fhir+xml. Detected from the content if missing."));
		module.addInput(input(INPUT_PROFILES, "string", false,
									 "One profile, 'canonical' or 'canonical|version'. The version selects the implementation guide " +
										 "version. Defaults to the base profile of the resource type."));
		module.addInput(input(INPUT_FAIL_ON, "string", false,
									 "The severity from which the result is FAILURE: error (default), warning or information."));
		module.addInput(input(INPUT_INCLUDE_CONTENT, "boolean", false,
									 "Whether the validated content is added to the report context (default true)."));
		module.addInput(input(INPUT_BP_WARNINGS, "string", false,
									 "Best practice warning level: Ignore, Hint, Warning or Error."));
		module.addInput(input(INPUT_RESOURCE_ID_RULE, "string", false,
									 "Resource id rule: OPTIONAL, REQUIRED or PROHIBITED."));
		module.addInput(input(INPUT_DISPLAY_WARNINGS, "boolean", false,
									 "Whether wrong displays are reported as warnings instead of errors (same as displayIssuesAreWarnings)."));

		// The matchbox validation parameters, as for $validate
		final Set<String> declared = new HashSet<>(Arrays.asList(INPUT_BP_WARNINGS, INPUT_RESOURCE_ID_RULE));
		for (final Field field : this.baseCliContext.getValidateEngineParameters()) {
			if (!declared.add(field.getName())) {
				continue;
			}
			final String type;
			if (field.getType() == boolean.class || field.getType() == Boolean.class) {
				type = "boolean";
			} else if (field.getType() == String[].class) {
				type = "list[string]";
			} else {
				type = "string";
			}
			module.addInput(input(field.getName(), type, false,
										 "Validation parameter, as for $validate (default: %s).".formatted(this.getDefaultValue(field))));
		}

		return new GetModuleDefinitionResponse().setModule(module);
	}

	/**
	 * Validates the input {@code contentToValidate}, and returns the result as a TAR report.
	 * <p>
	 * An invalid request gives HTTP 400 with {@code {"error": ...}}. A validation that cannot be done (e.g. an unknown
	 * profile) gives HTTP 200 with a {@code FAILURE} report, one where the engine failed an {@code UNDEFINED} report.
	 */
	@PostMapping(path = "/validate", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> validate(@RequestBody(required = false) final String body,
												 @RequestHeader(name = "Gitb-Reply-To", required = false) final String replyTo,
												 @RequestHeader(name = "Gitb-Test-Session-Identifier", required = false) final String testSessionId,
												 @RequestHeader(name = "Gitb-Test-Case-Identifier", required = false) final String testCaseId,
												 @RequestHeader(name = "Gitb-Test-Step-Identifier", required = false) final String testStepId,
												 @RequestHeader(name = "Gitb-Test-Engine-Version", required = false) final String testEngineVersion) {
		// No callbacks are made to the test engine, the headers are only logged
		log.info("ITB validate: session {}, test case {}, step {}, test engine {}, reply to {}",
					testSessionId, testCaseId, testStepId, testEngineVersion, replyTo);

		final ValidateRequest request;
		try {
			request = (body == null || body.isBlank())
				? new ValidateRequest()
				: this.objectMapper.readValue(body, ValidateRequest.class);
		} catch (final JsonProcessingException e) {
			return error(HttpStatus.BAD_REQUEST, "Malformed JSON: " + e.getOriginalMessage());
		}

		try {
			final String note = testSessionId != null ? testSessionId : request.getSessionId();
			return ResponseEntity.ok(new ValidationResponse().setReport(this.doValidate(request, note)));
		} catch (final ItbBadRequestException e) {
			return error(HttpStatus.BAD_REQUEST, e.getMessage());
		} catch (final Exception e) {
			log.error("Internal error during the ITB validation", e);
			return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error: " + e.getMessage());
		}
	}

	TAR doValidate(final ValidateRequest request, final @Nullable String note) {
		final var inputs = new ItbInputs(request.getInput());
		final String content = inputs.require(INPUT_CONTENT);
		final EncodingEnum encoding = getEncoding(inputs.optional(INPUT_CONTENT_TYPE), content);
		final var failOn = ItbTarMapper.FailOn.parse(inputs.optional(INPUT_FAIL_ON));
		final boolean includeContent = inputs.optionalBoolean(INPUT_INCLUDE_CONTENT, true);
		final String requestedProfile = getProfile(inputs.optional(INPUT_PROFILES));

		// Use a dedicated instance of the CLI context for this request, to avoid reusing wrong information (as the IGs)
		final CliContext cliContext = new CliContext(this.baseCliContext);
		ValidationHelper.applyValidationParameters(cliContext, inputs::values);
		if (inputs.optional(INPUT_DISPLAY_WARNINGS) != null) {
			cliContext.setDisplayIssuesAreWarnings(inputs.optionalBoolean(INPUT_DISPLAY_WARNINGS, true));
		}
		try {
			cliContext.getBestPracticeWarningLevel();
			cliContext.getResourceIdStatus();
		} catch (final IllegalArgumentException e) {
			throw new ItbBadRequestException(e.getMessage());
		}

		this.matchboxMetrics.ifPresent(MatchboxMetrics::addValidation);
		final var sw = new StopWatch();

		final String profile = requestedProfile != null ? requestedProfile : getBaseProfile(content, encoding);
		if (profile == null) {
			return withOverview(ItbTarMapper.failure(
				"The resource type of '%s' could not be determined, set the input '%s'".formatted(INPUT_CONTENT,
																												  INPUT_PROFILES)), null, note);
		}

		final MatchboxEngine engine;
		try {
			engine = this.validationHelper.getEngine(profile, cliContext, false);
		} catch (final MatchboxEngineCreationException e) {
			return withOverview(ItbTarMapper.failure(e.getMessage()), profile, note);
		}
		final String canonical = ValidationHelper.ProfileReference.parse(profile).canonical();
		final StructureDefinition structDef = engine.getStructureDefinitionR5(canonical);
		final String profileId = "%s|%s".formatted(structDef.getUrl(), structDef.getVersion());

		final List<ValidationMessage> messages;
		try {
			messages = ValidationHelper.doValidate(engine, content, encoding, canonical);
		} catch (final Exception e) {
			log.error("Error during validation", e);
			return withOverview(ItbTarMapper.undefined("Error during validation: %s".formatted(e.getMessage())),
									  profileId, note);
		}
		final long millis = sw.getMillis();
		this.matchboxMetrics.ifPresent(m -> m.addValidationDuration(java.time.Duration.ofMillis(millis)));

		final TAR tar = ItbTarMapper.toTar(messages, engine, failOn, includeContent ? ItbTarMapper.CONTEXT_CONTENT : null);

		// The OperationOutcome that $validate returns, in the FHIR version of the server
		final var operationOutcome = this.validationHelper.getOperationOutcome(tar.getId(), messages, canonical,
																										  engine, millis, cliContext);
		try {
			tar.getContext().addItem(ItbTarMapper.contextItem(ItbTarMapper.CONTEXT_OPERATION_OUTCOME,
																			  this.matchboxFhirVersion.serializeForResponse(operationOutcome),
																			  "application/fhir+json",
																			  true));
		} catch (final Exception e) {
			log.error("Error while serializing the OperationOutcome", e);
		}
		if (includeContent) {
			tar.getContext().addItem(ItbTarMapper.contextItem(ItbTarMapper.CONTEXT_CONTENT,
																			  content,
																			  encoding == EncodingEnum.XML ? "application/fhir+xml" : "application/fhir+json",
																			  true));
		}
		return withOverview(tar, profileId, note);
	}

	/**
	 * Returns the encoding of the content: from the content type if given, otherwise detected from the content.
	 */
	static EncodingEnum getEncoding(final @Nullable String contentType, final String content) {
		if (contentType == null) {
			return EncodingEnum.detectEncoding(content);
		}
		final EncodingEnum encoding = EncodingEnum.forContentType(contentType);
		if (encoding != EncodingEnum.JSON && encoding != EncodingEnum.XML) {
			throw new ItbBadRequestException(
				"Unsupported contentType '%s', expected application/fhir+json or application/fhir+xml".formatted(contentType));
		}
		return encoding;
	}

	/**
	 * Returns the requested profile, or {@code null} if none. Matchbox validates against one profile per call.
	 */
	static @Nullable String getProfile(final @Nullable String profiles) {
		if (profiles == null) {
			return null;
		}
		final List<String> list = Arrays.stream(profiles.split(",")).map(String::strip).filter(p -> !p.isEmpty()).toList();
		if (list.size() > 1) {
			throw new ItbBadRequestException(
				"Only one profile can be given in '%s', matchbox validates against one profile per call".formatted(INPUT_PROFILES));
		}
		return list.isEmpty() ? null : list.getFirst();
	}

	/**
	 * Returns the base profile of the resource type of the content, or {@code null} if it cannot be determined.
	 */
	@Nullable String getBaseProfile(final String content, final EncodingEnum encoding) {
		final String resourceType = encoding == EncodingEnum.XML ? getXmlRootName(content) : this.getJsonResourceType(content);
		if (resourceType == null || !resourceType.matches("[A-Z][A-Za-z]+")) {
			return null;
		}
		return BASE_PROFILE_PREFIX + resourceType;
	}

	private @Nullable String getJsonResourceType(final String content) {
		try {
			final JsonNode resourceType = this.objectMapper.readTree(content.replace("﻿", "")).get("resourceType");
			return (resourceType != null && resourceType.isTextual()) ? resourceType.asText() : null;
		} catch (final Exception e) {
			return null;
		}
	}

	private static @Nullable String getXmlRootName(final String content) {
		final var factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		XMLStreamReader reader = null;
		try {
			reader = factory.createXMLStreamReader(new StringReader(content.replace("﻿", "")));
			return reader.nextTag() == XMLStreamReader.START_ELEMENT ? reader.getLocalName() : null;
		} catch (final XMLStreamException e) {
			return null;
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (final XMLStreamException ignored) {
					// nothing to do
				}
			}
		}
	}

	private static TAR withOverview(final TAR tar, final @Nullable String profileId, final @Nullable String note) {
		return tar.setOverview(new ValidationOverview()
										  .setProfileID(profileId)
										  .setValidationServiceName(SERVICE_NAME)
										  .setValidationServiceVersion(VersionUtil.getVersion())
										  .setNote(note));
	}

	private static TypedParameter input(final String name,
													final String type,
													final boolean required,
													final String description) {
		return new TypedParameter()
			.setName(name)
			.setType(type)
			.setUse(required ? UsageEnumeration.R : UsageEnumeration.O)
			.setKind(ConfigurationType.SIMPLE)
			.setDesc(description);
	}

	private String getDefaultValue(final Field field) {
		try {
			field.setAccessible(true);
			final Object value = field.get(this.baseCliContext);
			if (value instanceof final String[] values) {
				return String.join(", ", values);
			}
			return String.valueOf(value);
		} catch (final IllegalAccessException e) {
			return "unknown";
		}
	}

	private static ResponseEntity<Map<String, String>> error(final HttpStatus status, final String message) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
	}
}
