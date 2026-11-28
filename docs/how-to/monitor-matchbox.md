# Monitor Matchbox

This guide shows how to check that Matchbox is running and how to follow its resource usage. Matchbox uses
[Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html); its endpoints are under
`/matchboxv3/actuator`.

## Health checks

Check that the server is live and ready:

```http
GET /matchboxv3/actuator/health HTTP/1.1
Accept: application/vnd.spring-boot.actuator.v3+json
```

```json
{
  "status": "UP",
  "groups": [
    "liveness",
    "readiness"
  ]
}
```

For Kubernetes probes, use the separate endpoints:

- liveness: `/matchboxv3/actuator/health/liveness`
- readiness: `/matchboxv3/actuator/health/readiness`

The Docker image has a `HEALTHCHECK` that calls the health endpoint. If you change the context path, set the
`HEALTHCHECK_URL` environment variable accordingly (see the [Docker image reference](../reference/docker-image.md)).
Kubernetes ignores the Docker `HEALTHCHECK`.

## Memory usage

Check the memory used by the JVM:

```http
GET /matchboxv3/actuator/metrics/jvm.memory.used HTTP/1.1
```

See [Memory and performance](../explanation/memory-and-performance.md) to interpret it.

## Validation statistics

Matchbox can store the result of each validation, to see later which profiles are validated and which issues are found.
Enable it in the configuration:

```yaml
matchbox:
  validation:
    save-statistics: true
```

**What is stored.** Each `$validate` request stores its `OperationOutcome` as a FHIR resource in the database, also
when the validation fails with an error. The stored resource has a new id and `meta.source` set to
`matchbox-validation`. Its first issue describes the validation: the profile and its version, the loaded packages, the
duration and the validation parameters (in the `http://matchbox.health/validation` extension). The validated resource
itself is not stored, but the messages of the validator often quote values of it (codes, identifiers, texts): treat
the statistics as data as sensitive as the validated resources.

The validations of the [Gazelle API](../reference/gazelle-api.md), of the [ITB API](../reference/itb-api.md) and of
the [HL7 validator API](../reference/core-api.md) are stored as well.

