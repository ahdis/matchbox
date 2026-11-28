package ch.ahdis.matchbox.validation;

import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.util.MatchboxEngineSupport.InstalledPackage;
import jakarta.annotation.Nullable;
import org.hl7.fhir.utilities.VersionUtilities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The implementation guides of a request that names them, instead of leaving the choice to the profile.
 * <p>
 * The validation engine is cached by the validation parameters, the implementation guides included
 * ({@link CliContext#sessionId()}). The requested implementation guides are therefore normalised, so that the same
 * set always gives the same engine: resolved to the installed {@code id#version}, without duplicates and without the
 * FHIR core package (it is given by the FHIR version), and sorted. One implementation guide gives the engine that the
 * other validation APIs use for its profiles.
 *
 * @param igs         the implementation guides to load, {@code id#version}, sorted. Empty if the request names none
 *                    (or only the FHIR core package): the profile then chooses the implementation guide.
 * @param fhirVersion the FHIR version of the first requested implementation guide, {@code null} if none is requested.
 */
public record IgSelection(List<String> igs, @Nullable String fhirVersion) {

	/**
	 * Selects the implementation guides.
	 *
	 * @param requested the implementation guides of the request, {@code id#version} or {@code id} (the current
	 *                  version); blank values are ignored.
	 * @param installed returns the installed package of an implementation guide, or empty if it is not installed.
	 * @throws IgNotInstalledException if a requested implementation guide is not installed.
	 */
	public static IgSelection of(final Collection<String> requested,
										  final Function<String, Optional<InstalledPackage>> installed) {
		final var igs = new TreeSet<String>();
		final List<String> notInstalled = new ArrayList<>();
		String fhirVersion = null;
		for (final String ig : requested) {
			if (ig == null || ig.isBlank()) {
				continue;
			}
			final Optional<InstalledPackage> installedPackage = installed.apply(ig.strip());
			if (installedPackage.isEmpty()) {
				notInstalled.add(ig.strip());
				continue;
			}
			if (fhirVersion == null) {
				fhirVersion = installedPackage.get().fhirVersion();
			}
			if (!VersionUtilities.isCorePackage(installedPackage.get().id())) {
				igs.add(installedPackage.get().ig());
			}
		}
		if (!notInstalled.isEmpty()) {
			throw new IgNotInstalledException(notInstalled);
		}
		return new IgSelection(List.copyOf(igs), fhirVersion);
	}

	/**
	 * Sets the implementation guides in the validation parameters of the request: the engine is then selected for
	 * them and not for the implementation guide of the profile. Does nothing if there are none.
	 */
	public void applyTo(final CliContext cliContext) {
		if (this.igs.isEmpty()) {
			return;
		}
		cliContext.setIg(this.igs.getFirst());
		cliContext.setIgs(this.igs.size() == 1 ? null : this.igs.subList(1, this.igs.size()).toArray(String[]::new));
	}

	/**
	 * Thrown when a requested implementation guide is not installed.
	 */
	public static class IgNotInstalledException extends RuntimeException {
		private final List<String> igs;

		public IgNotInstalledException(final List<String> igs) {
			super("Implementation guide not installed on this server: %s".formatted(String.join(", ", igs)));
			this.igs = List.copyOf(igs);
		}

		/**
		 * Returns the implementation guides that are not installed, as requested.
		 */
		public List<String> getIgs() {
			return this.igs;
		}
	}
}
