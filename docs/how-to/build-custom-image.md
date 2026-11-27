# Build an image with pre-installed packages

Instead of installing the implementation guides at every container start, you can build your own image that already
contains them in its database. The container then starts faster, and without access to the package servers or to the
package files.

## Write the Dockerfile

Put the packages in a `packages` directory and write an `application.yaml` that references them with `file:` URLs (see
[Load implementation guides](load-implementation-guides.md#install-an-ig-from-a-file)). Then copy both into the image
and install the packages during the build with `--hapi.fhir.only_install_packages=true` (Matchbox installs the packages
into the database and stops):

```dockerfile
FROM ghcr.io/ahdis/matchbox:v5.0.0

COPY ./application.yaml /config/
COPY ./packages/*.tgz /packages/

RUN java -Xmx1G -Xms1G -jar /matchbox.jar --hapi.fhir.only_install_packages=true
```

## Build and run it

```bash
docker build -t my-matchbox .
docker run -d --name matchbox -p 8080:8080 -m 4g my-matchbox
```

With the default H2 database, the installed packages are part of the image. Use the same `application.yaml` (with the
`file:/packages/...` URLs) for the build and at runtime.

## Example

[matchbox-ch-elm](https://github.com/ahdis/matchbox-ch-elm) is an example of such an image, configured for the
validation of the ch-elm implementation guide.
