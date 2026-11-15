package ch.ahdis.matchbox.validation.itb;

/**
 * An invalid ITB request, answered with HTTP 400 and {@code {"error": message}}.
 */
class ItbBadRequestException extends RuntimeException {
	ItbBadRequestException(final String message) {
		super(message);
	}
}
