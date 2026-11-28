package ch.ahdis.matchbox.validation;

import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import jakarta.annotation.Nullable;
import org.hl7.fhir.model.core.StructureDefinition;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a validation is done with: the engine, the profile and the validation parameters. Several resources can be
 * validated with it.
 *
 * @param engine              the validation engine.
 * @param cliContext          the validation parameters of the request.
 * @param profile             the profile canonical, without version.
 * @param structureDefinition the profile, as the engine knows it.
 * @param sessionId           the id of the engine in the engine cache, if it is cached.
 * @param otherMetaProfiles   see {@link ProfileSelection#otherMetaProfiles()}.
 */
public record PreparedValidation(MatchboxEngine engine,
											CliContext cliContext,
											String profile,
											StructureDefinition structureDefinition,
											@Nullable String sessionId,
											List<String> otherMetaProfiles) {

	/**
	 * Returns the profile with the version that is validated against, {@code canonical|version}.
	 */
	public String profileId() {
		return "%s|%s".formatted(this.structureDefinition.getUrl(), this.structureDefinition.getVersion());
	}

	/**
	 * Returns the date of the profile, or {@code null} if it has none.
	 */
	public @Nullable String profileDate() {
		return this.structureDefinition.hasDate() ? this.structureDefinition.getDateElement().asStringValue() : null;
	}

	/**
	 * Returns the validation parameters that are set, by name, in the order of their declaration. The values of a
	 * repeating parameter are joined with a comma.
	 */
	public Map<String, String> parameters() {
		final Map<String, String> parameters = new LinkedHashMap<>();
		for (final Field field : this.cliContext.getValidateEngineParameters()) {
			final String value = parameterValue(field, this.cliContext);
			if (value != null) {
				parameters.put(field.getName(), value);
			}
		}
		return parameters;
	}

	/**
	 * Returns the value of a validation parameter as text, or {@code null} if it is not set.
	 */
	public static @Nullable String parameterValue(final Field field, final CliContext cliContext) {
		try {
			field.setAccessible(true);
			final Object value = field.get(cliContext);
			if (value instanceof final String[] values) {
				return values.length == 0 ? null : String.join(", ", values);
			}
			return value == null ? null : String.valueOf(value);
		} catch (final IllegalAccessException e) {
			return null;
		}
	}
}
