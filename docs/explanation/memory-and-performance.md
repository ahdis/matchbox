# Memory and performance

Matchbox needs more memory than a typical web service. This page explains where the memory goes, how much to plan, and
why the Docker image has the JVM defaults it has. The options themselves are described in the
[Docker image reference](../reference/docker-image.md#jvm-options).

## Where the memory goes

The memory of Matchbox is dominated by the [validation engines](validation-engines.md). Each engine holds the
conformance resources of an implementation guide and of all its dependencies in memory, indexed for fast validation.
With one engine per implementation guide, packages that are shared (the FHIR core package, the terminology package,
national base guides) are loaded once per engine.

The memory therefore depends on the number of implementation guides that are **in use at the same time**, not on the
number of installed ones: an engine that isn't preloaded is removed from the cache an hour after its creation.

In addition, large documents and many parallel requests need memory during validation, and Matchbox needs a
substantial amount of memory outside the Java heap.

## How much memory to plan

A memory limit of **4 GB** for the container is enough for a typical setup of implementation guides.

Measured with the Swiss implementation guides of the `with-preload` configuration (12 implementation guides with their
dependencies, 55 packages, each validated in its own engine), Matchbox needs about 1.3 GB of live heap. A single
implementation guide such as ch-elm needs about 0.7 GB; a limit of 2 GB is enough then.

Plan more memory for many more implementation guides in use at the same time, for large documents, or for many
parallel requests.

## Why the heap is a percentage of the container memory

The image sets `-XX:MaxRAMPercentage=70`: the maximum heap is 70% of the memory limit of the container. The heap then
follows the limit that the operator sets, and the remaining 30% are left for the memory that Matchbox needs outside the
heap. A fixed heap size (`-Xmx`) would have to be changed together with every change of the limit, and a container
whose heap is too close to its limit is killed by the container runtime when it exceeds it.

The consequence is that a container **must have a memory limit**: without one, the heap is sized from the memory of the
host (e.g. of the Docker Desktop VM) and can grow to 70% of it.

## Why the JVM exits when it runs out of memory

With `-XX:+ExitOnOutOfMemoryError`, the JVM exits on the first `OutOfMemoryError`. After such an error, a JVM is in an
undefined state: threads may have died, and engines may be half created. For a validation service, a wrong or missing
answer is worse than a short interruption, so the container stops and is restarted by its orchestrator (Docker restart
policy or Kubernetes).

## Why strings are deduplicated

With `-XX:+UseStringDeduplication`, the garbage collector merges identical strings in the background. The loaded
implementation guides and their dependencies, often in several versions, contain many identical strings (URLs, element
paths, codes), so this reduces the memory needed by the engines: by about 15% for the ch-elm implementation guide.

## Response times

The first validation against an implementation guide is slow when its engine has to be created first (up to half a
minute); the following ones are fast. Preloading (`igsPreloaded`) moves this cost to the startup of the server, at the
price of a longer startup and of memory that is used permanently.

Once the engine exists, the duration of a validation depends mostly on:

- **the size and structure of the resource**: a document `Bundle` with many entries, slices and references takes
  much longer than a single resource, because each entry is validated against its profile and the references inside
  the Bundle are resolved and checked.
- **the terminology server**: each code that the validator can't check with the loaded packages is sent to the
  terminology server of the parameter `txServer`. An external server such as tx.fhir.org adds a network round trip per
  request, and makes the duration depend on its availability. The internal server (`/matchboxv3/tx`) answers without
  network access, and `txServer=n/a` skips the terminology checks completely. The answers of an external server are
  cached (`txServerCache`, enabled by default) in a directory per terminology server (`$HOME/txCache`), so the same
  codes aren't asked again, also after a restart if the directory is kept.

The duration of each validation is written in the first issue of the `OperationOutcome` (_Total: …ms_) and is
available as a metric (see [Monitor Matchbox](../how-to/monitor-matchbox.md)).

### Parallel requests

Requests that need the same engine use it at the same time: an engine is shared, not locked by a validation. Several
engines can be created in parallel, and an engine that is being created by one request is waited for by the others
that need it. Only a few actions block all validations for their duration: the creation of the main engine at startup,
the reload of the engines (e.g. after `$load-all`), and every request in the
[`onlyOneEngine` mode](validation-engines.md#only-one-engine). During the startup, the validation requests wait until
the implementation guides are installed, and the Gazelle API answers with HTTP 503.

The number of parallel validations a server can handle is limited by its CPUs (the validation itself), its memory
(large documents), and the terminology server.

### Startup

At startup, Matchbox installs the implementation guides of the configuration that aren't in the database yet, then
creates the main engine and the engines of the preloaded implementation guides. Installing a package downloads it and
stores its resources in the database, which takes much longer than loading an installed package. A
[custom image](../how-to/build-custom-image.md) with pre-installed packages, or a database that is kept between
restarts, avoids it.

### Several instances

Several instances of Matchbox can run on the same [PostgreSQL database](../how-to/use-postgresql.md), behind a load
balancer, to handle more requests or to stay available during an upgrade. Each instance has its own engines and
caches, so each needs the memory described above, and the first validation against an implementation guide is slow on
each of them. Install the implementation guides through the configuration (the same for all instances) rather than
through the API of one instance.

## Related

- [Run Matchbox with Docker](../how-to/run-with-docker.md#set-the-memory)
- [Monitor Matchbox](../how-to/monitor-matchbox.md)
- Blog: [Matchbox: from 12 GB to 4 GB](https://www.matchbox.health/blog/2026/matchbox-memory/), how the memory needs
  were reduced in Matchbox 4.1.18, measured with a reproducible load test
