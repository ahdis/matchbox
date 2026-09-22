package ch.ahdis.matchbox.validation;

import ca.uhn.fhir.rest.api.EncodingEnum;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.config.property.MatchboxFhirProperties;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.cli.VersionUtil;
import ch.ahdis.matchbox.engine.exception.MatchboxEngineCreationException;
import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import org.apache.commons.beanutils.BeanUtils;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.hl7.fhir.r5.elementmodel.Manager.FhirFormat;
import org.hl7.fhir.r5.model.BooleanType;
import org.hl7.fhir.r5.model.Duration;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.StringType;
import org.hl7.fhir.r5.model.UriType;
import org.hl7.fhir.r5.utils.EOperationOutcome;
import org.hl7.fhir.r5.utils.OperationOutcomeUtilities;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static ch.ahdis.matchbox.util.MatchboxServerUtils.addExtension;

/**
 * The validation logic shared by the validation APIs: {@code $validate}, the Gazelle API and the ITB API.
 */
public class ValidationHelper {
	private static final Logger log = LoggerFactory.getLogger(ValidationHelper.class);

	private final MatchboxEngineSupport matchboxEngineSupport;
	private final MatchboxFhirProperties matchboxProps;

	public ValidationHelper(final MatchboxEngineSupport matchboxEngineSupport,
									final MatchboxFhirProperties matchboxProps) {
		this.matchboxEngineSupport = Objects.requireNonNull(matchboxEngineSupport);
		this.matchboxProps = Objects.requireNonNull(matchboxProps);
	}

	/**
	 * A profile reference, {@code canonical} or {@code canonical|version}.
	 */
	public record ProfileReference(@NonNull String canonical, @Nullable String version) {
		public static ProfileReference parse(final @NonNull String profile) {
			final int versionSeparator = profile.lastIndexOf('|');
			if (versionSeparator == -1) {
				return new ProfileReference(profile, null);
			}
			return new ProfileReference(profile.substring(0, versionSeparator), profile.substring(versionSeparator + 1));
		}
	}

