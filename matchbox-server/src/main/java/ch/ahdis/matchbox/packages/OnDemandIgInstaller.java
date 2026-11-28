package ch.ahdis.matchbox.packages;

import ch.ahdis.matchbox.config.property.MatchboxFhirProperties;
import ch.ahdis.matchbox.events.ImplementationGuideInstalledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs an implementation guide that a request needs and that is not installed, for the APIs that have it: the
 * ITB processing service {@code IGManager} and the validation API of the HL7 validator ({@code /core/validate}).
 * <p>
 * The package is installed from the package registry with its dependencies, unless the server is read-only
 * ({@code matchbox.fhir.context.httpReadOnly}): the packages of a read-only server are the ones of its configuration.
 */
public class OnDemandIgInstaller {
	private static final Logger log = LoggerFactory.getLogger(OnDemandIgInstaller.class);

	// The id and the version go into the URL of the package registry
	private static final Pattern PACKAGE = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]*)#([A-Za-z0-9][A-Za-z0-9._+-]*)");

	// The versions that are resolved to another one, by the registry or among the installed packages
	private static final Set<String> VERSION_ALIASES = Set.of("latest", "current", "dev");

	private final MatchboxImplementationGuideProvider igProvider;
	private final MatchboxFhirProperties matchboxProps;
	private final ApplicationEventPublisher eventPublisher;

	public OnDemandIgInstaller(final MatchboxImplementationGuideProvider igProvider,
										final MatchboxFhirProperties matchboxProps,
										final ApplicationEventPublisher eventPublisher) {
		this.igProvider = Objects.requireNonNull(igProvider);
		this.matchboxProps = Objects.requireNonNull(matchboxProps);
		this.eventPublisher = Objects.requireNonNull(eventPublisher);
	}

	/**
	 * Returns whether the value is a package with an exact version, {@code id#version}: the only form that can be
	 * installed. A version like {@code latest} or {@code 3.3.x} is not exact.
	 */
	public static boolean isPackageWithVersion(final String ig) {
		final Matcher matcher = PACKAGE.matcher(ig);
		if (!matcher.matches()) {
			return false;
		}
		final String version = matcher.group(2);
		return !VERSION_ALIASES.contains(version) && !version.endsWith(".x") && !version.contains(".x.");
	}

	/**
	 * Makes sure that a package is installed: installs it from the package registry if it is not.
	 *
	 * @param ig the package, {@code id#version}.
	 * @throws IgInstallationException if the package is not installed and cannot be: the server is read-only, the
	 *                                 value is not a package with its version, or the installation failed.
	 */
	public void ensureInstalled(final String ig) {
		final boolean packageWithVersion = isPackageWithVersion(ig);
		final String id = packageWithVersion ? ig.substring(0, ig.indexOf('#')) : null;
		final String version = packageWithVersion ? ig.substring(ig.indexOf('#') + 1) : null;
		if (packageWithVersion && this.igProvider.has(id, version)) {
			return;
		}
		if (this.matchboxProps.getContext().isHttpReadOnly()) {
			throw new IgInstallationException(IgInstallationException.Reason.READ_ONLY,
				"The package %s is not installed and the server is read-only, add it to the configuration of matchbox"
					.formatted(ig));
		}
		if (!packageWithVersion) {
			throw new IgInstallationException(IgInstallationException.Reason.NOT_A_PACKAGE_WITH_VERSION,
				"The package %s is not installed, and it can only be installed with its version: 'id#version'"
					.formatted(ig));
		}
		final String error = this.install(id, version);
		if (error != null) {
			throw new IgInstallationException(IgInstallationException.Reason.FAILED,
				"The package %s could not be installed: %s".formatted(ig, error));
		}
	}

	/**
	 * Installs the package from the package registry, one at a time.
	 *
	 * @return the error, or {@code null} if the package is installed.
	 */
	private synchronized String install(final String id, final String version) {
		if (this.igProvider.has(id, version)) {
			return null; // installed by another request in the meantime
		}
		log.info("Installing {}#{} on demand", id, version);
		try {
			this.igProvider.installFromInternetRegistry(id, version);
		} catch (final Exception e) {
			log.error("Error installing {}#{}", id, version, e);
			return e.getMessage();
		}
		if (!this.igProvider.has(id, version)) {
			return "not found";
		}
		this.eventPublisher.publishEvent(new ImplementationGuideInstalledEvent(this));
		return null;
	}

	/**
	 * Thrown when a package is not installed and cannot be installed.
	 */
	public static class IgInstallationException extends RuntimeException {

		public enum Reason {
			/** The server is read-only. */
			READ_ONLY,
			/** The value is not {@code id#version} with an exact version. */
			NOT_A_PACKAGE_WITH_VERSION,
			/** The installation failed, e.g. the registry doesn't have the package. */
			FAILED
		}

		private final Reason reason;

		public IgInstallationException(final Reason reason, final String message) {
			super(message);
			this.reason = reason;
		}

		public Reason getReason() {
			return this.reason;
		}
	}
}
