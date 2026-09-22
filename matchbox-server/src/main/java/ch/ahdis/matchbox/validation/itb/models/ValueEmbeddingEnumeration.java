package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * Supported ways of embedding an AnyContent value.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
public enum ValueEmbeddingEnumeration {
	STRING,
	BASE_64,
	URI;

	/**
	 * Reads the value case-insensitively, and accepts {@code BASE64} for {@link #BASE_64}.
	 */
	@JsonCreator
	public static ValueEmbeddingEnumeration fromValue(final String value) {
		if (value == null) {
			return null;
		}
		final String normalized = value.strip().toUpperCase(Locale.ROOT);
		if ("BASE64".equals(normalized)) {
			return BASE_64;
		}
		for (final ValueEmbeddingEnumeration embedding : values()) {
			if (embedding.name().equals(normalized)) {
				return embedding;
			}
		}
		throw new IllegalArgumentException("Unknown embeddingMethod '%s'".formatted(value));
	}
}