	/**
	 * Sets the validation parameters ({@link CliContext#getValidateEngineParameters()}) found in a request on the
	 * given CLI context.
	 *
	 * @param cliContext      the CLI context of the request, updated in place.
	 * @param parameterValues returns the values of a parameter by name, or {@code null} if the request does not have it.
	 */
	public static void applyValidationParameters(final CliContext cliContext,
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
	 * Returns the Matchbox engine that can validate against the given profile.
	 *
	 * @param profile    the profile, {@code canonical} or {@code canonical|version}.
	 * @param cliContext the CLI context of the request.
	 * @param reload     whether to reload the engine.
	 * @throws MatchboxEngineCreationException if no engine can validate against the profile.
	 */
	public MatchboxEngine getEngine(final @NonNull String profile,
											  final @NonNull CliContext cliContext,
											  final boolean reload) throws MatchboxEngineCreationException {
		final MatchboxEngine engine;
		try {
			engine = this.matchboxEngineSupport.getMatchboxEngine(profile, cliContext, true, reload);
		} catch (final Exception e) {
			log.error("Error while initializing the validation engine", e);
			throw new MatchboxEngineCreationException(
				"Error while initializing the validation engine: %s".formatted(e.getMessage()), e);
		}
		if (engine == null) {
			throw new MatchboxEngineCreationException(
				"Matchbox engine for profile '%s' could not be created, check the installed IGs".formatted(profile));
		}
		if (engine.getStructureDefinitionR5(ProfileReference.parse(profile).canonical()) == null) {
			throw new MatchboxEngineCreationException(
				"Validation for profile '%s' not supported by this validator instance".formatted(profile));
		}
		if (!this.matchboxEngineSupport.isInitialized()) {
			throw new MatchboxEngineCreationException("Validation engine not initialized, please try again");
		}
		return engine;
	}

	/**
	 * Returns the id of the cached engine, or {@code null}.
	 */
	public @Nullable String getSessionId(final MatchboxEngine engine) {
		return this.matchboxEngineSupport.getSessionId(engine);
	}

	/**
	 * Validates the content against the profile.
	 *
	 * @param profile the profile canonical, without version.
	 */
	public static List<ValidationMessage> doValidate(final MatchboxEngine engine,
																	 String content,
																	 final EncodingEnum encoding,
																	 final String profile) throws EOperationOutcome {
		final List<ValidationMessage> messages = new ArrayList<>();

		if (content.startsWith("\uFEFF")) {
			content = content.replace("\uFEFF", "");
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
	 * Returns the text of a validation message, followed by its slicing details if there are any.
	 */
	public static String getMessageWithSliceInfo(final ValidationMessage message, final MatchboxEngine engine) {
		final var text = new StringBuilder(Objects.requireNonNullElse(message.getMessage(), ""));
		if (message.hasSliceInfo() && message.sliceHtml != null) {
			final List<String> sliceInfo = engine.filterSlicingMessages(message.sliceHtml);
			if (!sliceInfo.isEmpty()) {
				text.append(" Slice info:");
				for (int i = 0; i < sliceInfo.size(); ++i) {
					text.append(" ");
					text.append(i + 1);
					text.append(".) ");
					text.append(sliceInfo.get(i));
				}
			}
		}
		return text.toString();
	}

	/**
	 * Creates the OperationOutcome that {@code $validate} returns for the validation messages.
	 *
	 * @param profile the profile canonical, without version.
	 */
	public OperationOutcome getOperationOutcome(final String id,
															  final List<ValidationMessage> messages,
															  final String profile,
															  final MatchboxEngine engine,
															  final long ms,
															  final CliContext cliContext) {
		final var oo = new OperationOutcome();
		oo.setId(id);

		{
			// Add an information message about the validation
			final var issue = oo.addIssue();
			issue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
			issue.setCode(OperationOutcome.IssueType.INFORMATIONAL);

			final org.hl7.fhir.r5.model.StructureDefinition structDefR5 = engine.getStructureDefinitionR5(profile);

			final var profileDate = (structDefR5.getDateElement() != null)
				? " (%s)".formatted(structDefR5.getDateElement().asStringValue())
				: " ";

			issue.setDiagnostics(
				"Validation for profile %s|%s%s. Loaded packages: %s. Duration: %s. %s. Validation parameters: %s".formatted(
					structDefR5.getUrl(),
					structDefR5.getVersion(),
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
			addExtension(ext, "profile", new UriType(structDefR5.getUrl()));
			addExtension(ext, "profileVersion", new UriType(structDefR5.getVersion()));
			addExtension(ext, "profileDate", structDefR5.getDateElement());

			ext.addExtension("total", new Duration().setUnit("ms").setValue(ms));
			addExtension(ext, "validatorVersion", new StringType(VersionUtil.getPoweredBy()));
			cliContext.addContextToExtension(ext);
			ext.addExtension("onlyOneEngine", new BooleanType(this.matchboxProps.getContext().isOnlyOneEngine()));
			ext.addExtension("httpReadOnly", new BooleanType(this.matchboxProps.getContext().isHttpReadOnly()));
			ext.addExtension("ssrfProtectionEnabled", new BooleanType(this.matchboxProps.getContext().isSsrfProtectionEnabled()));

			final var sessionId = this.matchboxEngineSupport.getSessionId(engine);
			if (sessionId != null) {
				addExtension(ext, "sessionId", new StringType(sessionId));
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
		for (final ValidationMessage message : messages) {
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
		if (messages.stream().noneMatch(m -> m.getLevel() == ValidationMessage.IssueSeverity.FATAL || m.getLevel() == ValidationMessage.IssueSeverity.ERROR)) {
			final var issue = oo.addIssue();
			issue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
			issue.setCode(OperationOutcome.IssueType.INFORMATIONAL);
			issue.setDiagnostics("No fatal or error issues detected, the validation has passed");
		}

		return oo;
	}
}
