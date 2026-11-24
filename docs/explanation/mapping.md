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

A logical model carries what a parser needs to read its instances: the XML namespace and name of each element
(extensions `xml-namespace` and `xml-name` of the FHIR tools), whether an element is an XML attribute or the text
content of its parent (`xmlText`), and the types of its children. The parser of `org.hl7.fhir.core` uses this
information to read a CDA document into the same internal element model (`org.hl7.fhir.r5.elementmodel.Element`) that
it uses for FHIR resources, and to write that model back as XML. From then on, the validator, the FHIRPath engine and
the mapping engine don't see a difference between a CDA document and a FHIR resource.

The model of an input is found in two ways:

- **When transforming**, the model is the structure that the map declares as its first source (`uses "…" as
  source`): the input is parsed with that StructureDefinition. The input itself doesn't say that it is a CDA document.
- **When validating**, the profile of the request gives the model, and the root element of the document must match
  it: `ClinicalDocument` in the namespace `urn:hl7-org:v3`.

The target of a map can also be a logical model: the result is then created as an instance of that model and
serialized in XML, e.g. a CDA document created from a FHIR `Bundle`.

The model describes the structure of CDA, which the FHIR validator then checks: elements, cardinalities, data types,
bindings, and the invariants written in FHIRPath. It doesn't cover what CDA expresses otherwise: the XML schema, the
schematron rules of the CDA specifications and templates, or the constraints that are only in the narrative of a
specification. The parser of `org.hl7.fhir.core` had to be adapted for some details of CDA (the whitespace of
non-string attributes, the data type `ST.r2b`), see [What Matchbox adds](#what-matchbox-adds-to-the-hl7-implementation).

## Structured Data Capture: from answers to resources

A [Structured Data Capture](https://build.fhir.org/ig/HL7/sdc/) questionnaire collects data in the structure of a form.
To use the answers, they have to be turned into resources (a Patient, Observations, …). One of the
[extraction mechanisms](https://build.fhir.org/ig/HL7/sdc/extraction.html#map-extract) of SDC uses a StructureMap for
that: the questionnaire references a map, and the `$extract` operation applies it to the response. Matchbox implements
this mechanism, and has preliminary support for assembling modular questionnaires.

SDC defines several extraction mechanisms, from the simplest to the most flexible:

- **observation-based**: items marked with `observationExtract` become `Observation`s with the item's code;
- **definition-based**: items point with `definition` to the elements of a resource that they fill;
- **template-based**: the questionnaire contains resources as templates, filled with FHIRPath expressions;
- **StructureMap-based**: a map transforms the whole response into a resource or a `Bundle`.

Matchbox implements only the **StructureMap-based** extraction. It is the most general mechanism: a map can create
any resource, combine and convert answers, look up codes in ConceptMaps, and create a `transaction` Bundle with
references between the new resources. The rules are kept outside of the questionnaire, so the same form can be
extracted in different ways, and the maps can be tested on their own with `$transform`. The extraction also reuses
what Matchbox already does for other transformations: there is no separate engine to maintain.

The price is that the questionnaire and the map have to be maintained together: a `linkId` that changes in the
questionnaire must change in the map too. The other mechanisms keep the extraction rules next to the items, which is
simpler for forms that map their items one to one to resource elements; use a form filler or a server that supports
them if you need them.

## Development mode and production mode

Maps are developed iteratively: change the map, transform a sample, look at the result. This requires that the map can
be replaced on the server, which is only possible in the [`onlyOneEngine` mode](validation-engines.md#only-one-engine).
In production, the maps are part of an implementation guide and are installed like any other package, so that the
transformation is reproducible.

## What Matchbox adds to the HL7 implementation

Matchbox doesn't have its own implementation of the mapping language: the parser and the engine are the ones of
`org.hl7.fhir.core`, the library of the HL7 validator. Matchbox extends them where they didn't fit its use cases, and
contributes the fixes upstream when they are of general interest; the patched classes are removed once the fix is in a
release of `org.hl7.fhir.core`.

- **One internal model, any FHIR version.** The engine of `org.hl7.fhir.core` works with the R5 model internally.
  Maps are parsed into R5 StructureMaps, and converted to the FHIR version of the server only when they are stored or
  returned. The source and target of a transformation are kept as elements of their own StructureDefinitions, so a
  server in R4 can transform R4 resources. A map can also use structures of another FHIR version, with a versioned
  canonical URL such as `http://hl7.org/fhir/5.0/StructureDefinition/Patient`: Matchbox then loads the core package
  of that version next to the one of the engine.
- **CDA.** Matchbox patches the XML parser and the element model for the details of CDA (whitespace of non-string
  attributes, the data type `ST.r2b`, choice types), and the `translate()` function of the maps: it accepts the CDA
  coded types (`CD`, `CE`, …) as source, and implements the special ConceptMap
  `http://hl7.org/fhir/ConceptMap/special-oid2uri`, which converts OIDs to the URIs of the code systems.
- **FHIRPath in maps.** `resolve()` works on references inside a `Bundle`, and `conformsTo()` uses the profiles of the
  engine, as in validation.
- **Trace.** The trace of the engine (the groups and rules that are executed, with their variables, the conditions
  and the `log()` calls of the map) is collected and returned with `$transform` and `debug=true`, so a client such as
  the [FHIRPath Lab](https://fhirpath-lab.com) can show how a map was executed.

The language support is the one of `org.hl7.fhir.core`, with its limits: some parts of the mapping language
specification are not implemented, or are implemented differently than the specification describes. When a map
doesn't behave as expected, test it with `debug=true` and compare it with the maps in the tests of matchbox-engine,
which show the patterns that work.

## Related

- Tutorial: [Transform data with the FHIR mapping language](../tutorials/first-mapping.md)
- How-to: [Transform with the FHIR mapping language](../how-to/transform-with-fml.md),
  [Transform CDA to FHIR](../how-to/transform-cda-to-fhir.md),
  [Extract data from a QuestionnaireResponse](../how-to/extract-questionnaire-response.md)
