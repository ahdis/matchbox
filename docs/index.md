# Matchbox

[Matchbox](https://www.matchbox.health/) makes it easier to validate, transform and exchange healthcare information.
It checks that health data follows the [HL7® FHIR®](https://www.hl7.org/fhir/) standard and the
[implementation guides](https://hl7.org/fhir/implementationguide.html) built on it, and it maps data to and from FHIR
with the [FHIR mapping language](https://www.hl7.org/fhir/mapping-language.html).

Matchbox is used at [IHE Connectathons](https://www.ihe.net/testing/connectathon/), integrated in the
[Gazelle tooling](https://ihe-catalyst.net/test-system-gazelle/), to validate the messages of the participants against
FHIR implementation guides. It is also used in production to validate messages without any call to the internet.

Matchbox comes in two forms:

- **matchbox-server**: a FHIR server, distributed as a Docker image, that you run in your own infrastructure. It offers
  the FHIR API (`$validate`, `$transform`, …), a web interface, the Gazelle EVS API and an MCP server.
- **matchbox-engine**: a Java library that you embed in your own application, with the same validation and
  transformation features.

Matchbox is developed by [ahdis](https://www.ahdis.ch/en/home) and is freely available under the
[Apache Software License 2.0](https://github.com/ahdis/matchbox/blob/main/LICENSE). It is based on
[HAPI FHIR](https://hapifhir.io/) and the official
[HL7 FHIR validator](https://confluence.hl7.org/spaces/FHIR/pages/35718580/Using+the+FHIR+Validator).
A [public test server](https://test.ahdis.ch/matchboxv3/) is available to try it out.

## Main features

- **Validation** of FHIR resources (R4, R4B, R5) and logical models (e.g. CDA) against the FHIR specification and
  implementation guides, with implementation guides pre-installed so that no package server is needed at runtime.
- **Terminology**: use an external terminology server, or the internal one for an offline setup.
- **Mapping** of health data to and from FHIR with the FHIR mapping language (`StructureMap/$transform`), including
  CDA to FHIR.
- **Structured Data Capture**: `QuestionnaireResponse/$extract` based on the FHIR mapping language, and
  `Questionnaire/$assemble` for modular questionnaires.
- **AI support**: analysis of validation results by an LLM, and an MCP server for AI assistants.
- **Integration** with the IHE Gazelle EVS Client and Gazelle Maestro.

## How this documentation is organized

The documentation follows the [Diátaxis](https://diataxis.fr) framework:

<div class="grid cards" markdown>

- **[Tutorials](tutorials/index.md)**

    Lessons to get started: you follow the steps and learn Matchbox along the way.

- **[How-to guides](how-to/index.md)**

    Recipes for a specific goal: run Matchbox, load your implementation guides, configure a terminology server, …

- **[Reference](reference/index.md)**

    The technical description: configuration parameters, FHIR operations, Docker image, Java API.

- **[Explanation](explanation/index.md)**

    Background and design: how validation works, how engines and packages are managed, how to read validation errors.

</div>

What has changed in each version is listed in the [changelog](changelog.md).
