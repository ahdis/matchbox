package ch.ahdis.matchbox.validation;

import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.util.StopWatch;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.packages.OnDemandIgInstaller;
import ch.ahdis.matchbox.config.MatchboxFhirVersion;
import ch.ahdis.matchbox.config.property.MatchboxFhirProperties;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.cli.VersionUtil;
import ch.ahdis.matchbox.engine.exception.MatchboxEngineCreationException;
import ch.ahdis.matchbox.statistics.OperationOutcomeResourceProviderR4;
import ch.ahdis.matchbox.statistics.OperationOutcomeResourceProviderR4B;
import ch.ahdis.matchbox.statistics.OperationOutcomeResourceProviderR5;
import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import ch.ahdis.matchbox.util.metrics.MatchboxMetrics;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.commons.beanutils.BeanUtils;
import org.hl7.fhir.model.core.StructureDefinition;
import org.hl7.fhir.model.utilities.EOperationOutcome;
import org.hl7.fhir.model.utilities.formats.FhirFormat;
import org.hl7.fhir.r5.model.BooleanType;
import org.hl7.fhir.r5.model.Duration;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.StringType;
import org.hl7.fhir.r5.model.UriType;
import org.hl7.fhir.r5.utils.OperationOutcomeUtilities;
import org.hl7.fhir.utilities.VersionUtilities;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static ch.ahdis.matchbox.config.MatchboxFhirVersion.convertToR4;
import static ch.ahdis.matchbox.config.MatchboxFhirVersion.convertToR4B;
import static ch.ahdis.matchbox.util.MatchboxServerUtils.addExtension;

/**
 * The validation of a resource, the same for all validation APIs: {@code $validate}, the Gazelle API and the ITB API.
 * <p>
 * An API reads its request, then:
 * <ol>
 *    <li>{@link #newContext(Function)}: the validation parameters of the request;</li>
 *    <li>{@link ProfileSelection#select}: the profile, if the request doesn't have to give one;</li>
 *    <li>{@link #selectIgs}: the implementation guides, if the request names them instead of leaving the choice to
 *    the profile;</li>
 *    <li>{@link #prepare}: the validation engine for the profile and the parameters, from the engine cache;</li>
 *    <li>{@link #validate}: the validation of a resource, as often as there are resources;</li>
 * </ol>
 * and maps the {@link ValidationResult} to its report: {@link #toOperationOutcome} for {@code $validate}, a Gazelle
 * validation report, an ITB TAR. {@link #saveForStatistics} stores the outcome for the validation statistics.
 */
public class MatchboxValidationService {
	private static final Logger log = LoggerFactory.getLogger(MatchboxValidationService.class);

	private final MatchboxEngineSupport matchboxEngineSupport;
	private final MatchboxFhirProperties matchboxProps;
	private final CliContext baseCliContext;
	private final Optional<MatchboxMetrics> matchboxMetrics;

	// Only present if the validation statistics are saved (matchbox.validation.save-statistics)
	@Autowired(required = false)
	private Optional<OperationOutcomeResourceProviderR4> operationOutcomeResourceProviderR4 = Optional.empty();

	@Autowired(required = false)
	private Optional<OperationOutcomeResourceProviderR4B> operationOutcomeResourceProviderR4B = Optional.empty();

	@Autowired(required = false)
	private Optional<OperationOutcomeResourceProviderR5> operationOutcomeResourceProviderR5 = Optional.empty();

	@Autowired(required = false)
	private MatchboxFhirVersion matchboxFhirVersion;

	// Installs the implementation guides that a request names and that are not installed
	@Autowired(required = false)
	private OnDemandIgInstaller igInstaller;

	/**
	 * @param baseCliContext the validation parameters of the server, the defaults of every request.
	 */
	public MatchboxValidationService(final MatchboxEngineSupport matchboxEngineSupport,
												final MatchboxFhirProperties matchboxProps,
												final CliContext baseCliContext,
												final Optional<MatchboxMetrics> matchboxMetrics) {
		this.matchboxEngineSupport = Objects.requireNonNull(matchboxEngineSupport);
		this.matchboxProps = Objects.requireNonNull(matchboxProps);
		this.baseCliContext = Objects.requireNonNull(baseCliContext);
		this.matchboxMetrics = Objects.requireNonNull(matchboxMetrics);
	}

