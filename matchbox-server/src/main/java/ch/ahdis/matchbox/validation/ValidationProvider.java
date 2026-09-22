package ch.ahdis.matchbox.validation;

/*
 * #%L
 * Matchbox Server
 * %%
 * Copyright (C) 2018 - 2020 ahdis
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionResourceDao;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.util.StopWatch;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.config.MatchboxFhirVersion;
import ch.ahdis.matchbox.config.property.MatchboxFhirProperties;
import ch.ahdis.matchbox.statistics.OperationOutcomeResourceProviderR4;
import ch.ahdis.matchbox.statistics.OperationOutcomeResourceProviderR4B;
import ch.ahdis.matchbox.statistics.OperationOutcomeResourceProviderR5;
import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import ch.ahdis.matchbox.util.metrics.MatchboxMetrics;
import ch.ahdis.matchbox.validation.matchspark.LlmConnector;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.exception.MatchboxEngineCreationException;
import ch.ahdis.matchbox.packages.MatchboxImplementationGuideProvider;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import org.apache.commons.codec.digest.DigestUtils;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.*;
import org.hl7.fhir.r5.extensions.ExtensionDefinitions;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.ahdis.matchbox.validation.matchspark.LlmErrorMessage;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ch.ahdis.matchbox.config.MatchboxFhirVersion.convertToR4;
import static ch.ahdis.matchbox.config.MatchboxFhirVersion.convertToR4B;

/**
 * The HAPI provider of the operation $validate
 */
public class ValidationProvider {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ValidationProvider.class);

	public static final String PARAM_ANALYZE_ERRORS_WITH_LLM = "analyzeErrorsWithLlm";
	public static final String PARAM_LLM_PROVIDER = "llmProvider";
	public static final String PARAM_LLM_MODEL_NAME = "llmModelName";
	public static final String PARAM_LLM_API_KEY = "llmApiKey";

	@Autowired
	protected MatchboxEngineSupport matchboxEngineSupport;

	@Autowired
	protected MatchboxFhirProperties matchboxProps;

	@Autowired
	protected CliContext cliContext;

	@Autowired
	private ValidationHelper validationHelper;

	@Autowired
	private MatchboxFhirVersion matchboxFhirVersion;

	@Autowired
	private MatchboxImplementationGuideProvider igProvider;
	@Autowired
	private INpmPackageVersionResourceDao myPackageVersionResourceDao;

	@Autowired(required = false)
	private Optional<OperationOutcomeResourceProviderR4> operationOutcomeResourceProviderR4;

	@Autowired(required = false)
	private Optional<OperationOutcomeResourceProviderR4B> operationOutcomeResourceProviderR4B;

	@Autowired(required = false)
	private Optional<OperationOutcomeResourceProviderR5> operationOutcomeResourceProviderR5;

	@Autowired(required = false)
	private Optional<MatchboxMetrics> matchboxMetrics;

	/**
	 * The langchain4j {@link ChatModelListener} beans (e.g. Micrometer/Observation listeners registered in
	 * {@link ch.ahdis.matchbox.config.MatchboxMetricsConfig}) to attach to the AI chat model built by
	 * {@link LLMConnector}, so that gen_ai.* metrics are actually recorded.
	 */
	@Autowired(required = false)
	private List<ChatModelListener> chatModelListeners = List.of();

