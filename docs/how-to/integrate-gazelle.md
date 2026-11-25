# Integrate with Gazelle

Matchbox implements the validation service API of the
[IHE Gazelle Testing Platform](https://www.ihe-europe.net/testing-IHE/gazelle), so that the Gazelle tools can use it to
validate FHIR resources. The endpoints are listed in the [Gazelle API reference](../reference/gazelle-api.md); the
blog post [How we implemented the Gazelle EVS Client's new validation API in Matchbox](https://www.ahdis.ch/en/blog/2024/matchbox-gazelle-integration)
explains how the integration came about.

## Add Matchbox to the EVS Client

1. In `Administration → Validations services`, create a new validation service with the following parameters:
     - Validator Type: _DEFAULT_
     - Name: as you want
     - Stylesheet report location: any value will do
     - Target endpoint: _[server]/gazelle_ (e.g. `https://test.ahdis.ch/matchboxv3/gazelle`)
     - Support compression: _YES_
     - Is available: _YES_
     - [Example screenshot](../assets/evsclient_validation_service.png)
2. In `Administration → Referenced standards`, create a new standard:
     - Display name: as you want
     - Validator Type: _DEFAULT_
     - Validation filter: optional, can be used to filter the profiles by their IG identifier, e.g. _ch.fhir.ig.ch-core_
     - In _Available validation services_, add the previously created validation service by clicking on the "plus" icon.
     - [Example screenshot](../assets/evsclient_referenced_standard.png)
3. In `Administration → Menu Configuration`, add your new standard to an existing menu.
     - [Example screenshot](../assets/evsclient_menu.png)
4. You can now validate your resources: the new standard appears in the menu.

## Add Matchbox to Gazelle Maestro

Gazelle Maestro uses version 2 of the validation service API.

1. Check that Maestro can reach Matchbox: from the Maestro host, the metadata of the validation service must be
   returned.

    ```bash
    curl https://test.ahdis.ch/matchboxv3/gazelle/metadata
    ```

    The metadata declares two interfaces: `ValidationInterface` 1.0.0 (v1, for the EVS Client) and
    `Validation Service API` 2.0.0 (v2), whose binding is the base URL `[server]/gazelle`.
2. Register Matchbox in Maestro as a validation service of API version 2, with the base URL _[server]/gazelle_ (e.g.
   `https://test.ahdis.ch/matchboxv3/gazelle`). Maestro adds the paths of the API itself:
     - the profiles are read from `[server]/gazelle/validation/v2/profiles`;
     - the validations are sent to `[server]/gazelle/validation/v2/validate`.
3. In a test definition, choose the profile to validate against. Its id is the canonical URL of the profile with the
   version of its implementation guide (`http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient|5.0.0`); the
   profiles of the current version of an implementation guide are also listed without a version, so that a test
   definition doesn't have to be changed when the guide is updated.
4. Bind the item to validate to the input `contentToValidate`: it is the only input of every profile, and the one
   Maestro uses when a profile declares no input. The item is the FHIR resource, in JSON or XML.

Matchbox sends the profile list with an `ETag`: Maestro only downloads it again when the installed implementation guides
have changed. The request and the report are described in the [Gazelle API reference](../reference/gazelle-api.md).

The tutorial [Testing FHIR implementations: from validation to test scenarios](https://www.ahdis.ch/en/blog/2026/fhir-testing-tutorial)
has slides and hands-on exercises that use Matchbox with Gazelle Maestro, on the European Patient Summary.

## Configure Matchbox for Gazelle

The sample configuration [`with-gazelle`](https://github.com/ahdis/matchbox/tree/main/matchbox-server/with-gazelle)
shows a setup for a Connectathon. For a Gazelle setup, configure Matchbox as follows.

**Make the server read-only.** The Gazelle tools only list profiles and validate, so nothing needs to change the state
of the server; see [Read-only mode](secure-production-deployment.md#read-only-mode).

```yaml
matchbox:
  fhir:
    context:
      httpReadOnly: true
```

**Install the implementation guides in the configuration.** Gazelle offers every profile that Matchbox can validate
against: all the StructureDefinitions of the installed implementation guides, except the data types and the extensions.
To change the profiles offered to Gazelle, change the installed implementation guides. The `validationFilter` of a
referenced standard in the EVS Client then narrows the list for a menu.

```yaml
hapi:
  fhir:
    implementationguides:
      fhir_r4_core:
        name: hl7.fhir.r4.core
        version: 4.0.1
        url: classpath:/hl7.fhir.r4.core.tgz
      mhd:
        name: ihe.iti.mhd
        version: 4.2.1
```

See [Load implementation guides](load-implementation-guides.md).

**Create the engines at startup.** The first validation against an implementation guide creates its validation engine,
which can take from a few seconds to a few minutes. List the guides that are used during the test session in
`igsPreloaded`, so that their engines are created at startup and kept in memory (see
[Validation engines](../explanation/validation-engines.md)). Each engine takes memory: see
[Memory and performance](../explanation/memory-and-performance.md).

```yaml
matchbox:
  fhir:
    context:
      igsPreloaded:
        - ihe.iti.mhd#4.2.1
        - ihe.iti.pixm#3.0.4
```

While an engine is created after a restart or a reinstallation, the Gazelle API answers with the status `503` and the
header `Retry-After: 5`.

**Configure the terminology server.** Without a terminology server (`txServer: n/a`, the default), the codes of
external code systems such as SNOMED CT or LOINC are not checked. The `with-gazelle` configuration uses
`http://tx.fhir.org`; use a server of your network if the test session must not depend on the internet. See
[Configure a terminology server](configure-terminology-server.md).

**Suppress the known false positives.** Issues that are known to be wrong for an implementation guide can be removed
from the results with `suppressError` and `suppressWarnInfo`, as in the `with-gazelle` configuration; see
[Suppress validation issues](suppress-validation-issues.md).

**Set the public address.** Set `hapi.fhir.server_address` to the address through which the Gazelle tools reach
Matchbox, e.g. `https://gazelle.ihe.net/matchboxv3/fhir`; see
[Put Matchbox behind a reverse proxy](secure-production-deployment.md#put-matchbox-behind-a-reverse-proxy).
