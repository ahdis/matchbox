# Tutorials

The tutorials are lessons: follow the steps in order, and at the end you will have done something useful with
Matchbox. They don't explain every option; the [how-to guides](../how-to/index.md) and the
[reference](../reference/index.md) cover the details.

| Tutorial                                                     | You will learn to                                                                        | You need                   |
|--------------------------------------------------------------|------------------------------------------------------------------------------------------|----------------------------|
| [Validate your first FHIR resource](first-validation.md)     | validate a resource on the public test server, read the result and fix an error          | a browser and `curl`       |
| [Run your own Matchbox](run-your-own-matchbox.md)            | start Matchbox with Docker, load an implementation guide and validate against its profile | Docker                     |
| [Transform data with the FHIR mapping language](first-mapping.md) | write a map, load it into Matchbox and transform a resource with it                  | Docker (or the test server) |
| [Embed matchbox-engine in a Java application](embed-matchbox-engine.md) | validate a resource from Java code, without a server                            | Java 17+ and Maven         |
