# Mapping and transformation

Besides validation, Matchbox transforms health data to and from FHIR. This page gives the background: what the FHIR
mapping language is, why Matchbox uses it, and how the transformation of CDA and of questionnaire responses builds on
it.

## Why the FHIR mapping language

Health data exists in many formats: older FHIR versions, CDA documents, questionnaire responses, proprietary
structures. Converting it with custom code hides the mapping rules in an application, where others can neither review
nor reuse them.

The [FHIR mapping language](https://www.hl7.org/fhir/mapping-language.html) (FML) describes a mapping as text, which is
converted into a FHIR [StructureMap](https://www.hl7.org/fhir/structuremap.html) resource. A StructureMap is a
conformance resource like a profile: it can be published in an implementation guide, versioned, reviewed and executed
by any engine that supports it. Matchbox applies the maps to your data and, with its validation stack, checks that the
maps and their results are conformant.

In Switzerland, this approach is used for the mapping between the CDA and the FHIR exchange formats for medication
([CDA-FHIR-Maps](http://fhir.ch/ig/cda-fhir-maps/index.html)).

## Logical models: mapping data that isn't FHIR

A map reads a source structure and writes a target structure. Both are described by StructureDefinitions, and they
don't have to be FHIR resources: a _logical model_ describes any structure in the terms of FHIR. This is how Matchbox
handles CDA: the [CDA logical model](../reference/cda-logical-model.md) represents the CDA standard within FHIR, so that
a CDA document can be parsed, validated and used as source or target of a map, like a FHIR resource.

!!! abstract "To write"
    How an instance of a logical model is parsed and serialized (XML/JSON), how the engine finds the model of an input
    document, and the limits (what of CDA isn't covered, e.g. schematron rules).

## Structured Data Capture: from answers to resources

A [Structured Data Capture](https://build.fhir.org/ig/HL7/sdc/) questionnaire collects data in the structure of a form.
To use the answers, they have to be turned into resources (a Patient, Observations, …). One of the
[extraction mechanisms](https://build.fhir.org/ig/HL7/sdc/extraction.html#map-extract) of SDC uses a StructureMap for
that: the questionnaire references a map, and the `$extract` operation applies it to the response. Matchbox implements
this mechanism, and has preliminary support for assembling modular questionnaires.

!!! abstract "To write"
    Why StructureMap-based extraction rather than the other SDC mechanisms (observation-based, definition-based,
    template-based), and which of them Matchbox supports.

## Development mode and production mode

Maps are developed iteratively: change the map, transform a sample, look at the result. This requires that the map can
be replaced on the server, which is only possible in the [`onlyOneEngine` mode](validation-engines.md#only-one-engine).
In production, the maps are part of an implementation guide and are installed like any other package, so that the
transformation is reproducible.

## What Matchbox adds to the HL7 implementation

!!! abstract "To write"
    The differences to the mapping language implementation of `org.hl7.fhir.core`: the patched classes for CDA, the
    features contributed upstream, transformations across FHIR versions (R4 server, R5 internal model), the trace output
    for debugging maps, and known limits of the language support.

## Related

- Tutorial: [Transform data with the FHIR mapping language](../tutorials/first-mapping.md)
- How-to: [Transform with the FHIR mapping language](../how-to/transform-with-fml.md),
  [Transform CDA to FHIR](../how-to/transform-cda-to-fhir.md),
  [Extract data from a QuestionnaireResponse](../how-to/extract-questionnaire-response.md)
