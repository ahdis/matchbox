package ch.ahdis.matchbox.validation.itb;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gitb.model.core.AnyContent;
import com.gitb.model.core.ValueEmbeddingEnumeration;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * The JSON mapping of the GITB types ({@code eu.europa.ec.itb:gitb-types-model}) for the REST API of the ITB services
 * ({@code gitb_vs.json}). The types are plain Java classes without JSON annotations, so the mapping is configured here
 * and not left to the mapper of the server.
 */
public final class ItbJson {

	private static final ObjectMapper MAPPER = JsonMapper.builder()
		.addModule(new JavaTimeModule())
		.addModule(new SimpleModule().addDeserializer(ValueEmbeddingEnumeration.class, new EmbeddingMethodDeserializer()))
		.addMixIn(AnyContent.class, AnyContentMixIn.class)
		.serializationInclusion(JsonInclude.Include.NON_NULL)
		// TAR.date is a date-time with the offset, as it is sent
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
		.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
		// A newer test bed may send properties that this version of the types does not know
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private ItbJson() {
	}

	/**
	 * Returns the mapper for the GITB types. It is thread-safe and must not be reconfigured.
	 */
	public static ObjectMapper mapper() {
		return MAPPER;
	}

	/**
	 * The getter of the child items creates the list, so a value without children would be written with an empty
	 * {@code item} array.
	 */
	private abstract static class AnyContentMixIn {
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		abstract List<AnyContent> getItem();
	}

	/**
	 * Reads the embedding method case-insensitively, and accepts {@code BASE64} for
	 * {@link ValueEmbeddingEnumeration#BASE_64}.
	 */
	private static final class EmbeddingMethodDeserializer extends StdDeserializer<ValueEmbeddingEnumeration> {

		EmbeddingMethodDeserializer() {
			super(ValueEmbeddingEnumeration.class);
		}

		@Override
		public ValueEmbeddingEnumeration deserialize(final JsonParser parser,
																	final DeserializationContext context) throws IOException {
			final String value = parser.getValueAsString();
			if (value == null) {
				return null;
			}
			final String normalized = value.strip().toUpperCase(Locale.ROOT);
			if ("BASE64".equals(normalized)) {
				return ValueEmbeddingEnumeration.BASE_64;
			}
			for (final ValueEmbeddingEnumeration embedding : ValueEmbeddingEnumeration.values()) {
				if (embedding.name().equals(normalized)) {
					return embedding;
				}
			}
			return (ValueEmbeddingEnumeration) context.handleWeirdStringValue(ValueEmbeddingEnumeration.class, value,
																									"Unknown embeddingMethod");
		}
	}
}
