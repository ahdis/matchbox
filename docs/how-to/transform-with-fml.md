# Transform resources with the FHIR mapping language

!!! warning "Skeleton page"
    This page still has to be written. It should be a **how-to guide**: the steps to reach one goal, for a reader who
    already knows the FHIR mapping language (FML) basics from the [tutorial](../tutorials/first-mapping.md).

    - **Goal**: develop maps with Matchbox, then run them in production from an implementation guide.
    - **Keywords**: FHIR mapping language, `StructureMap`, `text/fhir-mapping`, `StructureMap/$transform`,
      `StructureMap/$convert`, `onlyOneEngine`, implementation guide, canonical URL, `$list`, mapping editor of the web
      interface, logical models, `fhirVersion` parameter of the content type.
    - **Sources**: [`fml.http`](https://github.com/ahdis/matchbox/blob/main/matchbox-server/fml.http),
      `StructureMapTransformProvider`, `MappingLanguageInterceptor`, `StructureMapListProvider`,
      [CDA-FHIR-Maps](http://fhir.ch/ig/cda-fhir-maps/index.html) as a real-world example.

## Develop maps against a development server

!!! abstract "To write"
    Start Matchbox with `onlyOneEngine: true`. `POST`/`PUT` the map with `Content-Type: text/fhir-mapping` (a FHIR
    version can be given: `text/fhir-mapping;fhirVersion=5.0`); the response is the `StructureMap` or an
    `OperationOutcome` with the syntax error. Iterate with `PUT StructureMap/{id}`.

## Convert a map to a StructureMap without storing it

!!! abstract "To write"
    `POST StructureMap/$convert` with the map as `text/fhir-mapping`: returns the `StructureMap` (JSON/XML), useful to
    put it in an implementation guide.

## Transform a resource

!!! abstract "To write"
    `POST StructureMap/$transform?source=<canonical URL>` with the resource in the body; or a `Parameters` with the
    `source` map and `content`. Choose the output format with `Accept`. Mention the `ig` parameter to select the
    engine when several IGs contain maps.

## Use the mapping editor of the web interface

!!! abstract "To write"
    The _Mapping Language_ page: edit a map, run it against a sample resource, see the result. Screenshot.

## Distribute maps in an implementation guide

!!! abstract "To write"
    For production: add the `StructureMap`s (and the logical models they use) to an implementation guide, build the
    package, [load it](load-implementation-guides.md) into Matchbox. No `onlyOneEngine` is needed then. List the
    available maps with `GET StructureMap/$list`.

## Related

- [Transform CDA to FHIR](transform-cda-to-fhir.md)
- [Extract data from a QuestionnaireResponse](extract-questionnaire-response.md)
- [FHIR operations reference](../reference/operations.md#transform)
