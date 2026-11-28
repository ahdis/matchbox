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

To include your changes in the server, build the frontend into the static resources of `matchbox-server` and build the
server again:

```bash
npm run build-matchbox
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

The documentation is in the `docs` directory and is built with [ProperDocs](https://pypi.org/project/properdocs/)
(configuration in `properdocs.yml`):

```bash
pip install -r requirements.txt
properdocs serve
```

It is published to [ahdis.github.io/matchbox](https://ahdis.github.io/matchbox/) when a release is tagged.

## Contribute a change

### Discuss it first

Report bugs and propose features in the [issues](https://github.com/ahdis/matchbox/issues); ask questions in the
[discussions](https://github.com/ahdis/matchbox/discussions). For a larger change, open an issue before you start, so
that the approach can be agreed on.

### Open a pull request

1. Create a branch from `main` (in your fork if you can't push to `ahdis/matchbox`).
2. Make the change, with tests: the Java tests are in `src/test` of each module.
3. Add an entry to [`docs/changelog.md`](../changelog.md), under the release at the top of the file: every pull request
   needs one. Describe the change for the users of Matchbox, and link the issue (`[#643](https://github.com/ahdis/matchbox/issues/643)`).
4. Update the documentation if the behavior or the configuration changes. Each page belongs to one of the four
   sections ([Diátaxis](https://diataxis.fr)); a new page must be added to the `nav` of `properdocs.yml`, and a moved
   page needs a redirect from its old address (`redirect_maps` of the `redirects` plugin).
5. Open the pull request against `ahdis/matchbox`. The repository is a fork of the HAPI FHIR JPA server starter: make
   sure that GitHub doesn't propose `hapifhir/hapi-fhir-jpaserver-starter` as the base repository.

    ```bash
    gh pr create --repo ahdis/matchbox
    ```

Don't commit the built frontend: when a change of `matchbox-frontend` is merged into `main`, a workflow builds it and
commits the result to `matchbox-server/src/main/resources/static`.

### Follow the code style

The formatting is defined in [`.editorconfig`](https://github.com/ahdis/matchbox/blob/main/.editorconfig), which most
IDEs apply: tabs for Java (width 3), two spaces for the other files, 120 characters per line at most. The classes copied
from HAPI FHIR and `org.hl7.fhir.core` (`ca/uhn/fhir/**` and `org/hl7/fhir/**`) keep the formatting of their project,
so that their patches stay easy to compare. For the frontend, run `npm run lint` and format with Prettier.

### Pass the checks

These workflows run on every pull request to `main` and must pass:

| Workflow                | What it checks                                                                                                    |
|-------------------------|-------------------------------------------------------------------------------------------------------------------|
| _Unit tests_            | The Maven tests of `matchbox-engine` and `matchbox-server`                                                        |
| _Integration tests_     | The tests of [matchbox-int-tests](https://github.com/ahdis/matchbox-int-tests), against the version being built  |
| _Javadoc test_          | That the Javadoc can be generated                                                                                 |
| _Test the Angular GUI_  | That the frontend can be built                                                                                    |
| _Security checks_       | The analysis of the Java code with CodeQL and of the dependencies with Trivy                                     |

Run the tests locally before you push (see [Run the tests](#run-the-tests)).

### Update the patched classes of the validator

Matchbox depends on [`org.hl7.fhir.core`](https://github.com/hapifhir/org.hl7.fhir.core), the library of the HL7
validator, and patches some of its classes. The patched copies are in `matchbox-engine/src/main/java/org/hl7/fhir`
(and a few of HAPI FHIR in `matchbox-server/src/main/java`); they take precedence over the original classes on the
classpath. Each patch is marked with a `matchbox patch` comment.

To change a patched class, edit the copy in Matchbox and mark your change with a `matchbox patch` comment. Don't change
the code around it: the copies are replaced at each update of `org.hl7.fhir.core`.

To update `org.hl7.fhir.core`:

1. Change `fhir.core.version` in the root `pom.xml`.
2. Copy the new versions of the patched classes into Matchbox, which removes the patches:
     - `./updatehapi.sh` copies them from a checkout of the release tag of `org.hl7.fhir.core` (and of `hapi-fhir`)
       next to the Matchbox repository;
     - or `./updatehapi.ps1` downloads every file of `matchbox-engine/src/main/java/org/hl7/fhir` from GitHub, at the
       version of `pom.xml` (`-DryRun` shows what would change).
3. With `git diff`, re-apply each `matchbox patch` on the new code, without reverting the upstream changes.
4. Fix the compilation errors due to API changes, then run all the tests.

The steps and the list of patches are described in detail in
[`udpatecoreversion.md`](https://github.com/ahdis/matchbox/blob/main/udpatecoreversion.md). When you patch a new
class, add it to `updatehapi.sh`.

### Change the test implementation guide

Tests that need an implementation guide (profiles, examples, a package to load) use the
[Matchbox test IG](https://github.com/ahdis/matchbox-test-ig) (`matchbox.health.test.ig.r4`) rather than a real
implementation guide, so that they don't depend on the content of external packages. To add what a test needs:

1. Check out [matchbox-test-ig](https://github.com/ahdis/matchbox-test-ig) next to the Matchbox repository, and add
   the profiles or examples (in FSH) to it.
2. Raise the version in its `sushi-config.yaml`, add the entry to its `CHANGELOG.md`, build it with SUSHI and the IG
   Publisher and commit. Every package that goes into the Matchbox repository has its own version.
3. Run its `dist.sh`: it copies `output/package.tgz` as `matchbox.health.test.ig.r4-<version>.tgz` to both
   `matchbox-server/src/test/resources` and `matchbox-engine/src/test/resources`, and removes the old package.
4. Update the references to the version, e.g. in `application-test-r4.yaml`: search for `matchbox.health.test.ig.r4`.

The test IG and matchbox-engine both use the latest version of `hl7.terminology.r4` and `hl7.fhir.uv.extensions.r4`
(`PACKAGE_R4_TERMINOLOGY` and `PACKAGE_R4_UV_EXTENSIONS` in `MatchboxEngine`), and it has to be the same one: with
another version, the test server downloads the packages and their dependencies from the registry when it starts.

When a new version of one of the two packages is released, update both in the same pull request:

1. Replace the bundled packages for R4 and R5 in `matchbox-engine/src/main/resources` with the new ones from
   `https://packages2.fhir.org/packages/<package>/<version>`, and update the constants in `MatchboxEngine` and the
   other references to the old version (search for it, e.g. `hl7.terminology#` in `IgLoaderFromJpaPackageCache`).
2. Build a new version of the test IG with the same dependency and add it as described above (`dist.sh` checks that
   the dependencies are the bundled versions).
