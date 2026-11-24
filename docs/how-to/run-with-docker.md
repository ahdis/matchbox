# Run Matchbox with Docker

This guide shows how to run matchbox-server as a Docker container with your own configuration. The image, its
environment variables and its JVM options are described in the [Docker image reference](../reference/docker-image.md).

## Start a container

```bash
docker run -d --name matchbox -p 8080:8080 -m 4g \
  -v ${PWD}/config:/config \
  europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:latest
docker logs --follow matchbox
```

When the log shows `Finished engines during startup`, Matchbox is ready (the earlier line
`A FHIR has been lit on this server` is logged before the implementation guides are installed and the engines are
created):

- FHIR API: [http://localhost:8080/matchboxv3/fhir/metadata](http://localhost:8080/matchboxv3/fhir/metadata)
- web interface: [http://localhost:8080/matchboxv3/](http://localhost:8080/matchboxv3/)

Use a version tag (e.g. `v4.1.21`) instead of `latest` for a reproducible setup.

## Provide the configuration

The `config` directory mounted at `/config` contains:

- `application.yaml`: the configuration of Matchbox (implementation guides, terminology server, validation settings),
  see the [configuration reference](../reference/configuration.md);
- `fhir-settings.json` (optional): the [settings of the FHIR validator](https://confluence.hl7.org/display/FHIR/Using+fhir-settings.json),
  e.g. the credentials of a terminology server, see
  [Configure a terminology server](configure-terminology-server.md#authentication).

The [`with-*` directories](https://github.com/ahdis/matchbox/tree/main/matchbox-server) of the repository contain
sample configurations for different use cases (Swiss IGs, IPS, CDA, Gazelle, …).

You can also set single parameters without a file:

- as Spring Boot arguments after the image name, e.g. `--matchbox.fhir.context.onlyOneEngine=true`;
- as environment variables before the image name, e.g. `-e MATCHBOX_FHIR_CONTEXT_TXSERVER=http://tx.fhir.org`.

## Choose the mode

- For **production** (a validation service), keep the default mode (one engine per implementation guide) and enable the
  [read-only mode](secure-production-deployment.md#read-only-mode).
- For **development** (writing maps or profiles), start with `--matchbox.fhir.context.onlyOneEngine=true`: you can then
  create and update conformance resources through the API. The changes are lost when the container stops.

See [Validation engines](../explanation/validation-engines.md) for the differences.

## Set the memory

Always give the container a memory limit (`-m 4g`, or `resources.limits.memory: 4Gi` in Kubernetes): the heap of the JVM
is sized from it. 4 GB is enough for a typical setup of implementation guides. See
[Memory and performance](../explanation/memory-and-performance.md) to size it for your setup, and
[JVM options](../reference/docker-image.md#jvm-options) to change the defaults, e.g. a fixed heap:

```bash
docker run -d --name matchbox -p 8080:8080 -m 4g \
  -e JDK_JAVA_OPTIONS="-Xmx2g -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication" \
  europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:latest
```

If the container is killed during the startup or under load (`Exited (137)`, `OOMKilled`), the memory limit is higher
than the memory that Docker itself has (e.g. the Docker Desktop VM), or too low for your implementation guides.

## Restart automatically

With the default JVM options, Matchbox exits on an `OutOfMemoryError`. Let Docker restart it:

```bash
docker run -d --name matchbox --restart unless-stopped -p 8080:8080 -m 4g europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:latest
```

In Kubernetes, use liveness and readiness probes on the [health endpoints](monitor-matchbox.md#health-checks).

## Keep the data

By default, Matchbox stores the installed packages in an H2 database in `/database` inside the container, and installs
them again when a new container starts. To keep them, either mount a volume at `/database`, use
[PostgreSQL](use-postgresql.md), or [build an image with the packages pre-installed](build-custom-image.md).

## Related

- [Load implementation guides](load-implementation-guides.md)
- [Monitor Matchbox](monitor-matchbox.md)
- [Secure a production deployment](secure-production-deployment.md)
- Blog: [Matchbox: from 12 GB to 4 GB](https://www.matchbox.health/blog/2026/matchbox-memory/), why 4 GB are enough
  since Matchbox 4.1.18
