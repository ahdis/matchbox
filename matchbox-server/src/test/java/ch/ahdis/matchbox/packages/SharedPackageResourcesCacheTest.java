package ch.ahdis.matchbox.packages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The cache keeps the resources of a package only as long as something else (the context of an engine) keeps them.
 */
class SharedPackageResourcesCacheTest {

	private static final String PACKAGE = "example.package#1.0.0";

	@Test
	void keepsTheResourcesWhileAnEngineUsesThem() {
		final SharedPackageResourcesCache cache = new SharedPackageResourcesCache();
		final SharedPackageResources resources = new SharedPackageResources(PACKAGE, null);
		cache.put(resources);
		gc();
		assertSame(resources, cache.get(PACKAGE));
	}

	@Test
	void releasesTheResourcesWhenNoEngineUsesThemAnymore() throws InterruptedException {
		final SharedPackageResourcesCache cache = new SharedPackageResourcesCache();
		cache.put(new SharedPackageResources(PACKAGE, null));
		for (int i = 0; i < 50 && cache.get(PACKAGE) != null; ++i) {
			gc();
			Thread.sleep(20);
		}
		assertNull(cache.get(PACKAGE));
	}

	@Test
	void evictsAnUninstalledPackage() {
		final SharedPackageResourcesCache cache = new SharedPackageResourcesCache();
		final SharedPackageResources resources = new SharedPackageResources(PACKAGE, null);
		cache.put(resources);
		assertNotNull(cache.get(PACKAGE));
		cache.evict(PACKAGE);
		assertNull(cache.get(PACKAGE));
	}

	@Test
	void keepsTheMetadataOfAPackage() {
		final SharedPackageResourcesCache cache = new SharedPackageResourcesCache();
		final PackageMetadata metadata = new PackageMetadata("example.package", "1.0.0",
																			  List.of("hl7.terminology.r4#7.0.1"), List.of());
		cache.putMetadata(42L, metadata);
		gc();
		assertSame(metadata, cache.getMetadata(42L));
		assertEquals(PACKAGE, metadata.packageId());
		assertNull(cache.getMetadata(43L));
	}

	@Test
	void evictsTheMetadataOfAnUninstalledPackage() {
		final SharedPackageResourcesCache cache = new SharedPackageResourcesCache();
		cache.putMetadata(42L, new PackageMetadata("example.package", "1.0.0", List.of(), List.of()));
		cache.putMetadata(43L, new PackageMetadata("example.package", "2.0.0", List.of(), List.of()));
		cache.evict(PACKAGE);
		assertNull(cache.getMetadata(42L));
		assertNotNull(cache.getMetadata(43L));
	}

	@Test
	void replacesTheMetadataOfAPackageVersionThatWasInstalledAgain() {
		final SharedPackageResourcesCache cache = new SharedPackageResourcesCache();
		cache.putMetadata(42L, new PackageMetadata("example.package", "1.0.0", List.of(), List.of()));
		final PackageMetadata reinstalled = new PackageMetadata("example.package", "1.0.0", List.of("other#1.0.0"),
																				  List.of());
		cache.putMetadata(44L, reinstalled);
		assertNull(cache.getMetadata(42L));
		assertSame(reinstalled, cache.getMetadata(44L));
	}

	private static void gc() {
		System.gc();
	}
}
