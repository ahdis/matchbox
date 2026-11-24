# Integrate with ITB

Matchbox provides the validation service `FHIRValidator` and the processing services `FHIRPathProcessor` and
`IGManager` of the [Interoperability Test Bed](https://interoperable-europe.ec.europa.eu/collection/itb) (ITB 1.30.0 or
later), with the same contract as the ITB services of the HL7 validator. The services are described in the
[ITB API reference](../reference/itb-api.md).

## Register Matchbox in ITB

1. Check that ITB can reach Matchbox: from the ITB host, the module definition of the validation service must be
   returned.

    ```bash
    curl http://matchbox:8080/matchboxv3/itb/fhir/getModuleDefinition
    ```

2. In the domain, register a test service `FHIRValidator` with the API type _REST_ and the address of the service root,
   e.g. `http://matchbox:8080/matchboxv3/itb/fhir`. Register `FHIRPathProcessor` (`[server]/itb/fhirPath`) and
   `IGManager` (`[server]/itb/igManager`) the same way if your test cases use them.

Test steps can also use the address of the service root directly as their `handler`, without a domain test service.

## Validate in a test case

Call the validation service in a `verify` step with `handlerApiType="REST"`, and capture the context of the report with
`output` to read its items:

```xml
<verify id="validatePatient" desc="Validate the patient" handler="$DOMAIN{FHIRValidator}" handlerApiType="REST" output="$ctx">
  <input name="contentToValidate">$patient</input>
  <input name="profiles">'http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient'</input>
  <input name="failOn">'warning'</input>
</verify>
<assign to="$errorCount">$ctx{errorCount}</assign>
```

The profile selects the implementation guide: install it in Matchbox beforehand, through the
[configuration](load-implementation-guides.md), or with the `loadIG` operation of `IGManager` if the server isn't
read-only. The inputs, the report and its context items are listed in the
[ITB API reference](../reference/itb-api.md#validation).

## Configure Matchbox for ITB

The recommendations for Gazelle apply to ITB too: make the server read-only, install the implementation guides through
the configuration and preload the ones you test with, see
[Configure Matchbox for Gazelle](integrate-gazelle.md#configure-matchbox-for-gazelle).

## Related

- [ITB API reference](../reference/itb-api.md)
- [Validate resources](validate-resources.md)
