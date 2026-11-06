# org.hl7.fhir.core

matchbox depends on org.hl7.fhir.core. the project is available on [github](https://github.com/hapifhir/org.hl7.fhir.core) and does frequent releases https://github.com/hapifhir/org.hl7.fhir.core/releases which have to be integrated in matchbox.

matchbox has patched a few classes, that's why an update cannot be done just through referencing mvn.

the following steps need to be performed:

## 1. Sync the forked project and checkout the release tag

```
cd ../org.hl7.fhir.core/
git checkout master
git fetch upstream
git merge upstream/master
git push
```

Check out the latest release tag (e.g. `v6.9.1`):

```
git checkout -b oe_$(git describe --tags --abbrev=0)
cd ../matchbox
```

**Note:** If `git merge upstream/master` has conflicts, you can skip the merge and directly checkout the tag:
```
git fetch upstream --tags
git checkout -b oe_v6.9.1 v6.9.1
```

## 2. Update matchbox version references

Update `fhir.core.version` in the root `pom.xml` to the new version:

```xml
<fhir.core.version>6.9.1</fhir.core.version>
```

## 3. Run updatehapi.sh to copy fresh files

```
./updatehapi.sh
```

This copies files from `../org.hl7.fhir.core/` into `matchbox-engine/src/main/java/`. After this step, all matchbox patches are lost and need to be re-applied.

**Note:** If files listed in `updatehapi.sh` have been removed in the new core release (e.g. `Params.java` was removed in 6.9.1 due to picocli refactoring), the copy will fail for those files. Check if matchbox imported or used those files — if not, they can be safely ignored.

## 4. Re-apply matchbox patches

All matchbox-specific patches are marked with `matchbox patch` comments in the source code. Use `git diff` to review what `updatehapi.sh` overwrote, then manually re-apply each patch.

### Patched files and their patches

The following files have matchbox-specific patches (as of the 7.0.2 update, versionless R6 modules):

| File | Patch Description | Issue |
|------|------------------|-------|
| `BaseWorkerContext.java` | `BinaryLoader` keeps a provider for its file instead of the whole `NpmPackage` | #597 |
| `BaseWorkerContext.java` | `retain()`: objects kept alive as long as the context and its copies | memory |
| `BaseWorkerContext.java` | Locale handling (preserve locale across engine copies) | #425 |
| `BaseWorkerContext.java` | `copy()` copies the inner sets/maps of `oidCacheManual` and `validationCache` (`allResourcesById` is copied upstream since 7.0.2) | #608 |
| `BaseWorkerContext.java` | Duplicate resource handling (no exception for duplicates) | #227, #452 |
| `BaseWorkerContext.java` | `registerOids()`, `getResourceVersion()` for lazy loaded proxies | #627 |
| `BaseWorkerContext.java` | Remove the narrative of resources loaded from packages | #566 |
| `BaseWorkerContext.java` | fhirVersioned URL stripping in fetchResourceWithExceptionByVersion | #265 |
| `CanonicalResourceManager.java` | `getVersion()`, `getMasterVersion()` without parsing proxies | #627 |
| `CanonicalResourceProxy.java` | `getResource()` synchronized, proxies are shared between contexts | concurrency |
| `BaseLoaderRN.java`, `R3/R4/R4B/R5ToRNLoader.java` | `skipNarrative`: don't parse the narrative | #614 |
| `FilesystemPackageCacheManager.java` | Core, terminology, extensions and xver packages from the classpath | packages |
| `ProfileUtilities.java` | findProfile | #424 |
| `Element.java` | Type of converted choice elements in `convertToElement`; `copy()` copies the xhtml (done upstream for R5 only) | FML, concurrency |
| `XmlParser.java` | ST.r2b datatype handling, whitespace of non string types for CDA | #439 |
| `FHIRPathHostServices.java` | FML resolve() in Bundle, conformsTo context | #359 |
| `StructureMapTools.java` | `actualType` given as the alias of a structure (no `matchbox patch` marker) | #67 |
| `TestDataFactory.java` | 3-param lookup | core #1942 |
| `BaseValidator.java` | ruleInv suppression uses invId instead of theMessage | Validation |
| `BundleValidator.java` | Profile-based validation of document bundle entries | #348 |
| `InstanceValidator.java` | resolve() through the fetch cache, OID registry loaded once (core PR #2711), warnings of referenced resources don't fail the referrer (#543), xver extensions (#138), resolveReferencesInBundle (#359) | Multiple |

matchbox-server overrides HAPI FHIR classes that HAPI builds with an older core: `HapiWorkerContext` (DSTU3, R4, R4B,
R5), `WorkerContextValidationSupportAdapter` and `TermReadSvcImpl` (the version convertors have no conversion path
since 7.0.2).

### Strategy for re-applying patches

Use a three-way merge per file, with the old core release as the base, the matchbox file as ours and the new core
release as theirs; it keeps the patches and the upstream changes, conflicts show where both changed the same code:

```bash
cd ../org.hl7.fhir.core
git show 7.0.1:$FILE > /tmp/base.java   # the core release matchbox was on
git show 7.0.2:$FILE > /tmp/theirs.java # the new core release
cd ../matchbox
git merge-file -p $MATCHBOX_FILE /tmp/base.java /tmp/theirs.java > /tmp/merged.java
```

- A patch that is done upstream is either merged without a difference to the new release or shows up as a conflict
  (take the upstream side and drop the patch).
- Some core files have CRLF line endings, the matchbox copies LF: strip the CRs of base and theirs before merging.
- Count the `matchbox patch` markers per file before and after, and check the new core release notes for changes in
  the same files that make a patch obsolete although the code differs.
- Compiling isn't enough for the HAPI FHIR jars: scan their bytecode for references to core classes and members that
  are missing in the new release (see the `ILoggingService` move in 7.0.0).

## 5. Handle API changes

Common types of API changes between core versions:

- **New method parameters**: e.g. `VersionResolutionRules` parameter added to `fetchResource`, `resolveURL`, etc.
- **Method renames**: e.g. `IsNoService()` → `isNoService()` in 6.9.1
- **Signature changes**: e.g. `validate()` now takes `InstanceValidatorParameters` instead of `List<String>` profiles
- **New constants**: e.g. `DEF_GROUP_NAME` for anonymous group rules in StructureMap
- **Removed classes**: e.g. `Params.java` removed in 6.9.1 (picocli refactor)

Check the core release notes and changelog for breaking changes.

## 6. Build and test

```bash
# Compile first to catch API changes
cd matchbox-engine
mvn clean compile

# Fix any compilation errors, then run tests
mvn clean test

# Also compile the server
cd ../matchbox-server
mvn clean compile
```

### Common test failures after a core update

- **Changed error message IDs**: The validator may use different message keys (e.g. `Extension_EXTP_Context_Wrong` vs `Extension_EXTP_Context_Wrong_VER`). Check the `Messages.properties` in `org.hl7.fhir.utilities`.
- **Changed message text format**: Error messages may include additional information (e.g. version in extension URLs).
- **CDA type matching**: CDA StructureDefinitions may change their `type` field format (short name vs full URL). The `matchesType` method in StructureMapUtilities handles this.
- **Whitespace handling**: XML attribute whitespace may be preserved differently between versions.
- **New validator behavior**: The InstanceValidator may require explicit factory setup (e.g. `context.setValidatorFactory(new InstanceValidatorFactory())`).

## 7. Create PR

After all tests pass, create a branch and PR with the changes.
