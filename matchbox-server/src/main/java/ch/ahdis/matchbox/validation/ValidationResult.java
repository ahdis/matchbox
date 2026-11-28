package ch.ahdis.matchbox.validation;

import ca.uhn.fhir.rest.api.EncodingEnum;
import jakarta.annotation.Nullable;
import org.hl7.fhir.utilities.validation.ValidationMessage;

import java.util.List;

/**
 * The result of the validation of one resource, which each validation API maps to its own report.
 *
 * @param prepared the engine, profile and parameters the resource was validated with.
 * @param content  the validated content.
 * @param encoding the encoding of the content.
 * @param messages the validation messages; empty if the validation failed.
 * @param millis   the duration of the validation, in milliseconds.
 * @param failure  why the content could not be validated (the engine failed), {@code null} if it was validated.
 */
public record ValidationResult(PreparedValidation prepared,
										 String content,
										 EncodingEnum encoding,
										 List<ValidationMessage> messages,
										 long millis,
										 @Nullable String failure) {

	/**
	 * Returns whether the content could be validated; the messages tell whether it is valid.
	 */
	public boolean isValidated() {
		return this.failure == null;
	}

	/**
	 * Returns whether the validation found fatal or error issues.
	 */
	public boolean hasErrors() {
		return this.messages.stream().anyMatch(
			m -> m.getLevel() == ValidationMessage.IssueSeverity.FATAL || m.getLevel() == ValidationMessage.IssueSeverity.ERROR);
	}
}
