# TODO: matchbox as an ITB validation service (#589)

State as of 2026-09-22. Issue: https://github.com/ahdis/matchbox/issues/589. Related:
[ISAITB/gitb#199](https://github.com/ISAITB/gitb/issues/199) and
[WHO-ITB-Trusted-Tests-Tools#16](https://github.com/WorldHealthOrganization/WHO-ITB-Trusted-Tests-Tools/issues/16).

## Background

- **ITB 1.30.0** (released 2026-09-21) can call test services over REST/JSON as well as SOAP, selected with
  `handlerApiType="REST"` on a step or by setting a domain test service's API type to REST. The contracts are in
  the gitb-types repo (`development` branch, `gitb-types-specs/src/main/resources/rest/`):
  - [gitb_vs.json](https://github.com/ISAITB/gitb-types/blob/development/gitb-types-specs/src/main/resources/rest/gitb_vs.json)
    is the validation service: `GET {root}/getModuleDefinition`, `POST {root}/validate`, which returns a TAR.
  - [gitb_ps.json](https://github.com/ISAITB/gitb-types/blob/development/gitb-types-specs/src/main/resources/rest/gitb_ps.json)
    is the processing service: `GET {root}/getModuleDefinition`, `POST {root}/process`,
    `POST {root}/beginTransaction`, `POST {root}/endTransaction`.
- **The core validator has a draft**, [hapifhir/org.hl7.fhir.core#2615](https://github.com/hapifhir/org.hl7.fhir.core/pull/2615)
  (@costateixeira). It adds 11 ITB services under `/itb/*` to the validator CLI HTTP server and depends on about six
  unmerged parent PRs. The code is written for `com.sun.net.httpserver` and `ValidationEngine`, so matchbox cannot reuse
  it. Its **wire contract** (`documentation/itb-rest-spec.md` in the PR) is what we follow: service names, input names,
  the OperationOutcome → TAR mapping and the error table.
- **The Gazelle API is the template in matchbox.** It is a plain Spring `@RestController`
  ([GazelleValidationWs.java](matchbox-server/src/main/java/ch/ahdis/matchbox/validation/gazelle/GazelleValidationWs.java))
  with hand-written Jackson DTOs, registered in
  [Application.java](matchbox-server/src/main/java/ca/uhn/fhir/jpa/starter/Application.java).
- **There is no usable Maven dependency.** Maven Central only has `gitb-types` 1.25.2, which holds the SOAP types. The
  REST model is not published, and ITB advises validator projects not to depend on it, so we write our own DTOs.
- **The test ITB** in [../gazelle/maestro-tutorial/itb](../gazelle/maestro-tutorial/itb) runs 1.29.5 and has to be
  moved to 1.30.0.

## Decision

Build our own Spring implementation that uses the **same contract as upstream #2615**: the same paths, input names,
context item names and result rules. A test case written for the HL7 validator's `FHIRValidator` should then run
unchanged against matchbox, with only the address changed. Where matchbox can do more (IG-versioned profiles, its
validation parameters), add optional inputs rather than rename existing ones.

## Phase 1: `FHIRValidator` validation service

Endpoints, below the context path (e.g. `http://host:8080/matchboxv3/itb/fhir`):
`GET /itb/fhir/getModuleDefinition` and `POST /itb/fhir/validate`.

### Code

- [x] New package `ch.ahdis.matchbox.validation.itb`
  - [x] `ItbValidationWs`: `@RestController`, registered in `Application` next to `GazelleValidationWs`
  - [x] `models/`: hand-written POJOs from `gitb_vs.json`: `AnyContent`, `Configuration`, `TypedParameter`,
        `Parameter`, `Metadata`, `ValidationModule`, `GetModuleDefinitionResponse`, `ValidateRequest`,
        `ValidationResponse`, `TAR`, `ReportItem`, `ValidationCounters`, `ValidationOverview`, plus the enums
        `ValueEmbeddingEnumeration`, `SeverityLevel`, `TestResultType`, `UsageEnumeration`, `ConfigurationType`
  - [x] `ItbInputs`: resolves `AnyContent` values
    - supports `STRING` and `BASE_64`
    - rejects `URI` with HTTP 400, as upstream does, because fetching a caller-supplied URL is an SSRF risk
    - HTTP 400 with `{"error": ...}` for a missing required input, a required input that is empty, or an invalid
      encoding (upstream §2.6)
  - [x] `ItbTarMapper`: converts a list of `ValidationMessage` into TAR items, counters and result
  - [x] `bpWarnings` and `resourceIdRule` are new `CliContext` fields (so `$validate` has them too), `displayWarnings`
        maps to `displayIssuesAreWarnings`
- [x] Refactor, so the logic is not copied a third time:
  - [x] extract the private `ValidationProvider.getOperationOutcome(...)` and the loop that applies validation
        parameters (`ValidationProvider.getValidation`, reflection over
        `CliContext.getValidateEngineParameters()`) into a shared helper
  - [x] move Gazelle's `canonical|version` split and `getEngine()` lookup into the same helper
  - [x] make `$validate`, Gazelle and ITB all use the helper

### Inputs

These are the upstream §3.1 inputs plus the matchbox extras.

| Input | Required | Behaviour in matchbox |
|---|---|---|
| `contentToValidate` | yes | JSON or XML. When `contentType` is missing, detect the format with `EncodingEnum.detectEncoding` (as Gazelle does) |
| `contentType` | no | `application/fhir+json` or `application/fhir+xml` |
| `profiles` | no | `canonical` or `canonical\|version`. Matchbox uses one engine per IG, so accept exactly one profile; more than one gives HTTP 400 (decision 1). If missing, use the base resource profile, as `$validate` does |
| `failOn` | no | `error` (default), `warning` or `information` |
| `includeContentInReport` | no | default `true` |
| `bpWarnings`, `resourceIdRule`, `displayWarnings` | no | mapped onto the matching `CliContext` fields |
| every field in `CliContext.getValidateEngineParameters()` | no | e.g. `txServer`, `extensibleBindingsAsErrors`, `suppressError`, applied like `$validate` does. All are listed in `getModuleDefinition` so ITB test authors can find them |

### Response (upstream §2.8)

- [x] `items[].level` from the message severity: fatal or error → `ERROR`, warning → `WARNING`, information → `INFO`
- [x] `items[].description` is the message text, plus the slicing details from `engine.filterSlicingMessages` as
      Gazelle does
- [x] `items[].location` is `content:<line>:<column>|<FHIRPath>`: ITB links it to the line in the context item
      `content` and shows the FHIRPath
- [x] `items[].assertionID` is the messageId (fallback: invId, then the issue type). `items[].type` is the issue type
- [x] `result` comes from the counters and `failOn`:
  - errors > 0 → `FAILURE`
  - no errors but warnings → `WARNING`
  - neither → `SUCCESS`
  - the engine threw an exception → `UNDEFINED`
  - `failOn=warning` makes warnings a `FAILURE`; `failOn=information` makes any issue a `FAILURE`
- [x] `counters`: `nrOfErrors`, `nrOfWarnings`, `nrOfAssertions` (the information count)
- [x] `context[]` items:
  - `errorCount`, `warningCount`, `informationCount` and `severity` (the highest severity seen), all with
    `forDisplay=false`
  - `validation`: a map with the profile, packages, validator, duration and the validation parameters, the
    information of the first OperationOutcome issue, so the ITB step report shows how the validation was done
  - `context` is one AnyContent of type `map` holding these items, as `gitb_vs.json` defines it (upstream sends an
    array, see the backlog)
  - `operationOutcome`: the same OperationOutcome that `$validate` returns
  - `content`: the validated payload, only when `includeContentInReport` is true
- [x] `overview` fields:
  - `profileID` is the resolved `canonical|version`
  - `validationServiceName` is `matchbox`
  - `validationServiceVersion` is the matchbox version
  - `note` is the `Gitb-Test-Session-Identifier` header
- [x] Log the other `Gitb-*` headers (`Gitb-Reply-To`, `Gitb-Test-Case-Identifier`, `Gitb-Test-Step-Identifier`,
      `Gitb-Test-Engine-Version`). Do not make any callbacks
- [x] A domain failure (unknown profile, parse error) returns HTTP 200 with a TAR whose result is `FAILURE`. HTTP 5xx is
      only for server bugs

### Config

- [x] Always on and read-only, like Gazelle. An `itb.enabled` flag can be added later if needed

## Phase 2: processing services (not in the first PR, see decision 2)

All use the upstream names. `beginTransaction` returns a new UUID and `endTransaction` does nothing, because the
services are stateless.

- [ ] **`FHIRTransformer`** (`/itb/transform`)
  - [ ] `transform`: inputs `content`, `map`, `contentType`, `targetFormat`; outputs `result` and `targetMime`.
        Uses `MatchboxEngine.transform(...)`, with the same engine lookup as
        [StructureMapTransformProvider](matchbox-server/src/main/java/ch/ahdis/matchbox/mappinglanguage/StructureMapTransformProvider.java).
        This also gives ITB CDA → FHIR through the matchbox maps
  - [ ] `parse`: FML to StructureMap. Inputs `content`, `name`, `targetFormat`; outputs `structureMap` and
        `targetMime`
- [ ] **`FHIRPathAssertion`** (`/itb/fhirPathAssertion`, a validation service): inputs `contentToValidate` and
      `expression`
- [ ] **`FHIRPathProcessor`** (`/itb/fhirPath`, operation `evaluate`): inputs `content` and `expression`; output
      `result`
- [ ] **`ValidationResultsProcessor`** (`/itb/validationResults`): `summarize`, `filterBySeverity`, `filterByText`.
      Pure JSON logic, no engine needed
- [ ] **`IGManager.loadIG`** (`/itb/igManager`): input `ig` (`package#version`), output `loaded`. Maps to the existing
      matchbox package install. **Must be rejected when `httpReadOnly` is set**, so shared deployments stay
      read-only

Not planned: `TestDataGenerator`, `QuestionnaireGenerator`, `PackageGenerator` and `ResourceLoader`. They depend on
engine methods that are not merged upstream yet, and `ResourceLoader` would let one ITB session change the profiles
another session validates against.

## Tests

- [x] `ItbApiR4Test` in `matchbox-server/src/test/java/ch/ahdis/matchbox/itb/`, set up like
      [GazelleApiR4Test](matchbox-server/src/test/java/ch/ahdis/matchbox/gazelle/GazelleApiR4Test.java)
      (`DEFINED_PORT`, profiles `tests` and `test-r4`), with a small `ItbClient`
  - [x] `getModuleDefinition` lists the inputs
  - [x] a valid Patient gives `SUCCESS`
  - [x] the `ehs-431` and `ehs-419` fixtures give `FAILURE` with the expected counters
  - [x] `failOn=warning` changes the result
  - [x] `profiles` with `|version` selects the right IG engine
  - [x] a missing `contentToValidate`, a `URI` embedding, and more than one profile in `profiles` each give HTTP 400
  - [x] an unknown profile gives HTTP 200 with a `FAILURE` TAR
  - [x] `context[]` contains `operationOutcome` and the counts
  - [x] `BASE_64` embedding works
- [ ] The Gazelle tests and the `$validate` tests still pass after the refactor
- [ ] Phase 2: a `FHIRTransformer` test using `qr2patgender.map`

## Demo in `../gazelle/maestro-tutorial/itb`

Verified on 2026-09-22 against ITB 1.30.0 and a local matchbox build: `./run-fhir-demo.sh` gives SUCCESS for the
valid and FAILURE (3 errors) for the invalid CH Core Patient, with the issues in the ITB report.

- [x] Move the four `isaitb/*` images in `docker-compose.yml` to **1.30.0** (`docker compose down -v` first)
- [x] matchbox runs from the local jar (`start-matchbox.sh`, `matchbox/application.yaml`: port 8081, CH Core 6.0.0),
      `gitb-srv` reaches it at `http://host.docker.internal:8081/matchboxv3/itb/fhir` (`extra_hosts` for Linux)
- [x] `setup-itb.sh` registers `FHIRValidator` and deploys the `fhirDemo` suite. **It is a domain parameter, not a
      test service**: the automation API of 1.30.0 refuses `apiType: "rest"` ("Only services with a SOAP API are
      currently supported."), a REST test service can only be created in the UI
- [x] Test suite `testsuite/fhir-demo/`: `fhirPatientValid` (verify + `StringValidator` on `$ctx{errorCount}`) and
      `fhirPatientInvalid` (verify fails, `log` of `$ctx{errorCount}` and `$ctx{severity}`); the patients are binary
      artifacts, sent base64 encoded
- [x] `run-fhir-demo.sh` starts both test cases over the automation API and prints the report items
- [x] Item locations: the ITB UI links `name:line:col` to the context item `name`, and shows the text after `|`
      (`test-assertion-report.component.ts`), so matchbox now sends `content:<line>:<col>|<FHIRPath>`
- [ ] Look at the report in the ITB UI (http://localhost:9000) once, to see the link open the content at the line
- [ ] Optional: a `testrun-itb-matchbox.json` that starts this test case through Maestro's ITB step, so the chain is
      Maestro → ITB → matchbox. Maestro's `UNDEFINED` verdict bug (issue 1 in `itb/README.md`) affects this too
- [ ] Optional: a matchbox container in the compose file instead of the local jar

## Docs and PR

- [x] A new section "ITB (GITB REST) API" in [docs/validation.md](docs/validation.md), next to "Gazelle EVS API":
      endpoint table, inputs, a TDL snippet, and how to register the service in ITB
- [x] Add the change to `docs/changelog.md` under a new release heading, referencing #589
- [ ] Open the PR against `ahdis/matchbox` (`gh pr create --repo ahdis/matchbox`)
- [ ] Comment on #589, ISAITB/gitb#199 and WHO-ITB-Trusted-Tests-Tools#16 with the matchbox endpoint
- [ ] Delete this TODO.md before merging

## Decisions (2026-09-22)

1. **One profile per call.** `profiles` accepts exactly one `canonical` or `canonical|version`. More than one
   comma-separated profile gives HTTP 400.
2. **The first PR is Phase 1 only.** `FHIRTransformer` and the other processing services come later.
3. **Contract alignment with upstream is in the backlog** (see below).

## Backlog

- [ ] Contract alignment with upstream #2615: propose that `profiles` accepts an optional `|version`, and that the extra
      validation-parameter inputs are allowed, so both implementations stay aligned
- [ ] Also report to #2615 where its wire format differs from `gitb_vs.json` (matchbox follows `gitb_vs.json`):
  - `TAR.context` is one `AnyContent` (a map with `item[]`), not an array
  - the display flag of `AnyContent` is `forDisplay`, not `forReport`
  - `ValidationModule.inputs` is an array of `TypedParameter` with `desc`, not `{param: [...]}` with `description`
- [ ] Ask ITB (ISAITB/gitb-types) to add `metadata` to `AnyContent` in the REST contracts, as in `gitb_core.xsd`.
      gitb-srv drops it today, so REST services cannot set the step report display hints (`level=INFO` renders an item
      as an info box, `forceDisplay=true` shows values over 100 characters inline); matchbox would use it for the
      `validation` context item
- [ ] ITB rejects the whole step report when one context item has a type it does not know (e.g. `list[string]`
      instead of `list`: "Unsupported data type"), keep an eye on the types matchbox sends
