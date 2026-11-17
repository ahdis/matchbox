# Transform CDA to FHIR

!!! warning "Skeleton page"
    This page still has to be written. It should be a **how-to guide** for a reader who has CDA documents and wants to
    convert them to FHIR (or back) with Matchbox.

    - **Goal**: set up Matchbox (server or engine) with the CDA logical model and a set of maps, and transform CDA
      documents.
    - **Keywords**: CDA R2, CDA logical model (`hl7.cda.uv.core`), StructureMap, `$transform`, `CdaMappingEngine`,
      [CDA-FHIR-Maps](http://fhir.ch/ig/cda-fhir-maps/index.html), CDA validation, `with-cda` / `with-cda-r5`, R5 model.
    - **Sources**: [`cda.http`](https://github.com/ahdis/matchbox/blob/main/matchbox-server/cda.http), the
      [`with-cda`](https://github.com/ahdis/matchbox/tree/main/matchbox-server/with-cda) configuration,
      `CdaToFhirTransformTests`, the [CDA logical model reference](../reference/cda-logical-model.md).

## Configure Matchbox for CDA

!!! abstract "To write"
    The `with-cda` configuration: which packages to install (the CDA logical model is bundled), the implementation guide
    with the maps (e.g. `ch.fhir.ig.cda-fhir-maps`).

## Transform a CDA document with the server

!!! abstract "To write"
    `POST StructureMap/$transform?source=<map URL>` with `Content-Type: application/xml` (or the CDA content type) and
    the CDA document in the body. Example with `CdaToBundle`. How to get JSON or XML back.

## Transform a CDA document with matchbox-engine

```java
CdaMappingEngine engine = new CdaMappingEngine.CdaMappingEngineBuilder().getCdaEngineR4();
engine.loadPackage(getClass().getResourceAsStream("/cda-fhir-maps.tgz"));
String result = engine.transform(cda, false, "http://fhir.ch/ig/cda-fhir-maps/StructureMap/CdaToBundle", true);
```

!!! abstract "To write"
    Explain the parameters of `transform()` (input, input is JSON, map URL, output as JSON).

## Validate a CDA document

!!! abstract "To write"
    `$validate` with the profile `http://hl7.org/cda/stds/core/StructureDefinition/ClinicalDocument` (or a template
    profile). What is checked and what isn't (schematron isn't).

## Write your own CDA maps

!!! abstract "To write"
    Pointers: how to reference CDA elements in FML (the logical model types), the ahdis extensions of the model (IHE
    PHARM, IHE LAB), testing the maps with the [mapping guide](transform-with-fml.md).
