# Validation engines

A _Matchbox engine_ is an instance of the validator that is loaded with a set of packages: a FHIR core package, an
implementation guide and its dependencies. This page explains why matchbox-server works with several engines, how they
are created and kept, and what the `onlyOneEngine` mode changes.

## Why one engine per implementation guide

An engine is meant to hold one version of an implementation guide: two versions of the same implementation guide
define the same canonical URLs, and there is no support for both in one engine. But a server has to deal with:

- several versions of the same implementation guide (e.g. the current and the previous version of a national guide);
- implementation guides that depend on different versions of the same package;
- implementation guides for different FHIR versions.

If all the packages were loaded into one engine, the profile `…/ch-core-patient` would be ambiguous, and a resource
could be validated against a mix of rules that no implementation guide ever defined. By default, matchbox-server
therefore creates a separate engine for each implementation guide and version, loaded with exactly its dependencies.
A validation is then **reproducible**: it only depends on the implementation guide, not on what else is installed on
the server. The first issue of every validation result lists the packages of the engine that was used.

## How an engine is chosen

For a `$validate` request, the engine is determined by the `ig` parameter if it is present, otherwise by the
implementation guide that contains the requested `profile`. If the implementation guide isn't installed and the server
isn't in read-only mode, Matchbox tries to install it first (see
[auto-installation](../reference/operations.md#auto-install-ig)).

## The engine cache

Creating an engine is expensive: the packages are read from the database and indexed, which can take half a minute and
needs several hundred megabytes of memory. Engines are therefore cached:

- The engines of the implementation guides listed in `igsPreloaded` are created when the server starts and stay in
  memory as long as the server runs. The first validation against them is fast.
- The other engines are created on demand, when the first request for their implementation guide arrives, and are
  removed from the cache 60 minutes later.

This is a trade-off between memory and the response time of the first request: preload the implementation guides that
are used often, and let the rarely used ones be created on demand. See
[Memory and performance](memory-and-performance.md).

## Only one engine {: #only-one-engine}

With `onlyOneEngine: true`, the server creates a single engine, loads all the installed packages into it, uses it for
all requests and keeps it for its whole lifetime. This has advantages:

- it lowers the memory and CPU consumption, as one engine is shared by all requests;
- it speeds up the responses, as the engine is always ready;
- conformance resources can be created and updated through the API (e.g. a `StructureMap` under development), because
  there is one well-defined engine to put them in.

This is the **development mode**: use it to develop maps or profiles, or for a server with a single implementation
guide. The changes made through the API live in the engine only: they are lost when the server restarts.

The price is the loss of separation described above. Keep the default mode when:

- implementation guides for different FHIR versions are installed: the server needs separate engines to load only the
  FHIR core package that each one needs;
- several versions of the same implementation guide are installed: each one needs its own engine, with only its own
  dependencies.

| | Default (one engine per IG) | `onlyOneEngine` |
|---|---|---|
| Intended use | production, validation service | development, single-IG server |
| Several versions of an IG | yes | no |
| Create/update conformance resources through the API | no | yes (not persisted) |
| Memory | grows with the number of IGs in use | one engine |
| First request for an IG | slow unless preloaded | fast |

## Related

- [Package management](package-management.md): where the packages of the engines come from.
- [Configuration reference](../reference/configuration.md#matchbox-parameters): `igsPreloaded`, `onlyOneEngine`,
  `httpReadOnly`.