	/**
	 * Returns the validation parameters of the server. Not to be modified: {@link #newContext(Function)} gives the
	 * parameters of a request.
	 */
	public CliContext getBaseContext() {
		return this.baseCliContext;
	}

	/**
	 * Returns the validation parameters of a request: the ones of the server, overridden by the ones found in the
	 * request. Every request gets its own instance, as the engine selection writes the IG into it.
	 *
	 * @param parameterValues returns the values of a parameter of the request by name, or {@code null} if the request
	 *                        does not have it; {@code null} if the API has no validation parameters.
	 */
	public CliContext newContext(final @Nullable Function<String, String[]> parameterValues) {
		final var cliContext = new CliContext(this.baseCliContext);
		if (parameterValues != null) {
			applyValidationParameters(cliContext, parameterValues);
		}
		return cliContext;
	}

	/**
	 * Sets the validation parameters ({@link CliContext#getValidateEngineParameters()}) found in a request on the
	 * given CLI context.
	 *
	 * @param cliContext      the CLI context of the request, updated in place.
	 * @param parameterValues returns the values of a parameter by name, or {@code null} if the request does not have it.
	 */
	static void applyValidationParameters(final CliContext cliContext,
													  final Function<String, String[]> parameterValues) {
		for (final Field field : cliContext.getValidateEngineParameters()) {
			final String name = field.getName();
			final String[] values = parameterValues.apply(name);
			if (values == null || values.length == 0) {
				continue;
			}
			try {
				if (field.getType() == String[].class) {
					field.setAccessible(true);
					field.set(cliContext, values);
				} else if (field.getType() == boolean.class || field.getType() == Boolean.class) {
					// currently only handles boolean or String
					BeanUtils.setProperty(cliContext, name, Boolean.parseBoolean(values[0]));
				} else {
					BeanUtils.setProperty(cliContext, name, values[0]);
				}
			} catch (final IllegalAccessException | InvocationTargetException e) {
				log.error("error setting property %s to %s".formatted(name, String.join(",", values)));
			}
		}
	}

	/**
	 * Selects the implementation guides that a request asks for by name, see {@link IgSelection}. An API that lets
	 * the profile choose the implementation guide doesn't need it.
	 * <p>
	 * An implementation guide that is not installed is installed from the package registry, unless the server is
	 * read-only, see {@link OnDemandIgInstaller}.
	 *
	 * @param requested the implementation guides of the request, {@code id#version} or {@code id}.
	 * @throws OnDemandIgInstaller.IgInstallationException if one of them is not installed and cannot be installed.
	 * @throws IgSelection.IgNotInstalledException         if one of them is not installed, and there is no installer.
	 */
	public IgSelection selectIgs(final @Nonnull Collection<String> requested) {
		try {
			return IgSelection.of(requested, this.matchboxEngineSupport::findInstalledPackage);
		} catch (final IgSelection.IgNotInstalledException e) {
			if (this.igInstaller == null) {
				throw e;
			}
			for (final String ig : e.getIgs()) {
				this.igInstaller.ensureInstalled(ig);
			}
			return IgSelection.of(requested, this.matchboxEngineSupport::findInstalledPackage);
		}
	}

	/**
	 * Prepares a validation against the selected profile, see {@link #prepare(String, List, CliContext, boolean)}.
	 */
	public PreparedValidation prepare(final @Nonnull ProfileSelection selection,
												 final @Nonnull CliContext cliContext,
												 final boolean reload) throws MatchboxEngineCreationException {
		return this.prepare(selection.profile(), selection.otherMetaProfiles(), cliContext, reload);
	}

	/**
	 * Prepares a validation against the profile, see {@link #prepare(String, List, CliContext, boolean)}.
	 */
	public PreparedValidation prepare(final @Nonnull String profile,
												 final @Nonnull CliContext cliContext,
												 final boolean reload) throws MatchboxEngineCreationException {
		return this.prepare(profile, List.of(), cliContext, reload);
	}

