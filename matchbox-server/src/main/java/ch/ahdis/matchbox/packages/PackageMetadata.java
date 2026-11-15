package ch.ahdis.matchbox.packages;

import java.util.List;

/**
 * What {@link IgLoaderFromJpaPackageCache} needs to know about a package before it registers its resources: the name
 * and version from its package.json, its dependencies and the internal dependencies declared in its
 * ImplementationGuide resource (#481). Kept in {@link SharedPackageResourcesCache}, so that an engine doesn't need to
 * read and unpack the package archive for a package that another engine has loaded (#609).
 */
public record PackageMetadata(String name, String version, List<String> dependencies,
										List<String> internalDependencies) {

	public PackageMetadata {
		dependencies = List.copyOf(dependencies);
		internalDependencies = List.copyOf(internalDependencies);
	}

	/**
	 * The package id and version as id#version.
	 */
	public String packageId() {
		return this.name + "#" + this.version;
	}
}
