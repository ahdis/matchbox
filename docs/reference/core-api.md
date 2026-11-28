# HL7 validator API

matchbox-server provides the validation API of the HL7 validator, as [validator.fhir.org](https://validator.fhir.org)
has it: `POST /validate` of the
[org.hl7.fhir.validator-wrapper](https://github.com/hapifhir/org.hl7.fhir.validator-wrapper), with the
`ValidationRequest` and `ValidationResponse` of
[org.hl7.fhir.core](https://github.com/hapifhir/org.hl7.fhir.core). A client of the HL7 validator can use Matchbox
with only the address changed.

## Endpoint

`[server]` is `http(s)://<host>:<port>/matchboxv3`.

| Endpoint                       | Description                                              |
|--------------------------------|----------------------------------------------------------|
| `POST [server]/core/validate`  | Validates resources, and returns the issues per resource |

The endpoint is under `/core`, as `[server]/validate` is the validation page of the web interface.

## Request

```json
{
  "validationContext": {
    "sv": "4.0.1",
    "igs": ["matchbox.health.test.ig.r4#0.3.1"],
    "profiles": ["http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required"]
  },
  "filesToValidate": [
    {
      "fileName": "practitioner.json",
      "fileContent": "{\"resourceType\": \"Practitioner\", \"active\": true}",
      "fileType": "json"
    }
  ]
}
```

| Property            | Required | Description                                                                                                                                                                              |
|---------------------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `filesToValidate`   | yes      | The resources: `fileContent` is the resource in JSON or XML, `fileName` is returned with the issues, `fileType` is `json` or `xml` (detected from the content if missing).                |
| `validationContext` | no       | The validation parameters, see below. `cliContext` is accepted as well (the former name), and so are `validationEngineParameters` and `instanceValidatorParameters` of newer clients.   |
| `sessionId`         | no       | Accepted and ignored, see [Sessions](#sessions).                                                                                                                                         |

### Validation parameters

Only the parameters that are in the request are read, and they override the
[validation parameters](configuration.md#validation-parameters) of the server, as for `$validate`. A parameter that is
not in the request has the value of the server configuration, and not the default value of the HL7 validator.

| Parameter           | Description                                                                                                                                                                                                                |
|---------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `igs`, `ig`         | The implementation guides, `id#version` or `id` (the installed current version), as a list or a single value. One that is not installed is installed first, unless the server is read-only, see [below](#implementation-guides). |
| `profiles`, `profile` | One profile, `canonical` or `canonical\|version`. Defaults to the first `meta.profile` of the resource, then to the base profile of its resource type. Matchbox validates against one profile per call: more than one gives HTTP 400. |
| `sv`                | The FHIR version: `4.0.1`, `4.3.0` or `5.0.0` (also `4.0`, `R4`, …). Defaults to the FHIR version of the first implementation guide, then to the one of the server.                                                          |
| `displayWarnings`   | Same as `displayIssuesAreWarnings`.                                                                                                                                                                                        |
| `checkIPSCodes`     | Same as `checkIpsCodes`.                                                                                                                                                                                                   |
| `bestPracticeLevel` | Same as `bpWarnings`: `Ignore`, `Hint`, `Warning` or `Error`.                                                                                                                                                              |
| `noEcosystem`       | The opposite of `txUseEcosystem`.                                                                                                                                                                                          |
| Any validation parameter of `$validate` | e.g. `txServer`, `extensions`, `noExtensibleBindingMessages`, `hintAboutNonMustSupport`, `locale`, `suppressErrors`.                                                                                  |

The other parameters of the HL7 validator (e.g. `showTimes`, `level`, `questionnaireMode`, `htmlInMarkdownCheck`) are
ignored.

### Implementation guides and profile {: #implementation-guides}

- With `igs`, the resource is validated with an engine that has these implementation guides loaded. The profile has to
  be in one of them, or in the FHIR core package.
- Without `igs`, the profile selects the implementation guide, as for `$validate`: the current version of the
  implementation guide that has the profile, or the version given with `canonical|version`.

An implementation guide of `igs` that is not installed in Matchbox is installed from the package registry with its
dependencies, as for the `ig` parameter of [`$validate`](operations.md#auto-install-ig) and the `loadIG` operation
of the [ITB API](itb-api.md#igmanager), and the request is answered when
it is installed: the first request for a large implementation guide takes time. The request gets HTTP 400 with the
reason instead, if

- the server is [read-only](../how-to/secure-production-deployment.md#read-only-mode): its implementation guides are
  the ones of its [configuration](../how-to/load-implementation-guides.md);
- the implementation guide is given without an exact version (`id`, `id#latest`, `id#1.0.x`): only `id#version` can
  be installed;
- the installation fails, e.g. the package registry doesn't have the package.

## Response

```json
{
  "outcomes": [
    {
      "fileInfo": {
        "fileName": "practitioner.json",
        "fileContent": "{\"resourceType\": \"Practitioner\", \"active\": true}",
        "fileType": "json"
      },
      "issues": [
        {
          "source": "InstanceValidator",
          "line": 1,
          "col": 49,
          "location": "Practitioner",
          "message": "Practitioner.identifier: minimum required = 1, but only found 0 (from http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required)",
          "messageId": "Validation_VAL_Profile_Minimum",
          "type": "STRUCTURE",
          "level": "ERROR",
          "html": "Practitioner.identifier: minimum required = 1, but only found 0 (from http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required)",
          "slicingHint": false,
          "signpost": false,
          "criticalSignpost": false,
          "matched": false,
          "ignorableError": false,
          "count": 0,
          "validationContext": "http://matchbox.health/ig/test/r4/StructureDefinition/practitioner-identifier-required"
        }
      ]
    }
  ],
  "sessionId": "a501597d615f634db636127c01216b43"
}
```

- `outcomes`: one per file, in the order of the request, with the file and its `issues`. An issue is the validation
  message of the HL7 validator with all its properties; `level` is `FATAL`, `ERROR`, `WARNING` or `INFORMATION`.
- A resource that could not be validated (e.g. a profile that no installed implementation guide has) has one `FATAL`
  issue with the reason.
- `sessionId`: the id of the validation engine that validated the first resource.

| Status | Body (text)                                                                                                    |
|--------|----------------------------------------------------------------------------------------------------------------|
| 400    | The request is not valid: malformed JSON, no file, an implementation guide that is not installed and cannot be installed, more than one profile, an unsupported FHIR version or parameter value |
| 503    | The validation engine is not initialized yet (startup, or an implementation guide is being loaded)             |
| 500    | Internal error                                                                                                 |

## Sessions {: #sessions}

In the HL7 validator, a session is a validation engine that the client reuses by sending back the `sessionId`. Matchbox
selects the engine from the validation parameters of the request, the implementation guides included, and keeps it in
its engine cache: requests with the same parameters share the engine, whether they send a `sessionId` or not, and
whichever API they use. The `sessionId` of a request is therefore ignored, and the one of the response is the id of
the engine, as in the `OperationOutcome` of `$validate`.

The implementation guides are normalised before the engine is selected: `igs` and `ig` are merged, resolved to the
installed version, sorted, and the FHIR core package is left out. `["a#1.0.0", "b#2.0.0"]`, `["b#2.0.0", "a"]` and
`"ig": "a", "igs": ["b#2.0.0"]` give the same engine, and one implementation guide gives the engine that `$validate`
uses for the profiles of this implementation guide.

## Differences to the HL7 validator

- An implementation guide that is not installed is installed in Matchbox and stays installed, if the server is not
  read-only. A read-only server only has the implementation guides of its configuration.
- An implementation guide is a package, `id#version` or `id`: not a URL or a file.
- One profile per request.
- The parameters that are not in the request have the values of the server configuration.
- Only `POST /validate` is provided, and not the other endpoints of validator.fhir.org (`/ig`, `/versions`,
  `/terminology`, …).
- The validations are counted in the [metrics and stored for the statistics](../how-to/monitor-matchbox.md), like the
  ones of `$validate`.