	/**
	 * Prepares a validation: gets the engine that can validate against the profile with the validation parameters,
	 * from the engine cache or by creating it. Counts as one validation in the metrics.
	 *
	 * @param profile           the profile, {@code canonical} or {@code canonical|version}. The version selects the
	 *                          version of the implementation guide.
	 * @param otherMetaProfiles see {@link ProfileSelection#otherMetaProfiles()}.
	 * @param cliContext        the validation parameters of the request, see {@link #newContext(Function)}.
	 * @param reload            whether to reload the engine.
	 * @throws EngineNotInitializedException   if the engine is not (yet) initialized, the request may succeed later.
	 * @throws MatchboxEngineCreationException if no engine can validate against the profile.
	 */
	public PreparedValidation prepare(final @Nonnull String profile,
												 final @Nonnull List<String> otherMetaProfiles,
												 final @Nonnull CliContext cliContext,
												 final boolean reload) throws MatchboxEngineCreationException {
		this.matchboxMetrics.ifPresent(MatchboxMetrics::addValidation);
		final String canonical = ProfileReference.parse(profile).canonical();
		this.installRequestedIgs(cliContext);

		final MatchboxEngine engine;
		try {
			engine = this.matchboxEngineSupport.getMatchboxEngine(profile, cliContext, true, reload);
		} catch (final Exception e) {
			log.error("Error while initializing the validation engine", e);
			throw new MatchboxEngineCreationException(
				"Error while initializing the validation engine: %s".formatted(e.getMessage()), e);
		}
		final StructureDefinition structureDefinition = engine == null ? null : engine.getStructureDefinitionR6(canonical);
		// A reload may have been started in the meantime: the profile is then not unknown, the engine is only not
		// ready yet, and the same request may succeed later.
		if (!this.matchboxEngineSupport.isInitialized()) {
			throw new EngineNotInitializedException("Validation engine not initialized, please try again");
		}
		if (structureDefinition == null) {
			throw new MatchboxEngineCreationException(
				"Validation for profile '%s' not supported by this validator instance".formatted(profile));
		}
		return new PreparedValidation(engine,
												cliContext,
												canonical,
												structureDefinition,
												this.matchboxEngineSupport.getSessionId(engine),
												List.copyOf(otherMetaProfiles));
	}

	/**
	 * Installs the implementation guides that the validation parameters name ({@code ig}, {@code igs}) and that are
	 * not installed, see {@link OnDemandIgInstaller}: the engine would otherwise be created without them. This is the
	 * same for every validation API.
	 *
	 * @throws MatchboxEngineCreationException if one of them is not installed and cannot be installed, e.g. because
	 *                                         the server is read-only.
	 */
	private void installRequestedIgs(final CliContext cliContext) throws MatchboxEngineCreationException {
		if (this.igInstaller == null) {
			return;
		}
		final List<String> requested = new ArrayList<>();
		if (cliContext.getIg() != null) {
			requested.add(cliContext.getIg());
		}
		if (cliContext.getIgs() != null) {
			requested.addAll(Arrays.asList(cliContext.getIgs()));
		}
		for (final String requestedIg : requested) {
			if (requestedIg == null || requestedIg.isBlank()) {
				continue;
			}
			final String ig = requestedIg.strip();
			// The FHIR core package is given by the FHIR version
			if (VersionUtilities.isCorePackage(ig.contains("#") ? ig.substring(0, ig.indexOf('#')) : ig)
				|| this.matchboxEngineSupport.findInstalledPackage(ig).isPresent()) {
				continue;
			}
			try {
				this.igInstaller.ensureInstalled(ig);
			} catch (final OnDemandIgInstaller.IgInstallationException e) {
				throw new MatchboxEngineCreationException(e.getMessage(), e);
			}
		}
	}

	/**
	 * Thrown when the validation engine is not (yet) initialized, i.e. during startup or while an implementation guide
	 * is being (re)loaded. Unlike the other failures of {@link #prepare}, the same request may succeed later.
	 */
	public static class EngineNotInitializedException extends MatchboxEngineCreationException {
		public EngineNotInitializedException(final String message) {
			super(message);
		}
	}

