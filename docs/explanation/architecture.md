# Architecture

Matchbox consists of a library, a server built on it, and a web interface. This page explains what each part does, on
which projects they are built, and why they are separated.

## The three modules

```
matchbox
├── matchbox-engine     Java library: validation and transformation
├── matchbox-server     FHIR server: REST API around the engine, package storage, terminology server
└── matchbox-frontend   Angular web interface, served by matchbox-server
```

**matchbox-engine** contains the validation and transformation logic. It was extracted from the server so that the same
features can be embedded in another Java application, and work without a connection to the internet: the packages it
needs are loaded from the classpath or from files. Because the server uses the same engine, a map or a profile that was
developed and tested against matchbox-server gives the same result when it runs in an application that embeds
matchbox-engine.

**matchbox-server** makes the engine available as a microservice. It adds what a shared service needs: a FHIR API
(`$validate`, `$transform`, …), a database in which the implementation guides are installed, the management of several
engines (see [Validation engines](validation-engines.md)), an internal terminology server, the Gazelle validation API,
the MCP server and the analysis of validation results with an LLM.

**matchbox-frontend** is the web interface for validating, transforming and browsing the installed implementation
guides. It only uses the FHIR API of the server.

## What Matchbox is built on

| Project | Used for |
|---------|----------|
| [org.hl7.fhir.core](https://github.com/hapifhir/org.hl7.fhir.core) | The official HL7 Java validator and the implementation of the FHIR mapping language. matchbox-engine is derived from it and depends only on these libraries. |
| [HAPI FHIR](https://hapifhir.io/) | The FHIR server framework of matchbox-server, which is based on the [hapi-fhir-jpaserver-starter](https://github.com/hapifhir/hapi-fhir-jpaserver-starter). |
| [Spring Boot](https://spring.io/projects/spring-boot) | The application framework and configuration of matchbox-server. |

Matchbox uses the same validation logic as the HL7 validator: a resource that is valid for the HL7 validator is valid
for Matchbox, with the same configuration. The version of `org.hl7.fhir.core` is fixed for each version of Matchbox
and is written in every validation result, so that a result can be reproduced. The differences in features are listed
in [Comparison with the HAPI FHIR validation](comparison-with-hapi-fhir.md).

## Patched classes

Some classes of `org.hl7.fhir.core` are patched in matchbox-engine, because of peculiarities in the parsing and mapping
of CDA and in the handling of packages (see the
[patched files](https://github.com/ahdis/matchbox/tree/main/matchbox-engine/src/main/java/org/hl7/fhir)). With each new
release of `org.hl7.fhir.core`, the patched files are [updated](https://github.com/ahdis/matchbox/blob/main/updatehapi.sh),
the changes are applied again and the tests are run. Features that were missing in the mapping language have been
contributed back to the upstream project
([pull requests](https://github.com/hapifhir/org.hl7.fhir.core/pulls?q=is%3Apr+is%3Aclosed+author%3Aoliveregger+)).

## A request through the server

!!! abstract "To write"
    A diagram and a walk-through of a `$validate` request: the HAPI servlet (`/fhir`), the interceptors (read-only,
    mapping language, validation), `ValidationProvider`, the selection or creation of the engine by
    `MatchboxEngineSupport`, the calls to the terminology server (`/tx` or external), the optional LLM analysis, the
    `OperationOutcome`. Also show the other servlets: `/tx`, `/gazelle`, `/mcp`, the static web interface, the actuator.

## FHIR versions

!!! abstract "To write"
    How R4, R4B and R5 are supported: the server has one FHIR version (`matchbox.fhir.context.fhirVersion`), engines are
    created per FHIR version, the internal model is R5 and resources are converted; cross-version extensions packages;
    what this means for implementation guides of different FHIR versions on one server.
