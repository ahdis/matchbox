# ITB (GITB REST) API

matchbox-server provides test services for the
[Interoperability Test Bed](https://interoperable-europe.ec.europa.eu/collection/itb) (ITB), which calls them over REST
since ITB 1.30.0. The services follow the contract of the ITB services of the HL7 validator
([hapifhir/org.hl7.fhir.core#2615](https://github.com/hapifhir/org.hl7.fhir.core/pull/2615)): the same service names,
input names and reports, so that a test case written for the HL7 validator runs against Matchbox with only the address
changed. See [Integrate with ITB](../how-to/integrate-itb.md) to use them in ITB.

## Endpoints

`[server]` is `http(s)://<host>:<port>/matchboxv3`.

| Service             | Type       | Root                     | Endpoints                                                               |
|---------------------|------------|--------------------------|-------------------------------------------------------------------------|
| `FHIRValidator`     | Validation | `[server]/itb/fhir`      | `GET [root]/getModuleDefinition`, `POST [root]/validate`                |
| `FHIRPathProcessor` | Processing | `[server]/itb/fhirPath`  | `GET [root]/getModuleDefinition`, `POST [root]/process`, `POST [root]/beginTransaction`, `POST [root]/endTransaction` |
| `IGManager`         | Processing | `[server]/itb/igManager` | Same as `FHIRPathProcessor`                                             |

`getModuleDefinition` describes the inputs of the service. The processing services are stateless: a transaction only
gets an id.

## Validation service `FHIRValidator` {: #validation}

### Inputs

| Input                    | Required | Description                                                                                                                                                                                       |
|--------------------------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `contentToValidate`      | yes      | The FHIR resource to validate, in JSON or XML.                                                                                                                                                    |
| `contentType`            | no       | `application/fhir+json` or `application/fhir+xml`. Detected from the content if missing.                                                                                                          |
| `profiles`               | no       | One profile, `canonical` or `canonical\|version`; the version selects the version of the implementation guide. Defaults to the first `meta.profile` of the resource, then to the base profile of its resource type. Matchbox validates against one profile per call: more than one gives HTTP 400. |
| `failOn`                 | no       | The severity from which the result is `FAILURE`: `error` (default), `warning` or `information`.                                                                                                   |
| `includeContentInReport` | no       | Whether the validated content is added to the context of the report (default `true`).                                                                                                            |
| `bpWarnings`             | no       | The level of the best practice warnings: `Ignore`, `Hint`, `Warning` or `Error`.                                                                                                                  |
| `resourceIdRule`         | no       | `OPTIONAL`, `REQUIRED` or `PROHIBITED`.                                                                                                                                                           |
| `displayWarnings`        | no       | Whether wrong displays are reported as warnings (same as `displayIssuesAreWarnings`).                                                                                                             |
| Any [validation parameter](configuration.md#validation-parameters) of `$validate` | no | e.g. `txServer`, `noExtensibleBindingMessages`, `suppressErrors`, `ig`. All are listed by `getModuleDefinition`.                                               |

The inputs are embedded as `STRING` or `BASE_64`. `URI` isn't supported, because fetching a URL given by the caller is
an SSRF risk.

### Report

The report (TAR) contains:

- `result`: `FAILURE` if there are errors, `WARNING` if there are only warnings, `SUCCESS` otherwise; `failOn` makes
  warnings or information issues a `FAILURE`. `UNDEFINED` if the validation engine failed.
- `items`: one per issue, with the message as `description`, the message id as `assertionID` and the issue type as
  `type`. The `location` is `content:<line>:<column>|<FHIRPath>`: ITB shows the FHIRPath and links it to the line in
  the validated content. Without the content in the report, the `location` is the FHIRPath only.
- `counters`: the number of errors, warnings and information issues.
- `overview`: the profile used (`canonical|version`), the version of Matchbox, and the ITB test session id as `note`.
- `context`:
    - `validation`: how the validation was done (profile, packages, validator version, duration and the validation
      parameters), which ITB shows as a group in the step report;
    - `errorCount`, `warningCount`, `informationCount` and `severity` (the highest severity), hidden in the displayed
      report;
    - `operationOutcome`: the `OperationOutcome` that `$validate` returns;
    - `content`: the validated resource.

### Errors

An invalid request (a missing `contentToValidate`, a `URI` embedding, more than one profile, …) gives HTTP 400 with
`{"error": "..."}`. A validation that can't be done, e.g. for an unknown profile, gives a report with the result
`FAILURE`.

## Processing service `FHIRPathProcessor` {: #fhirpath}

| Operation  | Inputs                                                                                                        | Output   |
|------------|---------------------------------------------------------------------------------------------------------------|----------|
| `evaluate` | `content` (the FHIR resource, JSON or XML), `expression` (the FHIRPath expression), optional `contentType`    | `result` |

`evaluate` uses the engine of the FHIR version of the server, without an implementation guide. The `result` is text:
`true` or `false` for a boolean, the values of a collection separated by a comma, and an empty text for an empty
collection. An expression or a content that can't be parsed gives HTTP 400 with `{"error": "..."}`.

```json
{"operation": "evaluate", "input": [
  {"name": "content", "value": "{\"resourceType\": \"Patient\", \"active\": true}", "embeddingMethod": "STRING"},
  {"name": "expression", "value": "Patient.active", "embeddingMethod": "STRING"}]}
```

```json
{"output": [{"name": "result", "value": "true", "embeddingMethod": "STRING", "type": "string", "mimeType": "text/plain"}]}
```

## Processing service `IGManager` {: #igmanager}

| Operation | Inputs                                  | Output   |
|-----------|-----------------------------------------|----------|
| `loadIG`  | `ig` (the package, `id#version`)        | `loaded` |

`loadIG` confirms a package that is installed in Matchbox. A package that isn't installed is installed from the package
registry with its dependencies, unless the server is [read-only](../how-to/secure-production-deployment.md#read-only-mode):
then, or if the installation fails, the answer has no `output` but a `report` with the result `FAILURE`, which fails the
test step. The validation picks the implementation guide from the profile: a test case doesn't have to call `loadIG`
before `validate`.
