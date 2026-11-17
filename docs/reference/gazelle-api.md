# Gazelle validation API

matchbox-server implements the Validation Service API of the
[IHE Gazelle Testing Platform](https://www.ihe-europe.net/testing-IHE/gazelle). The API is specified in the
[validation-service-api](https://gitlab.inria.fr/gazelle/library/validation-service-api) project. See
[Integrate with Gazelle](../how-to/integrate-gazelle.md) to configure the Gazelle tools.

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

!!! abstract "To write"
    The request and response models of both versions (validation request, validation report), how the issues of the
    `OperationOutcome` are mapped to the report (severities, overall result), an example request and response for each
    version. Source: the package `ch.ahdis.matchbox.validation.gazelle` and
    [`gazelle.http`](https://github.com/ahdis/matchbox/blob/main/matchbox-server/gazelle.http).
