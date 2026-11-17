# matchbox-engine

matchbox-engine is the Java library for validation and transformation. See
[Use matchbox-engine in a Java application](../how-to/use-matchbox-engine.md) for task-oriented instructions; the
complete API is in the [Javadoc](https://ahdis.github.io/matchbox/apidocs/).

## Maven coordinates

```xml
<dependency>
    <groupId>health.matchbox</groupId>
    <artifactId>matchbox-engine</artifactId>
    <version>4.1.21</version>
</dependency>
```

| Property     | Value                                                                                   |
|--------------|-----------------------------------------------------------------------------------------|
| Repository   | [Maven Central](https://central.sonatype.com/artifact/health.matchbox/matchbox-engine)  |
| Java version | 17 or newer (matchbox-server requires 21)                                               |
| License      | Apache License 2.0                                                                      |

## Bundled packages {: #bundled-packages}

The following FHIR packages are part of the library and are loaded from the classpath, without network access:

| Package                                          | Loaded by    |
|--------------------------------------------------|--------------|
| `hl7.fhir.r4.core#4.0.1`                         | R4 engines   |
| `hl7.terminology.r4#7.3.0`                       | R4 engines   |
| `hl7.fhir.uv.extensions.r4#5.3.0`                | R4 engines   |
| `hl7.fhir.uv.xver#0.1.0`                         | R4 engines   |
| `hl7.fhir.uv.xver-r5.r4#0.1.0`                   | R4 engines   |
| `hl7.fhir.r4b.core#4.3.0`                        | R4B engines  |
| `hl7.fhir.r5.core#5.0.0`                         | R5 engines   |
| `hl7.terminology.r5#7.3.0`                       | R5 engines   |
| `hl7.fhir.uv.extensions.r5#5.3.0`                | R5 engines   |
| `hl7.cda.uv.core#2.0.1-sd-202510-matchbox-patch` | CDA engines  |

The versions are the ones of the current release; they are defined by the `PACKAGE_*` constants of `MatchboxEngine`.

## `MatchboxEngineBuilder`

`ch.ahdis.matchbox.engine.MatchboxEngine.MatchboxEngineBuilder` creates engines.

| Method                                              | Description                                                                 |
|-----------------------------------------------------|-----------------------------------------------------------------------------|
| `getEngineR4()`                                     | Creates an engine for FHIR R4 with the bundled packages.                    |
| `getEngineR4B()`                                    | Creates an engine for FHIR R4B.                                             |
| `getEngineR5()`                                     | Creates an engine for FHIR R5.                                              |
| `getEngine()`                                       | Creates an empty engine, without any package.                               |
| `setTxServer(String)`                               | Sets the URL of the terminology server; without it, none is used.           |
| `setPackageCacheMode(FilesystemPackageCacheMode)`   | Sets the location of the [package cache](#package-cache) to a predefined one. |
| `setPackageCachePath(String)`                       | Sets the location of the package cache to a directory.                      |

`ch.ahdis.matchbox.engine.CdaMappingEngine.CdaMappingEngineBuilder` extends it for the engines that contain the
[CDA logical model](cda-logical-model.md):

| Method             | Description                                              |
|--------------------|----------------------------------------------------------|
| `getCdaEngineR4()` | Creates a CDA engine based on FHIR R4.                   |
| `getCdaEngineR5()` | Creates a CDA engine based on FHIR R5.                   |

## `MatchboxEngine`

The main methods of `ch.ahdis.matchbox.engine.MatchboxEngine`:

| Method                                                                         | Description                                                                              |
|--------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| `new MatchboxEngine(ValidationEngine other)`                                   | Creates an engine from an existing one.                                                  |
| `validate(InputStream stream, FhirFormat format, String profileUrl)`           | Validates the content against the profile; returns an R4 `OperationOutcome`.             |
| `validate(Resource resource, String profileUrl)`                               | Validates an R4 resource against the profile; returns an R4 `OperationOutcome`.          |
| `validate(FhirFormat format, InputStream stream, String profileUrl)`           | Validates the content against the profile; returns the list of `ValidationMessage`.      |
| `transform(String input, boolean inputJson, String mapUri, boolean outputJson)` | Transforms the input with the StructureMap; returns the result as JSON or XML.           |
| `transformToFhir(String input, boolean inputJson, String mapUri)`              | Transforms the input with the StructureMap; returns an R4 `Resource`.                    |
| `parseMap(String content)`                                                     | Parses a map in the FHIR mapping language; returns an R4 `StructureMap`.                 |
| `parseMapR5(String content)`                                                   | Parses a map in the FHIR mapping language; returns an R5 `StructureMap`.                 |
| `addCanonicalResource(Resource resource)`                                      | Adds a conformance resource (e.g. a `StructureMap`) to the engine.                       |
| `loadPackage(String id, String version)`                                       | Loads a package and its dependencies from the package cache or the FHIR package servers. |
| `loadPackage(InputStream inputStream)`                                         | Loads a package from its content (`package.tgz`); its dependencies aren't fetched.       |
| `createSnapshot(StructureDefinition sd)`                                       | Creates the snapshot of a profile.                                                       |
| `evaluateFhirPath(String input, boolean inputJson, String expression)`         | Evaluates a FHIRPath expression on a resource.                                           |
| `getCanonicalResource(String canonical, String fhirVersion)`                   | Returns a conformance resource of the engine.                                            |
| `addSuppressedWarnInfo(String text)` / `addSuppressedWarnInfoPattern(String pattern)` | Suppresses warning and information messages in the validation results.            |
| `addSuppressedError(String messageId, String regexPath)`                       | Suppresses errors in the validation results.                                             |

An engine contains at most one version of an implementation guide.

## Package cache {: #package-cache}

When used by itself, matchbox-engine stores the downloaded packages in a directory of the filesystem. The predefined
locations of `MatchboxEngineBuilder.setPackageCacheMode(FilesystemPackageCacheMode)` are:

| Value     | Directory on Windows          | Directory on other systems     |
|-----------|-------------------------------|--------------------------------|
| `USER`    | _$user.home_/.fhir/packages   | _$user.home_/.fhir/packages    |
| `SYSTEM`  | ProgramData\\.fhir\\packages  | /var/lib/.fhir/packages        |
| `TESTING` | c:\\temp\\.fhir\\packages     | _$tmp_/.fhir/packages          |

See the class `FilesystemPackageCacheManager` of HAPI for more details about these values. Another directory can be set
with `MatchboxEngineBuilder.setPackageCachePath(String)`.

In matchbox-server, the packages are stored in the database instead, see
[Package management](../explanation/package-management.md).
