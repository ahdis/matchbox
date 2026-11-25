# Upgrade Matchbox

This guide shows how to move a running Matchbox (the Docker image or the matchbox-engine dependency) to a newer
version, and how to check that the validation results are still the ones you expect.

## Choose the version

Each release `X.Y.Z` is described in the [changelog](../changelog.md) and on the
[GitHub releases](https://github.com/ahdis/matchbox/releases) page. Release candidates `X.Y.Z-rcN` (e.g. `5.0.0-rc1`)
are published as GitHub pre-releases, to test a version before it is released: don't run them in production.

Pin the version you run, with the image tag `vX.Y.Z` or a fixed Maven version, rather than following `latest`. You then
decide when to upgrade, the result of a validation doesn't change without notice, and you can go back to the previous
version.

## Read the changelog

Read every section of the [changelog](../changelog.md) between your version and the new one, and look for:

- **the version of `org.hl7.fhir.core`** (_Update org.hl7.fhir.core to …_): this is the HL7 validator that Matchbox
  uses. A new version can report new issues, stop reporting others, or change the text of messages.
- **the bundled packages** (`hl7.terminology`, `hl7.fhir.uv.extensions`, the CDA logical model): a new version can
  change the results. For example, `hl7.fhir.uv.extensions` 5.3.0 (in Matchbox 4.1.12) allows some extensions in more
  contexts, so the errors about these extensions disappeared.
- **configuration parameters** that are new or have a new default. For example, Matchbox 4.1.12 added
  `matchbox.fhir.context.ssrfProtectionEnabled`, which must be set when you use a terminology server on a local
  address.
- **the version of HAPI FHIR** (_Update to HAPI FHIR …_): a new version can change the schema of the HAPI FHIR tables
  of the database, see [Migrate the HAPI FHIR schema](#migrate-the-hapi-fhir-schema).
- **database changes**: new tables or columns, and the migrations that fill them.
- **breaking changes** for your use case, e.g. a new version of the CDA logical model that requires changes to existing
  CDA maps.

### Upgrade to Matchbox 5

Matchbox 5 is based on Spring Boot 4, HAPI FHIR 9 and `org.hl7.fhir.core` 7. Coming from Matchbox 4:

- **The server runs on Tomcat instead of Undertow.** The `server.undertow.*` settings aren't used anymore: remove them
  from your configuration. Matchbox doesn't limit the size of the requests anymore, set the limit in your reverse proxy
  (see [Limit the request size](secure-production-deployment.md#limit-the-request-size)).
- **HAPI FHIR 9** (from 8.12.1): if you keep the database,
  [migrate the HAPI FHIR schema](#migrate-the-hapi-fhir-schema).
- **`org.hl7.fhir.core` 7** (from 6.10.4) brings new checks of the validator and changes in the terminology handling:
  [check the validation results](#check-the-validation-results).
- **matchbox-engine** uses the R6 model of `org.hl7.fhir.core` 7 instead of `org.hl7.fhir.r5.model` (see
  [Model classes](../reference/matchbox-engine-api.md#model-classes)). `FhirFormat` is now
  `org.hl7.fhir.model.utilities.formats.FhirFormat`, `createSnapshot` adds the snapshot to the StructureDefinition it
  is given instead of returning a new one, and `useTxCacheFolder(folder)` and `disableTxCache()` replace
  `initTxCache(folder)`.

## Back up the database

If your server keeps its database (a volume for the H2 database, or [PostgreSQL](use-postgresql.md)), back it up before
the upgrade. A new version can add tables and columns to the database, and the old version isn't guaranteed to work
with the changed database: the backup is your way back.

- PostgreSQL: [export the database](use-postgresql.md#export-and-import-the-data) with `pg_dump`.
- H2: stop Matchbox and copy the database directory (by default `/database` in the container, `./database` otherwise).

## Upgrade the Docker image

Change the tag of the image and start a new container with it:

```bash
docker pull europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:v5.0.0
docker rm -f matchbox
docker run -d --name matchbox -p 8080:8080 -m 4g \
  -v ${PWD}/config:/config \
  europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:v5.0.0
```

With docker-compose, change the `image` of the `matchbox` service, then run `docker-compose pull` and
`docker-compose up -d`.

What happens to the installed implementation guides depends on where the database is:

- **Database inside the container** (the default): the new container starts with an empty database and installs the
  implementation guides of the configuration again. There is nothing else to do.
- **Database that is kept** (volume or PostgreSQL): the new version uses the existing database. It adds the new tables
  and columns at startup, and fills them in background tasks (watch the log). The installed packages are kept and
  aren't installed again, because a package is only installed once per name and version (see
  [Reinstall an IG that has changed](load-implementation-guides.md#reinstall-an-ig-that-has-changed)). If the new
  version uses another version of HAPI FHIR, [migrate the HAPI FHIR schema](#migrate-the-hapi-fhir-schema) before you
  start it.

If the new version doesn't start with the existing database, or if you want to be sure that the packages are installed
by the new version, start with an empty database: the implementation guides of the configuration are installed again at
startup, which takes some time.

### Custom image

If you [built an image with pre-installed packages](build-custom-image.md), change the version in its `FROM` line and
build it again. The packages are installed during the build by the new version:

```dockerfile
FROM europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:v5.0.0
```

## Upgrade matchbox-engine

Change the version of the dependency:

```xml
<dependency>
    <groupId>health.matchbox</groupId>
    <artifactId>matchbox-engine</artifactId>
    <version>5.0.0</version>
</dependency>
```

Then:

- compile your application and look in the changelog for changes of the [Java API](../reference/matchbox-engine-api.md)
  if it doesn't compile;
- check that the versions of HAPI FHIR and `org.hl7.fhir.core` in your project match the ones of matchbox-engine
  (`mvn dependency:tree`): matchbox-engine patches some classes of `org.hl7.fhir.core` and needs the version it was
  built with;
- the bundled packages (FHIR core, terminology, extensions) come with the new version of the library. The packages that
  you load by name and version stay in the [package cache](../reference/matchbox-engine-api.md#package-cache) and are
  reused.

## Check the validation results

A new version of the validator can change the result of a validation. Before you put a new version in production,
compare the results of the old and the new version:

1. Collect a set of reference resources: valid and invalid examples for each profile you use, e.g. the examples of the
   implementation guides and real (anonymized) messages.
2. Validate them with the old version and keep the `OperationOutcome`s.
3. Start the new version next to the old one (with another port and its own database), validate the same resources
   and compare the issues: their severity, location (`expression`) and text. Ignore the first `information` issue,
   which contains the versions and the duration of the validation.
4. For each difference, decide whether it is a fix (the new result is correct), a regression to
   [report](https://github.com/ahdis/matchbox/issues), or an issue to suppress.

Review your [suppressed issues](suppress-validation-issues.md) too:

- `suppressWarnInfo` matches the **text** of the messages, which can change with a new validator version: a warning
  that was suppressed can appear again. Check the new text and update the substring or the regular expression.
- `suppressError` matches the **message id**, which is stable across versions; the errors stay suppressed.

Automate this comparison if you upgrade regularly, e.g. as tests that validate the reference resources and compare the
issues with the expected ones.

## Upgrade the database

### Migrate the HAPI FHIR schema

Most of the tables of the database belong to HAPI FHIR (`HFJ_…`, `NPM_…`, `BT2_…`, …), the server framework of
Matchbox. When a release of Matchbox updates HAPI FHIR, the schema of these tables can change: new tables and columns,
but also changed column types and lengths, new or removed indexes and constraints, and data that has to be converted.

At startup, Matchbox only lets Hibernate add the missing tables and columns (`hibernate.hbm2ddl.auto: update`). This is
how the tables of Matchbox itself (`MB_…`) are created, but it doesn't perform the other changes, and it doesn't record
which migrations of HAPI FHIR have been applied. A database that is only updated this way can drift from the schema
that the new HAPI FHIR version expects, which shows up as errors when packages are installed or resources are stored.
HAPI FHIR provides its own migration tool for this: the command `migrate-database` of the
[HAPI FHIR CLI](https://hapifhir.io/hapi-fhir/docs/tools/hapi_fhir_cli.html).

You need it if both are true:

- the database is kept across the upgrade (a volume for H2, or PostgreSQL). A database inside the container is
  created again by the new version;
- the HAPI FHIR version changes between your version and the new one. It is in the changelog (_Update to HAPI FHIR …_)
  and in the property `hapi.fhir.version` of the
  [`pom.xml`](https://github.com/ahdis/matchbox/blob/main/pom.xml) of each release.

Then:

1. **Get the CLI of the new HAPI FHIR version.** Download `hapi-fhir-<version>-cli.zip` (or `.tar.bz2`) of the HAPI
   FHIR version of the new Matchbox from the [HAPI FHIR releases](https://github.com/hapifhir/hapi-fhir/releases),
   e.g. `hapi-fhir-9.0.0-cli.zip` for Matchbox 5, and unpack it. It needs a Java runtime (Java 17 or newer). The
   CLI only knows the migrations up to its own version: use the version of the new Matchbox, not the latest one.
2. **Stop Matchbox** and [back up the database](#back-up-the-database). No server may use the database during the
   migration; with H2, the database file can only be opened by one process anyway.
3. **Check what the migration would do**, with `--dry-run` (`-r`): the SQL statements are logged, nothing is changed.

    PostgreSQL:

    ```bash
    ./hapi-fhir-cli migrate-database --dry-run \
      -d POSTGRES_9_4 \
      -u "jdbc:postgresql://localhost:5432/matchbox" \
      -n matchbox -p matchbox
    ```

    H2:

    ```bash
    ./hapi-fhir-cli migrate-database --dry-run \
      -d H2_EMBEDDED \
      -u "jdbc:h2:file:/path/to/database/h2" \
      -n sa -p ""
    ```

    Use the JDBC URL, user name and password of the `spring.datasource` of your configuration, seen from the machine
    where the CLI runs (e.g. `localhost` instead of the service name `matchbox-db` of docker-compose, and the directory
    of the H2 volume on the host). `POSTGRES_9_4` is the name of the driver for all versions of PostgreSQL; the other
    drivers are listed by `./hapi-fhir-cli help migrate-database`.

4. **Run the migration**: the same command without `--dry-run`. The CLI records the applied migrations in the table
   `FLY_HFJ_MIGRATION`, and runs only the ones that are missing; it can be run again safely.
5. **Start the new version of Matchbox.** Hibernate then adds the new tables and columns of Matchbox, and Matchbox runs
   its own data migrations (see the changelog).

Other options of `migrate-database`:

| Option                            | Effect                                                                                                              |
|-----------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `--skip-versions <versions>`      | Skips the given migrations (comma-separated schema versions, e.g. `4_1_0.20191214.2`), e.g. one that fails because its change already exists. |
| `--no-column-shrink`              | Doesn't reduce the length of columns, which can be slow on large tables.                                            |
| `--enable-heavyweight-migrations` | Also runs the optional migrations that HAPI FHIR marks as heavyweight; usually not needed.                         |
| `-x`, `--flags <flags>`           | Version-specific flags of the migrations, see the [HAPI FHIR documentation](https://hapifhir.io/hapi-fhir/docs/server_jpa/upgrading.html). |

A database that was created by Matchbox and never migrated with the CLI has no `FLY_HFJ_MIGRATION` table yet: the
first run checks all the migrations of HAPI FHIR against the existing schema. Test it on a copy of the database first
(e.g. restored from the backup into a test PostgreSQL), and look at the result of `--dry-run` before you migrate the
production database.

Don't disable `hibernate.hbm2ddl.auto` (e.g. with `none` or `validate`) after the migration: the tables of Matchbox
itself are still created and updated by Hibernate.

If the migration fails, restore the backup and run the previous version of Matchbox, or start the new version with an
empty database: the implementation guides of the configuration are then installed again.

### Move to PostgreSQL 18

The move of an existing database to PostgreSQL 18 is described in
[Use a PostgreSQL database](use-postgresql.md#move-an-existing-database-to-postgresql-18).

## Related

- [Changelog](../changelog.md)
- [Docker image reference](../reference/docker-image.md)
- [Package management](../explanation/package-management.md)
