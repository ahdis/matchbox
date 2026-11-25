# Load implementation guides

Matchbox validates and transforms with the conformance resources (profiles, value sets, maps, …) of the implementation
guides (IGs) installed in its database. This guide shows the different ways to install them.

## Install IGs at startup

List the IGs in `hapi.fhir.implementationguides` of your `application.yaml`. They are installed into the database when
the server starts:

```yaml
hapi:
  fhir:
    implementationguides:
      fhir_r4_core:
        name: hl7.fhir.r4.core
        version: 4.0.1
        url: classpath:/hl7.fhir.r4.core.tgz
      ch-core:
        name: ch.fhir.ig.ch-core
        version: 4.0.1
      ch-elm:
        name: ch.fhir.ig.ch-elm
        version: 1.7.0
        url: https://build.fhir.org/ig/ahdis/ch-elm/package.tgz
```

The key of each entry (`ch-core`) is free. `name` and `version` are the ones of the package; `url` says where the
package comes from. Without `url`, it is downloaded from the FHIR package servers. All the possible values are in the
[configuration reference](../reference/configuration.md#implementation-guides).

## Install an IG from a file

Use a `file:` URL for a package that isn't published (e.g. the `output/package.tgz` of your own IG Publisher build), or
when the server has no internet access:

```yaml
hapi:
  fhir:
    implementationguides:
      my-ig:
        name: example.fhir.ig.my-ig
        version: 0.1.0
        url: file:/Users/me/github/my-ig/output/package.tgz
```

- The path must be **absolute** (`file:/path/package.tgz` or `file:///path/package.tgz`), a relative path isn't
  supported.
- `name` and `version` must be the `name` and `version` of the `package.json` in the package.
- The file must exist and be readable when the server starts.

### In Docker

The path is the path inside the container: mount the directory with the packages as a volume.

```bash
docker run -d --name matchbox -p 8080:8080 -m 4g \
  -v ${PWD}/config:/config \
  -v ${PWD}/packages:/packages \
  europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:latest
```

with `config/application.yaml`:

```yaml
hapi:
  fhir:
    implementationguides:
      my-ig:
        name: example.fhir.ig.my-ig
        version: 0.1.0
        url: file:/packages/example.fhir.ig.my-ig-0.1.0.tgz
```

## Reinstall an IG that has changed

A package is installed only once: if the database already contains a package with the same name and version, the file
isn't read again, even if its content has changed. When you rebuild your IG without changing its version, either:

- raise the version of the IG, or
- delete the database (by default the H2 database in `./database`) and restart.

Packages with an `http` URL on `localhost` or `build.fhir.org` are the exception: they are installed again at every
start.

## Start without internet access

The dependencies of a package (from its `package.json`) are installed too. Matchbox takes them from the database if they
are already installed there, otherwise it downloads them from the FHIR package servers. To start without internet
access:

1. list the dependencies in `hapi.fhir.implementationguides` as well (e.g. as `file:` URLs), before the IG that depends
   on them;
2. set `matchbox.fhir.context.txServer` to `n/a` or to a terminology server you can reach (see
   [Configure a terminology server](configure-terminology-server.md)).

`hl7.fhir.r4.core` and the versions of `hl7.terminology`, `hl7.fhir.uv.extensions` and the cross-version extension
packages bundled with Matchbox are not downloaded (see the [list of bundled packages](../reference/matchbox-engine-api.md#bundled-packages)).

To avoid installing the packages at every container start, [build an image with the packages pre-installed](build-custom-image.md).

## Install an IG while the server runs

If the server isn't in [read-only mode](secure-production-deployment.md#read-only-mode), send the package to the
operation [`$install-npm-package`](../reference/operations.md#install-npm-package):

```bash
curl -X POST "http://localhost:8080/matchboxv3/fhir/\$install-npm-package?name=ch.fhir.ig.ch-core&version=4.0.1" \
  -H "Content-Type: application/gzip" \
  --data-binary @ch.fhir.ig.ch-core-4.0.1.tgz
```

The `$validate` operation can also install a missing IG by itself, see
[auto-installation](../reference/operations.md#auto-install-ig).

## Check the installed IGs

- In the web interface, open the page of the implementation guides.
- With the FHIR API: `GET [base]/ImplementationGuide`.