**Where to see them.** The _Statistics_ page of the web interface (`/matchboxv3/statistics`) appears when the
statistics are enabled. It lists the stored results, the latest first, and filters them by date, severity of the issues,
implementation guide and profile; see the [web interface reference](../reference/web-interface.md#statistics). The
results can also be searched through the FHIR API, with three search parameters that Matchbox adds:

```http
GET /matchboxv3/fhir/OperationOutcome?issue=error&_sort=-_lastUpdated&_count=20 HTTP/1.1
```

| Parameter | Searches                                                            |
|-----------|---------------------------------------------------------------------|
| `issue`   | The severity of an issue (`fatal`, `error`, `warning`, `information`) |
| `ig`      | The implementation guide of the validation                         |
| `profile` | The canonical URL of the profile                                    |

**How much space it takes.** Each validation adds one resource, with its history and search indexes, to the database:
from a few kilobytes for a valid resource to much more for a document with hundreds of issues. Nothing is removed
automatically, so the database grows with the number of validations. Follow its size, and prefer a
[PostgreSQL database](use-postgresql.md) to H2 for a server that validates a lot.

**How to clean it up.** The stored results can't be deleted through the API: the FHIR API of Matchbox offers neither
`DELETE` nor `$expunge` for `OperationOutcome`, whatever the configuration. To start over, stop Matchbox and remove its
database (the `database` directory of H2, or the PostgreSQL database). At the next startup, Matchbox creates a new
database and installs again the implementation guides of its configuration; the guides installed with
[`$install-npm-package`](../reference/operations.md#install-npm-package) have to be installed again.

## Matchbox metrics

Matchbox publishes its own metrics through Micrometer when `otel.instrumentation.micrometer.enabled` is `true`:

| Metric                                     | Description                                              |
|--------------------------------------------|----------------------------------------------------------|
| `matchbox.validation.count`                | Number of validated resources                            |
| `matchbox.validation.duration`             | Duration of the validations                              |
| `matchbox.transformation.count`            | Number of transformed resources                          |
| `matchbox.engines.cached.permanent.number` | Number of engines that stay in memory                    |
| `matchbox.engines.cached.transient.number` | Number of engines in the cache that expire               |
| `matchbox.engines.creating.number`         | Number of engines that are being created                 |
| `matchbox.igs.number`                      | Number of installed implementation guides                |

Read a metric with `GET /matchboxv3/actuator/metrics/{name}`.

When the metrics are enabled, the calls to the LLM of the [AI analysis](enable-ai-analysis.md) are measured too, with
the metrics of the [OpenTelemetry semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-metrics/)
for generative AI:

| Metric                             | Description                                                                     |
|------------------------------------|---------------------------------------------------------------------------------|
| `gen_ai.client.operation.duration` | Duration of the calls to the LLM, per provider and model                       |
| `gen_ai.client.token.usage`        | Number of tokens sent and received (`gen_ai.token.type`: `input` or `output`)   |

### Export the metrics with OpenTelemetry

Matchbox contains the [OpenTelemetry Spring Boot starter](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/),
disabled by default (`otel.sdk.disabled: true`). Enable it to send the metrics, with the
[OTLP](https://opentelemetry.io/docs/specs/otlp/) protocol, to an OpenTelemetry Collector or to any backend that
accepts OTLP:

```yaml
otel:
  sdk:
    disabled: false
  service:
    name: matchbox
  instrumentation:
    micrometer:
      enabled: true
  exporter:
    otlp:
      endpoint: http://otel-collector:4318
  metrics:
    exporter: otlp
  traces:
    exporter: none
  logs:
    exporter: none
```

`otel.instrumentation.micrometer.enabled` publishes the Matchbox and JVM metrics of Micrometer through OpenTelemetry.
The starter also traces the HTTP requests: set `otel.traces.exporter` to `otlp` to send the traces as well. The metrics
are sent every minute (`otel.metric.export.interval`, in milliseconds). All the parameters can be given as environment
variables, e.g. `OTEL_SDK_DISABLED=false` and `OTEL_EXPORTER_OTLP_ENDPOINT`; they are listed in the
[documentation of the starter](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/sdk-configuration/).

The blog post [Monitoring Matchbox with OpenTelemetry](https://www.matchbox.health/blog/2026/matchbox-opentelemetry/)
walks through a complete setup: the metrics, traces and logs sent to a monitoring platform, a dashboard, and alerts on
the validation load, slow validations and memory.

### Collect the metrics with Prometheus

Matchbox has no Prometheus endpoint: Prometheus can't scrape it directly. Send the metrics with OTLP instead, either:

- to an OpenTelemetry Collector with a Prometheus exporter, which Prometheus scrapes;
- or to Prometheus itself, which accepts OTLP when its receiver is enabled (`--web.enable-otlp-receiver`, Prometheus
  3.0 or later). Set the metrics endpoint to its OTLP address:

    ```yaml
    otel:
      exporter:
        otlp:
          metrics:
            endpoint: http://prometheus:9090/api/v1/otlp/v1/metrics
    ```

Prometheus replaces the dots of the metric names with underscores and adds suffixes for the unit and, for the
counters, `_total`: check the exact names in Prometheus before you write the queries.

### Build a dashboard

A dashboard for Matchbox (e.g. in Grafana) usually shows:

| Panel                       | Metric                                                                                             |
|-----------------------------|----------------------------------------------------------------------------------------------------|
| Validations per minute      | Rate of `matchbox.validation.count`                                                                |
| Validation duration         | Distribution of `matchbox.validation.duration`                                                     |
| Transformations per minute  | Rate of `matchbox.transformation.count`                                                            |
| Engines                     | `matchbox.engines.cached.permanent.number`, `matchbox.engines.cached.transient.number` and `matchbox.engines.creating.number` |
| Implementation guides       | `matchbox.igs.number`                                                                              |
| Memory                      | `jvm.memory.used` against the memory limit of the container                                        |
| LLM usage                   | `gen_ai.client.token.usage` and `gen_ai.client.operation.duration`, per model                     |

A rise of `matchbox.engines.creating.number` with long validation durations means that engines are created during the
requests: list the most used implementation guides in `igsPreloaded` (see
[Validation engines](../explanation/validation-engines.md)).

## Logs

Matchbox writes its logs to the console (standard output) only. In Docker, read them with:

```bash
docker logs -f matchbox
```

In Kubernetes, use `kubectl logs`, or the log collector of your platform. To write the logs to files too, provide your
own [Logback](https://logback.qos.ch/manual/configuration.html) configuration with `logging.config` (e.g.
`LOGGING_CONFIG=/config/logback.xml`).

### Change the log level

The level of each logger is set with `logging.level.<logger>`:

```yaml
logging:
  level:
    ch.ahdis.matchbox: DEBUG
```

or with an environment variable, where the dots become underscores: `LOGGING_LEVEL_CH_AHDIS_MATCHBOX=DEBUG`. The default
level is `INFO`.

While the server runs, the level can be changed through the `loggers` endpoint of the Actuator, if it is exposed (see
[Restrict access](secure-production-deployment.md#restrict-access)); the change is lost at the next restart:

```http
POST /matchboxv3/actuator/loggers/ch.ahdis.matchbox HTTP/1.1
Content-Type: application/json

{ "configuredLevel": "DEBUG" }
```

### Useful loggers

| Logger                                      | What it logs                                                                                            |
|---------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `ch.ahdis.matchbox`                         | Everything Matchbox does                                                                               |
| `ch.ahdis.matchbox.packages`                | The installation of the implementation guides and their dependencies                                   |
| `ch.ahdis.matchbox.util.MatchboxEngineSupport` | The creation, caching and reuse of the validation engines                                           |
| `ch.ahdis.matchbox.EngineLoggingService`    | The messages of the HL7 validator, e.g. the loading of the packages; with `DEBUG`, its detailed messages |
| `ch.ahdis.matchbox.terminology`             | The internal terminology server                                                                        |
| `fhirtest.access`                           | One line per request to the FHIR API, with its operation, parameters and user agent (`ERROR` by default) |
| `ca.uhn.fhir`                               | HAPI FHIR, e.g. the database and the REST server                                                       |

The calls to an external terminology server are not logged by these loggers: log them in a file with the `txLog`
parameter, see [Configure a terminology server](configure-terminology-server.md#troubleshoot-the-terminology-calls).

`fhirtest.access` logs the request parameters, among them an `llmApiKey` given in the URL: keep it at `ERROR` if the
logs are collected.
