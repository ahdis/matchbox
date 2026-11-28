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

A package declares its dependencies in `package.json` (`"dependencies": {"hl7.fhir.r4.core": "4.0.1", …}`), and an
implementation guide can declare more in its `ImplementationGuide` resource (extension `ig-internal-dependency`).
Matchbox follows both, twice: when a package is installed, and when an engine loads it.

### When a package is installed

The dependencies are installed recursively, before the package that needs them is used. A dependency that is already
in the database is taken from there; otherwise it is downloaded from the FHIR package servers and installed too. A
dependency that can't be found makes the installation fail (except an internal dependency, which is only logged).

Some dependencies aren't installed, because Matchbox brings its own version or doesn't need them:

- `hl7.fhir.r4.core`, which is bundled with Matchbox;
- the packages in exactly the versions that are bundled with Matchbox: `hl7.terminology.r4#7.5.0`,
  `hl7.fhir.uv.extensions.r4#5.4.1` (and their R5 variants), the cross-version packages `hl7.fhir.uv.xver#0.1.0` and
  `hl7.fhir.uv.xver-r5.r4#0.1.0`, and the CDA logical model. Other versions of these packages are installed;
- `hl7.fhir.cda`, the development version of the CDA logical model.

A package that is installed again with a URL on `localhost` or `build.fhir.org` (a development or CI build) replaces
the installed one, even with the same name and version.

### When an engine is created

An engine starts with the packages of its FHIR version from the classpath: the core package, the terminology and
extensions packages, and the cross-version packages. Then it loads the implementation guide from the database, each
dependency before the package that needs it:

- the FHIR core packages and the core examples packages (e.g. `hl7.fhir.r4.examples`) in the dependencies are
  skipped, because the engine has already loaded the core package of its FHIR version. Loading a second core package
  would make the types ambiguous. An R5 engine also skips `hl7.fhir.uv.extensions.r4`.
- a dependency on `hl7.terminology#7.5.0` or `hl7.fhir.uv.extensions#5.4.1` is replaced with the bundled package for
  the FHIR version of the engine (e.g. `hl7.terminology.r4#7.5.0`), and `hl7.fhir.cda#dev` with the bundled CDA
  logical model. Other versions of these packages are loaded as declared, from the database.
- a version range such as `3.3.x` is resolved to the newest installed version that matches (e.g. `3.3.2`);
- a package is loaded once per engine: a second dependency on the same package and version (or on a range that
  resolves to a loaded version) is skipped.

The dependencies of the dependencies are loaded in the same way, so an engine contains the whole tree of packages of
its implementation guide. Different packages of the tree may require different versions of the same package (e.g.
`hl7.terminology` 5.5.0 and 6.2.0): both versions are loaded. When a canonical URL is then referenced without a
version, the resource of the newest version is used. To control which version is used, reference the canonical with
its version (`url|version`) or make your implementation guide depend directly on the version you want.

### Cross-version extensions

Profiles can use the extensions of another FHIR version, e.g. an R5 element in an R4 resource
(`http://hl7.org/fhir/5.0/StructureDefinition/extension-…`). These extensions are defined in the cross-version
packages, which are bundled with Matchbox: `hl7.fhir.uv.xver` is loaded in every engine, and `hl7.fhir.uv.xver-r5.r4`
(the R5 extensions for R4) in the R4 engines. The implementation guides don't need to depend on them, and the
dependencies on these versions aren't installed. If an implementation guide depends on another version of a
cross-version package, that version is installed and loaded like any other package.
