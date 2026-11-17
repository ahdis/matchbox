# Web interface

matchbox-server contains a web interface, served under the context path of the server (by default `/matchboxv3/`).

## Pages

| Page                  | Path                              | Purpose                                                                              |
|-----------------------|-----------------------------------|--------------------------------------------------------------------------------------|
| Home                  | `/matchboxv3/`                    | Entry page                                                                           |
| Validate              | `/matchboxv3/validate`            | Validate a resource against a profile and show the result                            |
| Implementation guides | `/matchboxv3/igs`                 | List, install and remove implementation guides                                       |
| Mapping language      | `/matchboxv3/mappinglanguage`     | Edit a map in the FHIR mapping language and run it                                   |
| Transform             | `/matchboxv3/transform`           | Transform a resource with an installed StructureMap                                  |
| Capability statement  | `/matchboxv3/CapabilityStatement` | Show the CapabilityStatement of the server                                           |
| Settings              | `/matchboxv3/settings`            | Settings of the web interface                                                        |
| Statistics            | `/matchboxv3/statistics`          | Statistics of the stored validation results (requires `matchbox.validation.save-statistics`) |

!!! abstract "To write"
    For each page: a screenshot, the available actions, and which actions are unavailable in read-only mode or outside
    the `onlyOneEngine` mode.

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
