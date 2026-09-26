package ch.ahdis.matchbox.packages;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Caches the conformance resources of the packages (id#version) that were loaded in a validation engine, so that the
 * engines of other IGs that depend on the same package register the same objects instead of loading and parsing the
 * package again.
 * <p>
 * The cache only keeps weak references: the resources are kept alive by the engines that use them (their worker
 * contexts retain the {@link SharedPackageResources}), and are garbage collected when the last of these engines is
 * dropped from the engine cache (e.g. after the expiry of a transient engine), without any explicit eviction here.
 */
public class SharedPackageResourcesCache {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SharedPackageResourcesCache.class);

	private final Map<String, WeakReference<SharedPackageResources>> cache = new HashMap<>();

	/**
	 * Returns the resources of a package, or null if no engine that loaded it is alive.
	 */
	public synchronized SharedPackageResources get(final String packageId) {
		final WeakReference<SharedPackageResources> reference = this.cache.get(packageId);
		final SharedPackageResources resources = reference == null ? null : reference.get();
		if (reference != null && resources == null) {
			this.cache.remove(packageId);
		}
		return resources;
	}

	public synchronized void put(final SharedPackageResources resources) {
		this.cache.values().removeIf(reference -> reference.get() == null);
		this.cache.put(resources.getPackageId(), new WeakReference<>(resources));
	}

	/**
	 * Removes a package, e.g. when it's uninstalled: it's loaded again by the next engine that needs it.
	 */
	public synchronized void evict(final String packageId) {
		if (this.cache.remove(packageId) != null) {
			log.info("Evicted the shared resources of package {}", packageId);
		}
	}
}
