# Gazelle validation API

matchbox-server implements the Validation Service API of the
[IHE Gazelle Testing Platform](https://www.ihe-europe.net/testing-IHE/gazelle). The API is specified in the
[validation-service-api](https://gitlab.inria.fr/gazelle/library/validation-service-api) project. See
[Integrate with Gazelle](../how-to/integrate-gazelle.md) to configure the Gazelle tools, and the blog post
[How we implemented the Gazelle EVS Client's new validation API in Matchbox](https://www.ahdis.ch/en/blog/2024/matchbox-gazelle-integration)
for the background of the implementation.

## Endpoints

Both versions of the API are served, with `[server]/gazelle` as base URL (`[server]` =
`http(s)://<host>:<port>/matchboxv3`):

|            | v1 (EVS Client)                             | v2 (e.g. Gazelle Maestro)                      |
|------------|---------------------------------------------|------------------------------------------------|
| Metadata   | `GET [server]/gazelle/metadata`             | `GET [server]/gazelle/metadata`                |
| Profiles   | `GET [server]/gazelle/validation/profiles`  | `GET [server]/gazelle/validation/v2/profiles`  |
| Validation | `POST [server]/gazelle/validation/validate` | `POST [server]/gazelle/validation/v2/validate` |

## Metadata

The metadata describes the validation service: its name (`Matchbox`), its version and the interfaces it provides.

## Profiles

The profiles returned are the profiles of the installed implementation guides.

In v2, every profile declares a single required input with the id `contentToValidate`: the FHIR resource (JSON or XML)
to validate against the profile.

## Validation

### Request

The request is a JSON object (`Content-Type: application/json`). Both versions have the same model, with one
renamed field:

| Field                 | v1                | v2       | Description                                                                                       |
|-----------------------|-------------------|----------|---------------------------------------------------------------------------------------------------|
| `validationProfileId` | ✓                | ✓       | The profile: a `profileID` of the profile list, i.e. a canonical URL with or without `\|version`. |
| inputs                | `validationItems` | `inputs` | The items to validate, each validated against the profile.                                        |
| `content`             | ✓                | ✓       | In each item: the FHIR resource (JSON or XML), encoded in base64.                                 |
| `id`                  | `role`            | `id`     | In each item: the id of the input (`contentToValidate` in v2).                                    |
| `itemId`              | ✓                | ✓       | In each item: an optional identifier, used in the name of the sub-report.                         |

Other fields (e.g. `apiVersion`, `validationServiceName` of v1, `location` and `mimeType`) are ignored. The format of
the content is detected from the content itself.

```http
POST [server]/gazelle/validation/v2/validate HTTP/1.1
Content-Type: application/json

{
  "validationProfileId": "http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient|5.0.0",
  "inputs": [
    {
      "id": "contentToValidate",
      "itemId": "patient-1",
      "content": "eyJyZXNvdXJjZVR5cGUiOiJQYXRpZW50In0="
    }
  ]
}
```

In v1, the same request is posted to `[server]/gazelle/validation/validate`, with `validationItems` instead of
`inputs` and `role` instead of `id`.

### Response

The response is a validation report:

| Field                | Description                                                                                                                                                                                                                 |
|----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `modelVersion`       | `2.0` in v2, `0.1` in v1.                                                                                                                                                                                                   |
| `uuid`, `dateTime`   | The id and date of the report.                                                                                                                                                                                              |
| `validationMethod`   | The validation service (`Matchbox` and its version) and the profile (id, version, and in v2 its name).                                                                                                                      |
| `overallResult`      | `PASSED`, `FAILED` or `UNDEFINED`, see below.                                                                                                                                                                               |
| `additionalMetadata` | Name/value pairs: the engine session, the versions of the validator, the loaded packages, the suppressed warnings and errors, the profile with its version and date, the validation parameters, and the duration (`total`). |
| `reports`            | One sub-report per item (`Validation of item #…`), with its `subReportResult`, `subCounters` and `assertionReports`, and the `unexpectedErrors`.                                                                            |
| inputs               | The items of the request (`validationItems` in v1, `inputs` in v2).                                                                                                                                                         |
| `counters`           | The number of assertions, failed assertions per severity and unexpected errors (and of undefined results in v2).                                                                                                            |

Each issue of the validation becomes an assertion report:

| Issue severity      | `severity` | `result` | `priority`    |
|---------------------|------------|----------|---------------|
| `fatal`, `error`    | `ERROR`    | `FAILED` | `MANDATORY`   |
| `warning`           | `WARNING`  | `FAILED` | `RECOMMENDED` |
| `information`       | `INFO`     | `PASSED` | `MANDATORY`   |

The assertion report also has:

- `assertionID`: the id of the invariant, otherwise the message id of the validator, otherwise the issue type;
- `assertionType`: the source of the issue in the validator (e.g. `InstanceValidator`);
- `description`: the message, followed by the slicing information if any;
- `subjectLocation`: `line L, column C, FHIRPath: path`; in v2 also `subjectLocations`, with one location of type
  `line-column` and one of type `FHIRPath`.

If an item has no issue at all, an assertion `PASSED` with the description _No fatal or error issues detected, the
validation has passed_ is added.

The result of a sub-report is `FAILED` if one of its assertions has the severity `ERROR`, `PASSED` otherwise:
warnings don't fail the validation. It is `UNDEFINED` if the item couldn't be validated (an unexpected error). The
`overallResult` is the worst result of the sub-reports, `UNDEFINED` being the worst.

```json
{
  "modelVersion": "2.0",
  "uuid": "…",
  "dateTime": "…",
  "disclaimer": "Matchbox disclaims",
  "validationMethod": {
    "validationServiceName": "Matchbox",
    "validationServiceVersion": "5.0.0",
    "validationProfileID": "http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient|5.0.0",
    "validationProfileVersion": "5.0.0",
    "validationProfileName": "FHIR http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient|5.0.0"
  },
  "overallResult": "FAILED",
  "additionalMetadata": [
    { "name": "package", "value": "ch.fhir.ig.ch-core#5.0.0" },
    { "name": "profileVersion", "value": "5.0.0" },
    { "name": "total", "value": "312ms" }
  ],
  "reports": [
    {
      "name": "Validation of item #patient-1",
      "subReportResult": "FAILED",
      "assertionReports": [
        {
          "assertionID": "Validation_VAL_Profile_Minimum",
          "assertionType": "InstanceValidator",
          "description": "Patient.identifier: minimum required = 1, but only found 0 (from http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient|5.0.0)",
          "subjectLocation": "line 1, column 2, FHIRPath: Patient",
          "subjectLocations": [
            { "type": "line-column", "value": "line 1, column 2", "inputId": "contentToValidate" },
            { "type": "FHIRPath", "value": "Patient", "inputId": "contentToValidate" }
          ],
          "severity": "ERROR",
          "priority": "MANDATORY",
          "result": "FAILED"
        }
      ],
      "subCounters": { "numberOfAssertions": 1, "numberOfFailedWithErrors": 1, "…": "…" }
    }
  ],
  "inputs": [ { "id": "contentToValidate", "itemId": "patient-1", "content": "…" } ],
  "counters": { "numberOfAssertions": 1, "numberOfFailedWithErrors": 1, "…": "…" }
}
```

The example is shortened: the identifiers, metadata and messages of a real report differ.

### Errors

| Status | Cause                                                                                                                                                                                   |
|--------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 200    | The validation ran, or the profile isn't supported or the engine couldn't be created: the report then has a sub-report _Unexpected error_ with the message, and the result `UNDEFINED`. |
| 400    | The request can't be read, or `validationProfileId` or the inputs are missing: `{"error": "…"}`.                                                                                        |
| 503    | The validation engine isn't initialized yet (startup, or reload of an implementation guide): a report with the result `UNDEFINED` and the header `Retry-After: 5`.                      |

The profile lists (`/validation/profiles` and `/validation/v2/profiles`) are sent with an `ETag` and
`Cache-Control: no-cache`: a client that sends `If-None-Match` gets `304 Not Modified` if the list hasn't changed.
