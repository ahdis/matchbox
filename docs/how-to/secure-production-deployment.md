# Secure a production deployment

This guide lists the settings to use when Matchbox runs in production, e.g. as a validation service. The parameters are
described in the [configuration reference](../reference/configuration.md).

## Read-only mode

Enable the read-only mode, so that the state of the server can't be changed through the API:

```yaml
matchbox:
  fhir:
    context:
      httpReadOnly: true
```

In read-only mode:

1. The following FHIR interactions are rejected: `CREATE`, `DELETE`, `UPDATE`, `PATCH`, `UPDATE_REWRITE_HISTORY`,
   `TRANSACTION`, `BATCH`, `ADD_TAGS`, `DELETE_TAGS`, `META_ADD`, `META_DELETE`.
2. The operation [`$install-npm-package`](../reference/operations.md#install-npm-package) is disabled.
3. The [auto-installation of implementation guides](../reference/operations.md#auto-install-ig) in `$validate` is
   disabled.

The implementation guides can then only be installed through the configuration, see
[Load implementation guides](load-implementation-guides.md).

## Don't use the development mode

Keep `onlyOneEngine` disabled (the default), so that conformance resources can't be changed at runtime and each
implementation guide is validated with its own dependencies. See [Validation engines](../explanation/validation-engines.md).

## Avoid calls to the internet

- Install the implementation guides from files or [in the image](build-custom-image.md), with their dependencies.
- Use the internal terminology server or a terminology server of your network, see
  [Configure a terminology server](configure-terminology-server.md).
- Keep the SSRF protection enabled (`ssrfProtectionEnabled: true`, the default): it limits the network access of the
  validator, see the [security notes of the validator](https://github.com/hapifhir/org.hl7.fhir.core/blob/master/SECURITY.md#network-access).

## Limit the request size

Matchbox 5 doesn't limit the size of the FHIR request bodies: its embedded Tomcat only limits form posts
(`server.tomcat.max-http-form-post-size`). Limit the size in the [reverse proxy](#put-matchbox-behind-a-reverse-proxy)
instead, to the size of the largest resources you validate. The setting `server.undertow.max-http-post-size` of
Matchbox 4 isn't used anymore.

## Put Matchbox behind a reverse proxy

Matchbox serves plain HTTP. Put it behind a reverse proxy (nginx, Traefik, a Kubernetes ingress, …) that terminates
TLS, and don't publish the port of Matchbox itself.

**Set the public address.** The FHIR API writes its own address in the responses (e.g. the `fullUrl` of the search
results and the paging links). Set it to the address that the clients use:

```yaml
hapi:
  fhir:
    server_address: https://fhir.example.org/matchboxv3/fhir
```

**Forward the original host and scheme.** Other parts of the server build their links from the request, e.g. the
binding URL in the [Gazelle metadata](../reference/gazelle-api.md#metadata). Have the proxy send the
`X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-For` headers, and let Spring Boot use them:

```yaml
server:
  forward-headers-strategy: native
```

Only enable it when Matchbox is reachable through the proxy alone: otherwise a client can send these headers itself.

**Keep the same path.** The web interface is served for the context path of the server (`/matchboxv3` by default), so
the proxy must not rewrite the path. To serve Matchbox at another path, change the context path of the server and
everything that contains it:

```yaml
server:
  servlet:
    context-path: /matchbox
hapi:
  fhir:
    server_address: https://fhir.example.org/matchbox/fhir
```

In Docker, also set the environment variable `HEALTHCHECK_URL` (here
`http://localhost:8080/matchbox/actuator/health`), otherwise the container is reported as unhealthy; see the
[Docker image reference](../reference/docker-image.md).

**Allow long requests.** The first validation against an implementation guide creates its engine, which can take a few
minutes, and large documents take a while to validate too. Raise the read timeout of the proxy accordingly (nginx:
`proxy_read_timeout`, 60 seconds by default), or create the engines at startup with `igsPreloaded` (see
[Validation engines](../explanation/validation-engines.md)).

**Limit the request size.** The limit of the proxy is the only one, as Matchbox has none, and it is often low (nginx:
`client_max_body_size`, 1 MB by default). Set it to the size of the largest resources you validate.

An example for nginx:

```nginx
location /matchboxv3/ {
    proxy_pass http://matchbox:8080/matchboxv3/;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 300s;
    client_max_body_size 5m;
}
```

If you expose the [MCP server](../reference/mcp-server.md), turn off the buffering of the responses for its path
(nginx: `proxy_buffering off`), so that streamed responses reach the client at once.

## Restrict access

Matchbox has no authentication or authorization of its own: whoever reaches it can use all its endpoints. Restrict the
access at the network level (firewall, private network, Kubernetes network policies) or in the reverse proxy (client
certificates, an authenticating proxy, IP allow lists).

**Expose only what you need.** All the endpoints are under the context path (`/matchboxv3` by default):

| Path                        | Content                                                                    | Expose                                                       |
|-----------------------------|----------------------------------------------------------------------------|--------------------------------------------------------------|
| `/fhir`                     | The FHIR API (`$validate`, `$transform`, the conformance resources, …)     | To the clients that validate or transform                    |
| `/` (other paths)           | The [web interface](../reference/web-interface.md)                        | To the people who use it, if any                             |
| `/tx`                       | The internal terminology server                                            | Only if used externally (what you probably shouldn't do)   |
| `/gazelle`                  | The [Gazelle validation API](../reference/gazelle-api.md)                  | To the Gazelle tools, if any                                 |
| `/itb`                      | The [ITB test services](../reference/itb-api.md)                          | To the Interoperability Test Bed, if any                     |
| `/mcp`                      | The [MCP server](../reference/mcp-server.md), when enabled                 | To the MCP clients, if any                                   |
| `/actuator`                 | The Spring Boot Actuator endpoints                                         | Never publicly                                               |

**Limit the Actuator endpoints.** The default configuration exposes all the Actuator endpoints
(`management.endpoints.web.exposure.include: "*"`), among them `heapdump` (a dump of the memory, with the secrets it
contains), `env`, `configprops` and `loggers` (which changes the log levels). Expose only the ones you use:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

Keep `health`: the health check of the Docker image and the Kubernetes probes use it (see
[Monitor Matchbox](monitor-matchbox.md#health-checks)).

**Restrict CORS.** Browsers allow web pages of any origin to call Matchbox (`hapi.fhir.cors.allowed_origin: "*"`). The
web interface of Matchbox doesn't need CORS, as it is served by Matchbox itself. If other web applications call Matchbox
from the browser, list their origins (the values are
[origin patterns](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/cors/CorsConfiguration.html#setAllowedOriginPatterns(java.util.List))):

```yaml
hapi:
  fhir:
    cors:
      allowed_origin:
        - "https://app.example.org"
        - "https://*.example.org"
```

The list replaces the default one. CORS only restricts browsers: it doesn't replace the restrictions above.

## Protect secrets

Matchbox can hold the API key of the LLM, the credentials of the terminology server and the password of the database.

**Give the secrets as environment variables or mounted files**, not in a configuration file of the image or of a
repository:

- in environment variables, e.g. `MATCHBOX_FHIR_CONTEXT_LLM_APIKEY` and `SPRING_DATASOURCE_PASSWORD`, filled from the
  secret store of your platform (Docker Compose `env_file`, Kubernetes `secretKeyRef`, …);
- or in files, e.g. Docker or Kubernetes secrets, that Spring Boot reads as a
  [configuration tree](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.files.configtree):
  each file is a parameter, its name is the name of the parameter and its content is the value.

    ```yaml
    spring:
      config:
        import: optional:configtree:/run/secrets/
    ```

    With this configuration, the file `/run/secrets/matchbox.fhir.context.llm.apiKey` sets the API key of the LLM.

Don't copy secrets into a [custom image](build-custom-image.md): anyone who can pull the image can read them.

**Protect `fhir-settings.json`.** The credentials of the terminology servers are in the
[validator settings file](../reference/configuration.md#validator-settings-file) (`/config/fhir-settings.json` in
Docker). Mount it read-only, from a secret if your platform has them, and don't leave it readable by the other users of
the host:

```bash
docker run -v $(pwd)/fhir-settings.json:/config/fhir-settings.json:ro ghcr.io/ahdis/matchbox:latest
```

The file must stay readable by the user that runs Matchbox in the container (the user `matchbox` of the image).

**Prefer the server's LLM key to the request parameter.** The `$validate` parameter `llmApiKey` is sent in the URL,
which proxies and access logs commonly record. Configure the key on the server instead (see
[Enable the AI analysis of validation results](enable-ai-analysis.md)).

**Don't expose the Actuator endpoints** `heapdump` and `env`, see [Restrict access](#restrict-access).
