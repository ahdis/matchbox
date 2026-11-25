# Comparison with the HAPI FHIR validation

Matchbox builds on the [HL7 FHIR Core](https://github.com/hapifhir/org.hl7.fhir.core) validation library and the
[HAPI FHIR](https://hapifhir.io/) framework, and extends them. This page clarifies how the other validation tools of
that ecosystem relate to Matchbox, to help you decide which one fits your needs.

## The HAPI FHIR validation approaches

There are different ways to validate FHIR resources with the HL7 and HAPI tools:

1. **HL7 FHIR Validator CLI**: the official
   [HL7 Java reference validator](https://github.com/hapifhir/org.hl7.fhir.core/tree/master/org.hl7.fhir.validation),
   a command-line tool that validates resources against profiles and implementation guides.
2. **Web-based FHIR Validator**: a web front-end hosted at [validator.fhir.org](https://validator.fhir.org), built on
   the [validator wrapper](https://github.com/hapifhir/org.hl7.fhir.validator-wrapper) project.
3. **HAPI FHIR JPA Server with validation**: the
   [HAPI FHIR JPA server](https://hapifhir.io/hapi-fhir/docs/validation/instance_validator.html#packages) can be
   configured with validation capabilities.

## Where Matchbox differs

| Area                                          | Matchbox                                                                                                                                                                                                  | HAPI FHIR                                                                                                                                                 |
|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| **FHIR API for validation**                   | Supports the `$validate` operation on the system endpoint of the FHIR server, which external tooling can use directly.                                                                                   | The web-based validator (2) and the JPA server (3) handle the validation API differently.                                                                 |
| **Pre-configured implementation guides**      | Implementation guides can be pre-configured for validation without access to the internet or to package servers at runtime, which is useful for offline and production deployments.                      | The JPA server (3) can store IGs. The CLI (1) and the web validator (2) typically resolve packages from online registries.                                |
| **Terminology server configuration**          | Fully configurable, including an internal minimal terminology server for production use (see [Terminology in validation](terminology.md)).                                                               | Not available in the standard HAPI FHIR JPA server setup (3). The CLI and the web validator (1, 2) support configuring a terminology server.              |
| **Enhanced validation report**                | The `OperationOutcome` includes all the parameters used for the validation (profile, packages, engine versions, validation settings), which makes the results reproducible and traceable.                | Not available in any of the HAPI FHIR validation approaches.                                                                                              |
| **AI analysis of the validation report**      | Validation results can be analyzed by an LLM to provide readable summaries and recommendations (see [AI-assisted validation](ai-assisted-validation.md)).                                                | Not available.                                                                                                                                            |
| **MCP support**                               | Matchbox provides a [Model Context Protocol](https://modelcontextprotocol.io/) server, which lets AI assistants validate FHIR resources.                                                                 | Not available.                                                                                                                                            |
| **Gazelle EVS Client integration**            | Matchbox implements the [IHE Gazelle validation API](../reference/gazelle-api.md), for the integration with the IHE Gazelle testing platform.                                                            | Not available.                                                                                                                                            |

## What is the same

Matchbox uses the same HL7 FHIR Core validation library as all the HAPI FHIR validation tools, so the validation logic
is consistent. On top of this foundation, Matchbox adds features aimed at production deployments, at the integration
with external tooling, and at the traceability of validation results. See [Architecture](architecture.md).
