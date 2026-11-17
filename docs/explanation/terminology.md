# Terminology in validation

Many elements of a FHIR resource are codes. This page explains why the validator may need a terminology server to check
them, what the internal terminology server of Matchbox can and can't do, and how to choose between the options.

## What the validator checks by itself

For a coded element (`code`, `Coding` or `CodeableConcept`), the validator has to answer two questions:

- does the code exist in its code system?
- if the element is bound to a value set: is the code in that value set?

The validator answers them by itself when it has everything it needs in the loaded packages: the code system is
defined there with all its codes, and the value set lists its codes or includes whole code systems that are available.

## Why a terminology server is needed

A terminology server is needed in two situations:

- **The code system isn't available locally.** Large code systems such as SNOMED CT or LOINC, and many ISO code
  systems, aren't distributed in FHIR packages. Without a terminology server, the validator can't tell whether a code
  exists in them.
- **The value set is complex.** A value set can define its content with
  [filters](http://build.fhir.org/valueset-filter-operator.html) instead of listing each code (an
  [intensional](https://hl7.org/fhir/valueset.html#int-ext) value set), e.g. "all the descendants of this SNOMED CT
  concept". Evaluating such a definition requires the knowledge of the code system, which only a terminology server
  has.

The validator also asks the terminology server to check the display names of the codes.

## The internal terminology server

matchbox-server comes with its own terminology server, so that it can validate without depending on the availability
of an external service, and without sending any data out. It expands the value sets when it can, and otherwise answers
that the code is valid.

That makes the validation **incomplete** in some cases. The internal terminology server gives complete results when:

- all the code systems that are used are defined in the implementation guides (or are among the special code systems
  supported by HAPI FHIR), and
- all the important coded elements are bound to a simple value set with the binding strength `required`.

Otherwise, be aware that:

- a code of a code system that isn't defined locally (SNOMED CT, LOINC, …) is considered valid if the element is not
  bound, or if the binding isn't `required`;
- intensional value sets are not evaluated: a code that isn't in the value set is not reported
  (see the [example](validation-errors.md#intensional-value-sets));
- display names are not validated.

## Choosing a terminology setup

| Setup | Result | Suitable when |
|---|---|---|
| External server (e.g. `tx.fhir.org`, Ontoserver, a national server) | Complete validation of codes and value sets. Requires network access to that server; the codes of the validated resources are sent to it. | Testing and conformance checks, when the completeness of the validation matters most. |
| Internal server | No external dependency, but incomplete for external code systems and complex value sets. | Production validation without calls to the internet, with implementation guides that mostly use their own code systems and simple `required` bindings. |
| No server (`n/a`) | Only what the validator can check by itself. | Structure-only checks. |

A result obtained with one setup can differ from the result with another one. The terminology server that was used is
written in the first issue of every validation result (`txServer=…`).

## Related

- [Configure a terminology server](../how-to/configure-terminology-server.md)
- [How the validator talks to a terminology server](terminology-server-interactions.md)
