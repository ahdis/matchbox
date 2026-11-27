# Use a PostgreSQL database

By default, Matchbox stores its data (installed packages, resources, validation statistics) in an embedded H2 database.
This guide shows how to use PostgreSQL instead, which is recommended when the data must persist or the database is
shared.

## Configure the database connection

Add the datasource to your `application.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/matchbox
    username: matchbox
    password: matchbox
    driverClassName: org.postgresql.Driver
  jpa:
    properties:
      hibernate.dialect: ca.uhn.fhir.jpa.model.dialect.HapiFhirPostgresDialect
```

The same values can be given as environment variables (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`, …).

## Run Matchbox and PostgreSQL with docker-compose

The repository contains two docker-compose setups with PostgreSQL:

- [`with-postgres`](https://github.com/ahdis/matchbox/tree/main/matchbox-server/with-postgres): Matchbox without
  implementation guides;
- [`with-preload`](https://github.com/ahdis/matchbox/tree/main/matchbox-server/with-preload): Matchbox with the Swiss
  implementation guides of the public test server.

1. Check out the [repository](https://github.com/ahdis/matchbox) and change to one of the directories, e.g.
   `matchbox-server/with-postgres`. The compose file uses the image `matchbox:latest`: build it first (see
   [Build Matchbox from source](build-from-source.md)) or change the image to
   `ghcr.io/ahdis/matchbox:latest`.
2. Create the directory for the database and start the database alone the first time, so that it is initialized
   before Matchbox connects to it:

    ```bash
    mkdir data
    docker-compose up matchbox-db
    ```

3. Start everything:

    ```bash
    docker-compose up
    ```

Matchbox is available at [http://localhost:8080/matchboxv3/fhir](http://localhost:8080/matchboxv3/fhir), its web
interface at [http://localhost:8080/matchboxv3/](http://localhost:8080/matchboxv3/). The database is stored in the
`data` directory.

## Export and import the data

Export the database:

```bash
docker-compose exec -T matchbox-db pg_dump -Fc -U matchbox matchbox > mydump
```

Import it again (replacing the existing data):

```bash
docker-compose exec -T matchbox-db pg_restore -c -U matchbox -d matchbox < mydump
```

## Move an existing database to PostgreSQL 18

The compose files use PostgreSQL 18, which keeps its data in a subdirectory per major version (`data/18/docker`), so
the `data` directory is mounted at `/var/lib/postgresql`. A `data` directory created with an earlier version of these
files (PostgreSQL 17 or older, mounted at `/var/lib/postgresql/data`) isn't used by PostgreSQL 18. Either keep the old
mount and pin the image to the old version, or move the database to PostgreSQL 18 with a dump:

```bash
# with the old docker-compose.yml, and the image pinned to the version that created the data (e.g. postgres:17)
docker-compose up -d matchbox-db
docker-compose exec -T matchbox-db pg_dump -Fc -U matchbox matchbox > mydump
docker-compose down
mv data data-old && mkdir data

# with the new docker-compose.yml
docker-compose up -d matchbox-db
# wait until 'docker-compose logs matchbox-db' shows "PostgreSQL init process complete"
docker-compose exec -T matchbox-db pg_restore -U matchbox -d matchbox < mydump
docker-compose up -d
```

## Related

- [Configuration reference](../reference/configuration.md)
- [Upgrade Matchbox](upgrade-matchbox.md)
