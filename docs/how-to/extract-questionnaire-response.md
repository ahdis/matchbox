# Extract data from a QuestionnaireResponse

!!! warning "Skeleton page"
    This page still has to be written. It should be a **how-to guide** for a reader who uses
    [Structured Data Capture (SDC)](https://build.fhir.org/ig/HL7/sdc/) questionnaires and wants to turn the answers
    into FHIR resources.

    - **Goal**: extract resources from a `QuestionnaireResponse` with a map
      ([StructureMap-based extraction](https://build.fhir.org/ig/HL7/sdc/extraction.html#map-extract)), and assemble
      modular questionnaires.
    - **Keywords**: SDC, `QuestionnaireResponse/$extract`, `targetStructureMap` extension, `Questionnaire/$assemble`,
      sub-questionnaires, FHIR mapping language, Smart Forms (which validates the extracted resources with
      `[base]/{Type}/$validate`).
    - **Sources**: [`fml.http`](https://github.com/ahdis/matchbox/blob/main/matchbox-server/fml.http) (sections
      _SDC Extraction_), `QuestionnaireResponseExtractProviderR4`, `QuestionnaireAssembleProviderR4`.

## Link the questionnaire to a map

!!! abstract "To write"
    The `Questionnaire` references the map with the SDC extension `targetStructureMap`; the map transforms the
    `QuestionnaireResponse` into a `Bundle` (or a resource). Example questionnaire and map.

## Extract with stored resources

!!! abstract "To write"
    Install the questionnaire and the map (implementation guide or API in development mode), then
    `POST QuestionnaireResponse/$extract` with the response; the `questionnaire` element of the response links to the
    questionnaire.

## Extract with all resources in the request

!!! abstract "To write"
    `$extract` with a `Parameters` containing the `QuestionnaireResponse`, the `Questionnaire` and the `StructureMap`
    (as a resource or as FML text), for clients that don't store them on the server.

## Assemble a modular questionnaire

!!! abstract "To write"
    `Questionnaire/$assemble`: what is supported ("preliminary support"), example request and result.
