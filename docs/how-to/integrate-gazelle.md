# Integrate with Gazelle

Matchbox implements the validation service API of the
[IHE Gazelle Testing Platform](https://www.ihe-europe.net/testing-IHE/gazelle), so that the Gazelle tools can use it to
validate FHIR resources. The endpoints are listed in the [Gazelle API reference](../reference/gazelle-api.md).

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

!!! abstract "To write"
    How to register Matchbox as a validation service (API v2) in Gazelle Maestro: base URL `[server]/gazelle`, the
    `contentToValidate` input, how profiles are listed. Add screenshots like for the EVS Client.

## Configure Matchbox for Gazelle

The sample configuration [`with-gazelle`](https://github.com/ahdis/matchbox/tree/main/matchbox-server/with-gazelle)
shows a setup for a Connectathon.

!!! abstract "To write"
    Recommended settings for a Gazelle setup: read-only mode, pre-loaded IGs (`igsPreloaded`), terminology server,
    which IGs are offered to Gazelle (all installed profiles are listed).
