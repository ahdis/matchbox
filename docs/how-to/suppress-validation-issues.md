# Suppress validation issues

Some validation issues are known and not relevant for your use case (e.g. a warning caused by the implementation guide
itself). This guide shows how to remove them from the validation results. Suppressed issues are configured per
implementation guide, with an optional version.

## Suppress warnings and information messages

List the messages in `matchbox.fhir.context.suppressWarnInfo`:

- to match an exact substring of the message, add the text;
- to match a regular expression, add the prefix `regex:`.

```yaml
matchbox:
  fhir:
    context:
      suppressWarnInfo:
        hl7.fhir.r4.core#4.0.1:
          - "Constraint failed: dom-6:"
          - "regex:Entry '(.+)' isn't reachable by traversing forwards from the Composition"
        ch.fhir.ig.ch-elm:
          - "regex:Binding for path (.+) has no source, so can't be checked"
          - "regex:None of the codings provided are in the value set 'Observation Interpretation Codes'(.*)"
```

## Suppress errors

Errors are matched by their message id, not by their text. Find the message id of the error in the
[list of message ids](https://github.com/hapifhir/org.hl7.fhir.core/blob/master/org.hl7.fhir.utilities/src/main/java/org/hl7/fhir/utilities/i18n/I18nConstants.java)
and list it in `matchbox.fhir.context.suppressError`. To limit the suppression to some paths of the resource, add a
regular expression for the path after `@^`: `messageId@^regex`.

For example, errors about unknown extensions look like this:

```
Bundle.entry[17].resource/Patient/e5ZHnklVuYuT85PnDVOepOg3/.extension[6].url:
URL value 'http://hl7.org/fhir/us/core/StructureDefinition/us-core-sex' does not resolve
```

The following configuration suppresses them for all extensions, and suppresses another error for `RelatedPerson` only:

```yaml
matchbox:
  fhir:
    context:
      suppressError:
        hl7.fhir.r4.core#4.0.1:
          - "Extension_EXTP_Context_Wrong@^RelatedPerson"
          - "Type_Specific_Checks_DT_URL_Resolve@^.*extension.*url"
```

## Accept unknown extensions

To accept extensions from some domains only, instead of suppressing the errors, set `matchbox.fhir.context.extensions`
to a comma-separated list of URL patterns (the default `any` accepts all extensions).

## Related

- [Configuration reference](../reference/configuration.md)
