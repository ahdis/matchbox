# Matchbox Server

Matchbox is a FHIR server that can be deployed as a microservice in your IT infrastructure.
It includes all features from Matchbox-engine, plus additional features that are API-specific.

## Configuration

The following configuration parameters are available for the Matchbox server.
They can be set in the Spring configuration (e.g. `application.properties`/`application.yml`), or as system properties.

| Parameter                                           | Default value | Description                                                                                                                                                     |
|-----------------------------------------------------|---------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `matchbox.fhir.context.fhirVersion`                 | `4.0.1`       | The FHIR version of the server.                                                                                                                                 |
| `matchbox.fhir.context.txServer`                    | `n/a`         | The URL of the terminology server to use, or `n/a` not to use a terminology server.                                                                             |
| `matchbox.fhir.context.igsPreloaded`                | `[]`          | The list of IGs to always pre-load when initializing a Matchbox engine.                                                                                         |
| `matchbox.fhir.context.onlyOneEngine`               | `false`       | Forces the server to initialize only one engine. See the section [_Only one engine_](#only-one-engine) below.                                                   |
| `matchbox.fhir.context.fetchCacheSize`              | `100`         | The number of resources each engine keeps converted for `resolve()` in FHIRPath expressions (e.g. ConceptMaps in invariants), `0` to keep none. |
| `matchbox.fhir.context.suppressWarnInfo`            | `{}`          | The list of warnings/infos to suppress in validation reports. See [_Suppress warning/information-level issues in validation_](validation.md#suppress-warnings). |
| `matchbox.fhir.context.suppressError`               | `{}`          | The list of errors to suppress in validation reports. See [_Suppress error-level issues in validation_](validation.md#suppress-errors).                         |
| `matchbox.fhir.context.httpReadOnly`                | `false`       | Whether the server is in read-only mode or not. See the section [_Read-only mode_](#read-only) below.                                                           |
| `matchbox.fhir.context.extensions`                  | `any`         | The list of domains allowed in extensions while validating resources; `any` will allow all extensions.                                                          |
| `matchbox.validation.save-statistics`               | `false`       | Whether the Operation Outcome containing the validation results should be stored or not.                                                                        |
| `matchbox.fhir.context.llm.provider`                |               | The LLM provider used for the AI analysis of validation.                                                                                                        |
| `matchbox.fhir.context.llm.modelName`               |               | The LLM model used for the AI analysis of validation.                                                                                                           |
| `matchbox.fhir.context.llm.apiKey`                  |               | Your API key for the desired LLM provider.                                                                                                                      |
| `matchbox.fhir.context.ssrfProtectionEnabled`       | `true`        | Whether the [SSRF protection](https://github.com/hapifhir/org.hl7.fhir.core/blob/master/SECURITY.md#network-access) is enabled or not.                          |
| `matchbox.fhir.validation.analyzeErrorsWithLlm`     | `false`       | Whether the validation outcome should be analyzed by a LLM, when it includes `error` or `fatal` issues, or not. Requires the LLM parameters to be correctly set |
| `matchbox.fhir.mcp.requestAnalysisFromClient`       | `false`       | Whether to request an analysis of the validation outcome by the MCP client or not.                                                                              |
| `spring.ai.mcp.server.enabled`                      |               | Whether Matchbox should be provided as MCP-Server or not.                                                                                                       |

In addition for validation the different [java validator parameters](https://confluence.hl7.org/spaces/FHIR/pages/35718580/Using+the+FHIR+Validator) can also be configured for default values: e.g: matchbox.fhir.context.displayIssuesAreWarnings is set default to true, but you can overwrite that by providing another value. To see the current supported list of parameters, you can check the OperationDefinition of $validate on Matchbox [test instance](https://test.ahdis.ch/matchboxv3/fhir/OperationDefinition/-s-validate).

See an example of configuration, to show the expected format:

```yaml
matchbox:
  fhir:
    context:
      displayIssuesAreWarnings: false
      igsPreloaded: ch.fhir.ig.ch-elm#1.4.0
      suppressWarnInfo:
        hl7.fhir.r4.core#4.0.1:
          - "Constraint failed: dom-6:"
          - "regex:Entry '(.+)' isn't reachable by traversing forwards from the Composition"
        ch.fhir.ig.ch-elm:
          - "regex:Binding for path (.+) has no source, so can't be checked"
          - "regex:None of the codings provided are in the value set 'Observation Interpretation Codes'(.*)"
      llm:
        provider: anthropic
        modelName: claude-3-5-sonnet-20241022
        apiKey: sk-xxx
    validation:
      analyzeErrorsWithLlm: true

```

The HAPI configuration parameters are also available.
The following example shows the use of the most commonly used parameters:

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

See [_Providing implementation guides_](#implementationguides) below for where the packages can come from, e.g. from
a local file.

Some more configuration parameters used by validation are described in the
[Validation](validation.md#configuration-parameters) page.

### Providing implementation guides {: #implementationguides}

The implementation guides listed in `hapi.fhir.implementationguides` are installed into the database when the server
starts. The `url` of each entry says where the package (the `package.tgz` of an IG) comes from:

| `url`                   | Example                                             | Package is loaded from                                         |
|-------------------------|-----------------------------------------------------|----------------------------------------------------------------|
| _(none)_                |                                                     | the FHIR package servers (packages.fhir.org), by name/version  |
| `https://` or `http://` | `https://build.fhir.org/ig/ahdis/ch-elm/package.tgz` | the given address                                              |
| `file:`                 | `file:/packages/ch.fhir.ig.ch-elm.tgz`              | the filesystem of the server (or of the container)             |
| `classpath:`            | `classpath:/hl7.fhir.r4.core.tgz`                   | the classpath of Matchbox (packages built into `matchbox.jar`) |

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
- The file must exist and be readable when the server starts. When running in Docker, the path is the path inside the
  container, so mount the directory with the packages as a volume, see
  [Docker](docker.md#packages-from-files).
- A package is installed only once: if the database already contains a package with the same name and version, the
  file isn't read again, even if its content has changed. When you rebuild your IG without changing its version,
  delete the database (by default the H2 database in `./database`) or raise the version, so that the new content is
  installed. (Packages with an `http` URL on `localhost` or `build.fhir.org` are the exception, they are installed
  again at every start.)
- The **dependencies** of the package (from its `package.json`) are installed too. Matchbox takes them from the
  database if they are already installed there, otherwise it downloads them from the FHIR package servers. To start
  without internet access, also list the dependencies in `hapi.fhir.implementationguides` (e.g. as `file:` URLs),
  before the IG that depends on them. `hl7.fhir.r4.core` and the versions of `hl7.terminology`,
  `hl7.fhir.uv.extensions` and the cross-version extension packages bundled with Matchbox (see `MatchboxEngine`) are not
  downloaded.

To install the packages without a server restart, see
[_Installing an NPM package through the operation $install-npm-package_](#install-npm-package). To ship them in a
docker image, see [_Configure an own docker image with preinstalled packages_](docker.md#own-image).

### Maximum request size

Matchbox runs on the embedded Tomcat of Spring Boot (Spring Boot 4 dropped the support for Undertow), which does
not limit the size of FHIR request bodies (`server.tomcat.max-http-form-post-size` only applies to form posts). The
former `server.undertow.max-http-post-size` setting is not used anymore. Note that a reverse proxy in front of Matchbox
may enforce its own limit.

## Read-only mode {: #read-only}

When enabling `httpReadOnly`, the server will reject any operation that would modify its state.
In particular:

1. The following FHIR operations are disabled: `CREATE`, `DELETE`, `UPDATE`, `PATCH`, `UPDATE_REWRITE_HISTORY`,
   `TRANSACTION`, `BATCH`, `ADD_TAGS`, `DELETE_TAGS`, `META_ADD`, `META_DELETE`.
2. The feature [_Installing an NPM package through the operation $install-npm-package_](#install-npm-package) is 
   disabled.
3. The feature [_Auto-installation of FHIR packages in the $validate operation_](#auto-install-ig) is disabled.

It is helpful to enable this mode when the server is used in a production environment (e.g. as a validation server),
to make sure its state can't be changed through the API.

## Only one engine {: #only-one-engine}

When enabling `onlyOneEngine`, the server will initialize a single Matchbox engine, use it for all requests, and keep it
running for the whole serve life duration.
It provides the following advantages:

- It lowers the memory and CPU consumption, as a single engine is shared among all requests.
- It speeds up the response time, as the engine is already initialized and ready to use.
- You can overwrite conformance resources (e.g. update StructureMaps, ConceptMaps)

It is helpful to enable this mode when the server is used in a development environment (e.g. as a validation server or for FML map development).

Disabling this mode is recommended when more context separation is required, in particular when dealing with the 
following situations:

- when installing ImplementationGuides that use different FHIR versions, the server will need to initialize multiple 
  engines, to only load the FHIR Core needed for each IG;
- when installing multiple versions of the same ImplementationGuide, the server will also initialize separate 
  engines, with only the dependencies required.

## Installing an NPM package through the operation $install-npm-package {: #install-npm-package}

The operation `$install-npm-package` allows you to install an NPM package through the API.
It is available if and only if `httpReadOnly` is disabled.

| Parameter IN | Card | Description                                 |
|--------------|------|---------------------------------------------|
| name         | 1..1 | The name of the package.                    |
| version      | 1..1 | The version of the package.                 |
| body         | 1..1 | The content of the NPM package as HTTP body |

```http
POST /matchboxv3/fhir/$install-npm-package?name=ch.fhir.ig.ch-core&version=4.0.1 HTTP/1.1
Content-Type: application/gzip

<gzip content of the NPM package>
```

## Auto-installation of FHIR packages in the $validate operation {: #auto-install-ig}

The operation `$validate` will try to install the FHIR package if it is not already installed, if and only if
`httpReadOnly` is disabled.

The FHIR package to install will be determined by (in the implemented order):

1. The `ig` parameter, if present;
2. The `profile` parameter. Matchbox will search for IGs that contain that canonical with the
   [Simplifier API](https://app.swaggerhub.com/apis-docs/firely/Simplifier.net_FHIR_Package_API/1.0.1) and install 
   the first result.

## LLM support {: #llm-support}
Adding `llm` configurations will allow the server to make API calls to the specified LLM and add an analysis of the validation results to the operation outcome. This provides the user with AI generated instructions on how to fix errors in the validated FHIR resource.

This feature requires a provider, model and API key to be defined in the application configuration.

Supported LLMs:

| Provider                                                  | Recommended Model            | Supported Models                                                                                                |
|-----------------------------------------------------------|------------------------------|-----------------------------------------------------------------------------------------------------------------|
| [OpenAI](https://openai.com/index/openai-api/) (`openai`) | `gpt-4o-mini`                | `gpt-3.5-turbo`, `gpt-4`, `gpt-4o`, `gpt-4o-mini`                                                               |
| [Anthropic](https://www.anthropic.com/api) (`anthropic`)  | `claude-3-5-sonnet-20241022` | `claude-3-5-sonnet-20241022`, `claude-3-5-haiku-20241022`, `claude-3-sonnet-20240229`, `claude-3-opus-20240229` |
