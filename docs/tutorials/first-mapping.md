# Transform data with the FHIR mapping language

In this tutorial, you will write a small map in the [FHIR mapping language](https://hl7.org/fhir/mapping-language.html)
(FML), load it into Matchbox and use it to transform a `QuestionnaireResponse` into a `Patient`.

You need:

- [Docker](https://docs.docker.com/get-docker/) with at least 4 GB of memory available;
- `curl` in a terminal.

## Step 1: start Matchbox in development mode

To create and change maps through the API, Matchbox has to run with a single engine (see
[Validation engines](../explanation/validation-engines.md) for why). Start it with `onlyOneEngine` enabled:

```bash
docker run -d --name matchbox -p 8080:8080 -m 4g ghcr.io/ahdis/matchbox:latest --matchbox.fhir.context.onlyOneEngine=true
docker logs --follow matchbox
```

Matchbox is ready when the log shows `Finished engines during startup`, which takes about a minute. (The line
`A FHIR has been lit on this server` appears earlier: the server is not ready yet at that point.) Then stop following
the logs with Ctrl+C.

If the container stops by itself during the startup (`docker ps -a` shows `Exited (137)`), Docker doesn't have enough
memory: give Docker at least 4 GB (in Docker Desktop: _Settings → Resources_).

## Step 2: write the map

The map reads the answer to the question `patient.sex` of a questionnaire and writes it into `Patient.gender`. Save it
as `qr2patgender.map`:

```
map "http://ahdis.ch/matchbox/fml/qr2patgender" = "qr2patgender"

uses "http://hl7.org/fhir/StructureDefinition/QuestionnaireResponse" alias QuestionnaireResponse as source
uses "http://hl7.org/fhir/StructureDefinition/Patient" alias Patient as target

group QuestionnaireResponse(source src : QuestionnaireResponse, target tgt : Patient) {
  src.item as item -> tgt as patient then item(item, patient);
}

group item(source src, target tgt: Patient) {
  src.item as item where linkId.value in ('patient.sex') -> tgt.gender = (item.answer.valueString);
}
```

- `map` gives the map its canonical URL; you will use this URL to call the map.
- `uses` declares the structures the map reads (`source`) and writes (`target`).
- each `group` contains rules: `source -> target`.

## Step 3: load the map into Matchbox

Send the map to Matchbox with the content type `text/fhir-mapping`. Matchbox parses it and stores it as a
`StructureMap` resource:

```bash
curl -X POST http://localhost:8080/matchboxv3/fhir/StructureMap \
  -H "Content-Type: text/fhir-mapping" \
  -H "Accept: application/fhir+json" \
  --data-binary @qr2patgender.map
```

The response is the `StructureMap` resource. If the map has a syntax error, the response is an `OperationOutcome` that
tells you where the error is.

## Step 4: transform a resource

Save the following `QuestionnaireResponse` as `qr.json`:

```json
{
  "resourceType": "QuestionnaireResponse",
  "status": "completed",
  "item": [
    {
      "linkId": "patient",
      "item": [
        {
          "linkId": "patient.sex",
          "answer": [{ "valueString": "female" }]
        }
      ]
    }
  ]
}
```

Call the operation `StructureMap/$transform` with the canonical URL of your map in the `source` parameter:

```bash
curl -X POST "http://localhost:8080/matchboxv3/fhir/StructureMap/\$transform?source=http://ahdis.ch/matchbox/fml/qr2patgender" \
  -H "Content-Type: application/fhir+json" \
  -H "Accept: application/fhir+json" \
  --data-binary @qr.json
```

The response is the `Patient` created by the map:

```json
{
  "resourceType": "Patient",
  "gender": "female"
}
```

## Step 5: change the map

Change the answer in `qr.json` to `male` and transform again: the patient is now male. Then extend the map yourself,
e.g. add a question `patient.birthDate` to the questionnaire response and a rule that writes it into
`Patient.birthDate`. Update the stored map with a `PUT` on the `id` that Matchbox returned in step 3, then transform
again:

```bash
curl -X PUT http://localhost:8080/matchboxv3/fhir/StructureMap/<id> \
  -H "Content-Type: text/fhir-mapping" \
  -H "Accept: application/fhir+json" \
  --data-binary @qr2patgender.map
```

## Step 6: clean up

```bash
docker rm -f matchbox
```

## What you have learned

- A map in the FHIR mapping language is identified by its **canonical URL**.
- Matchbox converts the textual map into a **StructureMap** resource when you post it with `text/fhir-mapping`.
- `StructureMap/$transform` applies the map to a resource.
- Creating and changing maps through the API requires the **development mode** (`onlyOneEngine`).

## Next steps

- [Distribute your maps in an implementation guide](../how-to/transform-with-fml.md) for production use.
- [Extract data from a QuestionnaireResponse](../how-to/extract-questionnaire-response.md) with SDC `$extract`.
- [Transform CDA documents to FHIR](../how-to/transform-cda-to-fhir.md).