//	@Operation(name = "$canonical", manualRequest = true, idempotent = true, returnParameters = {
//			@OperationParam(name = "return", type = IBase.class, min = 1, max = 1) })
//	public IBaseResource canonical(HttpServletRequest theRequest) {
//    String contentString = getContentString(theRequest, null);
//    EncodingEnum encoding = EncodingEnum.forContentType(theRequest.getContentType());
//    if (encoding == null) {
//      encoding = EncodingEnum.detectEncoding(contentString);
//    }
//    IBaseResource resource = null;
//    try {
//      // we still parse to catch wrongli formatted
//      resource = encoding.newParser(myFhirCtx).parseResource(contentString);
//      Canonicalizer canonicalizer= new Canonicalizer(this.myFhirCtx);
//      return canonicalizer.canonicalize(resource);
//    } catch (DataFormatException e) {
//      return getValidationMessageDataFormatException(e);
//    }
//		return null;
//	}

	@Operation(name = "$validate", manualRequest = true, idempotent = true, returnParameters = {
		@OperationParam(name = "return", type = IBase.class, min = 1, max = 1)})
	public IBaseResource validate(final HttpServletRequest theRequest) {
		try {
			// validate
			final var response = this.getValidation(theRequest);

			// check if validation response is an OperationOutcome and store it
			if (response instanceof final OperationOutcome operationOutcome) {
				this.saveOperationOutcome(operationOutcome);
			}

			// returns response in correct FHIR version
			return this.matchboxFhirVersion.convertForResponse(response);
		} catch (final BaseServerResponseException hapiException) {
			// check if exception contains a OperationOutcome
			final IBaseOperationOutcome operationOutcome = hapiException.getOperationOutcome();

			// store OperationOutcome
			if (operationOutcome != null) {
				this.saveOperationOutcome(MatchboxFhirVersion.convertToR5(operationOutcome, OperationOutcome.class));
			}

			// rethrow the exception
			throw hapiException;
		}
	}

	private OperationOutcome getValidation(final HttpServletRequest theRequest) {
		log.debug("$validate");
		this.matchboxMetrics.ifPresent(MatchboxMetrics::addValidation);

		final var sw = new StopWatch();
		sw.startTask("Total");

		// we extract here all config
		final CliContext cliContext = new CliContext(this.cliContext);

		ValidationHelper.applyValidationParameters(cliContext, theRequest::getParameterValues);

		if (theRequest.getParameter("profile") == null) {
			return this.getOoForError("The 'profile' parameter must be provided");
		}
		String profile = theRequest.getParameter("profile");

		boolean reload = false;
		if (theRequest.getParameter("reload") != null) {
			reload = theRequest.getParameter("reload").equals("true");
		}

		String contentString = "";
		try {
			contentString = new String(theRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		} catch (final Exception e) {
			log.error(e.getMessage(), e);
		}

		if (contentString.isEmpty()) {
			return this.getOoForError("No content provided in HTTP body");
		} else {
			log.trace(contentString);
		}

		final MatchboxEngine engine;
		try {
			engine = this.validationHelper.getEngine(profile, cliContext, reload);
		} catch (final MatchboxEngineCreationException e) {
			return this.getOoForError(e.getMessage());
		}
		profile = ValidationHelper.ProfileReference.parse(profile).canonical();

		final String sha3Hex = new DigestUtils("SHA3-256").digestAsHex(contentString + profile);

		EncodingEnum encoding = EncodingEnum.forContentType(theRequest.getContentType());
		if (encoding == null) {
			encoding = EncodingEnum.detectEncoding(contentString);
		}

		final List<ValidationMessage> messages;
		try {
			messages = ValidationHelper.doValidate(engine, contentString, encoding, profile);
		} catch (final Exception e) {
			sw.endCurrentTask();
			log.debug("Validation time: {}", sw);
			log.error("Error during validation", e);
			return this.getOoForError("Error during validation: %s".formatted(e.getMessage()));
		}

		long millis = sw.getMillis();
		log.debug("Validation time: {}", sw);
		this.matchboxMetrics.ifPresent(m -> m.addValidationDuration(java.time.Duration.ofMillis(millis)));

		final OperationOutcome oo = this.validationHelper.getOperationOutcome(sha3Hex, messages, profile, engine, millis, cliContext);

		// Check if we should analyze errors with LLM, either from the request parameter or from the configuration
		boolean analyzeErrorsWithLlm = this.matchboxProps.getValidation().isAnalyzeErrorsWithLlm();
		if (theRequest.getParameter(PARAM_ANALYZE_ERRORS_WITH_LLM) != null) {
			analyzeErrorsWithLlm = Boolean.parseBoolean(theRequest.getParameter(PARAM_ANALYZE_ERRORS_WITH_LLM));
		}

		if (analyzeErrorsWithLlm && hasError(oo)) {
			// Use the Matchbox LLM configuration, and update it with the request parameters if provided
			final var llmConnectorConfig = this.matchboxProps.getContext().getLlm().clone();
			if (theRequest.getParameter(PARAM_LLM_PROVIDER) != null) {
				llmConnectorConfig.setProvider(theRequest.getParameter(PARAM_LLM_PROVIDER));
			}
			if (theRequest.getParameter(PARAM_LLM_MODEL_NAME) != null) {
				llmConnectorConfig.setModelName(theRequest.getParameter(PARAM_LLM_MODEL_NAME));
			}
			if (theRequest.getParameter(PARAM_LLM_API_KEY) != null) {
				llmConnectorConfig.setApiKey(theRequest.getParameter(PARAM_LLM_API_KEY));
			}

			if (!llmConnectorConfig.isValid()) {
				log.debug("LLM configuration is not valid, skipping AI analysis");
				oo.addIssue()
					.setSeverity(OperationOutcome.IssueSeverity.WARNING)
					.setCode(OperationOutcome.IssueType.REQUIRED)
					.setDiagnostics("The error outcome analysis was requested but the LLM configuration is invalid");
			} else {
				try {
					final var openAIConnector = LlmConnector.getConnector(llmConnectorConfig, this.chatModelListeners);
					final String json = FhirContext.forR5Cached().newJsonParser().encodeResourceToString(oo);
					final String aiResult = openAIConnector.interpretWithMatchbox(contentString, json);
					this.addAIIssueToOperationOutcome(oo, aiResult);
				} catch (final Exception e) {
					log.error("Error during AI analysis", e);
					// add the error to the OperationOutcome, so the client still gets the validation result
					this.addExceptionToOperationOutcome(oo, e);
				}
			}
		}

		return oo;
	}

	private OperationOutcome getOoForError(final @NonNull String message) {
		final var oo = new OperationOutcome();
		final var issue = oo.addIssue();
		issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
		issue.setCode(OperationOutcome.IssueType.EXCEPTION);
		issue.setDiagnostics(message);
		issue.addExtension().setUrl(ExtensionDefinitions.EXT_ISSUE_SOURCE).setValue(new StringType("ValidationProvider"));
		return oo;
	}

	public void addAIIssueToOperationOutcome(final OperationOutcome outcome, final String aiResponse) {
		final var details = new CodeableConcept();
		details.setText("AI Analyze of the Operation Outcome");

		outcome.addIssue()
			.setSeverity(OperationOutcome.IssueSeverity.INFORMATION)
			.setCode(OperationOutcome.IssueType.INFORMATIONAL)
			.setDiagnostics(aiResponse)
			.setDetails(details)
			.addExtension()
			.setUrl("http://hl7.org/fhir/StructureDefinition/rendering-style")
			.setValue(new StringType("markdown"));
	}

	public void addExceptionToOperationOutcome(final OperationOutcome outcome, final Exception e) {
		var message = e.getMessage();
		if (message != null && message.strip().startsWith("{")) {
			try {
				// This is a best effort to extract a "message" field from a JSON error response from an LLM provider
				final ObjectMapper om = new ObjectMapper();
				final LlmErrorMessage parsed = om.readValue(message, LlmErrorMessage.class);
				if (parsed != null) {
					if (parsed.getMessage() != null && !parsed.getMessage().isBlank()) {
						message = parsed.getMessage();
					} else if (parsed.getError() != null) {
						final LlmErrorMessage.ErrorObject err = parsed.getError();
						if (err.getMessage() != null && !err.getMessage().isBlank()) {
							message = err.getMessage();
						} else if (err.getErrors() != null && !err.getErrors().isEmpty()) {
							final LlmErrorMessage.FieldError fe = err.getErrors().getFirst();
							if (fe.getMessage() != null && !fe.getMessage().isBlank()) {
								message = fe.getMessage();
							}
						} else if (err.getInfo() != null && !err.getInfo().isEmpty()) {
							message = err.getInfo().toString();
						}
					}
				}
			} catch (final Exception ex) {
				// Not a big deal, we just couldn't parse the JSON error message, so we will use the original JSON string
				log.debug("Could not parse LLM JSON error message: {}", ex.getMessage());
			}
		}

		outcome.addIssue()
			.setSeverity(OperationOutcome.IssueSeverity.ERROR)
			.setCode(OperationOutcome.IssueType.EXCEPTION)
			.setDiagnostics(message);
	}

	public void saveOperationOutcome(final OperationOutcome operationOutcome) {
		// checks if all operationOutcomeResourceProviders are empty (indicating that save-statistics = false
		// and operationOutcome is valid)
		boolean hasNoOperationOutcomeResourceProvider = operationOutcomeResourceProviderR4.isEmpty()
																&& operationOutcomeResourceProviderR4B.isEmpty()
																&& operationOutcomeResourceProviderR5.isEmpty();

		if (hasNoOperationOutcomeResourceProvider || operationOutcome == null) {
			return;
		}

		operationOutcome.setId(UUID.randomUUID().toString());
		operationOutcome.getMeta().setSource("matchbox-validation");

		// uses the correct ResourceProvider for the server version. Gets the dao from the rp and stores the
		// OperationOutcome. Converts to R4 or R4B first if needed.
		this.matchboxFhirVersion.execute(
			() -> this.operationOutcomeResourceProviderR4.ifPresent(rp -> rp.getDao().create(convertToR4(operationOutcome, org.hl7.fhir.r4.model.OperationOutcome.class))),
			() -> this.operationOutcomeResourceProviderR4B.ifPresent(rp -> rp.getDao().create(convertToR4B(operationOutcome, org.hl7.fhir.r4b.model.OperationOutcome.class))),
			() -> this.operationOutcomeResourceProviderR5.ifPresent(rp -> rp.getDao().create(operationOutcome))
		);
	}

	private boolean hasError(final OperationOutcome operationOutcome) {
		if (operationOutcome == null || operationOutcome.getIssue() == null) {
			return false;
		}
		for (final OperationOutcome.OperationOutcomeIssueComponent issue : operationOutcome.getIssue()) {
			if (issue.getSeverity() == OperationOutcome.IssueSeverity.ERROR || issue.getSeverity() == OperationOutcome.IssueSeverity.FATAL) {
				return true;
			}
		}
		return false;
	}
}

