# Running matchbox in docker

You can run matchbox directly in docker.

```bash
docker run -d --name matchbox -p 8080:8080 -e matchbox.fhir.context.onlyOneEngine=true -v ${PWD}/config/:/config/ europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:latest
docker logs --follow matchbox
```
If you see A 'FHIR has been lit on this server' you can point your browser to http://localhost:8080/matchboxv3/fhir/metadata.

The optional local volume /Users/oliveregger/config/ will be mapped inside the container and Matchbox will use [fhir-settings.json](https://confluence.hl7.org/display/FHIR/Using+fhir-settings.json) and application.yaml for additional configuration see [https://github.com/ahdis/matchbox/tree/main/matchbox-server](https://github.com/ahdis/matchbox/tree/main/matchbox-server) different directories started with with-xxx for sample configurations.

The parameter (matchbox.fhir.context.onlyOneEngine) is to set development environment, which allows you to create/update conformance resources (e.g. transform StructureMaps). If not provided, you need to provide the conformance resources by an FHIR Implementation Guide.

We recommend a memory limit of 4 GB for the container (e.g. `docker run -m 4g ...` or `resources.limits.memory: 4Gi` in
Kubernetes), which is enough for a typical setup of implementation guides; see [JVM options](#jvm-options).

## JVM options

The image sets the JVM options through the `JDK_JAVA_OPTIONS` environment variable, which defaults to
`-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication`.

With `-XX:MaxRAMPercentage=70` the maximum heap is 70% of the memory limit of the container: a container with a limit of
4 GB (`-m 4g`) gets a heap of 2.8 GB, and the rest remains for the memory matchbox needs outside the heap. **Set a memory
limit for the container**: without one, the heap is sized from the memory of the host (e.g. of the Docker Desktop VM) and
can grow to 70% of it.

A memory limit of **4 GB** is enough for a typical setup of implementation guides. Measured with the Swiss IGs of
`with-preload` (12 IGs with their dependencies, 55 packages, each validated in its own engine), matchbox needs about
1.3 GB of live heap; a single IG such as ch-elm needs about 0.7 GB (a limit of 2 GB is enough then). Plan more memory
for many more IGs in use at the same time, for large documents or many parallel requests.

With `-XX:+ExitOnOutOfMemoryError` the JVM exits
on the first `OutOfMemoryError` instead of continuing in an undefined state, so that the container can be restarted
(e.g. with `--restart unless-stopped` or by Kubernetes). With `-XX:+UseStringDeduplication` the garbage collector
merges identical strings in the background. The loaded implementation guides and their dependencies, often in several
versions, contain many identical strings, so this reduces the memory needed by the validation engines (by about 15% for
the ch-elm implementation guide).

```bash
docker run -d --name matchbox -p 8080:8080 -m 4g europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:latest
```

You can override the options, e.g. to set a fixed heap size:

```bash
docker run -d --name matchbox -p 8080:8080 -m 4g -e JDK_JAVA_OPTIONS="-Xmx2g -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication" europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:latest
```

Setting `JDK_JAVA_OPTIONS` replaces the default, so include a heap setting (`-XX:MaxRAMPercentage` or `-Xmx`),
`-XX:+ExitOnOutOfMemoryError` and `-XX:+UseStringDeduplication` together with any other option you add, otherwise the JVM uses its default of 25% of the
container memory and keeps running after an `OutOfMemoryError`. `-Xmx` takes precedence over `-XX:MaxRAMPercentage`, and
a fixed `-Xmx` doesn't follow the memory limit of the container: keep it well below the limit, matchbox also needs a
substantial amount of memory outside the heap, otherwise the container is killed when it exceeds its limit.

Arguments given after the image name are passed to matchbox as Spring Boot arguments, e.g.
`--matchbox.fhir.context.onlyOneEngine=true`.

## Live and Readiness checks

To check if the container is live and ready you can check the health:

```http
GET http:///localhost:8080/matchboxv3/actuator/health HTTP/1.1
Accept: application/vnd.spring-boot.actuator.v3+json

HTTP/1.1 200
Content-Type: application/vnd.spring-boot.actuator.v3+json
Transfer-Encoding: chunked
Date: Thu, 02 Feb 2023 15:55:12 GMT
Via: 1.1 google
Alt-Svc: h3=":443"; ma=2592000,h3-29=":443"; ma=2592000
Connection: close

{
  "status": "UP",
  "groups": [
    "liveness",
    "readiness"
  ]
}
```

You can also use actuator/health/liveness or actuator/health/readiness.

To check the amount of memory used by the jvm use: /actuator/metrics/jvm.memory.used

## Using docker-compose with a persistent postgreSQL database

To use docker-compose with Matchbox you need to check out Matchbox from [github](https://github.com/ahdis/matchbox).

The database will be stored in the "data" directory. The configuration can be found in the "with-postgres" directory or in the "with-preload" directory.

Change to either with-posgres directory or the with-preload directory

For the first time, you might need to do

```
mkdir data
docker-compose up matchbox-db
```

that the database gets initialized before Matchbox is starting up (needs a fix)

```
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

## Configure an own docker image with preinstalled packages

During a regular container startup all implementation guides will be deployed to the database. This packages can be provided by the

1. FHIR package servers
2. absolute http address to package
3. classpath
4. filesystem

If you want to prepare a container which does not need internet access during the startup (required by 1 and 2) you can
build a new container image will do the download and installation packages already during the startup process (adding this line
into the Dockerfile):

```
RUN java -Xmx1G -Xms1G -jar /matchbox.jar --hapi.fhir.only_install_packages=true
```
