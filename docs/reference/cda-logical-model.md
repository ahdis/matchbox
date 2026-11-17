# CDA logical model

To validate CDA documents and to transform them to and from FHIR, Matchbox uses a logical model that represents the
CDA standard within FHIR.

| Property              | Value                                                                                         |
|-----------------------|-----------------------------------------------------------------------------------------------|
| Browsable model       | [CDA logical model](../cda-logical-model/index.html)                                          |
| Package               | [package.tgz](https://ahdis.github.io/matchbox/cda-logical-model/package.tgz)                 |
| Package bundled with Matchbox | `hl7.cda.uv.core#2.0.1-sd-202510-matchbox-patch`                                      |
| FHIR version of the model | R5                                                                                        |
| Source                | [ahdis/cda-core-2.0](https://github.com/ahdis/cda-core-2.0/tree/main), a fork of the [HL7 CDA core](https://build.fhir.org/ig/HL7/CDA-core-2.0/) logical model |

## Differences to the HL7 model

The logical model is developed by [HL7 International](https://www.hl7.org/)
([CI build](https://build.fhir.org/ig/HL7/CDA-core-2.0/)). The ahdis fork adds:

- the IHE PHARM extensions: PharmContent, PharmIngredient, PharmMedicineClass, PharmPackagedMedicine,
  PharmSpecializedKind, PharmSubstance, PharmSuperContent;
- the IHE LAB extensions: [LabCriterion](../cda-logical-model/StructureDefinition-LabCriterion.html),
  [LabPrecondition](../cda-logical-model/StructureDefinition-LabPrecondition.html);
- `Patient.birthTime`.

## Related

- [Transform CDA to FHIR](../how-to/transform-cda-to-fhir.md)
- [Mapping and transformation](../explanation/mapping.md)
