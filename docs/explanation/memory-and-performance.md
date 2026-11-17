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

!!! abstract "To write"
    Typical validation times for small resources and for large documents, the effect of the terminology server and of
    its cache on the duration, the number of parallel validations a server can handle, the startup time with and
    without pre-installed packages, and when to scale horizontally (several instances on one database).
