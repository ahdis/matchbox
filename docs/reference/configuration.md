# Configuration

matchbox-server is a Spring Boot application. Its configuration parameters can be set:

- in a Spring configuration file, `application.yaml` (in the Docker image: `/config/application.yaml`);
- as environment variables (`matchbox.fhir.context.txServer` becomes `MATCHBOX_FHIR_CONTEXT_TXSERVER`);
- as command-line arguments (`--matchbox.fhir.context.txServer=…`) or Java system properties.

The default values are in the
[`application.yaml`](https://github.com/ahdis/matchbox/blob/main/matchbox-server/src/main/resources/application.yaml)
of matchbox-server. Sample configurations are in the
[`with-*` directories](https://github.com/ahdis/matchbox/tree/main/matchbox-server).

## Matchbox parameters {: #matchbox-parameters}

| Parameter                                       | Default value | Description                                                                                                                                                                                             |
|-------------------------------------------------|---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `matchbox.fhir.context.fhirVersion`             | `4.0.1`       | The FHIR version of the server.                                                                                                                                                                         |
| `matchbox.fhir.context.txServer`                | `n/a`         | The URL of the terminology server, or `n/a` for none. The internal terminology server is at `http://localhost:${server.port}/matchboxv3/tx`.                                                             |
| `matchbox.fhir.context.igsPreloaded`            | `[]`          | The list of implementation guides (`name#version`) for which an engine is created at startup and kept in memory. See [Validation engines](../explanation/validation-engines.md).                         |
| `matchbox.fhir.context.onlyOneEngine`           | `false`       | Whether the server uses a single engine for all requests (development mode). See [Validation engines](../explanation/validation-engines.md#only-one-engine).                                             |
| `matchbox.fhir.context.fetchCacheSize`          | `100`         | The number of resources each engine keeps converted for `resolve()` in FHIRPath expressions (e.g. ConceptMaps in invariants), `0` to keep none. |
| `matchbox.fhir.context.httpReadOnly`            | `false`       | Whether the server rejects the requests that would change its state. See [Read-only mode](../how-to/secure-production-deployment.md#read-only-mode).                                                     |
| `matchbox.fhir.context.suppressWarnInfo`        | `{}`          | The warning and information messages to remove from the validation results, per implementation guide. See [Suppress validation issues](../how-to/suppress-validation-issues.md).                         |
| `matchbox.fhir.context.suppressError`           | `{}`          | The errors to remove from the validation results, per implementation guide. See [Suppress validation issues](../how-to/suppress-validation-issues.md#suppress-errors).                                   |
| `matchbox.fhir.context.extensions`              | `any`         | The domains of the extensions that are accepted even if they aren't defined in the implementation guides, as a comma-separated list of URL patterns; `any` accepts all extensions.                      |
| `matchbox.fhir.context.ssrfProtectionEnabled`   | `true`        | Whether the [SSRF protection](https://github.com/hapifhir/org.hl7.fhir.core/blob/master/SECURITY.md#network-access) of the validator is enabled.                                                         |
| `matchbox.fhir.context.llm.provider`            |               | The LLM provider for the AI analysis of validation results. See [LLM](#llm).                                                                                                                            |
| `matchbox.fhir.context.llm.modelName`           |               | The LLM model for the AI analysis of validation results.                                                                                                                                                |
| `matchbox.fhir.context.llm.apiKey`              |               | The API key for the LLM provider.                                                                                                                                                                       |
| `matchbox.fhir.validation.analyzeErrorsWithLlm` | `false`       | Whether the validation results with `error` or `fatal` issues are analyzed by the LLM. Requires the LLM parameters.                                                                                      |
| `matchbox.fhir.mcp.requestAnalysisFromClient`   | `false`       | Whether the validations through the [MCP server](mcp-server.md#analysis) ask the LLM of the MCP client to analyze the result.                                                                          |
| `matchbox.validation.save-statistics`           | `false`       | Whether the `OperationOutcome` of each validation is stored; enables the _Statistics_ page of the web interface.                                                                                        |
| `spring.ai.mcp.server.enabled`                  | `false`       | Whether the [MCP server](mcp-server.md) is enabled.                                                                                                                                                     |

## Validation parameters {: #validation-parameters}

The parameters of the HL7 validator (see
[Using the FHIR Validator](https://confluence.hl7.org/spaces/FHIR/pages/35718580/Using+the+FHIR+Validator)) can be
set under `matchbox.fhir.context`. They are the default values of the [`$validate`](operations.md#validate) operation and
can be overridden in each request. A parameter has the same name in the configuration and in the request; the name is
case-sensitive and written in camel case (`checkReferences`, not `check-references`). The complete list is in the OperationDefinition of `$validate`
(`GET [base]/OperationDefinition/-s-validate`, e.g. on the
[test server](https://test.ahdis.ch/matchboxv3/fhir/OperationDefinition/-s-validate)). The most used ones:

| Parameter (`matchbox.fhir.context.…`) | Default value | Description                                                                                                                                                                                                              |
|---------------------------------------|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `txUseEcosystem`                      | `true`        | Whether the terminology servers are asked for which code systems they are authoritative.                                                                                                                                 |
| `txServerCache`                       | `true`        | Whether the responses of the terminology server are cached.                                                                                                                                                              |
| `txLog`                               |               | The path of a file in which the requests to the terminology server are logged; it must end in `.txt` or `.html`.                                                                                                         |
| `bpWarnings`                           |               | The level of the best practice warnings: `Ignore`, `Hint`, `Warning` or `Error`. Without a value, the default of the validator. |
| `resourceIdRule`                       |               | Whether the validated resource must have an id: `OPTIONAL`, `REQUIRED` or `PROHIBITED`. Without a value, the default of the validator. |
| `displayIssuesAreWarnings`            | `true`        | Whether wrong display names of codes are reported as warnings instead of errors.                                                                                                                                         |
| `disableDefaultResourceFetcher`       | `true`        | With `true`, Matchbox resolves, validates and checks the references within a Bundle (this differs from the HL7 validator). With `false`, the two following parameters apply.                                             |
| `checkReferences`                     | `false`       | With `disableDefaultResourceFetcher: false`: whether the validator follows absolute URLs of references. Invalid absolute URLs and any relative URL then cause validation errors.                                         |
| `resolutionContext`                   |               | With `disableDefaultResourceFetcher: false`: the address of the server against which the validator resolves relative references.                                                                                         |

## Implementation guides {: #implementation-guides}

The implementation guides listed in `hapi.fhir.implementationguides` are installed into the database when the server
starts. Each entry has a free key and the following properties:

| Property  | Card. | Description                                                         |
|-----------|-------|---------------------------------------------------------------------|
| `name`    | 1..1  | The name of the package, as in its `package.json`.                  |
| `version` | 1..1  | The version of the package, as in its `package.json`.               |
| `url`     | 0..1  | Where the package (the `package.tgz` of an IG) is loaded from.      |

| `url`                   | Example                                              | The package is loaded from                                      |
|-------------------------|------------------------------------------------------|-----------------------------------------------------------------|
| _(none)_                |                                                      | the FHIR package servers (packages.fhir.org), by name/version   |
| `https://` or `http://` | `https://build.fhir.org/ig/ahdis/ch-elm/package.tgz` | the given address                                               |
| `file:`                 | `file:/packages/ch.fhir.ig.ch-elm.tgz`               | the filesystem of the server (or of the container); absolute path |
| `classpath:`            | `classpath:/hl7.fhir.r4.core.tgz`                    | the classpath of Matchbox (packages built into `matchbox.jar`)  |

```yaml
hapi:
  fhir:
    implementationguides:
      fhir_r4_core:
        name: hl7.fhir.r4.core
        version: 4.0.1
        url: classpath:/hl7.fhir.r4.core.tgz
      ch-core:
        name: ch.fhir.ig.ch-core
        version: 4.0.1
      ch-elm:
        name: ch.fhir.ig.ch-elm
        version: 1.7.0
        url: https://build.fhir.org/ig/ahdis/ch-elm/package.tgz
```

Installation rules:

- A package is installed only once: if the database already contains a package with the same name and version, it isn't
  installed again. Packages with an `http` URL on `localhost` or `build.fhir.org` are installed again at every start.
- The dependencies of a package are installed too, from the database if they are there, otherwise from the FHIR package
  servers.
- `hapi.fhir.only_install_packages: true` makes Matchbox install the packages and stop (see
  [Build an image with pre-installed packages](../how-to/build-custom-image.md)).

See [Load implementation guides](../how-to/load-implementation-guides.md).

## LLM {: #llm}

| `llm.provider` | Service                                                                                                                  | `llm.modelName`                                                 |
|----------------|--------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| `openai`       | [OpenAI](https://openai.com/index/openai-api/)                                                                           | A chat model of the provider, e.g. `gpt-4o-mini`                |
| `anthropic`    | [Anthropic](https://www.anthropic.com/api)                                                                               | A chat model of the provider, e.g. `claude-3-5-sonnet-20241022` |
| `google`       | [Google AI Gemini](https://ai.google.dev/)                                                                               | A chat model of the provider                                    |
| `huggingface`  | [Hugging Face Inference Providers](https://huggingface.co/docs/inference-providers), `https://router.huggingface.co/v1` | A chat model available through the router                       |

The model name is passed to the provider as it is: Matchbox does not restrict it. The provider, the model name and the
API key are all required; if one of them is missing, the analysis is skipped and the validation result contains a
warning. They can also be given for a single request, with the `$validate` parameters `llmProvider`, `llmModelName`
and `llmApiKey`.

See [Enable the AI analysis of validation results](../how-to/enable-ai-analysis.md).

## Server parameters {: #server-parameters}

The parameters of [Spring Boot](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) and of
the [HAPI FHIR JPA server starter](https://github.com/hapifhir/hapi-fhir-jpaserver-starter) are available too. The
most used ones:

| Parameter                             | Default value                           | Description                                                                                                                               |
|---------------------------------------|-----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `server.port`                         | `8080`                                  | The port of the server.                                                                                                                   |
| `server.servlet.context-path`         | `/matchboxv3`                           | The context path of the server.                                                                                                           |
| `hapi.fhir.server_address`            |                                         | The public address of the FHIR API, used in the responses (e.g. `http://localhost:8080/matchboxv3/fhir`).                                 |
| `hapi.fhir.cors.allowed_origin`       | `*`                                     | The allowed origin patterns for CORS.                                                                                                     |
| `spring.datasource.url`               | `jdbc:h2:file:./database/h2`            | The JDBC URL of the database. See [Use a PostgreSQL database](../how-to/use-postgresql.md).                                               |
| `spring.datasource.username`          | `sa`                                    | The user of the database.                                                                                                                 |
| `spring.datasource.password`          |                                         | The password of the database.                                                                                                             |
| `spring.datasource.driverClassName`   | `org.h2.Driver`                         | The JDBC driver.                                                                                                                          |
| `spring.jpa.properties.hibernate.dialect` | `ca.uhn.fhir.jpa.model.dialect.HapiFhirH2Dialect` | The Hibernate dialect of the database.                                                                                      |
| `logging.level.<logger>`              | `root: INFO`                            | The log level of a logger, e.g. `logging.level.ch.ahdis.matchbox: DEBUG`.                                                                 |
| `otel.instrumentation.micrometer.enabled` |                                     | Whether the [Matchbox metrics](../how-to/monitor-matchbox.md#matchbox-metrics) are published.                                              |

A request body larger than `max-http-post-size` is rejected with
`RequestTooBigException: UT000020: Connection terminated as request was larger than …`.

## Complete example

```yaml
server:
  port: 8080
  servlet:
    context-path: /matchboxv3
hapi:
  fhir:
    server_address: http://localhost:8080/matchboxv3/fhir
    implementationguides:
      fhir_r4_core:
        name: hl7.fhir.r4.core
        version: 4.0.1
        url: classpath:/hl7.fhir.r4.core.tgz
      ch-elm:
        name: ch.fhir.ig.ch-elm
        version: 1.4.0
matchbox:
  fhir:
    context:
      fhirVersion: 4.0.1
      txServer: http://localhost:${server.port}/matchboxv3/tx
      httpReadOnly: true
      displayIssuesAreWarnings: false
      igsPreloaded: ch.fhir.ig.ch-elm#1.4.0
      suppressWarnInfo:
        hl7.fhir.r4.core#4.0.1:
          - "Constraint failed: dom-6:"
          - "regex:Entry '(.+)' isn't reachable by traversing forwards from the Composition"
        ch.fhir.ig.ch-elm:
          - "regex:Binding for path (.+) has no source, so can't be checked"
      llm:
        provider: anthropic
        modelName: claude-3-5-sonnet-20241022
        # apiKey: set it with the environment variable MATCHBOX_FHIR_CONTEXT_LLM_APIKEY rather than in this file
    validation:
      analyzeErrorsWithLlm: true
```

## Validator settings file

The [`fhir-settings.json`](https://confluence.hl7.org/display/FHIR/Using+fhir-settings.json) file of the HL7 validator
is read from the path given by the system property `fhir.settings.path` (in the Docker image:
`/config/fhir-settings.json`). It is used e.g. for the credentials of terminology servers.
