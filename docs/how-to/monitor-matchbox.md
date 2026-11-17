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

!!! abstract "To write"
    Enable `matchbox.validation.save-statistics` to store the `OperationOutcome` of each validation; the _Statistics_
    page of the web interface. What is stored, how much space it takes, how to clean it up.

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

!!! abstract "To write"
    How to export the metrics with OpenTelemetry (`otel.sdk.disabled`, exporter settings) or scrape them with
    Prometheus; the metrics of the LLM calls (langchain4j); a sample dashboard.

## Logs

!!! abstract "To write"
    Where the logs go (`docker logs`), how to change the level (`logging.level.ch.ahdis.matchbox: DEBUG` or the
    environment variable `LOGGING_LEVEL_CH_AHDIS_MATCHBOX`), which loggers are useful (terminology calls, package
    installation).
