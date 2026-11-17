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

Matchbox accepts request bodies up to 100 MB. Lower or raise the limit with `server.undertow.max-http-post-size` (or the
environment variable `SERVER_UNDERTOW_MAX_HTTP_POST_SIZE`). A reverse proxy in front of Matchbox may enforce its own,
smaller limit.

## Put Matchbox behind a reverse proxy

!!! abstract "To write"
    TLS termination, `hapi.fhir.server_address` so that the links in responses use the public address, changing the
    context path (`server.servlet.context-path`) and the `HEALTHCHECK_URL`, forwarding headers, timeouts for long
    validations, request size limit of the proxy.

## Restrict access

!!! abstract "To write"
    Matchbox has no authentication of its own: restrict access at the network or proxy level. CORS settings
    (`hapi.fhir.cors.allowed_origin`, default `*`). Which endpoints to expose (FHIR API, web interface, `/tx`, `/gazelle`,
    `/mcp`, actuator endpoints, which expose all their endpoints by default).

## Protect secrets

!!! abstract "To write"
    API keys of the LLM and credentials of the terminology server: environment variables or mounted secrets instead of
    files in the image; `fhir-settings.json` permissions.
