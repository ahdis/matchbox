# Validate your first FHIR resource

In this tutorial, you will validate a FHIR resource with Matchbox, read the validation result, fix an error, and see
how the choice of the profile changes the result. You will use the [public test server](https://test.ahdis.ch/matchboxv3/),
so there is nothing to install.

You need:

- a web browser;
- `curl` in a terminal (any other HTTP client works too, e.g. the REST Client extension of VS Code).

## Step 1: validate a resource in the web interface

Open the following link. It opens the validation page of the test server with a small `Patient` resource and the
base `Patient` profile of FHIR R4 already filled in:

[Validate a Patient on the test server](https://test.ahdis.ch/matchboxv3/validate#resource=PFBhdGllbnQgeG1sbnM9Imh0dHA6Ly9obDcub3JnL2ZoaXIiPgogICAgPG5hbWU-CiAgICAgIDxmYW1pbHkgdmFsdWU9IlRlc3QiLz4KICAgIDwvbmFtZT4KICA8L1BhdGllbnQ-&profile=http%3A%2F%2Fhl7.org%2Ffhir%2FStructureDefinition%2FPatient)

Start the validation. After a few seconds, the result shows two _information_ messages, and the last one says:

> No fatal or error issues detected, the validation has passed

Your resource is valid against the `Patient` profile.

## Step 2: validate the same resource through the FHIR API

The web interface calls the FHIR operation `$validate` of the server. Call it yourself; the `profile` parameter says
which profile to validate against:

```bash
curl -X POST "https://test.ahdis.ch/matchboxv3/fhir/\$validate?profile=http://hl7.org/fhir/StructureDefinition/Patient" \
  -H "Content-Type: application/fhir+json" \
  -H "Accept: application/fhir+json" \
  -d '{"resourceType": "Patient", "name": [{"family": "Test"}]}'
```

The response is an `OperationOutcome` resource:

```json
{
  "resourceType": "OperationOutcome",
  "issue": [
    {
      "severity": "information",
      "code": "informational",
      "diagnostics": "Validation for profile http://hl7.org/fhir/StructureDefinition/Patient|4.0.1 (…). Loaded packages: hl7.fhir.r4.core#4.0.1, … powered by matchbox …"
    },
    {
      "severity": "information",
      "code": "informational",
      "diagnostics": "No fatal or error issues detected, the validation has passed"
    }
  ]
}
```

Look at the first issue: it tells you which profile and which packages were used, and which version of Matchbox did the
validation. Matchbox always adds it, so that you know how a result was obtained.

## Step 3: make a mistake

In JSON, `Patient.name` is a list, because a patient can have several names. Send the name as a single object instead
of a list:

```bash
curl -X POST "https://test.ahdis.ch/matchboxv3/fhir/\$validate?profile=http://hl7.org/fhir/StructureDefinition/Patient" \
  -H "Content-Type: application/fhir+json" \
  -H "Accept: application/fhir+json" \
  -d '{"resourceType": "Patient", "name": {"family": "Test"}}'
```

This time, the `OperationOutcome` contains an issue with the severity `error`:

| Severity | Code    | Diagnostics                                                        | Expression   |
|----------|---------|--------------------------------------------------------------------|--------------|
| error    | invalid | The property name must be a JSON Array, not an Object (at Patient) | Patient.name |

The _expression_ tells you where the problem is (`Patient.name`), the _diagnostics_ what the problem is.
The validation has failed: the success message of step 2 is missing.

The test server adds one more `information` issue, _AI Analyze of the Operation Outcome_: an explanation of the errors
written by a language model. It is a help for the reader, the result of the validation is in the other issues.

## Step 4: fix the mistake

Put the name back into a list (`"name": [{"family": "Test"}]`) and send the request of step 2 again. The error is gone
and the validation passes.

## Step 5: validate against an implementation guide

So far you validated against the base `Patient` profile of FHIR, which requires almost nothing. Implementation guides
add their own rules. The Swiss [CH Core Patient EPR](https://fhir.ch/ig/ch-core/StructureDefinition-ch-core-patient-epr.html)
profile, for example, requires an identifier, a name, a gender and a birth date. The CH Core implementation guide is
installed on the test server, so you can use its profile. Validate an empty `Patient` against it:

```bash
curl -X POST "https://test.ahdis.ch/matchboxv3/fhir/\$validate?profile=http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient-epr" \
  -H "Content-Type: application/fhir+json" \
  -H "Accept: application/fhir+json" \
  -d '{"resourceType": "Patient"}'
```

The same resource that was valid against the base profile now has several errors:

| Severity | Code      | Diagnostics                                                                          |
|----------|-----------|--------------------------------------------------------------------------------------|
| error    | structure | Patient.identifier: minimum required = 1, but only found 0 …                         |
| error    | structure | Patient.name: minimum required = 1, but only found 0 …                               |
| error    | structure | Patient.gender: minimum required = 1, but only found 0 …                             |
| error    | structure | Patient.birthDate: minimum required = 1, but only found 0 …                          |
| error    | invariant | Constraint failed: ch-pat-1-epr: 'At least one human name shall have a family name.' |

Each message ends with the profile and its version, e.g. `(from http://fhir.ch/…/ch-core-patient-epr|7.0.0-ballot)`.
The test server validates against the latest version of CH Core that is installed on it, so the version and the exact
list of errors may differ from the ones shown here.

Add the missing elements. Save the following resource as `patient.json`:

```json
{
  "resourceType": "Patient",
  "identifier": [{"system": "urn:oid:2.16.756.5.30.1.127.3.10.3", "value": "761337610411265304"}],
  "name": [{"family": "Test", "given": ["Anna"]}],
  "gender": "female",
  "birthDate": "1980-01-01"
}
```

and validate it:

```bash
curl -X POST "https://test.ahdis.ch/matchboxv3/fhir/\$validate?profile=http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient-epr" \
  -H "Content-Type: application/fhir+json" \
  -H "Accept: application/fhir+json" \
  --data-binary @patient.json
```

The errors are gone and the validation has passed. The result still contains an `information` issue about the
identifier (_This element does not match any known slice…_): issues of the severity `information` and `warning` are
hints, they don't make a validation fail.

## What you have learned

- Matchbox validates a resource against a **profile**, through the web interface or the `$validate` operation.
- The result is an **OperationOutcome**: the first issue describes the validation setup, and the validation has passed
  when no issue has the severity `error` or `fatal`; `warning` and `information` issues are hints.
- The same resource can be valid against one profile and invalid against another: the **implementation guide** defines
  the rules.

## Next steps

- [Run your own Matchbox](run-your-own-matchbox.md) with the implementation guides you need.
- [Understand validation errors](../explanation/validation-errors.md): structure, terminology, invariants and slicing.
- All the parameters of `$validate` are in the [FHIR operations reference](../reference/operations.md#validate).
