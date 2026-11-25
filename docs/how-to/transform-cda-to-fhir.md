# Transform CDA to FHIR

This guide shows how to set up Matchbox (the server or matchbox-engine) to transform CDA R2 documents to FHIR and back,
and to validate CDA documents. The transformations are maps in the [FHIR mapping language](transform-with-fml.md) that
read or write the [CDA logical model](../reference/cda-logical-model.md). More examples of requests are in
[`cda.http`](https://github.com/ahdis/matchbox/blob/main/matchbox-server/cda.http).

## Configure Matchbox for CDA

The CDA logical model (`hl7.cda.uv.core#2.0.1-sd-202510-matchbox-patch`) is bundled with Matchbox: install it from the
classpath, together with the FHIR core package. Then add the implementation guide that contains your maps, e.g.
[CDA-FHIR-Maps](http://fhir.ch/ig/cda-fhir-maps/index.html) (`ch.fhir.ig.cda-fhir-maps`) for the Swiss medication
documents:

```yaml
hapi:
  fhir:
    implementationguides:
      fhir_r4_core:
        name: hl7.fhir.r4.core
        version: 4.0.1
        url: classpath:/hl7.fhir.r4.core.tgz
      cda:
        name: hl7.cda.uv.core
        version: 2.0.1-sd-202510-matchbox-patch
        url: classpath:/hl7.cda.uv.core#2.0.1-sd-202510-matchbox-patch.tgz
      cda-fhir-maps:
        name: ch.fhir.ig.cda-fhir-maps
        version: 0.3.0
```

The repository contains two sample configurations, both in the
[development mode](../explanation/validation-engines.md#only-one-engine) to develop maps:

- [`with-cda`](https://github.com/ahdis/matchbox/tree/main/matchbox-server/with-cda): FHIR R4 and the CDA logical model;
- [`with-cda-r5`](https://github.com/ahdis/matchbox/tree/main/matchbox-server/with-cda-r5): the same with FHIR R5
  (`hapi.fhir.fhir_version: R5` and `hl7.fhir.r5.core`).

The maps have to be written for the version of the CDA logical model that is installed: a new version of the model
(see the [changelog](../changelog.md)) can require changes to the maps.

## Transform a CDA document with the server

Call `StructureMap/$transform` with the canonical URL of the map and the CDA document as the body:

```bash
curl -X POST "http://localhost:8080/matchboxv3/fhir/StructureMap/\$transform?source=http://fhir.ch/ig/cda-fhir-maps/StructureMap/CdaToBundle" \
  -H "Content-Type: application/xml" \
  -H "Accept: application/fhir+json" \
  --data-binary @cda.xml
```

- The document is parsed with the logical model that the map declares as its first source (`uses
  "http://hl7.org/cda/stds/core/StructureDefinition/ClinicalDocument" … as source`): the body doesn't need to say that
  it is CDA, only that it is XML.
- The response is the FHIR resource created by the map (here a `Bundle`), in JSON with
  `Accept: application/fhir+json`, in XML otherwise.

The other direction works the same way: send the FHIR resource and use a map whose target is CDA (e.g.
`http://fhir.ch/ig/cda-fhir-maps/StructureMap/BundleToCda`), with `Accept: application/fhir+xml` to get the CDA
document in XML.

## Transform a CDA document with matchbox-engine

`CdaMappingEngine` is a `MatchboxEngine` that has the CDA logical model already loaded. Load the package with your
maps, or add maps in FML one by one:

```java
CdaMappingEngine engine = new CdaMappingEngine.CdaMappingEngineBuilder().getCdaEngineR4();

// the maps from an implementation guide (its dependencies must be loaded first)
engine.loadPackage(getClass().getResourceAsStream("/cda-fhir-maps.tgz"));
// or a single map in FML
engine.addCanonicalResource(engine.parseMap(fml));

String result = engine.transform(cda, false, "http://fhir.ch/ig/cda-fhir-maps/StructureMap/CdaToBundle", true);
```

The parameters of `transform(input, inputJson, mapUri, outputJson)` are:

| Parameter    | Description                                                                 |
|--------------|-----------------------------------------------------------------------------|
| `input`      | the document or resource to transform, as a string                          |
| `inputJson`  | `true` if the input is in JSON, `false` if it is in XML (CDA is always XML) |
| `mapUri`     | the canonical URL of the map                                                |
| `outputJson` | `true` to get the result in JSON, `false` to get it in XML                  |

To get a parsed FHIR R4 resource instead of a string, use `transformToFhir(input, inputJson, mapUri)`, or
`transformCdaToFhir(cda, mapUri)`, which returns a `Bundle`. Use `getCdaEngineR5()` for maps that produce FHIR R5.

## Validate a CDA document

CDA documents are validated like FHIR resources, against the `ClinicalDocument` of the logical model or a profile of
it (e.g. a CDA template defined as a profile in an implementation guide):

```bash
curl -X POST "http://localhost:8080/matchboxv3/fhir/\$validate?profile=http://hl7.org/cda/stds/core/StructureDefinition/ClinicalDocument" \
  -H "Content-Type: application/fhir+xml" \
  -H "Accept: application/fhir+json" \
  --data-binary @cda.xml
```

With matchbox-engine:

```java
OperationOutcome outcome = engine.validate(new FileInputStream("cda.xml"), FhirFormat.XML,
    "http://hl7.org/cda/stds/core/StructureDefinition/ClinicalDocument");
```

The validator checks the structure of the document (elements, attributes, cardinalities, data types), the bindings and
the invariants of the logical model and of the profile. It doesn't run the schematron rules of CDA specifications,
and it doesn't validate against the CDA XML schema: use the schematrons in addition if your specification has some.

## Write your own CDA maps

A map that reads CDA declares the CDA classes and data types it uses with their canonical URLs, which start with
`http://hl7.org/cda/stds/core/StructureDefinition/`:

```
map "http://example.org/StructureMap/CdaObservationToFhir" = "CdaObservationToFhir"

uses "http://hl7.org/cda/stds/core/StructureDefinition/Observation" alias CdaObservation as source
uses "http://hl7.org/cda/stds/core/StructureDefinition/IVL-TS" alias IVL_TS as source
uses "http://hl7.org/fhir/StructureDefinition/Observation" alias FhirObservation as target

group CdaToFhir(source cda : CdaObservation, target obs : FhirObservation) {
  cda -> obs.status = 'final' "status";
  cda.code as code -> obs.code as cc, cc.coding as coding then {
    code.code as c -> coding.code = cast(c, 'string') "code";
    code.codeSystem as s -> coding.system = translate(s, 'http://hl7.org/fhir/ConceptMap/special-oid2uri', 'uri') "system";
  } "code";
}
```

- The names of the elements are the ones of the CDA XML: elements and attributes alike (`code.code`,
  `code.codeSystem`, `recordTarget.patientRole.patient.birthTime.value`). The text content of an element is
  `xmlText` (e.g. `title.xmlText`), and the `sdtc:` extensions have the prefix `sdtc` (e.g. `sdtcStatusCode`).
- The attributes have CDA data types: convert them to FHIR types with `cast(…, 'string')`. Code systems are OIDs in
  CDA: `translate()` with the ConceptMap `http://hl7.org/fhir/ConceptMap/special-oid2uri` turns them into the URIs
  that FHIR uses (e.g. `2.16.840.1.113883.6.1` into `http://loinc.org`).
- Choose a data type with a typed source, e.g. `cda.value : CD as value`.
- Dates of CDA (`20220330112426+0100`) are converted to FHIR dates when they are read: `effectiveTime.value` is
  `2022-03-30T11:24:26+01:00`.
- The model contains the ahdis extensions for IHE PHARM and IHE LAB, see
  [Differences to the HL7 model](../reference/cda-logical-model.md#differences-to-the-hl7-model).
- Put common rules (data types, header) into separate maps and use them with `imports`, as CDA-FHIR-Maps does with
  `CdaToFhirTypes` and `FhirToCdaTypes`.

Develop the maps against a server in the development mode, as described in
[Transform resources with the FHIR mapping language](transform-with-fml.md#develop-maps-against-a-development-server),
and test them with sample documents. The maps and documents of the matchbox-engine tests
([`src/test/resources/cda`](https://github.com/ahdis/matchbox/tree/main/matchbox-engine/src/test/resources/cda)) are
examples, for CDA to FHIR and FHIR to CDA.

## Related

- [CDA logical model reference](../reference/cda-logical-model.md)
- [Transform resources with the FHIR mapping language](transform-with-fml.md)
- [Mapping and transformation](../explanation/mapping.md)
