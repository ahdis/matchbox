# Web interface

matchbox-server contains a web interface, served under the context path of the server (by default `/matchboxv3/`).

## Pages

| Page                  | Path                              | Purpose                                                                                      |
|-----------------------|-----------------------------------|----------------------------------------------------------------------------------------------|
| Home                  | `/matchboxv3/`                    | Entry page                                                                                   |
| Validate              | `/matchboxv3/validate`            | Validate a resource against a profile and show the result                                    |
| Implementation guides | `/matchboxv3/igs`                 | List, install and remove implementation guides                                               |
| Mapping language      | `/matchboxv3/mappinglanguage`     | Edit a map in the FHIR mapping language and run it                                           |
| Transform             | `/matchboxv3/transform`           | Transform a resource with an installed StructureMap                                          |
| Capability statement  | `/matchboxv3/CapabilityStatement` | Show the CapabilityStatement of the server                                                   |
| Settings              | `/matchboxv3/settings`            | Settings of the web interface                                                                |
| Statistics            | `/matchboxv3/statistics`          | Statistics of the stored validation results (requires `matchbox.validation.save-statistics`) |

The pages call the FHIR API of the same server, so they can do what the API allows and nothing more. Two settings of
the server change what is available:

| Page                  | Read-only mode (`httpReadOnly: true`)                    | Without the `onlyOneEngine` mode |
|-----------------------|----------------------------------------------------------|----------------------------------|
| Validate              | the implementation guides aren't installed automatically | available                        |
| Implementation guides | install, update and delete are hidden                    | available                        |
| Mapping language      | not available (creating a StructureMap is rejected)      | not available (HTTP 405)         |
| Transform             | available                                                | available                        |

### Home

Links to the validation, implementation guides and settings pages, the version of Matchbox, and links to other tools:
the [FHIRPath Lab](https://fhirpath-lab.com/fml) (to run maps on a Matchbox server) and the terminology server
registry of tx.fhir.org.

### Validate

Validates a resource with [`$validate`](operations.md#validate):

1. Load the resource: drop a file or select it. The resource type and id are shown.
2. Choose the **profile**. The list contains the profiles that the server accepts (the `targetProfile`s of the
   OperationDefinition), with their implementation guide and version; type to filter it. For a document `Bundle`, the
   _Proposed profiles_ found with [`$get-profiles`](operations.md#get-profiles) are listed, and can be selected with a
   click. When the profile is unlocked, it is taken from `meta.profile` of each new resource; lock it to keep it.
3. Optionally, choose the **implementation guide**: _Automatic selection_, or a specific version of an installed one
   (the `ig` parameter).
4. Click _Validate_.

The button with the gear shows the validation parameters of the server (the parameters of the OperationDefinition),
which can be changed for the next validations.

Each validation is added to the _Validation history_ (kept in the browser tab until it is reloaded): select an entry to
show its result, remove it, or copy a [direct link](#validation-page-url) to it. The result lists the issues by
severity; click an issue to go to its location in the resource. The editor shows the validated resource or the
`OperationOutcome`. If an [LLM](configuration.md#llm) is configured, _Analyze Operation Outcome with AI_ explains the
issues.

### Implementation guides

- **Installed**: the implementation guides installed on the server, with their package id, version and title. Click a
  row to select it.
- **Browse the package registry**: search [packages2.fhir.org](https://packages2.fhir.org) by package name, FHIR
  version and publication date. Select a package to choose one of its versions and install (or reinstall) it.
- **Install an ImplementationGuide**: install a package by its id and version (_Upload_), from the package registry
  or, if it isn't published there, from a URL. With an installed implementation guide selected, _Update_ installs it
  again with the given id, version and URL, and _Delete_ removes it and its conformance resources.

The result of the last action is shown as an `OperationOutcome`. These actions are hidden when the server doesn't
offer `$install-npm-package` (read-only mode).

### Mapping language

An editor for maps in the FHIR mapping language, see
[Use the mapping editor of the web interface](../how-to/transform-with-fml.md#use-the-mapping-editor-of-the-web-interface).
Paste or upload a source resource (_Source_) and a map: one second after each change, the map is stored on the server
(`POST StructureMap`) and the source is transformed with it (_Transformed_). It needs a server in the `onlyOneEngine`
mode.

### Transform

Transforms a resource with [`$transform`](operations.md#transform):

1. Upload the resource to transform.
2. Choose the map: _By reference_, from the StructureMaps installed on the server (type to filter), or
   _By uploading_ a StructureMap in JSON or XML.
3. Optionally, upload a StructureDefinition, or a `Bundle` of them, that the map uses.
4. Click _Transform_: the result is shown in _Transformed_, or the errors of the transformation.

A map or model that is uploaded is only used for the transformation, it isn't stored. The page works in all modes.

### CapabilityStatement

Shows the CapabilityStatement of the server (`GET [base]/metadata`) in JSON.

### Statistics

Shown only when the server stores the validation results (`matchbox.validation.save-statistics`). Lists the stored
`OperationOutcome`s with their date, profile, implementation guide, duration and number of issues, and filters them by
date, severity of the issues, implementation guide and profile. Click a row to show the whole `OperationOutcome`.

### Settings

Shows the version of Matchbox and a link to the source code. There are no settings to change.

## Validation page URL {: #validation-page-url}

The validation page reads its input from the fragment of the URL (the part after `#`). The parameters are written as
in a query string:

```
/matchboxv3/validate#resource=…&profile=…
```

| Parameter        | Card. | Description                                                                                            |
|------------------|-------|--------------------------------------------------------------------------------------------------------|
| `resource`       | 1..1  | The resource to validate, encoded in base64url.                                                        |
| `profile`        | 0..1  | The canonical URL of the profile. Default: `http://hl7.org/fhir/StructureDefinition/{resourceType}`.   |
| `filename`       | 0..1  | The file name to show for the resource.                                                                |
| other parameters | 0..1  | Any [validation parameter](configuration.md#validation-parameters) of `$validate`, e.g. `txServer`.    |

Example:

```
https://test.ahdis.ch/matchboxv3/validate#profile=http%3A%2F%2Fhl7.org%2Ffhir%2FStructureDefinition%2FBundle&txServer=http://tx.fhir.org/r4&resource=PFBhdGllbnQgeG1sbnM9Imh0dHA6Ly9obDcub3JnL2ZoaXIiPgogIDxuYW1lPgogICAgPGZhbWlseSB2YWx1ZT0iVGVzdCIvPgogIDwvbmFtZT4KPC9QYXRpZW50Pg
```
