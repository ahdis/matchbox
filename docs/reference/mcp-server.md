# MCP server

matchbox-server contains a [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server, which lets AI
assistants use Matchbox. See [Connect an MCP client](../how-to/connect-mcp-client.md) to set it up.

## Endpoint

| Property   | Value                                                  |
|------------|--------------------------------------------------------|
| Enabled by | `spring.ai.mcp.server.enabled: true` (default `false`) |
| Endpoint   | `http(s)://<host>:<port>/matchboxv3/mcp/message`       |
| Transport  | Streamable HTTP                                        |
| Name       | `matchbox-mcp`                                         |

## Validation tools

These tools are always available when the MCP server is enabled.

| Tool                                 | Description                                                                           | Parameters                                                                                                           |
|--------------------------------------|---------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `validate-fhir-resource`             | Validates a FHIR resource or logical model against a profile; returns an OperationOutcome | `resource` (XML or JSON), `profile`, `validationparams` (optional, e.g. `txServer=http://tx.fhir.org,txUseEcosystem=false`) |
| `list-fhir-profiles-to-validate-for` | Lists the profiles available for validation                                           |                                                                                                                      |
| `list-fhir-igs`                      | Lists the implementation guides available for validation                              |                                                                                                                      |
| `list-validation-parameters`         | Lists the additional parameters available for validation                              |                                                                                                                      |
| `get-profiles-for-document-bundle`   | Returns the profiles that match a FHIR document Bundle                                | the Bundle                                                                                                           |

## FHIR resource tools

These tools give access to the conformance resources of the server. They are only available in the
[`onlyOneEngine` mode](../explanation/validation-engines.md#only-one-engine); the tools that change resources
additionally require that the read-only mode is disabled.

| Tool                               | Description                              | Requires                                    |
|------------------------------------|------------------------------------------|---------------------------------------------|
| `read-fhir-resource`               | Reads a FHIR resource                    | `onlyOneEngine: true`                       |
| `search-fhir-resources`            | Searches FHIR resources                  | `onlyOneEngine: true`                       |
| `create-fhir-resource`             | Creates a FHIR resource                  | `onlyOneEngine: true`, `httpReadOnly: false` |
| `update-fhir-resource`             | Updates a FHIR resource                  | `onlyOneEngine: true`, `httpReadOnly: false` |
| `conditional-update-fhir-resource` | Updates a FHIR resource conditionally    | `onlyOneEngine: true`, `httpReadOnly: false` |
| `patch-fhir-resource`              | Patches a FHIR resource                  | `onlyOneEngine: true`, `httpReadOnly: false` |
| `conditional-patch-fhir-resource`  | Patches a FHIR resource conditionally    | `onlyOneEngine: true`, `httpReadOnly: false` |
| `delete-fhir-resource`             | Deletes a FHIR resource                  | `onlyOneEngine: true`, `httpReadOnly: false` |
| `create-fhir-transaction`          | Executes a FHIR transaction              | `onlyOneEngine: true`, `httpReadOnly: false` |

## Analysis of the validation result {: #analysis}

The tool `validate-fhir-resource` can ask the LLM of the MCP client to analyze the validation result. This is
controlled by `requestAnalysisFromClient`:

| Setting                                                             | Effect                                                             |
|---------------------------------------------------------------------|--------------------------------------------------------------------|
| `matchbox.fhir.mcp.requestAnalysisFromClient: true` (configuration) | The analysis is requested for every validation through MCP.        |
| `requestAnalysisFromClient=true` or `=false` in `validationparams`   | Overrides the configuration for this call of the tool.             |

When the analysis is requested, the result of the tool depends on the client:

| Client                                                                                                      | Result of the tool                                                                 |
|-------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| supports [sampling](https://modelcontextprotocol.io/specification/2025-11-25/client/sampling)               | The analysis written by the LLM of the client, obtained with a sampling request. If the sampling fails, the `OperationOutcome`. |
| doesn't support sampling                                                                                    | The analysis prompt, followed by the `OperationOutcome`.                           |

The analysis is requested whether the validation has errors or not. Without `requestAnalysisFromClient`, the result
of the tool is the `OperationOutcome`.

The LLM configured in Matchbox is never used for a validation through MCP: the validation parameter
`analyzeErrorsWithLlm` is always set to `false` by the tool, whatever the configuration or the `validationparams`
say. See [AI-assisted validation](../explanation/ai-assisted-validation.md#analysis-through-mcp).

## Parameters and results of the tools {: #tool-parameters}

The tools return their result as text content: JSON for the lists and FHIR resources, the analysis in Markdown for
`validate-fhir-resource` with a sampling client. A failure is returned as a tool result with `isError: true` and a
message, e.g. _FHIR server error 422: …_ with the body of the error.

### Validation tools

| Tool                                 | Parameter          | Type    | Req. | Description                                                                                                                                                |
|--------------------------------------|--------------------|---------|------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `validate-fhir-resource`             | `resource`         | string  | yes  | The resource or the instance of a logical model, in XML or JSON.                                                                                           |
|                                      | `profile`          | string  | yes  | The canonical URL of the profile, optionally with `\|version`.                                                                                             |
|                                      | `validationparams` | string  | no   | Validation parameters as `name=value` pairs separated by commas, e.g. `txServer=http://tx.fhir.org,txUseEcosystem=false`, and `requestAnalysisFromClient`. |
| `list-fhir-profiles-to-validate-for` | `resourceType`     | string  | no   | Only the profiles whose title starts with this text (usually the resource type).                                                                           |
|                                      | `ig`               | string  | no   | Only the profiles of this package id.                                                                                                                      |
| `list-fhir-igs`                      | `includeVersions`  | boolean | no   | Whether to list only the current version of each implementation guide, or all installed versions.                                                          |
| `list-validation-parameters`         |                    |         |      |                                                                                                                                                            |
| `get-profiles-for-document-bundle`   | `bundleSerialized` | string  | yes  | The `Bundle` of type `document`, in XML or JSON.                                                                                                           |

The results are:

- `validate-fhir-resource`: the `OperationOutcome` in JSON, as returned by [`$validate`](operations.md#validate),
  or the analysis (see [above](#analysis)). The values in `validationparams` are passed as query parameters of
  `$validate`; a pair without `=` is ignored.
- `list-fhir-profiles-to-validate-for`: a JSON array of `{"title": …, "profile": …, "ig": …}`, with the profiles of
  the current version of each implementation guide.
- `list-fhir-igs`: a `Bundle` of `ImplementationGuide` resources.
- `list-validation-parameters`: a JSON array with the input parameters of the OperationDefinition of `$validate`
  (except `resource` and `profile`), with their name, type, cardinality and documentation.
- `get-profiles-for-document-bundle`: a JSON array of `{"canonical": …, "igId": …, "igVersion": …, "igCurrent": …,
  "title": …}`, like [`$get-profiles`](operations.md#get-profiles).

Example call of `validate-fhir-resource` (the `arguments` of an MCP `tools/call` request):

```json
{
  "name": "validate-fhir-resource",
  "arguments": {
    "resource": "{\"resourceType\": \"Patient\", \"gender\": \"female\"}",
    "profile": "http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient",
    "validationparams": "txServer=n/a,requestAnalysisFromClient=false"
  }
}
```

### FHIR resource tools

| Tool                               | Parameters                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------------|
| `read-fhir-resource`               | `resourceType`, `id`                                                                                          |
| `search-fhir-resources`            | `resourceType`, `query`: search parameters separated by commas, e.g. `url=http://…,version=1.0.0`             |
| `create-fhir-resource`             | `resourceType`, `resource` (JSON object), `headers` (optional, e.g. `{"If-None-Exist": "url=http://…"}`)      |
| `update-fhir-resource`             | `resourceType`, `id`, `resource` (JSON object)                                                                |
| `conditional-update-fhir-resource` | `resourceType`, `resource` (JSON object), `query` (optional), `headers` (optional, e.g. `If-None-Match`)      |
| `patch-fhir-resource`              | `resourceType`, `id`, `resource` (the patch, JSON object)                                                     |
| `conditional-patch-fhir-resource`  | `resourceType`, `resource` (the patch, JSON object), `query` (optional), `headers` (optional)                 |
| `delete-fhir-resource`             | `resourceType`, `id`                                                                                          |
| `create-fhir-transaction`          | `resourceType` (`Bundle`), `resource` (the transaction `Bundle`, JSON object)                                 |

They are executed as the corresponding FHIR interactions on the server, and return the response of the server: the
resource, the search `Bundle`, or the `OperationOutcome`. As through the REST API, only the conformance resources can
be changed (e.g. `StructureMap`, `StructureDefinition`, `Questionnaire`).
