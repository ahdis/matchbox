# Matchbox Engine

Matchbox engine has been created to allow a standalone FHIR validation and transformation without the need for a connection to the internet:

- java library
- supports CDA to FHIR mapping
- FHIR validation with no external terminology server
- local configuration (no internet access necessary, ig packages can be loaded directly from classpath)
- [javadoc](../apidocs/)

default fhir package configuration of the R4 engine:

- hl7.fhir.r4.core#4.0.1
- hl7.terminology.r4#7.3.0
- hl7.fhir.uv.extensions.r4#5.3.0
- hl7.fhir.uv.xver#0.1.0
- hl7.fhir.uv.xver-r5.r4#0.1.0
- hl7.cda.uv.core#2.0.1-sd-202510-matchbox-patch (CDA engine only)

## Library based on Matchbox and hapi-fhir / org.hl7.fhir.core

The transformation and validation functionality has been extracted out of Matchbox ([https://github.com/ahdis/matchbox](https://github.com/ahdis/matchbox)) into the matchbox-engine ([https://github.com/ahdis/matchbox/tree/main/matchbox-engine](https://github.com/ahdis/matchbox/tree/main/matchbox-engine)) library. The Matchbox project has two modules: matchbox-engine creates the java library, and matchbox-server provides the FHIR API as a microservice. matchbox-server uses matchbox-engine, so if Matchbox is used during developing and testing the mapping, matchbox-engine will deliver the same result.

matchbox-engine is only based on org.hl7.fhir.core libraries (HAPI FHIR - HL7 FHIR Core Artifacts), the dependency to hapi-fhir is not necessary for the library. The library is derived from the [HL7 Java FHIR Validator](https://confluence.hl7.org/display/FHIR/Using+the+FHIR+Validator) and [FHIR Mapping Language](https://www.hl7.org/fhir/mapping-language.html) implementation. During the development of the mapping a few missing functionalities in the Mapping Language have been discovered and have been also contributed back to the org.hl7.fhir.core project ([Pull requests](https://github.com/hapifhir/org.hl7.fhir.core/pulls?q=is%3Apr+is%3Aclosed+author%3Aoliveregger+)). Note however that there are some classes patched in matchbox-engine because of peculiarities in the parsing/mapping of CDA and package handling ([patched files](https://github.com/ahdis/matchbox/tree/main/matchbox-engine/src/main/java/org/hl7/fhir)). These patched files are [updated](https://github.com/ahdis/matchbox/blob/main/updatehapi.sh) and changes applied during each new release of org.hl7.fhir.core and tests are run to verify the correctness of the defined mappings. Since org.hl7.fhir.core 7, the validator, the FHIR Mapping Language and the worker context are based on the versionless R6 modules (`org.hl7.fhir.model`, `org.hl7.fhir.services`, `org.hl7.fhir.standalone`) instead of `org.hl7.fhir.r5`, and the patches are applied to the R6 classes. The library requires Java 17, as org.hl7.fhir.core 7 does.

### Integrate matchbox-engine with Maven

You can add the matchbox-engine dependency in your `pom.xml`, with the latest version from
[Maven Central](https://central.sonatype.com/artifact/health.matchbox/matchbox-engine):

```xml
<dependency>
    <groupId>health.matchbox</groupId>
    <artifactId>matchbox-engine</artifactId>
    <version>4.1.21</version>
</dependency>
```

Matchbox 5 is built with a build of HAPI FHIR that isn't released yet (Spring Boot 4 support), so matchbox-engine 5 isn't
published to Maven Central until HAPI FHIR releases it.

## API and Javadoc

The source code is documented with [Javadoc](https://ahdis.github.io/matchbox/apidocs/). Test cases illustrate the main functionality for transformation with the [FHIR Mapping Language](https://github.com/ahdis/matchbox/blob/main/matchbox-engine/src/test/java/ch/ahdis/matchbox/engine/tests/FhirMappingLanguageTests.java) and for [CDA to FHIR transformation](https://github.com/ahdis/matchbox/blob/main/matchbox-engine/src/test/java/ch/ahdis/matchbox/engine/tests/CdaToFhirTransformTests.java).

Tests of matchbox-engine and matchbox-server that need an implementation guide use the
[Matchbox test IG](https://github.com/ahdis/matchbox-test-ig) (`matchbox.health.test.ig.r4`), whose package is in the
test resources of both modules.

For validation or transformation, you need to instantiate a matchbox-engine. This matchbox-engine can be configured
with a specific version of an implementation guide and its dependencies. You can instantiate multiple engines with different ig's.

### Validation

Validation is supported for FHIR R4, R4B and R5. You can get an instance of matchbox-engine the following way:

```java
engine = new MatchboxEngineBuilder().getEngineR4(); // or getEngineR4B(), getEngineR5()
```

The R4 engine is configured with the core packages for FHIR Release 4 listed above. You can then invoke the validation with

```java
import org.hl7.fhir.model.utilities.formats.FhirFormat; // org.hl7.fhir.r5.elementmodel.Manager.FhirFormat before matchbox 5

InputStream in = CdaToFhirTransformTests.class.getResourceAsStream("/pat.json");
org.hl7.fhir.r4.model.OperationOutcome outcome = engine.validate(in, FhirFormat.JSON, "http://hl7.org/fhir/StructureDefinition/Patient");
```

You have the ability to add additional implementation guides to the engine (e.g. if you wan’t to support validation for your implementation guide).
The engine will look for it in the local disk cache or in the remote FHIR package repository.

```java
engine.loadPackage("ihe.formatcode.fhir", "1.0.0");
```

It is possible to load an IG from the content of its NPM package, but its dependencies won't be automatically fetched.

```java
engine.loadPackage(getClass().getResourceAsStream("/myig.tgz"));
```

You can create also a new instance based on an existing engine. This might be needed if you want to update conformance resources in an instance or if you want to have support for validation with different versions of ig’s (an engine can only be configured with one ig version, there is no support that an engine has two versions of the same ig).

### Model classes

The engine API accepts and returns resources of the HAPI FHIR R4 model (`org.hl7.fhir.r4.model`), e.g. the
`OperationOutcome` of `validate`, the `StructureMap` of `parseMap` and the result of `transformToFhir`. Where the API
uses the model of the validator, it is the versionless R6 model of org.hl7.fhir.core 7 (`org.hl7.fhir.model.core`,
`org.hl7.fhir.model.fml` for `StructureMap`) since matchbox 5, instead of `org.hl7.fhir.r5.model`:

- `getStructureDefinitionR6()` returns a `org.hl7.fhir.model.core.StructureDefinition` and `parseMapR6()` a
  `org.hl7.fhir.model.fml.StructureMap`; `getStructureDefinitionR5()` and `parseMapR5()` still return the HAPI R5
  model (`org.hl7.fhir.r5.model`)
- `createSnapshot` and `addCanonicalResource` have overloads for the R6 model, the HAPI R4 model and the HAPI R5 model;
  `createSnapshot` adds the snapshot to the StructureDefinition it is given, in all three models
- `useTxCacheFolder(folder)` stores the terminology cache in a folder, `disableTxCache()` disables it (instead of
  `initTxCache(folder)` and `initTxCache(null)`, which are deprecated)
- `transform(...)` with the element model works with `org.hl7.fhir.services.elementmodel.Element`
- `validate(FhirFormat, InputStream, String)` returns the `ValidationMessage`s of the validator

`R6Model` converts between the HAPI R5 model and the R6 model (`R6Model.toR5`, `R6Model.fromR5`).

### Using the FHIR Mapping Language for transformation

If the engine has been configured already with StructureMap resources (e.g. provided within an Implementation Guide) the transformation can be directly called:

```java
Resource res = engine.transformToFhir(getFileAsStringFromResources("/qr.json"), true, "http://ahdis.ch/matchbox/fml/qr2patgender");
```

If you wan’t to provide the StructureMap yourself you can parse a FHIR Mapping Language map file and add the StructureMap resource then to the engine.

```java
StructureMap sm = engine.parseMap(getFileAsStringFromResources("/qr2patgender.map"));
engine.addCanonicalResource(sm);
```

For the transformation the canonical Url of the StructureMaps has to be used.

### CDA to FHIR mapping

To support CDA to FHIR mapping (or vice versa) a specific CDAEngine is available, which is configured with [CDA Logical Model](https://ahdis.github.io/matchbox/cda-logical-model/index.html). To do CDA to FHIR transformations you need to write the mapping logic and provide them as StructureMap resources in an Implementation Guide. 

You can then load the engine with those maps:

```java
engine = new CdaMappingEngine.CdaMappingEngineBuilder().getEngine();
engine.loadPackage(getClass().getResourceAsStream("/cda-fhir-maps.tgz"));
```

And then do the transformation with

```java
String result = engine.transform(cda, false, "http://fhir.ch/ig/cda-fhir-maps/StructureMap/CdaToBundle", true);
```
