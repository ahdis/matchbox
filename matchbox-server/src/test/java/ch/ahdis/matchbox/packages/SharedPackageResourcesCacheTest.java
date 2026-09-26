package ch.ahdis.matchbox.packages;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

	private static void gc() {
		System.gc();
	}
}
