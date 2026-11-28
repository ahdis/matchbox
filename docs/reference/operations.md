# FHIR operations

matchbox-server exposes a FHIR API under `[base]` = `http(s)://<host>:<port>/matchboxv3/fhir`. This page describes the
operations that Matchbox adds or implements. The capabilities of a server are in its CapabilityStatement
(`GET [base]/metadata`), the operations are described by their OperationDefinitions
(`GET [base]/OperationDefinition`), and an OpenAPI description (Swagger UI) is available at `[base]/swagger-ui/`.

| Operation                                                   | Endpoint                                              | Purpose                                                            |
|-------------------------------------------------------------|-------------------------------------------------------|--------------------------------------------------------------------|
| [`$validate`](#validate)                                    | `POST [base]/$validate`, `POST [base]/{Type}/$validate` | Validate a resource against a profile                              |
| [`$transform`](#transform)                                  | `POST [base]/StructureMap/$transform`                 | Transform a resource with a StructureMap                           |
| [`$convert`](#convert)                                      | `POST [base]/StructureMap/$convert`                   | Convert a map in the FHIR mapping language into a StructureMap     |
| [`$list`](#list)                                            | `GET [base]/StructureMap/$list`                       | List the available StructureMaps                                   |
| [`$extract`](#extract)                                      | `POST [base]/QuestionnaireResponse/$extract`          | Extract resources from a QuestionnaireResponse (SDC)               |
| [`$assemble`](#assemble)                                    | `POST [base]/Questionnaire/$assemble`                 | Assemble a modular Questionnaire (SDC)                             |
| [`$install-npm-package`](#install-npm-package)              | `POST [base]/$install-npm-package`                    | Install a FHIR package                                             |
| [`$load-all`](#load-all)                                    | `POST [base]/ImplementationGuide/$load-all`           | Install again the implementation guides of the configuration       |
| [`$get-profiles`](#get-profiles)                            | `POST [base]/Bundle/$get-profiles`                    | Find the profiles that match a document Bundle                     |
| [`$list-recognizable-documents`](#list-recognizable-documents) | `GET [base]/Bundle/$list-recognizable-documents`   | List the document profiles that can be recognized                  |
| [Terminology operations](#terminology)                      | `/matchboxv3/tx`                                      | Internal terminology server                                        |
| [Terminology server registry](#tx-reg)                      | `GET /matchboxv3/tx-reg/resolve`                      | Tells the validator which terminology server to use                |

The conformance resources of the installed implementation guides (StructureDefinition, ValueSet, CodeSystem,
ConceptMap, NamingSystem, StructureMap, Questionnaire, ImplementationGuide) can be read and searched with the usual
FHIR interactions.

## $validate {: #validate}

Validates a resource (or an instance of a logical model, e.g. a CDA document) against a profile of the installed
implementation guides.

```http
POST [base]/$validate?profile={canonical} HTTP/1.1
Content-Type: application/fhir+json
```

| Parameter IN     | Card. | Description                                                                                                                                                         |
|------------------|-------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| _body_           | 1..1  | The resource to validate. The `Content-Type` header says whether it is `application/fhir+json` or `application/fhir+xml`.                                            |
| `profile`        | 1..1  | The canonical URL of the profile to validate against, with an optional business version (`canonical|version`).                                                      |
| `ig`             | 0..1  | The implementation guide and its version to use for the validation, e.g. `ch.fhir.ig.ch-core#3.0.0`.                                                                |
| other parameters | 0..1  | Any [validation parameter](configuration.md#validation-parameters), e.g. `txServer`, `analyzeErrorsWithLlm`; it overrides the default value of the configuration.   |
| `llmProvider`, `llmModelName`, `llmApiKey` | 0..1 | The [LLM](configuration.md#llm) to use for the analysis of this validation, instead of the configured one. |

The profiles that can be used are listed as `targetProfile` of the `profile` parameter in the OperationDefinition of
the operation (`GET [base]/OperationDefinition/-s-validate`), with and without their business version.

| Parameter OUT | Card. | Description                                                                    |
|---------------|-------|--------------------------------------------------------------------------------|
| `return`      | 1..1  | An `OperationOutcome` with the issues of severity `fatal`, `error`, `warning` or `information`. |

The first issue of the `OperationOutcome` is always an `information` issue that describes how the validation was done:
the profile and its version, the loaded packages, the duration, the versions of Matchbox, HAPI FHIR and
`org.hl7.fhir.core`, and all the validation parameters. For example:

```
Validation for profile http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient|3.0.0 (2018-10-15T00:00:00+10:00)
with packages: hl7.fhir.xver-extensions#0.0.13, hl7.fhir.r4.core#4.0.1, hl7.terminology#5.1.0,
ihe.formatcode.fhir#1.1.0, ch.fhir.ig.ch-epr-term#2.0.8, ch.fhir.ig.ch-core#3.0.0 No Issues detected. Total: 4430ms
powered by matchbox-engine 3.1.0, hapi-fhir 6.2.5 and org.hl7.fhir.core 5.6.92 validation parameters Parameters
{doNative=false, hintAboutNonMustSupport=false, recursive=false, …, txServer='http://tx.fhir.org', …}
```

If no issue has the severity `fatal` or `error`, the `OperationOutcome` contains an `information` issue with the text
_No fatal or error issues detected, the validation has passed_.

### Type-level validation {: #validate-type-level}

```http
POST [base]/{Type}/$validate HTTP/1.1
```

The operation exists for every resource type of the FHIR version of the server, as defined by the FHIR specification.
The type in the URL says which resource is expected, so the resource can be wrapped in a `Parameters` resource:

| Request body                                                          | Validated resource                                       |
|-----------------------------------------------------------------------|----------------------------------------------------------|
| a resource of type `{Type}`                                           | the body itself                                          |
| a `Parameters` with a `resource` parameter of type `{Type}`           | the resource in the parameter                            |
| a `Parameters` posted to `Parameters/$validate`                       | the `Parameters` itself, it's not read as an envelope    |
| anything else, e.g. a `Parameters` whose `resource` has another type  | not validated, an `error` issue names the expected type  |

The `profile` is optional on the type level; the first match is used:

1. the `profile` query parameter;
2. the `profile` parameter of the `Parameters` envelope;
3. `meta.profile` of the resource. With several profiles, the first one selects the validation engine (and the
   implementation guide); an `information` issue lists the other ones, which are only validated if they're known in
   that implementation guide;
4. the base definition `http://hl7.org/fhir/StructureDefinition/{Type}`.

The response is the same `OperationOutcome` as on the system level; the first issue tells which profile was used. All
other validation parameters are query parameters, as on the system level.

A JSON resource in a `Parameters` is validated exactly as it was sent (the line and column of an issue are relative to
the resource, not to the `Parameters`). An XML resource in a `Parameters` is serialized again, because it inherits its
namespaces from the envelope.

```http
POST [base]/DocumentReference/$validate HTTP/1.1
Accept: application/fhir+json
Content-Type: application/fhir+json

{
  "resourceType": "Parameters",
  "parameter": [
    {
      "name": "resource",
      "resource": {
        "resourceType": "DocumentReference",
        "meta": {
          "profile": [ "http://fhir.ch/ig/ch-ekm/StructureDefinition/ch-ekm-documentreference" ]
        },
        "status": "current",
        "content": [ { "attachment": { "contentType": "application/fhir+json", "url": "Bundle/1013" } } ]
      }
    }
  ]
}
```

### Auto-installation of implementation guides {: #auto-install-ig}

If the server isn't in read-only mode (`httpReadOnly: false`), an implementation guide of the `ig` parameter that
isn't installed yet is installed from the package registry with its dependencies, before the validation. It needs an
exact version (`id#version`, not `id`, `id#latest` or `id#1.0.x`). The first request for a large implementation guide
takes time.

If the implementation guide isn't installed and can't be installed (the server is in read-only mode, no exact version,
or the package registry doesn't have the package), the `OperationOutcome` has one error with the reason, and the
resource isn't validated.

Only the `ig` parameter installs an implementation guide: a `profile` that no installed implementation guide has is
an error. The other validation APIs do the same for their `ig` parameter: the
[ITB API](itb-api.md) and the [HL7 validator API](core-api.md#implementation-guides).

## $transform {: #transform}

Transforms a resource with a StructureMap, see
[StructureMap/$transform](https://www.hl7.org/fhir/operation-structuremap-transform.html).

```http
POST [base]/StructureMap/$transform?source={canonical of the StructureMap} HTTP/1.1
Content-Type: application/fhir+json
```

| Parameter IN | Card. | Description                                                                                                                            |
|--------------|-------|----------------------------------------------------------------------------------------------------------------------------------------|
| `source`     | 0..1  | The canonical URL of the StructureMap to use (query parameter, or parameter of a `Parameters` body). The map must exist on the server. |
| `debug`      | 0..1  | Query parameter. `true` to get the trace of the transformation, see below.                                                             |
| _body_       | 1..1  | The resource to transform, or a `Parameters` resource with the following parameters.                                                   |
| `resource`   | 0..1  | In a `Parameters` body: the resource to transform, as a string (JSON or XML).                                                          |
| `map`        | 0..1  | In a `Parameters` body: the StructureMap to use, as a string: a `StructureMap` in JSON or XML, or a map in the FHIR mapping language.  |
| `model`      | 0..1  | In a `Parameters` body: a `StructureDefinition`, or a `Bundle` of them, used by the map, as a string.                                  |

Either `source` or `map` must be provided. The response is the transformed resource, in the format of the `Accept`
header.

The request has one of two forms:

- **`source` as a query parameter**: the body is the resource to transform, as it is. It can be a FHIR resource of
  any FHIR version, or an instance of a logical model, e.g. a CDA document in XML: it is parsed with the structure that
  the map declares as its first source. The format is taken from the `Content-Type` header (`json` or `xml`), or
  detected from the content.
- **without `source` in the query**: the body must be a `Parameters` resource in the FHIR version of the server, with
  the parameter `resource` and either `source` or `map`. A map and models sent in the request are only used for this
  request, they aren't stored. This form works in all modes.

The engine that runs the transformation is chosen from the canonical URL of the map: the implementation guide that
contains the map is used. There is no `ig` parameter.

The response is the target of the map, in JSON if the `Accept` header contains `application/fhir+json` or
`application/json+fhir`, in XML otherwise. It isn't wrapped in a `Parameters`.

With `debug=true`, the response is a `Parameters` in the FHIR version of the server, with the parameters:

| Parameter OUT | Description                                                                                         |
|---------------|-----------------------------------------------------------------------------------------------------|
| `result`      | the transformed resource, as a string                                                               |
| `trace`       | the trace of the rules that were executed, as parts                                                 |
| `parameters`  | the parameters that were used: `evaluator` (the version of Matchbox) and `map` (the `StructureMap`) |

| Status | Cause                                                                                                                                                                                                                 |
|--------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 400    | several `source` query parameters; no `source` in the query and the body isn't a `Parameters`; the `Parameters` has no `resource`, or neither `source` nor `map`; `model` isn't a `StructureDefinition` or a `Bundle` |
| 422    | the map isn't found (_Map not available with canonical url …_), or no engine could be initialized                                                                                                                     |
| 500    | an error during the transformation, e.g. a rule that fails or a source that can't be parsed                                                                                                                           |

## $convert {: #convert}

Converts a map in the FHIR mapping language (`Content-Type: text/fhir-mapping`) into a `StructureMap` resource, without
storing it.

```http
POST [base]/StructureMap/$convert HTTP/1.1
Content-Type: text/fhir-mapping
Accept: application/fhir+json
```

A map in the FHIR mapping language can also be stored directly with `POST [base]/StructureMap` or
`PUT [base]/StructureMap/{id}` and the content type `text/fhir-mapping` (optionally with a FHIR version:
`text/fhir-mapping;fhirVersion=5.0`). This is only allowed in the
[`onlyOneEngine` mode](../explanation/validation-engines.md#only-one-engine): otherwise the request is rejected with
HTTP 405. A map with a syntax error is rejected with HTTP 500 and an `OperationOutcome` that gives the position of the
error.

| Parameter IN       | Card. | Description                                                                                       |
|--------------------|-------|---------------------------------------------------------------------------------------------------|
| _body_ (`input`)   | 1..1  | The map in the FHIR mapping language, with `Content-Type: text/fhir-mapping`.                     |
| `ig`, `from`, `to` | 0..1  | Declared for compatibility, but ignored.                                                          |

| Parameter OUT | Card. | Description                                                                                |
|---------------|-------|--------------------------------------------------------------------------------------------|
| `output`      | 1..1  | The `StructureMap`, returned as the body, in JSON or XML according to the `Accept` header. |

The map is parsed in the FHIR version of the server, or in the version given in the content type
(`text/fhir-mapping;fhirVersion=5.0`). The operation is available in all modes.

There is no conversion in the other direction: a `StructureMap` sent as JSON or XML is returned unchanged, not as a
map in the FHIR mapping language. The StructureMaps that are created with the content type `text/fhir-mapping` have
the map in the FHIR mapping language as their narrative (`text`).

## $list {: #list}

```http
GET [base]/StructureMap/$list HTTP/1.1
```

Returns a `Bundle` of type `searchset` with the StructureMaps of the installed implementation guides and, in the
`onlyOneEngine` mode, the ones of the engine. Only a minimal subset of the elements of each StructureMap is returned
(e.g. its name and URL): the operation is meant for clients that display a list of maps.

## $extract {: #extract}

```http
POST [base]/QuestionnaireResponse/$extract HTTP/1.1
```

Extracts resources from a `QuestionnaireResponse` with the StructureMap referenced by its `Questionnaire`, see
[SDC StructureMap-based extraction](https://build.fhir.org/ig/HL7/sdc/extraction.html#map-extract) and the
[OperationDefinition](http://build.fhir.org/ig/HL7/sdc/OperationDefinition-QuestionnaireResponse-extract.html).

The body is either the `QuestionnaireResponse` itself, or a `Parameters` resource:

| Parameter IN             | Card. | Description                                                                                                                           |
|--------------------------|-------|---------------------------------------------------------------------------------------------------------------------------------------|
| `questionnaire-response` | 1..1  | The `QuestionnaireResponse` to extract data from.                                                                                     |
| `questionnaire`          | 0..1  | Matchbox extension. The `Questionnaire`, if it isn't on the server. Its `url` must be equal to `QuestionnaireResponse.questionnaire`. |
| `structure-map`          | 0..1  | Matchbox extension. The `StructureMap`, if it isn't on the server: as a resource, or as a string in the FHIR mapping language.        |

The operation:

1. finds the `Questionnaire` of `QuestionnaireResponse.questionnaire`: the one of the request, or the one installed
   on the server;
2. reads the canonical URL of the map in its extension
   `http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-targetStructureMap`;
3. transforms the `QuestionnaireResponse` with that map, which is the one of the request or the one installed on the
   server.

The response is the resource created by the map (a single resource, or a `Bundle`, e.g. of type `transaction`), as
the body; it isn't wrapped in a `Parameters`. The request and the response can be in another FHIR version than the
server, with the `fhirVersion` parameter of the `Content-Type` and `Accept` headers.

| Status | Cause                                                                                                                                                                                                                                                               |
|--------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 400    | the `structure-map` string can't be parsed (_Unable to parse the FML language_)                                                                                                                                                                                     |
| 422    | the body isn't a `Parameters` or a `QuestionnaireResponse`; `questionnaire-response` is missing; the response has no `questionnaire`; the `questionnaire` of the request has another `url`; the questionnaire isn't found; it has no `targetStructureMap` extension |
| 500    | an error during the transformation, e.g. the map isn't found or a rule fails                                                                                                                                                                                        |

See [Extract data from a QuestionnaireResponse](../how-to/extract-questionnaire-response.md) for examples.

## $assemble {: #assemble}

```http
POST [base]/Questionnaire/$assemble HTTP/1.1
```

Assembles a modular `Questionnaire` from its sub-questionnaires (preliminary support), see
[SDC modular forms](https://build.fhir.org/ig/HL7/sdc/modular.html).

| Parameter IN    | Card. | Description                                          |
|-----------------|-------|------------------------------------------------------|
| `questionnaire` | 1..1  | The modular `Questionnaire`, in a `Parameters` body. |

| Parameter OUT | Card. | Description                                                                          |
|---------------|-------|--------------------------------------------------------------------------------------|
| `return`      | 1..1  | The assembled `Questionnaire`, returned as the body (not wrapped in a `Parameters`). |

The sub-questionnaires are read from the installed implementation guides (or, in the `onlyOneEngine` mode, from the
engine); they can't be sent with the request.

Supported:

- the items with the extension `sdc-questionnaire-subQuestionnaire` are replaced by the items of the referenced
  questionnaire, recursively. The version of the canonical is ignored. An item whose questionnaire isn't found is kept
  as it is, without an error;
- an extension `sdc-questionnaire-assembledFrom` (`url|version`) is added for each sub-questionnaire;
- the version of the assembled questionnaire is `<version>-assembled`, and its narrative is removed;
- the extension `sdc-questionnaire-assemble-expectation` is adjusted: `assemble-root` is removed, the other codes
  become `independent-…`;
- a `linkIdPrefix` defined with the extension `variable` (a FHIRPath expression that is evaluated without context,
  e.g. `'name.'`) is prepended to the `linkId` of the items of the sub-questionnaires that declare `linkIdPrefix` in
  their extension `sdc-questionnaire-assembleContext`.

Not supported: the language check, the propagation of contained resources and of extensions (`cqf-library`,
`launch-context`, `variable`, `questionnaire-constraint`, `itemPopulationContext`, `itemExtractionContext`), the
`linkIdPrefix` in `enableWhen.question`, in contained resources and in expressions, `%linkIdPrefix` in the expression
of the `variable`, the `item.definition` propagation, the detection of recursive references and of duplicate
`linkId`s, and the validation of the result. The response is never a `Parameters` with an `outcome`.

## $install-npm-package {: #install-npm-package}

Installs a FHIR package (NPM package) into the database. It is only available if the read-only mode is disabled.

| Parameter IN | Card. | Description                                       |
|--------------|-------|---------------------------------------------------|
| `name`       | 1..1  | The name of the package (query parameter).        |
| `version`    | 1..1  | The version of the package (query parameter).     |
| _body_       | 1..1  | The content of the NPM package (`package.tgz`).   |

```http
POST [base]/$install-npm-package?name=ch.fhir.ig.ch-core&version=4.0.1 HTTP/1.1
Content-Type: application/gzip

<gzip content of the NPM package>
```

The response is an `OperationOutcome` with the messages of the installation and the list of installed resources.

## $load-all {: #load-all}

```http
POST [base]/ImplementationGuide/$load-all HTTP/1.1
```

Installs again the implementation guides of the configuration (`hapi.fhir.implementationguides`). The response is an
`OperationOutcome`.

## $get-profiles {: #get-profiles}

```http
POST [base]/Bundle/$get-profiles HTTP/1.1
```

Takes a `Bundle` of type `document` (as body, or as `resource` parameter of a `Parameters`) and returns the profiles of
the installed implementation guides that may apply to it. The profiles are found by comparing the type and category
codes of the `Composition` with the fixed patterns of the installed document profiles.

## $list-recognizable-documents {: #list-recognizable-documents}

```http
GET [base]/Bundle/$list-recognizable-documents HTTP/1.1
```

A debug operation that lists the document profiles that `$get-profiles` can recognize, as a `Parameters` resource: one
`profile` parameter per profile, with the extensions `composition-type` and `composition-category`.

## Terminology operations {: #terminology}

The internal terminology server has its own base address, `[tx]` = `http(s)://<host>:<port>/matchboxv3/tx`. It
implements the two operations that the validator needs to check codes:

| Operation        | Endpoint                              | Purpose                                        |
|------------------|---------------------------------------|------------------------------------------------|
| `$validate-code` | `POST [tx]/CodeSystem/$validate-code` | Checks whether a code exists in a code system  |
| `$validate-code` | `POST [tx]/ValueSet/$validate-code`   | Checks whether a code is in a value set        |

The CapabilityStatement of the terminology server is at `GET [tx]/metadata`.

Other terminology operations are **not implemented**: `CodeSystem/$lookup` and `ValueSet/$expand` are declared but
return no result (HTTP 404), and the system-level operations (`[tx]/$validate-code`, `[tx]/$batch-validate-code`,
`[tx]/$versions`) are rejected (HTTP 400). The internal terminology server is made for the validator of Matchbox, it is
not a general-purpose terminology server. Its limits are described in
[Terminology in validation](../explanation/terminology.md#the-internal-terminology-server).

### $validate-code {: #validate-code}

Both operations take a `Parameters` body (in the FHIR version of the server) and return a `Parameters`.

`ValueSet/$validate-code`:

| Parameter IN      | Card. | Description                                                                                                  |
|-------------------|-------|--------------------------------------------------------------------------------------------------------------|
| `code`            | 0..1  | A code alone. It is always accepted, without any check.                                                      |
| `coding`          | 0..1  | The `Coding` to check.                                                                                       |
| `codeableConcept` | 0..1  | The `CodeableConcept` to check; it is valid if one of its codings is in the value set.                       |
| `valueSet`        | 0..1  | The `ValueSet` to check against, as a resource.                                                              |
| `url`             | 0..1  | The canonical URL of a value set that was sent earlier with the same `cache-id`.                             |
| `cache-id`        | 0..1  | The id of the validator's cache: the expansion of a `valueSet` is kept under this id, to be used with `url`. |
| `valueSetMode`    | 0..1  | `NO_MEMBERSHIP_CHECK` to accept the code without checking it.                                                |
| `inferSystem`     | 0..1  | `true` to use the first code system of the value set when the coding has no `system`.                        |

Other parameters (e.g. `mode`, `lenient-display-validation`, `default-to-latest-version`, `profile-url`) are ignored.
`coding` or `codeableConcept` is required (otherwise HTTP 422).

The code is checked against the expansion of the value set, done in memory by HAPI FHIR. The code is accepted
without a check when the value set isn't sent or isn't in the cache, when the expansion is empty, or when it fails
and the code isn't explicitly included or excluded (by a `concept` or a `regex` filter) in the `compose` of the value
set.

`CodeSystem/$validate-code` takes a `coding` or a `codeableConcept` and always accepts it: the internal terminology
server doesn't know the content of the code systems.

| Parameter OUT                          | Card. | Description                                                                                   |
|----------------------------------------|-------|-----------------------------------------------------------------------------------------------|
| `result`                               | 1..1  | `true` if the code is valid, `false` otherwise.                                               |
| `code`, `system`, `version`, `display` | 0..1  | The elements of the `coding` that was checked (the first valid one of a `codeableConcept`).   |
| `codeableConcept`                      | 0..1  | The `codeableConcept` that was checked (`CodeSystem/$validate-code`, and when invalid).       |
| `message`                              | 0..1  | If invalid: why, e.g. _The provided Coding/CodeableConcept is not in the value set …_.        |
| `issues`                               | 0..1  | If invalid: an `OperationOutcome` with an `error` issue of type `code-invalid` (`not-in-vs`). |

## Terminology server registry {: #tx-reg}

```http
GET /matchboxv3/tx-reg/resolve HTTP/1.1
```

Answers the `resolve` request of a terminology server registry, with which a validator asks which terminology server
to use for a code system (see the validation parameter `txUseEcosystem`). Matchbox always answers with a single
candidate: the terminology server of its configuration (`matchbox.fhir.context.txServer`).
