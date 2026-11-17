# Docker image

matchbox-server is published as a Docker image. See [Run Matchbox with Docker](../how-to/run-with-docker.md) to use it.

## Image

| Property       | Value                                                                                   |
|----------------|-----------------------------------------------------------------------------------------|
| Image          | `europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox`                                   |
| Tags           | `vX.Y.Z` for each release (e.g. `v4.1.21`), `vX.Y.Z-rcN` for release candidates, `latest` for the latest release (not for release candidates) |
| Platforms      | `linux/amd64`, `linux/arm64`                                                            |
| Exposed port   | `8080`                                                                                  |
| User           | `matchbox` (not root)                                                                   |
| Application    | `/matchbox.jar`                                                                         |

The image contains no implementation guide besides the packages [bundled with Matchbox](matchbox-engine-api.md#bundled-packages).

## Paths

| Path                         | Content                                                                                                  |
|------------------------------|----------------------------------------------------------------------------------------------------------|
| `/config/application.yaml`   | The [configuration](configuration.md) of Matchbox (optional).                                            |
| `/config/fhir-settings.json` | The [settings file of the validator](https://confluence.hl7.org/display/FHIR/Using+fhir-settings.json) (optional). |
| `/database`                  | The default H2 database.                                                                                 |

## Environment variables

| Variable           | Default value                                                                    | Description                                                                                         |
|--------------------|----------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `JDK_JAVA_OPTIONS` | `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication` | The [options of the JVM](#jvm-options).                                                             |
| `HEALTHCHECK_URL`  | `http://localhost:8080/matchboxv3/actuator/health`                               | The URL called by the health check of the image. Change it if you change the port or the context path. |

All the [configuration parameters](configuration.md) can be given as environment variables too (e.g.
`MATCHBOX_FHIR_CONTEXT_TXSERVER`).

## Arguments

The arguments after the image name are passed to Matchbox as Spring Boot arguments, e.g.
`--matchbox.fhir.context.onlyOneEngine=true`. The Docker options (`-v`, `-e`, `-m`, …) go before the image name.

| Argument                                 | Description                                                                                                         |
|------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `--hapi.fhir.only_install_packages=true` | Install the configured implementation guides into the database and stop (see [Build an image with pre-installed packages](../how-to/build-custom-image.md)). |

## JVM options {: #jvm-options}

The image sets the JVM options through the `JDK_JAVA_OPTIONS` environment variable.

| Default option                 | Effect                                                                                                                                                     |
|--------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `-XX:MaxRAMPercentage=70`      | The maximum heap is 70% of the memory limit of the container: a container with a limit of 4 GB (`-m 4g`) gets a heap of 2.8 GB. Without a memory limit, the heap is sized from the memory of the host. |
| `-XX:+ExitOnOutOfMemoryError`  | The JVM exits on the first `OutOfMemoryError` instead of continuing in an undefined state, so that the container can be restarted.                        |
| `-XX:+UseStringDeduplication`  | The garbage collector merges identical strings in the background, which reduces the memory needed by the validation engines.                              |

Setting `JDK_JAVA_OPTIONS` replaces the default: include a heap setting (`-XX:MaxRAMPercentage` or `-Xmx`),
`-XX:+ExitOnOutOfMemoryError` and `-XX:+UseStringDeduplication` together with any other option you add. Otherwise, the
JVM uses its default of 25% of the container memory and keeps running after an `OutOfMemoryError`.

`-Xmx` takes precedence over `-XX:MaxRAMPercentage`. A fixed `-Xmx` doesn't follow the memory limit of the container:
keep it well below the limit, because Matchbox also needs a substantial amount of memory outside the heap; otherwise the
container is killed when it exceeds its limit.

The recommended memory limit and the reasons for these defaults are in
[Memory and performance](../explanation/memory-and-performance.md).

## Health check

The image declares a `HEALTHCHECK` that calls `HEALTHCHECK_URL` every 10 seconds (timeout 3 seconds, start period
30 seconds, 3 retries). Kubernetes ignores it: use probes on the
[health endpoints](../how-to/monitor-matchbox.md#health-checks) instead.

## Sample configurations

The repository contains sample configurations in the
[`with-*` directories](https://github.com/ahdis/matchbox/tree/main/matchbox-server) of `matchbox-server`:

| Directory        | Content                                                                                      |
|------------------|----------------------------------------------------------------------------------------------|
| `with-settings`  | A configuration with a `fhir-settings.json` file                                             |
| `with-oneengine` | Development mode (`onlyOneEngine`), FHIR R4 only                                             |
| `with-postgres`  | PostgreSQL database with docker-compose, no implementation guide                             |
| `with-preload`   | The Swiss implementation guides of the public test server, with PostgreSQL and docker-compose |
| `with-ch`        | Swiss CH Core and CH EPR FHIR                                                                |
| `with-elm`       | Swiss CH ELM (laboratory reporting)                                                          |
| `with-eprik`     | IPS, EU laboratory, CH Core, CH Lab Report and CH ELM                                        |
| `with-alis`      | Swiss CH ALIS                                                                                |
| `with-ips`       | International Patient Summary (IPS)                                                          |
| `with-cda`       | CDA logical model with FHIR R4                                                               |
| `with-cda-r5`    | CDA logical model with FHIR R5                                                               |
| `with-gazelle`   | IHE profiles (MHD, PIXm, PDQm, BALP, …) and IPS, for the IHE Gazelle integration             |
| `with-ca`        | Canadian PS-CA, with a terminology server that requires authentication                       |
| `with-ans`       | French ANS and MyHealth@EU laboratory                                                        |
| `with-fr`        | French terminology server                                                                    |
| `with-eu`, `with-it`, `with-oo`, `with-who` | Other national and project configurations                              |
