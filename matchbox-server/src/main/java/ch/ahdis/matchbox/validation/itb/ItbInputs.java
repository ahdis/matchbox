package ch.ahdis.matchbox.validation.itb;

import ch.ahdis.matchbox.validation.itb.models.AnyContent;
import ch.ahdis.matchbox.validation.itb.models.ValueEmbeddingEnumeration;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The inputs of an ITB request, with their values resolved according to their embedding method.
 * <p>
 * {@code STRING} and {@code BASE_64} are supported. {@code URI} is rejected, as in the HL7 validator, because fetching
 * a URL given by the caller is an SSRF risk.
 */
class ItbInputs {

	private final List<AnyContent> inputs;

	ItbInputs(final @Nullable List<AnyContent> inputs) {
		this.inputs = inputs == null ? List.of() : inputs.stream().filter(Objects::nonNull).toList();
	}

	/**
	 * Returns the value of a required input.
	 *
	 * @throws ItbBadRequestException if the input is missing or empty.
	 */
	String require(final String name) {
		final AnyContent input = this.find(name);
		if (input == null) {
			throw new ItbBadRequestException("Missing required input: " + name);
		}
		final String value = resolve(input);
		if (value == null || value.isEmpty()) {
			throw new ItbBadRequestException("Required input '%s' is present but empty".formatted(name));
		}
		return value;
	}

	/**
	 * Returns the value of an optional input, or {@code null} if it is missing or empty.
	 */
	@Nullable String optional(final String name) {
		final AnyContent input = this.find(name);
		if (input == null) {
			return null;
		}
		final String value = resolve(input);
		return (value == null || value.isBlank()) ? null : value.strip();
	}

	/**
	 * Returns the value of an optional boolean input, or the default value if it is missing or empty.
	 *
	 * @throws ItbBadRequestException if the value is not {@code true} or {@code false}.
	 */
	boolean optionalBoolean(final String name, final boolean defaultValue) {
		final String value = this.optional(name);
		if (value == null) {
			return defaultValue;
		}
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "true" -> true;
			case "false" -> false;
			default -> throw new ItbBadRequestException(
				"Invalid value '%s' for input '%s', expected true or false".formatted(value, name));
		};
	}

	/**
	 * Returns all values given for an input, or {@code null} if there are none. An input can be repeated, and an input
	 * of type list gives the values of its items.
	 */
	String @Nullable [] values(final String name) {
		final var values = new ArrayList<String>();
		for (final AnyContent input : this.inputs) {
			if (!name.equals(input.getName())) {
				continue;
			}
			if (input.getItem() != null && !input.getItem().isEmpty()) {
				for (final AnyContent item : input.getItem()) {
					if (item != null) {
						addIfPresent(values, resolve(item));
					}
				}
			} else {
				addIfPresent(values, resolve(input));
			}
		}
		return values.isEmpty() ? null : values.toArray(String[]::new);
	}

	private @Nullable AnyContent find(final String name) {
		return this.inputs.stream().filter(input -> name.equals(input.getName())).findFirst().orElse(null);
	}

	private static void addIfPresent(final List<String> values, final @Nullable String value) {
		if (value != null && !value.isBlank()) {
			values.add(value.strip());
		}
	}

	/**
	 * Returns the value of an input, decoded according to its embedding method.
	 *
	 * @throws ItbBadRequestException if the embedding method is not supported or the value cannot be decoded.
	 */
	static @Nullable String resolve(final AnyContent input) {
		final String value = input.getValue();
		if (value == null) {
			return null;
		}
		final var embedding = Objects.requireNonNullElse(input.getEmbeddingMethod(), ValueEmbeddingEnumeration.STRING);
		return switch (embedding) {
			case STRING -> value;
			case BASE_64 -> {
				try {
					yield new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
				} catch (final IllegalArgumentException e) {
					throw new ItbBadRequestException("Invalid base64 value for input '%s'".formatted(input.getName()));
				}
			}
			case URI -> throw new ItbBadRequestException(
				"embeddingMethod URI is not supported (input '%s'), send the content as STRING or BASE_64".formatted(input.getName()));
		};
	}
}
