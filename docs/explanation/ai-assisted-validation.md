# AI-assisted validation

Matchbox can involve a large language model (LLM) in validation in two ways. This page explains what each one does,
how they differ, and what to consider before enabling them.

## Why

The result of a validation is precise, but it is written for people who know FHIR. For complex resources, it can
contain dozens of messages, many of them consequences of one root cause (see
[Understanding validation errors](validation-errors.md#slicing-in-fhir-documents)). An LLM is good at exactly this
kind of task: reading the whole result, grouping the related messages, explaining them in plain language and proposing
a corrected resource. This makes validation more accessible for people who are not FHIR specialists.

The LLM doesn't validate: the validation is always done by the HL7 validator, and its `OperationOutcome` stays the
authoritative result. The analysis is an additional, clearly marked `information` issue; it can be wrong, as any LLM
output.

## Two approaches

| | Analysis by Matchbox | Validation through MCP |
|---|---|---|
| Who starts | A client calls `$validate` (API or web interface) | A user talks to an AI assistant (Claude Desktop, GitHub Copilot, …) |
| Role of Matchbox | Validates, then calls an LLM for the analysis | Offers validation as a tool to the assistant |
| Which LLM | The one configured in Matchbox (`llm.provider`, API key of the operator) | The one of the assistant |
| Enabled by | `analyzeErrorsWithLlm` | `spring.ai.mcp.server.enabled`; `requestAnalysisFromClient` for the guided analysis |
| Result | An `OperationOutcome` with an additional analysis issue | A conversation: the assistant explains, the user can ask and iterate |
| Guide | [Enable the AI analysis](../how-to/enable-ai-analysis.md) | [Connect an MCP client](../how-to/connect-mcp-client.md) |

### Analysis by Matchbox

When a validation has `error` or `fatal` issues and the analysis is requested, Matchbox sends the validated resource
and the validation result to the configured LLM provider, and adds the answer to the `OperationOutcome` as a Markdown
text. Successful validations aren't analyzed.

### Analysis through MCP {: #analysis-through-mcp}

With the [Model Context Protocol](https://modelcontextprotocol.io/), the direction is reversed: the AI assistant calls
Matchbox. The assistant already has an LLM, so it would be wasteful (and would require a second API key) to let Matchbox
call its own. The LLM configured in Matchbox is therefore never used for a validation through MCP, and the setting
`analyzeErrorsWithLlm` has no effect there.

By default, the validation tool simply returns the `OperationOutcome`, and the assistant interprets it as it sees fit.
With the setting `requestAnalysisFromClient`, Matchbox additionally steers the analysis with its own prompt, the same
one that it uses for its own LLM:

- If the client supports [sampling](https://modelcontextprotocol.io/specification/2025-11-25/client/sampling),
  Matchbox sends the prompt and the validation result to the client's LLM during the tool call, and returns the
  analysis as the result of the tool.
- Otherwise, Matchbox returns the prompt together with the `OperationOutcome`, and the assistant follows the prompt.

Unlike the analysis by Matchbox, this one isn't limited to validations with errors.

## What to consider

- **Data protection**: the validated resource and the validation result are sent to the LLM provider. Don't enable the
  analysis for resources with real patient data unless the provider is allowed to process them.
- **Cost and time**: each analysis is a call to the LLM, billed to the API key of the operator, and adds its duration
  to the validation. That is why it can be requested per validation instead of always.
- **Reliability**: the analysis is a help for humans, not a result to process automatically.

## The prompt

Both approaches use the same prompt, so that an analysis looks the same whichever LLM writes it. The prompt is sent
as the system message (or, through MCP, before the validation result) and asks the LLM to:

- act as a helper that analyzes FHIR resources and their validation results;
- **focus on the issues of severity `fatal`, `error` and `warning`**, explain their underlying cause and give examples
  of what has to be fixed. The `information` issues, e.g. the first issue that describes the validation, are noise
  for this task;
- **summarize the errors that have the same source**, because one mistake often produces many messages (a wrong slice,
  a missing profile);
- say so when it can't help with an issue, rather than invent an explanation;
- answer only _The resource is valid_ if there is no such issue, and otherwise keep the answer as short as possible;
- answer in Markdown, which the web interface renders.

The analysis by Matchbox sends the validated resource and the `OperationOutcome` (in R5 JSON) after the prompt, as one
user message. Each analysis is a new conversation: no previous request is kept.

Matchbox talks to the LLM providers with [LangChain4j](https://docs.langchain4j.dev/), which makes the code independent
of the provider. The supported providers are `openai`, `anthropic`, `google` (Gemini) and `huggingface` (through its
OpenAI-compatible API); the model is any model of the provider, chosen with `llm.modelName` (see the
[configuration](../reference/configuration.md#llm)).
