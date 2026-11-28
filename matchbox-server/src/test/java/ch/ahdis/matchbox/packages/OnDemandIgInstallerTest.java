package ch.ahdis.matchbox.packages;

import ca.uhn.fhir.jpa.packages.PackageInstallOutcomeJson;
import ch.ahdis.matchbox.config.property.MatchboxFhirProperties;
import ch.ahdis.matchbox.events.ImplementationGuideInstalledEvent;
import ch.ahdis.matchbox.packages.OnDemandIgInstaller.IgInstallationException;
import ch.ahdis.matchbox.packages.OnDemandIgInstaller.IgInstallationException.Reason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An implementation guide that a request needs is installed from the package registry, unless the server is
 * read-only.
 */
class OnDemandIgInstallerTest {

	/**
	 * A provider with the installed packages and the ones that the registry has.
	 */
	private static class Provider implements MatchboxImplementationGuideProvider {
		final Set<String> installed = new HashSet<>(Set.of("a.ig#1.0.0"));
		final Set<String> registry = new HashSet<>(Set.of("b.ig#2.0.0"));
		final List<String> installations = new ArrayList<>();
		boolean registryIsDown = false;

		@Override
		public PackageInstallOutcomeJson loadAll(final boolean replace) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean has(final String packageId, final String packageVersion) {
			return this.installed.contains(packageId + "#" + packageVersion);
		}

		@Override
		public void installFromInternetRegistry(final String packageId, final String packageVersion) {
			final String ig = packageId + "#" + packageVersion;
			this.installations.add(ig);
			if (this.registryIsDown) {
				throw new IllegalStateException("registry is down");
			}
			if (this.registry.contains(ig)) {
				this.installed.add(ig);
			}
		}

		@Override
		public long count() {
			return this.installed.size();
		}
	}

	private final Provider provider = new Provider();
	private final List<Object> events = new ArrayList<>();

	private OnDemandIgInstaller installer(final boolean readOnly) {
		final var properties = new MatchboxFhirProperties();
		properties.getContext().setHttpReadOnly(readOnly);
		return new OnDemandIgInstaller(this.provider, properties, this.events::add);
	}

	private static IgInstallationException assertNotInstalled(final OnDemandIgInstaller installer,
																				 final String ig,
																				 final Reason reason) {
		final var exception = assertThrows(IgInstallationException.class, () -> installer.ensureInstalled(ig));
		assertEquals(reason, exception.getReason());
		assertTrue(exception.getMessage().contains(ig), exception.getMessage());
		return exception;
	}

	@Test
	void anInstalledPackageIsNotInstalledAgain() {
		this.installer(false).ensureInstalled("a.ig#1.0.0");
		this.installer(true).ensureInstalled("a.ig#1.0.0");
		assertTrue(this.provider.installations.isEmpty());
		assertTrue(this.events.isEmpty());
	}

	@Test
	void aMissingPackageIsInstalledFromTheRegistry() {
		final OnDemandIgInstaller installer = this.installer(false);
		installer.ensureInstalled("b.ig#2.0.0");
		assertTrue(this.provider.has("b.ig", "2.0.0"));
		assertEquals(List.of("b.ig#2.0.0"), this.provider.installations);
		assertEquals(1, this.events.size());
		assertInstanceOf(ImplementationGuideInstalledEvent.class, this.events.getFirst());

		installer.ensureInstalled("b.ig#2.0.0");
		assertEquals(1, this.provider.installations.size());
	}

	@Test
	void aReadOnlyServerInstallsNothing() {
		final OnDemandIgInstaller installer = this.installer(true);
		final var exception = assertNotInstalled(installer, "b.ig#2.0.0", Reason.READ_ONLY);
		assertTrue(exception.getMessage().contains("read-only"), exception.getMessage());
		// read-only is the reason, whatever the value is
		assertNotInstalled(installer, "b.ig", Reason.READ_ONLY);
		assertTrue(this.provider.installations.isEmpty());
		assertFalse(this.provider.has("b.ig", "2.0.0"));
		assertTrue(this.events.isEmpty());
	}

	@Test
	void aPackageThatTheRegistryDoesNotHaveIsAnError() {
		final OnDemandIgInstaller installer = this.installer(false);
		assertNotInstalled(installer, "c.ig#3.0.0", Reason.FAILED);
		assertEquals(List.of("c.ig#3.0.0"), this.provider.installations);

		this.provider.registryIsDown = true;
		final var exception = assertNotInstalled(installer, "b.ig#2.0.0", Reason.FAILED);
		assertTrue(exception.getMessage().contains("registry is down"), exception.getMessage());
		assertTrue(this.events.isEmpty());
	}

	@Test
	void onlyAPackageWithItsVersionIsInstalled() {
		final OnDemandIgInstaller installer = this.installer(false);
		for (final String ig : List.of("b.ig", "b.ig#", "b.ig#latest", "b.ig#2.0.x", "b.ig#current", "../b.ig#2.0.0",
												 "b.ig#2.0.0/../x", "http://example.org/ig")) {
			assertFalse(OnDemandIgInstaller.isPackageWithVersion(ig), ig);
			assertNotInstalled(installer, ig, Reason.NOT_A_PACKAGE_WITH_VERSION);
		}
		assertTrue(this.provider.installations.isEmpty());

		for (final String ig : List.of("b.ig#2.0.0", "hl7.fhir.uv.ips#2.0.0-ballot", "ch.fhir.ig.ch-core#6.0.0+build.1",
												 "hl7.fhir.uv.extensions.r4#5.2.0")) {
			assertTrue(OnDemandIgInstaller.isPackageWithVersion(ig), ig);
		}
	}
}
