# Package management

FHIR implementation guides are distributed as NPM packages (`package.tgz`). This page explains where Matchbox keeps
the packages and how they get into an engine, for the library and for the server, because the two work differently.

## In matchbox-engine: a package cache on the filesystem

When matchbox-engine is used by itself, it behaves like the HL7 validator: packages that are requested by name and
version are looked up in a package cache directory on the filesystem, and downloaded from the FHIR package servers
into that directory if they are missing. The location of the directory is configurable (see the
[reference](../reference/matchbox-engine-api.md#package-cache)).

The packages that every engine needs (the FHIR core package, the terminology and the extensions packages) are bundled
with the library and are loaded from the classpath, so that an engine can be created without any network access. A
package can also be loaded directly from its content, which bypasses the cache; its dependencies must then be loaded
explicitly.

## In matchbox-server: packages in the database

A server can't rely on a directory of the machine it happens to run on: containers are replaced, and several instances
may share one database. When an engine is created by matchbox-server, it is therefore configured with another package
loader (`IgLoaderFromJpaPackageCache`): the packages are stored in the database, including all their resources and the
content of the NPM file itself.

This has several consequences:

- An implementation guide must be **installed** in the database before it can be used: at startup from the
  configuration, or at runtime through the API (see
  [Load implementation guides](../how-to/load-implementation-guides.md)).
- The conformance resources of the installed packages can be read and searched through the FHIR API.
- The original NPM file can be served again by the server (`ImplementationGuidePackageInterceptor`).
- A package is identified by its name and version: once it is installed, it isn't read again from its source, even if
  the source has changed.

## From the database to an engine

Installing a package doesn't load it into an engine. The loading of the packages into the engines is controlled by the
server:

1. When an engine is requested with `MatchboxEngineSupport.getMatchboxEngine()`, the implementation guide to load is
   determined by the `canonical` parameter (the profile or map that is requested) and the `ig` property of the
   `CliContext`.
2. Otherwise, specific calls to `MatchboxEngine.loadPackage()` or `MatchboxEngine.getIgLoader().loadIg()` load a given
   implementation guide.

In all cases, the packages are loaded from the database. Which engines exist and how long they are kept is explained in
[Validation engines](validation-engines.md).

## Dependencies

!!! abstract "To write"
    How the dependencies of a package are resolved when it is installed and when an engine is created: version ranges
    and `current` versions, the packages bundled with Matchbox that replace the declared core/terminology/extensions
    dependencies, what happens with conflicting versions of a transitive dependency, and the cross-version packages
    (`hl7.fhir.uv.xver-*`).
