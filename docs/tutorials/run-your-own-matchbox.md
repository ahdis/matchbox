# Run your own Matchbox

In this tutorial, you will start Matchbox on your computer with Docker, install an implementation guide in it, and
validate a resource against one of its profiles. You will use the
[International Patient Summary (IPS)](https://hl7.org/fhir/uv/ips/) implementation guide.

You need:

- [Docker](https://docs.docker.com/get-docker/) with at least 4 GB of memory available;
- an internet connection (Matchbox downloads the implementation guide from the FHIR package server);
- `curl` in a terminal.

## Step 1: start Matchbox

Start a container from the Matchbox image:

```bash
docker run -d --name matchbox -p 8080:8080 -m 4g ghcr.io/ahdis/matchbox:latest
```

Follow its logs:

```bash
docker logs --follow matchbox
```

Matchbox is ready when the log shows `Finished engines during startup`, which takes about a minute. (The line
`A FHIR has been lit on this server` appears earlier: the server is not ready yet at that point.) Then stop following
the logs with Ctrl+C.

If the container stops by itself during the startup (`docker ps -a` shows `Exited (137)`), Docker doesn't have enough
memory: give Docker at least 4 GB (in Docker Desktop: _Settings → Resources_).

Open [http://localhost:8080/matchboxv3/](http://localhost:8080/matchboxv3/) in your browser: this is the web interface
of your own Matchbox. The FHIR API is at `http://localhost:8080/matchboxv3/fhir`; check it with:

```bash
curl http://localhost:8080/matchboxv3/fhir/metadata
```

## Step 2: tell Matchbox which implementation guide to install

Matchbox reads its configuration from the `/config` directory of the container. Create a directory `config` with a file
`application.yaml`:

```yaml
hapi:
  fhir:
    implementationguides:
      fhir_r4_core:
        name: hl7.fhir.r4.core
        version: 4.0.1
        url: classpath:/hl7.fhir.r4.core.tgz
      ips:
        name: hl7.fhir.uv.ips
        version: 1.1.0
matchbox:
  fhir:
    context:
      txServer: http://tx.fhir.org
```

This configuration says that Matchbox installs FHIR R4 (from a package built into Matchbox) and IPS 1.1.0 (from the FHIR
package server), and uses the terminology server `tx.fhir.org` to check codes.

## Step 3: restart Matchbox with your configuration

Remove the first container and start a new one, with your `config` directory mounted at `/config`:

```bash
docker rm -f matchbox
docker run -d --name matchbox -p 8080:8080 -m 4g -v "${PWD}/config:/config" ghcr.io/ahdis/matchbox:latest
docker logs --follow matchbox
```

This time, the log shows that the IPS package and its dependencies are downloaded and installed (lines starting with
`Parsing package … from https://packages2.fhir.org/packages/hl7.fhir.uv.ips/1.1.0`), and then that the validation
engines are created. Wait again for `Finished engines during startup`.

In the web interface, open the page of the implementation guides: `hl7.fhir.uv.ips#1.1.0` is in the list.

## Step 4: validate against an IPS profile

The [IPS Patient](https://hl7.org/fhir/uv/ips/STU1.1/StructureDefinition-Patient-uv-ips.html) profile requires a name
and a birth date. Validate a patient without a birth date:

```bash
curl -X POST "http://localhost:8080/matchboxv3/fhir/\$validate?profile=http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips" \
  -H "Content-Type: application/fhir+json" \
  -H "Accept: application/fhir+json" \
  -d '{"resourceType": "Patient", "name": [{"family": "Test", "given": ["Anna"]}]}'
```

The result contains an error:

> Patient.birthDate: minimum required = 1, but only found 0 (from http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips|1.1.0)

It also contains a `warning` (_Constraint failed: dom-6: 'A resource should have narrative for robust management'_):
a warning is a recommendation, it doesn't make the validation fail.

Add `"birthDate": "1980-01-01"` to the resource and validate again: the error is gone and the validation passes. The
first issue of the `OperationOutcome` shows the setup of the validation: the version of the profile
(`Patient-uv-ips|1.1.0`) and the packages that were loaded, among them `hl7.fhir.uv.ips#1.1.0`. The second validation
is also faster than the first one, which had to prepare the validation engine.

## Step 5: clean up

Stop and remove the container:

```bash
docker rm -f matchbox
```

## What you have learned

- Matchbox runs as a Docker container, with a FHIR API under `/matchboxv3/fhir` and a web interface under `/matchboxv3/`.
- Its configuration is an `application.yaml` file in the `/config` directory.
- The implementation guides listed in `hapi.fhir.implementationguides` are installed when Matchbox starts, and their
  profiles can then be used for validation.

## Next steps

- [Load implementation guides](../how-to/load-implementation-guides.md) from files, without internet access.
- [Run Matchbox with Docker](../how-to/run-with-docker.md) for a durable setup (memory, database, health checks).
- Read why Matchbox [creates one engine per implementation guide](../explanation/validation-engines.md).