	/**
	 * Validates the content against the profile of the prepared validation, and records the duration in the metrics.
	 * It doesn't throw: if the engine fails, the result has a {@link ValidationResult#failure()}.
	 *
	 * @param encoding the encoding of the content, detected from the content if {@code null}.
	 */
	public ValidationResult validate(final @Nonnull PreparedValidation prepared,
												final @Nonnull String content,
												final @Nullable EncodingEnum encoding) {
		final EncodingEnum contentEncoding = encoding != null ? encoding : EncodingEnum.detectEncoding(content);
		final var sw = new StopWatch();
		final List<ValidationMessage> messages;
		try {
			messages = doValidate(prepared.engine(), content, contentEncoding, prepared.profile());
		} catch (final Exception e) {
			log.error("Error during validation", e);
			return new ValidationResult(prepared, content, contentEncoding, List.of(), sw.getMillis(),
												 "Error during validation: %s".formatted(e.getMessage()));
		}
		final long millis = sw.getMillis();
		log.debug("Validation time: {} ms", millis);
		this.matchboxMetrics.ifPresent(m -> m.addValidationDuration(java.time.Duration.ofMillis(millis)));
		return new ValidationResult(prepared, content, contentEncoding, messages, millis, null);
	}

	/**
	 * Validates the content against the profile.
	 *
	 * @param profile the profile canonical, without version.
	 */
	static List<ValidationMessage> doValidate(final MatchboxEngine engine,
															String content,
															final EncodingEnum encoding,
															final String profile) throws EOperationOutcome {
		final List<ValidationMessage> messages = new ArrayList<>();

		if (content.startsWith("﻿")) {
			content = content.replace("﻿", "");
			final var m = new ValidationMessage();
			m.setLevel(ValidationMessage.IssueSeverity.WARNING);
			m.setMessage(
				"Resource content has a UTF-8 BOM marking, skipping BOM, see https://en.wikipedia.org/wiki/Byte_order_mark");
			m.setCol(0);
			m.setLine(0);
			messages.add(m);
		}

		final var format = encoding == EncodingEnum.XML ? FhirFormat.XML : FhirFormat.JSON;
		final var stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
		try {
			messages.addAll(engine.validate(format, stream, profile));
		} catch (IOException e) {
			log.error("Internal validation error", e);
			final var m = new ValidationMessage();
			m.setLevel(ValidationMessage.IssueSeverity.FATAL);
			m.setMessage(
				"Internal validation exception, contact support " + e.getMessage());
			m.setCol(0);
			m.setLine(0);
			messages.add(m);
		}
		return messages;
	}

	/**
	 * Returns the slicing details of a validation message, empty if it has none.
	 */
	public static List<String> getSliceInfo(final ValidationMessage message, final MatchboxEngine engine) {
		if (message.hasSliceInfo() && message.sliceHtml != null) {
			return engine.filterSlicingMessages(message.sliceHtml);
		}
		return List.of();
	}

	/**
	 * Returns the text of a validation message, followed by its slicing details if there are any.
	 */
	public static String getMessageWithSliceInfo(final ValidationMessage message, final MatchboxEngine engine) {
		final var text = new StringBuilder(Objects.requireNonNullElse(message.getMessage(), ""));
		final List<String> sliceInfo = getSliceInfo(message, engine);
		if (!sliceInfo.isEmpty()) {
			text.append(" Slice info:");
			for (int i = 0; i < sliceInfo.size(); ++i) {
				text.append(" ");
				text.append(i + 1);
				text.append(".) ");
				text.append(sliceInfo.get(i));
			}
		}
		return text.toString();
	}

