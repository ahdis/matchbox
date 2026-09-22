package ch.ahdis.matchbox.validation.itb;

import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.validation.ValidationHelper;
import ch.ahdis.matchbox.validation.itb.models.AnyContent;
import ch.ahdis.matchbox.validation.itb.models.ReportItem;
import ch.ahdis.matchbox.validation.itb.models.SeverityLevel;
import ch.ahdis.matchbox.validation.itb.models.TAR;
import ch.ahdis.matchbox.validation.itb.models.TestResultType;
import ch.ahdis.matchbox.validation.itb.models.ValidationCounters;
import ch.ahdis.matchbox.validation.itb.models.ValueEmbeddingEnumeration;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.hl7.fhir.utilities.validation.ValidationMessage;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Converts validation messages into an ITB test assertion report (TAR), following the mapping of the HL7 validator's
 * ITB services (hapifhir/org.hl7.fhir.core#2615, {@code itb-rest-spec.md} §2.8).
 */
final class ItbTarMapper {

	static final String CONTEXT_ERROR_COUNT = "errorCount";
	static final String CONTEXT_WARNING_COUNT = "warningCount";
	static final String CONTEXT_INFORMATION_COUNT = "informationCount";
	static final String CONTEXT_SEVERITY = "severity";
	static final String CONTEXT_VALIDATION = "validation";
	static final String CONTEXT_OPERATION_OUTCOME = "operationOutcome";
	static final String CONTEXT_CONTENT = "content";

	private ItbTarMapper() {
	}

	/**
	 * The severity from which the result of the report is {@link TestResultType#FAILURE}.
	 */
	enum FailOn {
		ERROR,
		WARNING,
		INFORMATION;

		/**
		 * Parses the {@code failOn} input; {@code null} gives {@link #ERROR}.
		 *
		 * @throws ItbBadRequestException if the value is not error, warning or information.
		 */
		static FailOn parse(final @Nullable String value) {
			if (value == null) {
				return ERROR;
			}
			return switch (value.toLowerCase(Locale.ROOT)) {
				case "error" -> ERROR;
				case "warning" -> WARNING;
				case "information" -> INFORMATION;
				default -> throw new ItbBadRequestException(
					"Invalid value '%s' for input 'failOn', expected error, warning or information".formatted(value));
			};
		}
	}

	/**
	 * Creates the report for the validation messages, with its items, counters, result and the count items of the
	 * context.
	 *
	 * @param contentName the name of the context item holding the validated content, or {@code null} if the content is
	 *                    not in the report. When set, item locations point to the line and column in that content.
	 */
	static TAR toTar(final List<ValidationMessage> messages,
						  final MatchboxEngine engine,
						  final FailOn failOn,
						  final @Nullable String contentName) {
		final var tar = newTar();
		for (final ValidationMessage message : messages) {
			tar.addItem(toItem(message, engine, contentName));
		}
		final var counters = countItems(tar.getItems());
		tar.setCounters(counters);
		tar.setResult(deriveResult(counters, failOn));
		tar.setContext(newContext(counters, highestSeverity(messages)));
		return tar;
	}

	/**
	 * Creates a report with a single error, for a validation that could not be done (e.g. an unknown profile).
	 */
	static TAR failure(final String description) {
		return singleErrorTar(TestResultType.FAILURE, description);
	}

	/**
	 * Creates a report with a single error, for a validation where the engine failed.
	 */
	static TAR undefined(final String description) {
		return singleErrorTar(TestResultType.UNDEFINED, description);
	}

	static ReportItem toItem(final ValidationMessage message,
									 final MatchboxEngine engine,
									 final @Nullable String contentName) {
		final var item = new ReportItem();
		item.setLevel(toLevel(message.getLevel()));
		item.setDescription(ValidationHelper.getMessageWithSliceInfo(message, engine));

		// ITB links a location '<context item name>:<line>:<column>' to that point in the content, and shows the text
		// after a '|' as the location, here the FHIRPath of the element
		if (contentName != null && message.getLine() > 0) {
			item.setLocation("%s:%d:%d|%s".formatted(contentName,
																 message.getLine(),
																 Math.max(message.getCol(), 0),
																 Objects.requireNonNullElse(message.getLocation(), "")));
		} else {
			item.setLocation(message.getLocation());
		}

		if (message.getMessageId() != null) {
			item.setAssertionID(message.getMessageId());
		} else if (message.getInvId() != null) {
			item.setAssertionID(message.getInvId());
		} else if (message.getType() != null) {
			item.setAssertionID(message.getType().toCode());
		}
		if (message.getType() != null) {
			item.setType(message.getType().toCode());
		}
		return item;
	}

	static SeverityLevel toLevel(final ValidationMessage.@Nullable IssueSeverity severity) {
		if (severity == null) {
			return SeverityLevel.INFO;
		}
		return switch (severity) {
			case FATAL, ERROR -> SeverityLevel.ERROR;
			case WARNING -> SeverityLevel.WARNING;
			default -> SeverityLevel.INFO;
		};
	}

	/**
	 * Derives the result of the report: by default, errors give {@code FAILURE} and warnings {@code WARNING};
	 * {@code failOn=warning} makes warnings a {@code FAILURE}, and {@code failOn=information} any issue.
	 */
	static TestResultType deriveResult(final ValidationCounters counters, final FailOn failOn) {
		final boolean hasErrors = counters.getNrOfErrors() > 0;
		final boolean hasWarnings = counters.getNrOfWarnings() > 0;
		final boolean hasInformation = counters.getNrOfAssertions() > 0;
		return switch (failOn) {
			case ERROR -> hasErrors ? TestResultType.FAILURE : (hasWarnings ? TestResultType.WARNING : TestResultType.SUCCESS);
			case WARNING -> (hasErrors || hasWarnings) ? TestResultType.FAILURE : TestResultType.SUCCESS;
			case INFORMATION -> (hasErrors || hasWarnings || hasInformation) ? TestResultType.FAILURE : TestResultType.SUCCESS;
		};
	}

	/**
	 * Creates a context item, as plain text.
	 *
	 * @param forDisplay whether ITB shows the item in the report; hidden items can still be read by the test session.
	 */
	static AnyContent contextItem(final @Nullable String name,
											final String value,
											final String mimeType,
											final boolean forDisplay) {
		return new AnyContent()
			.setName(name)
			.setValue(value)
			.setType("string")
			.setEmbeddingMethod(ValueEmbeddingEnumeration.STRING)
			.setEncoding("UTF-8")
			.setMimeType(mimeType)
			.setForContext(true)
			.setForDisplay(forDisplay);
	}

	private static TAR newTar() {
		return new TAR()
			.setId(UUID.randomUUID().toString())
			.setDate(OffsetDateTime.now().toString())
			.setItems(new ArrayList<>());
	}

	private static TAR singleErrorTar(final TestResultType result, final String description) {
		final var tar = newTar();
		tar.addItem(new ReportItem().setLevel(SeverityLevel.ERROR).setDescription(description).setType("exception"));
		final var counters = countItems(tar.getItems());
		tar.setCounters(counters);
		tar.setResult(result);
		tar.setContext(newContext(counters, "error"));
		return tar;
	}

	private static ValidationCounters countItems(final List<ReportItem> items) {
		int errors = 0;
		int warnings = 0;
		int information = 0;
		for (final ReportItem item : items) {
			switch (item.getLevel()) {
				case ERROR -> errors++;
				case WARNING -> warnings++;
				case INFO -> information++;
			}
		}
		return new ValidationCounters().setNrOfErrors(errors).setNrOfWarnings(warnings).setNrOfAssertions(information);
	}

	/**
	 * Creates the report context, a map whose items the test session can read, e.g. {@code $ctx{errorCount}} after
	 * {@code <verify output="$ctx">}. The counts are hidden from the displayed report, which shows the counters.
	 */
	private static AnyContent newContext(final ValidationCounters counters, final String severity) {
		return new AnyContent()
			.setType("map")
			.addItem(contextItem(CONTEXT_ERROR_COUNT, String.valueOf(counters.getNrOfErrors()), "text/plain", false))
			.addItem(contextItem(CONTEXT_WARNING_COUNT, String.valueOf(counters.getNrOfWarnings()), "text/plain", false))
			.addItem(contextItem(CONTEXT_INFORMATION_COUNT, String.valueOf(counters.getNrOfAssertions()), "text/plain", false))
			.addItem(contextItem(CONTEXT_SEVERITY, severity, "text/plain", false));
	}

	/**
	 * Returns the highest severity of the messages as a FHIR issue severity code; {@code information} if there are none.
	 */
	private static String highestSeverity(final List<ValidationMessage> messages) {
		ValidationMessage.IssueSeverity highest = null;
		for (final ValidationMessage message : messages) {
			final var severity = message.getLevel();
			// Lower ordinal = more severe (FATAL, ERROR, WARNING, INFORMATION)
			if (severity != null && severity != ValidationMessage.IssueSeverity.NULL
				&& (highest == null || severity.ordinal() < highest.ordinal())) {
				highest = severity;
			}
		}
		return highest == null ? "information" : highest.toCode();
	}
}
