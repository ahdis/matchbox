# Validate resources

This guide shows the different ways to validate a FHIR resource (or a logical model instance, e.g. a CDA document) with
matchbox-server. All the parameters are in the [`$validate` reference](../reference/operations.md#validate); how to read
the result is explained in [Understanding validation errors](../explanation/validation-errors.md).

## Validate against a profile

Post the resource to `[base]/$validate` with the canonical URL of the profile. Set the `Content-Type` to
`application/fhir+json` or `application/fhir+xml`:

```http
POST /matchboxv3/fhir/$validate?profile=http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient HTTP/1.1
Content-Type: application/fhir+json
Accept: application/fhir+json

{ "resourceType": "Patient", ... }
```

The implementation guide that defines the profile must be [installed](load-implementation-guides.md).

## Validate against a specific version of an implementation guide

When several versions of an implementation guide are installed, choose the version with the `ig` parameter, or add the
version of the profile to its canonical URL:

```http
POST /matchboxv3/fhir/$validate?profile=http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient&ig=ch.fhir.ig.ch-core%235.0.0 HTTP/1.1
```

```http
POST /matchboxv3/fhir/$validate?profile=http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient|5.0.0 HTTP/1.1
```

(`%23` is the encoded `#` of `ch.fhir.ig.ch-core#5.0.0`.)

## Validate without giving the profile

Use the type-level operation `[base]/{Type}/$validate`. The profile is then taken from `meta.profile` of the resource,
or the base profile of the type is used:

```http
POST /matchboxv3/fhir/Patient/$validate HTTP/1.1
Content-Type: application/fhir+json

{ "resourceType": "Patient", "meta": { "profile": [ "http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient" ] }, ... }
```

The type-level operation also accepts the resource wrapped in a `Parameters` resource, as FHIR clients send it. The
rules for choosing the profile are in the [reference](../reference/operations.md#validate-type-level).

## Change the validation settings for one request

Every validator setting can be given as a query parameter, e.g. to validate against another terminology server or to
skip the invariants:

```http
POST /matchboxv3/fhir/$validate?profile=http://hl7.org/fhir/StructureDefinition/Patient&txServer=http://tx.fhir.org&noInvariants=true HTTP/1.1
```

The list of settings is in the OperationDefinition of `$validate` (`GET [base]/OperationDefinition/-s-validate`); the
default values are set in the [configuration](../reference/configuration.md#validation-parameters).

## Validate in the web interface

Open the validation page of the web interface (`/matchboxv3/validate`), paste or upload the resource, choose the
profile and start the validation.

To share a validation, build a link that opens the page with the resource and the parameters already filled in: encode
the resource in base64url and add it to the fragment of the URL, e.g.

```
https://test.ahdis.ch/matchboxv3/validate#resource=<base64url>&profile=http%3A%2F%2Fhl7.org%2Ffhir%2FStructureDefinition%2FPatient
```

The parameters are listed in the [web interface reference](../reference/web-interface.md#validation-page-url).

## Validate from other tools

- From Java code, without a server: [Use matchbox-engine](use-matchbox-engine.md).
- From the IHE Gazelle EVS Client: [Integrate with Gazelle](integrate-gazelle.md).
- From the Interoperability Test Bed: [Integrate with ITB](integrate-itb.md).
- From an AI assistant: [Connect an MCP client](connect-mcp-client.md).
- From your own web application: the blog post
  [One verdict for IPS and ODH](https://www.matchbox.health/blog/2026/matchbox-ihe-na-connectathon/) shows a
  Connectathon validator built on Matchbox, which validates a document against two implementation guides in one run.

## Related

- [Suppress validation issues](suppress-validation-issues.md)
- [Configure a terminology server](configure-terminology-server.md)
- [Enable the AI analysis of validation results](enable-ai-analysis.md)
