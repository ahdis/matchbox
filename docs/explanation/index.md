# Explanation

These pages give the background of Matchbox: how it works and why it was designed that way. They don't tell you what to
do (see the [how-to guides](../how-to/index.md)) and don't list every option (see the
[reference](../reference/index.md)); read them to understand.

## Matchbox

- [Architecture](architecture.md): the engine, the server and the web interface, and the projects they are built on
- [Validation engines](validation-engines.md): why there is one engine per implementation guide, and what the
  development mode changes
- [Package management](package-management.md): where the implementation guides are stored and how they get into an
  engine
- [Memory and performance](memory-and-performance.md): where the memory goes and how to size a deployment

## Validation

- [Understanding validation errors](validation-errors.md): what a validator checks, with the typical messages of each
  level
- [Terminology in validation](terminology.md): why a terminology server is needed and what the internal one can do
- [How the validator talks to a terminology server](terminology-server-interactions.md): the requests and the cache
- [AI-assisted validation](ai-assisted-validation.md): analysis by an LLM and validation through MCP
- [Comparison with the HAPI FHIR validation](comparison-with-hapi-fhir.md)

## Transformation

- [Mapping and transformation](mapping.md): the FHIR mapping language, logical models, CDA and SDC