	/**
	 * Creates the OperationOutcome that {@code $validate} returns for a validation result.
	 *
	 * @param id     the id of the OperationOutcome.
	 * @param result the result of a validation that could be done ({@link ValidationResult#isValidated()}).
	 */
	public OperationOutcome toOperationOutcome(final String id, final ValidationResult result) {
		final PreparedValidation prepared = result.prepared();
		final MatchboxEngine engine = prepared.engine();
		final CliContext cliContext = prepared.cliContext();
		final long ms = result.millis();
		final var oo = new OperationOutcome();
		oo.setId(id);

		{
			// Add an information message about the validation
			final var issue = oo.addIssue();
			issue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
			issue.setCode(OperationOutcome.IssueType.INFORMATIONAL);

			final StructureDefinition structDef = prepared.structureDefinition();

			final var profileDate = (structDef.getDateElement() != null)
				? " (%s)".formatted(structDef.getDateElement().asStringValue())
				: " ";

			issue.setDiagnostics(
				"Validation for profile %s|%s%s. Loaded packages: %s. Duration: %s. %s. Validation parameters: %s".formatted(
					structDef.getUrl(),
					structDef.getVersion(),
					profileDate,
					String.join(", ", engine.getContext().getLoadedPackages()),
					ms / 1000.0 + "s",
					VersionUtil.getPoweredBy(),
					cliContext.toString()
				));

			// Set the validator version, as per the FHIR Tooling Extensions
			oo.addExtension("http://hl7.org/fhir/tools/StructureDefinition/validator-version",
								 new StringType(VersionUtil.getPoweredBy()));

			var ext = issue.addExtension().setUrl("http://matchbox.health/validation");
			addExtension(ext, "profile", new UriType(structDef.getUrl()));
			addExtension(ext, "profileVersion", new UriType(structDef.getVersion()));
			addExtension(ext, "profileDate", structDef.hasDate()
				? new org.hl7.fhir.r5.model.DateTimeType(structDef.getDateElement().getValueAsString())
				: null);

			ext.addExtension("total", new Duration().setUnit("ms").setValue(ms));
			addExtension(ext, "validatorVersion", new StringType(VersionUtil.getPoweredBy()));
			cliContext.addContextToExtension(ext);
			ext.addExtension("onlyOneEngine", new BooleanType(this.matchboxProps.getContext().isOnlyOneEngine()));
			ext.addExtension("httpReadOnly", new BooleanType(this.matchboxProps.getContext().isHttpReadOnly()));
			ext.addExtension("ssrfProtectionEnabled", new BooleanType(this.matchboxProps.getContext().isSsrfProtectionEnabled()));

			if (prepared.sessionId() != null) {
				addExtension(ext, "sessionId", new StringType(prepared.sessionId()));
			}
			for (final String pkg : engine.getContext().getLoadedPackages()) {
				addExtension(ext, "package", new StringType(pkg));
			}
			for (final String suppressedWarning : engine.getSuppressedWarnInfoPatterns()) {
				addExtension(ext, "suppressedWarning", new StringType(suppressedWarning));
			}
			for (final String suppressedError : engine.getSuppressedErrors()) {
				addExtension(ext, "suppressedError", new StringType(suppressedError));
			}
		}

		// Map the SingleValidationMessages to OperationOutcomeIssue
		for (final ValidationMessage message : result.messages()) {
			if (message.getType() == null) {
				// Note: this did not happen with previous core versions
				message.setType(ValidationMessage.IssueType.UNKNOWN);
			}
			final var issue = OperationOutcomeUtilities.convertToIssue(message, oo);

			// Note: the message is mapped to details.text by HAPI, but we still need it in diagnostics for the EVSClient,
			//       so we move it. This could be changed in the future.
			// The slice info is added to diagnostics as well.
			issue.setDiagnostics(getMessageWithSliceInfo(message, engine));
			issue.setDetails(null);

			oo.addIssue(issue);
		}

		// Add an information message about success, if needed
		if (!result.hasErrors()) {
			final var issue = oo.addIssue();
			issue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
			issue.setCode(OperationOutcome.IssueType.INFORMATIONAL);
			issue.setDiagnostics("No fatal or error issues detected, the validation has passed");
		}

		if (!prepared.otherMetaProfiles().isEmpty()) {
			oo.addIssue()
				.setSeverity(OperationOutcome.IssueSeverity.INFORMATION)
				.setCode(OperationOutcome.IssueType.INFORMATIONAL)
				.setDiagnostics(
					"The resource declares several profiles in meta.profile, the validation engine was selected for the first one, '%s'. The other ones are only validated if this engine knows them: %s".formatted(
						prepared.profile(), String.join(", ", prepared.otherMetaProfiles())));
		}

		return oo;
	}

	/**
	 * Stores the OperationOutcome of a validation for the validation statistics, if they are saved
	 * ({@code matchbox.validation.save-statistics}); does nothing otherwise. The OperationOutcome gets a new id.
	 */
	public void saveForStatistics(final @Nullable OperationOutcome operationOutcome) {
		final boolean hasNoOperationOutcomeResourceProvider = this.operationOutcomeResourceProviderR4.isEmpty()
			&& this.operationOutcomeResourceProviderR4B.isEmpty()
			&& this.operationOutcomeResourceProviderR5.isEmpty();

		if (hasNoOperationOutcomeResourceProvider || operationOutcome == null || this.matchboxFhirVersion == null) {
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
}
