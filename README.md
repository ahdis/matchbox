# Matchbox

[Matchbox](https://matchbox.health) is a FHIR server based on the [hapifhir/hapi-fhir-jpaserver-starter](https://github.com/hapifhir/hapi-fhir-jpaserver-starter)

- (pre-)load FHIR implementation guides from the package server for conformance resources (StructureMap, Questionnaire, CodeSystem, ValueSet, ConceptMap, NamingSystem, StructureDefinition). The "with-preload" subfolder contains an example with the implementation guides provided for the [public test server](https://test.ahdis.ch/matchbox/fhir).
- validation support: [server]/$validate for checking FHIR resources conforming to the loaded implementation guides
- FHIR Mapping Language endpoints for creation of StructureMaps and support for the [StructureMap/$transform](https://www.hl7.org/fhir/operation-structuremap-transform.html) operation
- SDC (Structured Data Capture) [extraction](https://build.fhir.org/ig/HL7/sdc/extraction.html#map-extract) support based on the FHIR Mapping language and [Questionnaire/$extract](http://build.fhir.org/ig/HL7/sdc/OperationDefinition-QuestionnaireResponse-extract.html)

the server can be run in two configurations, development (allowing updating resources, set flag in application.yaml in matchbox.fhir.context.onlyOneEngine to true) or deployment (default), see also additional [documentation](https://ahdis.github.io/matchbox)

a public development server is hosted at [https://test.ahdis.ch/matchbox/fhir](https://test.ahdis.ch/matchbox/fhir) with a corresponding gui [https://test.ahdis.ch/matchbox/](https://test.ahdis.ch/matchbox/#)

a public test server is hosted at [https://test.ahdis.ch/matchboxv3/fhir](https://test.ahdis.ch/matchboxv3/fhir) with a corresponding gui [https://test.ahdis.ch/matchboxv3/](https://test.ahdis.ch/matchboxv3/#)

## containers

The docker file will create a docker image with no preloaded implementation guides. A list of implementation guides to load can be passed as config-map.

## Prerequisites

- [This project](https://github.com/ahdis/matchbox) checked out. You may wish to create a GitHub Fork of the project and check that out instead so that you can customize the project and save the results to GitHub. Check out the main branch (master is kept in sync with [hapi-fhir-jpaserver-starter](https://github.com/hapifhir/hapi-fhir-jpaserver-starter)
- Oracle Java (JDK) installed: Minimum JDK11 or newer.
- Apache Maven build tool (newest version)

## Running locally

The easiest way to run this server entirely depends on your environment requirements. At least, the following 4 ways are supported:

## using prebuilt image

```
docker run -d --name matchbox -p 8080:8080  europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:v3.9.10 -v /Users/oegger/Documents/github/matchbox/matchbox-server/with-settings:/config matchbox
```

note replace /Users/oegger/Documents/github/matchbox/matchbox-server/with-settings with the folder where you have your application.yaml (and since v3.9.10) your [fhir-settings.json](https://confluence.hl7.org/display/FHIR/Using+fhir-settings.json).

### Using spring-boot

With no implementation guide:

```bash
mvn clean install -DskipTests spring-boot:run
```

Load example implementation guides (needs postgres):

```bash
mvn clean install -DskipTests spring-boot:run -Dspring-boot.run.arguments=--spring.config.additional-location=file:with-preload/application.yaml
```

or

```
java -Dspring.config.additional-location=file:with-preload/application.yaml -jar target/matchbox.jar
```

```
mvn clean install -DskipTests spring-boot:run -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
```

Then, browse to the following link to use the server:

[http://localhost:8080/matchbox/fhir](http://localhost:8080/matchboxv3/fhir)
or
[http://localhost:8080/matchbox/#/](http://localhost:8080/matchboxv3/#/)


## building with Docker

### Configurable base image:

```bash
mvn package -DskipTests
cd matchbox-server
docker build -t matchbox .
docker run -d --name matchbox -p 8080:8080 -v /Users/oegger/Documents/github/matchbox/matchbox-server/with-settings:/config matchbox
```

Server will then be accessible at http://localhost:8080/matchboxv3/fhir/metadata.

The Docker image includes a HEALTHCHECK that pings `http://localhost:8080/matchboxv3/actuator/health` by default. If you change the context path, set the `HEALTHCHECK_URL` environment variable accordingly:

```bash
docker run -d --name matchbox -p 8080:8080 -e HEALTHCHECK_URL=http://localhost:8080/mycontext/actuator/health matchbox
```

Note: In Kubernetes environments, Docker HEALTHCHECK is ignored — use `livenessProbe`/`readinessProbe` in your pod spec instead.

To dynamically configure run in a kubernetes environment and add a kubernetes config map that provides /config/application.yaml file with implementation guide list like in "with-preload/application.yaml"

## Using docker-compose with a persistent postgreSQL database

The database will be stored in the "data" directory. The configuration can be found in the "with-postgres" directory or in the "with-preload" directory.

Change to either with-posgres directory or the with-preload directory (contains a list of swiss ig's).

For the first time, you might need to do

```
docker-compose up matchbox-db
```

that the database gets initialized before matchbox is starting up (needs a fix)

```
mkdir data
mvn clean package -DskipTests
docker build -t matchbox .
docker-compose up
```

Matchbox will be available at [http://localhost:8080/matchboxv3/fhir](http://localhost:8080/matchboxv3/fhir)
Matchbox-gui will be available at [http://localhost:8080/matchboxv3/#/](http://localhost:8080/matchboxv3/#/)

Export the DB data:

```
docker-compose exec -T matchbox-test-db pg_dump -Fc -U matchbox matchbox > mydump
```

Reimport the DB data:

```
docker-compose exec -T matchbox-test-db pg_restore -c -U matchbox -d matchbox < mydump
```

### making container available

```
docker tag matchbox eu.gcr.io/fhir-ch/matchbox:4.0.12

docker push eu.gcr.io/fhir-ch/matchbox:v4.0.12
```

### publish docs

documentation is maintained in docs folder using [mkdocs-material](https://squidfunk.github.io/mkdocs-material/):

- develop docs: mkdocs serve
- publish docs: mkdocs gh-deploy --force

docs are then available at https://ahdis.github.io/matchbox/

# Kubernetes

kubectl cp matchbox-test-0:fhir.logdir_IS_UNDEFINED ./fhir.logdir/

kubectl cp matchbox-test-app-d684cf865 ./fhir.logdir/

# FHIR-to-OMOP ID Mapping

When running StructureMaps from a FHIR-to-OMOP IG, integer ID assignment is a known infrastructure problem. FHIR uses string logical IDs; OMOP requires integer primary keys. StructureMap has no built-in sequence generator, so the mapping needs external support.

## The problem

OMOP tables require integer primary keys (`person_id`, `visit_occurrence_id`, etc.). When a Condition references `Patient/abc-123`, the resulting `condition_occurrence.person_id` must match whatever integer was assigned to that patient — whether the two resources arrive in the same Bundle or separately.

## What the HL7 FHIR-to-OMOP IG says

The [HL7 FHIR-to-OMOP IG](https://build.fhir.org/ig/HL7/fhir-omop-ig/en/) explicitly acknowledges this as an unsolved problem:

> "There is no single approach that can be uniformly applied to transformation of FHIR identifiers to OMOP."

Its recommended approach is an external mapping table linking `[ResourceType]/[Resource.id]` to OMOP-generated integer IDs, maintained outside the core OMOP schema. No concrete implementation is provided.

The IG's own StructureMaps reflect this gap. The Condition map (`StructureMap/ConditionMap`) uses hardcoded placeholder values for all three integer ID fields:

```
src.id as id    -> tgt.condition_occurrence_id = '1';
src.subject     -> tgt.person_id = '1';
src.encounter   -> tgt.visit_occurrence_id = '1';
```

Comments in the map note that these should be "a map from FHIR ID to an external key." The IG explicitly leaves implementation to the deployer. Notable: the IG also populates `condition_source_value` from the coding display text rather than from a FHIR identifier, so it does not follow a `*_source_value`-for-traceability pattern.

## Why $translate via the terminology server is not the right fit

Matchbox is configured with a single terminology server (currently Echidna). Routing ID assignment through `$translate` would entangle ID management with terminology lookups on that server. Echidna is a standardized FHIR terminology server and should not need modification for this purpose.

Additionally, `ConceptMapEngine` requires the ConceptMap resource to be loaded locally before it will contact the terminology server — a null ConceptMap causes an immediate exception, not a server fallback.

## Preferred approach: custom StructureMap function

The cleanest solution is a custom function — e.g. `omopId(fhirReference)` — implemented in `FHIRPathHostServices.executeFunction()`. The hooks (`resolveFunction`, `checkFunction`, `executeFunction`) exist in the codebase but are currently stubs. The function would be called directly from StructureMap rules at each integer ID assignment point.

The StructureMap change is minimal and mechanical — replacing each `= '1'` placeholder with a function call:

```
src.id as id    -> tgt.condition_occurrence_id = omopId(id);
src.subject     -> tgt.person_id = omopId(src.subject);
src.encounter   -> tgt.visit_occurrence_id = omopId(src.encounter);
```

### Hash-based (recommended starting point)

Hash the input string to a 32-bit integer deterministically. Because the hash is a pure function, the same FHIR reference always produces the same OMOP integer across any number of separate `$transform` calls — no state or persistence required. Collision probability is negligible at OMOP data scales.

### Database-backed

Use Matchbox's existing H2/PostgreSQL to store a `fhir_id → omop_id` mapping table. Adds auditability and supports truly sequential IDs if needed downstream, at the cost of a persistent dependency.

## Logical IDs vs. business identifiers

This choice of hash input matters for deployments that aggregate data from multiple FHIR servers.

**Logical IDs** (`resource.id`) are server-relative. `Patient/abc-123` on server A is unrelated to `Patient/abc-123` on server B. Hashing a bare logical ID is only safe in single-source deployments.

**Business identifiers** (`resource.identifier`, a system+value pair — e.g. SSN, MRN, NPI) can be globally unique when the `system` is a well-known namespace. Hashing the composite `system|value` produces a stable integer that survives server migrations and re-ingestion across sources.

In practice: use a business identifier where one is reliably present; fall back to a server-qualified logical ID (e.g. `https://myserver.org/fhir/Patient/abc-123`) otherwise. Not all resource types carry usable business identifiers (Observation, Condition often do not).

# MVN run unit tests

mvn -Dtest=CapabilityStatementTests test

# Making a release

1. Create a pull request that updates the version in the different files (pom.xml files, package.json, the docker pull
   command in documentation, the changelog, etc.).
2. Merge the pull request if all tests have succeeded.
3. Wait for the [Angular workflow](https://github.com/ahdis/matchbox/blob/main/.github/workflows/angular_build.yml)
   to complete. Since the package.json was modified, the Angular project is rebuilt, unless you also have built the 
   Angular project in your commit ; in that case, the Angular workflow won't commit anything.
4. Create a [release](https://github.com/ahdis/matchbox/releases) with the changelog (e.g. "matchbox v3.4.1") and a
   [tag](https://github.com/ahdis/matchbox/tags) (e.g. `v.3.4.1`) in GitHub.
5. It will trigger two workflows:
   1. The [Docker workflow](https://github.com/ahdis/matchbox/blob/main/.github/workflows/googleregistry.yml), that
      builds a Docker container around `matchbox-server` and publishes it to the Google Artifact registry.
   2. The [Maven workflow](https://github.com/ahdis/matchbox/blob/main/.github/workflows/central_repository.yml), that
      builds the `matchbox-engine` JAR and publishes it to the Maven Central Repository. The version used is the one
      specified in the POM.

