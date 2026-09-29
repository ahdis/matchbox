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
 * <p>
 * It also keeps the {@link PackageMetadata} of the installed package versions, by the id of their
 * package archive (Binary), so that the archive only needs to be read to load the resources (#609). The metadata is
 * small and kept as long as the package is installed; a package version that is installed again gets a new archive.
 */
public class SharedPackageResourcesCache {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SharedPackageResourcesCache.class);

	private final Map<String, WeakReference<SharedPackageResources>> cache = new HashMap<>();

	private final Map<Long, PackageMetadata> metadata = new HashMap<>();

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
	 * Returns the metadata of an installed package version, or null if it hasn't been read yet.
	 *
	 * @param packageArchivePid the id of the package archive (Binary) of the package version
	 */
	public synchronized PackageMetadata getMetadata(final long packageArchivePid) {
		return this.metadata.get(packageArchivePid);
	}

	public synchronized void putMetadata(final long packageArchivePid, final PackageMetadata packageMetadata) {
		// a package version that was installed again (e.g. a ci-build) has a new archive
		this.metadata.values().removeIf(m -> m.packageId().equals(packageMetadata.packageId()));
		this.metadata.put(packageArchivePid, packageMetadata);
	}

	/**
	 * Removes a package, e.g. when it's uninstalled: it's loaded again by the next engine that needs it.
	 */
	public synchronized void evict(final String packageId) {
		this.metadata.values().removeIf(m -> m.packageId().equals(packageId));
		if (this.cache.remove(packageId) != null) {
			log.info("Evicted the shared resources of package {}", packageId);
		}
	}
}
