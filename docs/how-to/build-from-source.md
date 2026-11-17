# Build Matchbox from source

This guide shows how to build and run Matchbox from its source code, e.g. to contribute a change or to build your own
Docker image.

You need Java 21 (JDK), [Maven](https://maven.apache.org/), and [Node.js](https://nodejs.org/) with npm for the web
interface.

## Get the source

```bash
git clone https://github.com/ahdis/matchbox.git
```

The repository contains three modules: `matchbox-engine` (the library), `matchbox-server` (the FHIR server) and
`matchbox-frontend` (the web interface, in Angular).

## Build and run the server

```bash
cd matchbox
mvn clean install -DskipTests
cd matchbox-server
mvn spring-boot:run
```

The server is then available at [http://localhost:8080/matchboxv3/fhir](http://localhost:8080/matchboxv3/fhir).

To run it with one of the sample configurations:

```bash
java -Dspring.config.additional-location=file:with-preload/application.yaml -jar target/matchbox.jar
```

To debug it, start it with the debug port 5005 open and attach your IDE:

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
```

## Work on the web interface

Run the server (see above), then start the development server of the frontend, which forwards the API calls to
`http://localhost:8080`:

```bash
cd matchbox-frontend
npm install
npm start
```

The web interface is then at [http://localhost:4200](http://localhost:4200) and reloads when you change the code.

To include your changes in the server, build the frontend (the output goes to the static resources of
`matchbox-server`) and build the server again:

```bash
npm run build
cd ../matchbox-server
mvn clean install -DskipTests
```

## Run the tests

```bash
mvn clean test                        # all the tests of the engine and the server
mvn -Dtest=MatchboxApiR4Test test     # a single test class
cd matchbox-frontend && npm test      # the tests of the frontend
```

Tests that need an implementation guide use the [Matchbox test IG](https://github.com/ahdis/matchbox-test-ig)
(`matchbox.health.test.ig.r4`), whose package is in the test resources of both Java modules.

## Build the Docker image

```bash
mvn package -DskipTests
cd matchbox-server
docker build -t matchbox .
docker run -d --name matchbox -p 8080:8080 -m 4g -v ${PWD}/with-settings:/config matchbox
```

## Build the documentation

The documentation is in the `docs` directory and is built with [mkdocs-material](https://squidfunk.github.io/mkdocs-material/):

```bash
pip install -r requirements.txt
mkdocs serve
```

It is published to [ahdis.github.io/matchbox](https://ahdis.github.io/matchbox/) when a release is tagged.

## Contribute a change

!!! abstract "To write"
    The contribution workflow: issues and discussions on GitHub, branch and pull request against `ahdis/matchbox`, the
    required entry in `docs/changelog.md`, code style, the CI workflows that must pass, how the patched
    `org.hl7.fhir.core` classes in `matchbox-engine/src/main/java/org/hl7/fhir` are maintained (`updatehapi.sh`), and
    how to change the test IG.
