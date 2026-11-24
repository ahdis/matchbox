# Extract data from a QuestionnaireResponse

This guide shows how to turn the answers of a [Structured Data Capture (SDC)](https://build.fhir.org/ig/HL7/sdc/)
questionnaire into FHIR resources with a map
([StructureMap-based extraction](https://build.fhir.org/ig/HL7/sdc/extraction.html#map-extract)), and how to assemble
modular questionnaires. It assumes that you can write a map in the FHIR mapping language, see
[Transform resources with the FHIR mapping language](transform-with-fml.md). More examples of requests are in
[`fml.http`](https://github.com/ahdis/matchbox/blob/main/matchbox-server/fml.http) (sections _SDC Extraction_).

## Link the questionnaire to a map

The `Questionnaire` names the map that extracts its answers in the SDC extension `targetStructureMap`:

```json
{
  "resourceType": "Questionnaire",
  "id": "qrpatientsex",
  "extension": [
    {
      "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-targetStructureMap",
      "valueCanonical": "http://ahdis.ch/matchbox/fml/qr2patgender"
    }
  ],
  "url": "http://ahdis.ch/matchbox/fml/qrpatientsex",
  "version": "0.1.0",
  "name": "QuestionnairePatientSexExtractDemo",
  "status": "active",
  "subjectType": ["Patient"],
  "item": [
    {
      "linkId": "patient.sex",
      "text": "Sex",
      "type": "string"
    }
  ]
}
```

The map has the `QuestionnaireResponse` as its source and the extracted resource as its target: a single resource,
or a `Bundle` (e.g. of type `transaction`) when several resources are extracted. This map writes the answer to
`patient.sex` into `Patient.gender`:

```
map "http://ahdis.ch/matchbox/fml/qr2patgender" = "qr2patgender"

uses "http://hl7.org/fhir/StructureDefinition/QuestionnaireResponse" alias QuestionnaireResponse as source
uses "http://hl7.org/fhir/StructureDefinition/Patient" alias Patient as target

group QuestionnaireResponse(source src : QuestionnaireResponse, target tgt : Patient) {
  src.item as item -> tgt as patient then item(item, patient);
}

group item(source src, target tgt: Patient) {
  src.item as item where linkId.value in ('patient.sex') -> tgt.gender = (item.answer.valueString);
}
```

## Extract with stored resources

When the questionnaire and the map are on the server, the client only sends the `QuestionnaireResponse`.

1. Put the questionnaire and the map on the server:
    - in production, add both to an implementation guide and [install it](load-implementation-guides.md);
    - while you develop them, start the server in the
      [development mode](../explanation/validation-engines.md#only-one-engine) and create them through the API:

    ```bash
    curl -X POST http://localhost:8080/matchboxv3/fhir/StructureMap \
      -H "Content-Type: text/fhir-mapping" \
      --data-binary @qr2patgender.map
    curl -X PUT http://localhost:8080/matchboxv3/fhir/Questionnaire/qrpatientsex \
      -H "Content-Type: application/fhir+json" \
      --data-binary @questionnaire.json
    ```

2. Send the response to `QuestionnaireResponse/$extract`. Its `questionnaire` element gives the canonical URL of the
   questionnaire:

    ```bash
    curl -X POST http://localhost:8080/matchboxv3/fhir/QuestionnaireResponse/\$extract \
      -H "Content-Type: application/fhir+json" \
      -H "Accept: application/fhir+json" \
      --data-binary @qr.json
    ```

    with `qr.json`:

    ```json
    {
      "resourceType": "QuestionnaireResponse",
      "questionnaire": "http://ahdis.ch/matchbox/fml/qrpatientsex",
      "status": "completed",
      "item": [
        {
          "linkId": "patient",
          "item": [
            {
              "linkId": "patient.sex",
              "answer": [{ "valueString": "female" }]
            }
          ]
        }
      ]
    }
    ```

The response is the extracted resource itself (here a `Patient` with `"gender": "female"`), not wrapped in a
`Parameters`.

## Extract with all resources in the request

A client that doesn't store its questionnaires and maps on the server (e.g. a form filler that loads them from
elsewhere) sends them with the response, in a `Parameters`. This works in all modes; the resources are used for this
request only.

| Parameter                | Content                                                                                         |
|--------------------------|-------------------------------------------------------------------------------------------------|
| `questionnaire-response` | the `QuestionnaireResponse` (required)                                                          |
| `questionnaire`          | the `Questionnaire`; its `url` must be the `questionnaire` of the response (Matchbox extension) |
| `structure-map`          | the map, as a `StructureMap` resource or as a string in FML (Matchbox extension)                |

```json
{
  "resourceType": "Parameters",
  "parameter": [
    {
      "name": "questionnaire-response",
      "resource": { "resourceType": "QuestionnaireResponse", "questionnaire": "http://ahdis.ch/matchbox/fml/qrpatientsex", "…": "…" }
    },
    {
      "name": "questionnaire",
      "resource": { "resourceType": "Questionnaire", "url": "http://ahdis.ch/matchbox/fml/qrpatientsex", "…": "…" }
    },
    {
      "name": "structure-map",
      "valueString": "map \"http://ahdis.ch/matchbox/fml/qr2patgender\" = \"qr2patgender\"\nuses …"
    }
  ]
}
```

Leave out `questionnaire` or `structure-map` when it is on the server. When you send the `questionnaire`, send the
`structure-map` too: the map can then only be taken from the server in the development mode.

The request and the response can be in another FHIR version than the server, with the `fhirVersion` parameter of the
content type, e.g. `Content-Type: application/fhir+json;fhirVersion=5.0` and
`Accept: application/fhir+json;fhirVersion=5.0`.

## Troubleshoot the extraction

The errors are returned as an `OperationOutcome` with HTTP 422:

| Message                                                                                                 | Cause                                                                                                                             |
|---------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| _No questionnaire canonical URL given._                                                                 | The response has no `questionnaire` element.                                                                                      |
| _Questionnaire canonical URL in QuestionnaireResponse does not match questionnaire given in parameters_ | The `questionnaire` of the response isn't exactly the `url` of the questionnaire, e.g. because it has a version (`url\|version`). |
| _Could not fetch questionnaire with canonical URL …_                                                    | The questionnaire isn't on the server: install it, or send it in the request.                                                     |
| _No sdc-questionnaire-targetStructureMap extension found in resource_                                   | The questionnaire doesn't name a map.                                                                                             |

To find errors in the map itself, run it with [`$transform`](transform-with-fml.md#transform-a-resource) on the same
response, with `debug=true`. Validate the extracted resources before you use them: an extraction from an invalid
response can succeed but produce wrong resources.

## Assemble a modular questionnaire

A [modular questionnaire](https://build.fhir.org/ig/HL7/sdc/modular.html) contains `display` items with the SDC
extension `subQuestionnaire`, which are replaced by the items of another questionnaire. `Questionnaire/$assemble`
returns the assembled questionnaire. The support is **preliminary**.

The sub-questionnaires must be on the server (installed in an implementation guide, or created through the API in the
development mode). Send the modular questionnaire in a `Parameters`:

```json
{
  "resourceType": "Parameters",
  "parameter": [
    {
      "name": "questionnaire",
      "resource": {
        "resourceType": "Questionnaire",
        "url": "http://example.org/Questionnaire/root",
        "version": "1.0.0",
        "status": "active",
        "item": [
          {
            "linkId": "name",
            "type": "display",
            "text": "Name",
            "extension": [
              {
                "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-subQuestionnaire",
                "valueCanonical": "http://example.org/Questionnaire/name"
              }
            ]
          }
        ]
      }
    }
  ]
}
```

```bash
curl -X POST http://localhost:8080/matchboxv3/fhir/Questionnaire/\$assemble \
  -H "Content-Type: application/fhir+json" \
  -H "Accept: application/fhir+json" \
  --data-binary @assemble.json
```

The response is the assembled `Questionnaire`:

- each item with a `subQuestionnaire` extension is replaced by the items of the sub-questionnaire, recursively. The
  version in the canonical of the sub-questionnaire is ignored. An item whose sub-questionnaire isn't found stays as it
  is;
- an `assembledFrom` extension is added for each sub-questionnaire (`url|version`);
- the version is `<version>-assembled`;
- the `assemble-expectation` extension is adjusted (`assemble-root` is removed, `assemble-…` becomes `independent-…`);
- a `linkIdPrefix` set with the `variable` extension (a constant FHIRPath expression, e.g. `'name.'`) is prepended to
  the `linkId` of the items of the sub-questionnaires that declare it with the `assembleContext` extension.

Not supported yet: the propagation of contained resources and of the extensions of the sub-questionnaires
(e.g. `launch-context`, `variable`), the `linkIdPrefix` in `enableWhen.question` and in expressions, and the checks for
recursive references and duplicate `linkId`s.

## Related

- [FHIR operations reference](../reference/operations.md#extract)
- [Transform resources with the FHIR mapping language](transform-with-fml.md)
- [Mapping and transformation](../explanation/mapping.md#structured-data-capture-from-answers-to-resources)
