package ch.ahdis.matchbox.validation;

import jakarta.annotation.Nullable;

import java.util.List;

/**
 * The profile to validate a resource against, chosen the same way by all validation APIs.
 *
 * @param profile           the profile, {@code canonical} or {@code canonical|version}.
 * @param otherMetaProfiles the other profiles that the resource declares in {@code meta.profile}, if the profile is
 *                          the first of them. The validation engine is selected for the profile only, the other ones
 *                          are validated if this engine knows them.
 */
public record ProfileSelection(String profile, List<String> otherMetaProfiles) {

	private static final String BASE_DEFINITION_PREFIX = "http://hl7.org/fhir/StructureDefinition/";

	/**
	 * Selects the profile: the requested one, otherwise the one given with the resource (the {@code profile} parameter
	 * of a {@code Parameters} envelope), otherwise the first {@code meta.profile} of the resource, otherwise the base
	 * profile of its resource type.
	 *
	 * @param requested       the profile requested by the client, if any.
	 * @param envelopeProfile the profile given with the resource, if any.
	 * @param metaProfiles    the {@code meta.profile} values of the resource.
	 * @param resourceType    the resource type, if known.
	 * @return the selection, or {@code null} if no profile is given and the resource type is not known.
	 */
	public static @Nullable ProfileSelection select(final @Nullable String requested,
																	final @Nullable String envelopeProfile,
																	final List<String> metaProfiles,
																	final @Nullable String resourceType) {
		if (requested != null) {
			return new ProfileSelection(requested, List.of());
		}
		if (envelopeProfile != null) {
			return new ProfileSelection(envelopeProfile, List.of());
		}
		if (!metaProfiles.isEmpty()) {
			return new ProfileSelection(metaProfiles.getFirst(), List.copyOf(metaProfiles.subList(1, metaProfiles.size())));
		}
		if (resourceType != null && resourceType.matches("[A-Z][A-Za-z]+")) {
			return new ProfileSelection(BASE_DEFINITION_PREFIX + resourceType, List.of());
		}
		return null;
	}
}
