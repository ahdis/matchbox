# Upgrade Matchbox

!!! warning "Skeleton page"
    This page still has to be written. It should be a **how-to guide** for an operator who runs Matchbox and wants to
    move to a newer version without surprises.

    - **Goal**: upgrade a running Matchbox (Docker image or the matchbox-engine dependency) and check that validation
      results are still the expected ones.
    - **Keywords**: versioning scheme, release candidates (`X.Y.Z-rcN`), image tags (`vX.Y.Z`, `latest`), database
      schema, H2 vs. PostgreSQL, bundled packages, version of `org.hl7.fhir.core`, changed validation messages,
      suppressed issues, regression tests.
    - **Sources**: the [changelog](../changelog.md), the release workflow, the
      [PostgreSQL 18 migration](use-postgresql.md#move-an-existing-database-to-postgresql-18).

## Read the changelog

!!! abstract "To write"
    What to look for in the [changelog](../changelog.md): the new version of the HL7 validator
    (`org.hl7.fhir.core`), changed defaults of configuration parameters, changed bundled packages, breaking changes.

## Upgrade the Docker image

!!! abstract "To write"
    Change the image tag; what happens to the database (can an existing H2/PostgreSQL database be reused, or should it
    be recreated so that the packages are installed again?); rebuilding a
    [custom image](build-custom-image.md).

## Upgrade matchbox-engine

!!! abstract "To write"
    Change the version of the Maven dependency; changes of the Java API; the package cache.

## Check the validation results

!!! abstract "To write"
    A new validator version can report new or different issues. Validate a set of reference resources before and after
    the upgrade and compare the results; review `suppressWarnInfo` (texts may have changed) and `suppressError`
    (message ids are stable).

## Upgrade the database

The move of an existing database to PostgreSQL 18 is described in
[Use a PostgreSQL database](use-postgresql.md#move-an-existing-database-to-postgresql-18).
