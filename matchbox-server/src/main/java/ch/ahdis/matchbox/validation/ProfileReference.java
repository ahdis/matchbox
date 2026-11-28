package ch.ahdis.matchbox.validation;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * A profile reference, {@code canonical} or {@code canonical|version}.
 */
public record ProfileReference(@Nonnull String canonical, @Nullable String version) {

	public static ProfileReference parse(final @Nonnull String profile) {
		final int versionSeparator = profile.lastIndexOf('|');
		if (versionSeparator == -1) {
			return new ProfileReference(profile, null);
		}
		return new ProfileReference(profile.substring(0, versionSeparator), profile.substring(versionSeparator + 1));
	}
}
