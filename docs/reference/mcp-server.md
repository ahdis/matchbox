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

!!! abstract "To write"
    The exact parameters of each tool (JSON schema) and an example call and result for each of them (source:
    `McpMatchboxBridge`).
