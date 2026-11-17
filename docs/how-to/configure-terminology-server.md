# Configure a terminology server

The validator uses a terminology server to check codes that it can't check by itself (see
[Terminology in validation](../explanation/terminology.md)). This guide shows how to choose and configure it.

## Use an external terminology server

Set its address in `matchbox.fhir.context.txServer`:

```yaml
matchbox:
  fhir:
    context:
      txServer: http://tx.fhir.org
```

`tx.fhir.org` is the terminology server used by the HL7 FHIR validator. Other servers work too, e.g.
[Ontoserver](https://ontoserver.csiro.au/) (from version 6.15.0) or a national terminology server.

## Use the internal terminology server

For a setup without any external call, use the terminology server built into matchbox-server. Its address is
`/matchboxv3/tx` on the same server:

```yaml
matchbox:
  fhir:
    context:
      txServer: http://localhost:${server.port}/matchboxv3/tx
```

The internal terminology server only knows the code systems and value sets of the installed implementation guides and
can't expand complex value sets: read its [limitations](../explanation/terminology.md#the-internal-terminology-server)
before using it.

## Validate without a terminology server

Set `txServer` to `n/a` (the default). Codes from code systems that aren't defined in the installed implementation
guides (SNOMED CT, LOINC, …) are then not checked.

## Use a terminology server that requires authentication {: #authentication}

Put the credentials in a [`fhir-settings.json`](https://confluence.hl7.org/display/FHIR/Using+fhir-settings.json) file
next to your `application.yaml` (in Docker: in the `/config` directory). See the example in
[`with-ca`](https://github.com/ahdis/matchbox/tree/main/matchbox-server/with-ca).

## Change the terminology server for one request

Add the `txServer` parameter to the `$validate` request:

```http
POST /matchboxv3/fhir/$validate?profile=...&txServer=http://tx.fhir.org HTTP/1.1
```

## Troubleshoot the terminology calls

- Log the requests to the terminology server with the `txLog` parameter (a file path ending in `.txt` or `.html`).
- Disable the cache of the terminology responses with `txServerCache: false` while testing.
- [How the validator talks to a terminology server](../explanation/terminology-server-interactions.md) describes the
  requests and the cache.

The parameters are described in the [configuration reference](../reference/configuration.md#validation-parameters).
