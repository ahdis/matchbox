# Transform resources with the FHIR mapping language

This guide shows how to develop maps in the [FHIR mapping language](https://hl7.org/fhir/mapping-language.html) (FML)
with Matchbox, and how to run them in production from an implementation guide. It assumes that you know the basics of
FML, e.g. from the [tutorial](../tutorials/first-mapping.md). More examples of requests are in
[`fml.http`](https://github.com/ahdis/matchbox/blob/main/matchbox-server/fml.http).

## Develop maps against a development server

To create and change maps through the API, start Matchbox in the
[`onlyOneEngine` mode](../explanation/validation-engines.md#only-one-engine) (development mode):

```bash
docker run -d --name matchbox -p 8080:8080 -m 4g ghcr.io/ahdis/matchbox:latest \
  --matchbox.fhir.context.onlyOneEngine=true
```

Without it, creating or updating a `StructureMap` is rejected with HTTP 405.

Send the map in FML with the content type `text/fhir-mapping`. Matchbox parses it and stores it as a `StructureMap`:

```bash
curl -X POST http://localhost:8080/matchboxv3/fhir/StructureMap \
  -H "Content-Type: text/fhir-mapping" \
  -H "Accept: application/fhir+json" \
  --data-binary @qr2patgender.map
```

- The response is the `StructureMap`, with the map rendered in FML as its narrative. Its `id` is the last segment of the
  canonical URL of the map (`qr2patgender` for `http://ahdis.ch/matchbox/fml/qr2patgender`).
- A map with a syntax error is rejected with an `OperationOutcome` that gives the position of the error, e.g.
  `Error @1, 1: Found "asdfasdf" expecting "map"`.
- The map is parsed in the FHIR version of the server. Give another version in the content type if needed, e.g.
  `text/fhir-mapping;fhirVersion=5.0`; in the `onlyOneEngine` mode, it must be the FHIR version of the server
  (otherwise HTTP 415).

To change the map, send it again: with `PUT StructureMap/{id}`, or with another `POST`, which replaces the map that has
the same canonical URL.

```bash
curl -X PUT http://localhost:8080/matchboxv3/fhir/StructureMap/qr2patgender \
  -H "Content-Type: text/fhir-mapping" \
  -H "Accept: application/fhir+json" \
  --data-binary @qr2patgender.map
```

The maps created through the API are kept in the engine only: they are **lost when the server restarts**. Keep the FML
files in your version control, and send them again after a restart.

The structures used by a map (the `uses` statements) must be known by the server: FHIR resources and data types are
always there; logical models and profiles must be in an implementation guide that is
[installed](load-implementation-guides.md), or be created through the API like the map (`POST StructureDefinition`).
Maps that `imports` other maps need those maps on the server too.

## Convert a map to a StructureMap without storing it

`StructureMap/$convert` parses a map in FML and returns the `StructureMap`, without storing it. Use it to check the
syntax of a map, or to get the `StructureMap` to put in an implementation guide. It is available in all modes, not only
in the development mode:

```bash
curl -X POST http://localhost:8080/matchboxv3/fhir/StructureMap/\$convert \
  -H "Content-Type: text/fhir-mapping" \
  -H "Accept: application/fhir+json" \
  --data-binary @qr2patgender.map > StructureMap-qr2patgender.json
```

Use `Accept: application/fhir+xml` to get the `StructureMap` in XML.

## Transform a resource

Call `StructureMap/$transform` with the canonical URL of the map in the `source` query parameter, and the resource to
transform in the body:

```bash
curl -X POST "http://localhost:8080/matchboxv3/fhir/StructureMap/\$transform?source=http://ahdis.ch/matchbox/fml/qr2patgender" \
  -H "Content-Type: application/fhir+json" \
  -H "Accept: application/fhir+json" \
  --data-binary @qr.json
```

- The body is the source of the map: a FHIR resource of any FHIR version, or an instance of a logical model (e.g. a CDA
  document, see [Transform CDA to FHIR](transform-cda-to-fhir.md)). The `Content-Type` says whether it is JSON or XML;
  without it, the format is detected from the content.
- The response is the target of the map, in JSON if the `Accept` header asks for JSON, in XML otherwise.
- The server chooses the engine with the map from its canonical URL: when several implementation guides are installed,
  the one that contains the map is used. A map that isn't found is rejected with HTTP 422
  (_Map not available with canonical url …_).

### Send the map with the request

Instead of using a map stored on the server, the map can be sent with the resource, in a `Parameters` body. This works
in all modes: the map is used for this request only. The parameters are strings:

| Parameter  | Content                                                                              |
|------------|--------------------------------------------------------------------------------------|
| `resource` | the resource to transform, in JSON or XML                                            |
| `map`      | the map, in FML or as a `StructureMap` in JSON or XML                                |
| `model`    | optional: a `StructureDefinition`, or a `Bundle` of them, used by the map            |
| `source`   | instead of `map`: the canonical URL of a map stored on the server                    |

```json
{
  "resourceType": "Parameters",
  "parameter": [
    {
      "name": "map",
      "valueString": "map \"http://ahdis.ch/matchbox/fml/qr2patgender\" = \"qr2patgender\"\nuses \"http://hl7.org/fhir/StructureDefinition/QuestionnaireResponse\" alias QuestionnaireResponse as source\nuses \"http://hl7.org/fhir/StructureDefinition/Patient\" alias Patient as target\ngroup QuestionnaireResponse(source src : QuestionnaireResponse, target tgt : Patient) {\n  src.item as item -> tgt as patient then item(item, patient);\n}\ngroup item(source src, target tgt: Patient) {\n  src.item as item where linkId.value in ('patient.sex') -> tgt.gender = (item.answer.valueString);\n}"
    },
    {
      "name": "resource",
      "valueString": "{\"resourceType\": \"QuestionnaireResponse\", \"status\": \"completed\", \"item\": [{\"linkId\": \"patient\", \"item\": [{\"linkId\": \"patient.sex\", \"answer\": [{\"valueString\": \"female\"}]}]}]}"
    }
  ]
}
```

```bash
curl -X POST http://localhost:8080/matchboxv3/fhir/StructureMap/\$transform \
  -H "Content-Type: application/fhir+json" \
  -H "Accept: application/fhir+json" \
  --data-binary @parameters.json
```

The `Parameters` must be in the FHIR version of the server.

### Debug a map

Add `debug=true` to the query: the response is then a `Parameters` with the result of the transformation (`result`),
the trace of the rules that were executed (`trace`), and the map and the version of Matchbox that were used
(`parameters`). The [FHIRPath Lab](https://fhirpath-lab.com) uses this output to show how a map was executed.

```bash
curl -X POST "http://localhost:8080/matchboxv3/fhir/StructureMap/\$transform?debug=true" \
  -H "Content-Type: application/fhir+json" \
  -H "Accept: application/fhir+json" \
  --data-binary @parameters.json
```

## Use the mapping editor of the web interface

The _Mapping Language_ page of the [web interface](../reference/web-interface.md) (`/matchboxv3/mappinglanguage`)
runs the steps above for you, on a server in the development mode:

1. Paste a source resource in JSON into _Source_, or upload a `.json` file.
2. Write the map into _FHIR Mapping Language map_, or upload a `.map` file.
3. At each change, the map is sent to the server (`POST StructureMap`) and the source is transformed: the result is
   shown in _Transformed_. Syntax errors of the map and errors of the transformation are shown below the map and in
   _Transformed_.

The _Transform_ page (`/matchboxv3/transform`) transforms a resource with a map that is already installed, in any mode.

## Distribute maps in an implementation guide

For production, put the maps into an implementation guide, and install it like any other package. The transformations
are then reproducible: everyone who installs the same version of the implementation guide gets the same maps.

1. Add the maps to your implementation guide as `StructureMap` resources (e.g. converted with
   [`$convert`](#convert-a-map-to-a-structuremap-without-storing-it)), together with the logical models and the
   ConceptMaps that they use. The [IG Publisher](https://confluence.hl7.org/display/FHIR/IG+Publisher+Documentation)
   can also take maps in FML directly.
2. Build the implementation guide and [install the package](load-implementation-guides.md) into Matchbox: at startup
   from the configuration, or with `$install-npm-package`.
3. Call `$transform` with the canonical URLs of the maps, as above. The development mode isn't needed.

List the maps that are available on the server with `$list`, which returns their title, URL and version:

```bash
curl http://localhost:8080/matchboxv3/fhir/StructureMap/\$list -H "Accept: application/fhir+json"
```

The [CDA-FHIR-Maps](http://fhir.ch/ig/cda-fhir-maps/index.html) implementation guide is an example: it contains the maps between the Swiss CDA and FHIR
exchange formats for medication, in both directions.

## Related

- [Transform CDA to FHIR](transform-cda-to-fhir.md)
- [Extract data from a QuestionnaireResponse](extract-questionnaire-response.md)
- [FHIR operations reference](../reference/operations.md#transform)
- [Mapping and transformation](../explanation/mapping.md)
