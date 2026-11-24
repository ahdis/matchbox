# Use matchbox-engine in a Java application

This guide shows how to use [matchbox-engine](../explanation/architecture.md) as a library: add it to your project,
create engines, load implementation guides, validate and transform. If you have never used it, start with the
[tutorial](../tutorials/embed-matchbox-engine.md). The API is described in the
[matchbox-engine reference](../reference/matchbox-engine-api.md) and the [Javadoc](https://ahdis.github.io/matchbox/apidocs/).

## Add the dependency

```xml
<dependency>
    <groupId>health.matchbox</groupId>
    <artifactId>matchbox-engine</artifactId>
    <version>5.0.0</version>
</dependency>
```

Use the [latest version](https://central.sonatype.com/artifact/health.matchbox/matchbox-engine) from Maven Central.

## Create an engine

```java
MatchboxEngine engine = new MatchboxEngine.MatchboxEngineBuilder().getEngineR4();
```

The engine is configured with FHIR R4 and the terminology and extension packages bundled with matchbox-engine. Use
`getEngineR4B()` or `getEngineR5()` for the other FHIR versions.

An engine can only contain one version of an implementation guide. To validate against several versions, or to change
conformance resources without affecting other users of an engine, create several engines; you can create a new engine
from an existing one with `new MatchboxEngine(existingEngine)`.

## Validate a resource

```java
import org.hl7.fhir.model.utilities.formats.FhirFormat;
import org.hl7.fhir.r4.model.OperationOutcome;

InputStream in = getClass().getResourceAsStream("/pat.json");
OperationOutcome outcome = engine.validate(in, FhirFormat.JSON, "http://hl7.org/fhir/StructureDefinition/Patient");
```

## Load an implementation guide

From the local package cache or the FHIR package servers:

```java
engine.loadPackage("ihe.formatcode.fhir", "1.0.0");
```

From the content of an NPM package (its dependencies aren't fetched automatically: load them first):

```java
engine.loadPackage(getClass().getResourceAsStream("/myig.tgz"));
```

## Choose where the packages are cached

Downloaded packages are stored in a package cache on the filesystem. Choose its location with the builder, before
creating the engine:

```java
MatchboxEngine.MatchboxEngineBuilder builder = new MatchboxEngine.MatchboxEngineBuilder();
builder.setPackageCachePath("/var/cache/fhir-packages");
// or: builder.setPackageCacheMode(FilesystemPackageCacheMode.SYSTEM);
MatchboxEngine engine = builder.getEngineR4();
```

The predefined modes are listed in the [reference](../reference/matchbox-engine-api.md#package-cache).

## Use a terminology server

```java
builder.setTxServer("http://tx.fhir.org");
```

Without a terminology server, codes of code systems that aren't loaded in the engine aren't checked; see
[Terminology in validation](../explanation/terminology.md).

The answers of the terminology server are cached in memory. To keep them between runs, store the cache in a folder;
to always ask the server, disable the cache:

```java
engine.useTxCacheFolder("/var/cache/fhir-tx");
// or: engine.disableTxCache();
```

## Transform with the FHIR mapping language

If the engine already contains the `StructureMap` (e.g. from an implementation guide), call it by its canonical URL:

```java
Resource res = engine.transformToFhir(getFileAsStringFromResources("/qr.json"), true, "http://ahdis.ch/matchbox/fml/qr2patgender");
```

To use a map written in the FHIR mapping language, parse it and add it to the engine first:

```java
StructureMap sm = engine.parseMap(getFileAsStringFromResources("/qr2patgender.map"));
engine.addCanonicalResource(sm);
```

## Transform CDA to FHIR

Use the `CdaMappingEngine`, which is configured with the [CDA logical model](../reference/cda-logical-model.md), and load
the implementation guide that contains your maps:

```java
CdaMappingEngine engine = new CdaMappingEngine.CdaMappingEngineBuilder().getCdaEngineR4();
engine.loadPackage(getClass().getResourceAsStream("/cda-fhir-maps.tgz"));
String result = engine.transform(cda, false, "http://fhir.ch/ig/cda-fhir-maps/StructureMap/CdaToBundle", true);
```

See [Transform CDA to FHIR](transform-cda-to-fhir.md).

## Examples

The tests of matchbox-engine show the main features:

- [FHIR mapping language](https://github.com/ahdis/matchbox/blob/main/matchbox-engine/src/test/java/ch/ahdis/matchbox/engine/tests/FhirMappingLanguageTests.java)
- [CDA to FHIR transformation](https://github.com/ahdis/matchbox/blob/main/matchbox-engine/src/test/java/ch/ahdis/matchbox/engine/tests/CdaToFhirTransformTests.java)
