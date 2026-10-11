# Profile analysis plan: org.hl7.fhir.core 7.0.1 validates ~10% slower

How to find where matchbox on org.hl7.fhir.core 7.0.1 (branch `fhir-core-7`) loses about 10% of validation time
compared with 6.10.4 (PR #636). This file is written so a Claude Code session (or a person) can do the analysis
without rediscovering the setup. The load test itself is described in `claude-jmeter-check.md`.

## What we know (2026-10-10)

`memory.jmx`: 8,000 `$validate` calls of the ch-elm DocumentReference against `PublishDocumentReferenceStrict`,
4 threads. Two runs of each image, alternating on the same day, idle machine:

| Image | core | Server time (`total`) median / p95 | Client time median / p95 | Live heap after the test |
|---|---|---|---|---|
| `matchbox-ch-elm:1156-pr636` | 6.10.4 | 87–88 / 96–97 ms | 89–90 / 99 ms | 663 MiB |
| `matchbox-ch-elm:1156-fhir-core-7` | 7.0.1 | 96–97 / 108–109 ms | 98–100 / 110–111 ms | 684–686 MiB |

- The same 6 issues in the OperationOutcome, the same response size: both images do the same work.
- The gap (~9 ms per request) is in the server time, not in HTTP or serialization (client minus server time is the
  same, ~2 ms).
- Both images have the same OS and JDK layers (bellsoft liberica JDK 26): `1156-fhir-core-7` is `matchbox:pr636`
  with `/matchbox.jar` replaced (see footnote ⁷ in `claude-jmeter-check.md`). HAPI FHIR, Spring Boot and Tomcat are
  the same version. The difference is org.hl7.fhir.core and the matchbox code ported to it.
- Engines are created once and cached (`MatchboxEngineSupport`), so engine creation and the snapshot generation in
  `BaseWorkerContext.copy()` aren't in the per-request path. Startup is slower too (ready after 37 vs 31 s), which is
  a separate question.

The server time (`total` in the OperationOutcome, `ValidationProvider`, `StopWatch` from the start of the operation
to the end of `doValidate()`) includes:

1. reading and parsing the request
2. `matchboxEngineSupport.getMatchboxEngine(profile, …)`: engine lookup, `synchronized`, may look up the IG of the
   profile in the database (see "Latency to the database" in `claude-jmeter-check.md`)
3. `engine.getStructureDefinitionR5(profile)`
4. `doValidate()` → `MatchboxEngine.validate()`: `getValidator()` (a new `InstanceValidator` per request, see
   `ValidationEngine.getValidator`), then `InstanceValidator.validate()` on the element model, then
   `filterValidationMessages()`

## Preconditions

- Images: `docker images | grep 1156-` must list `matchbox-ch-elm:1156-pr636` and
  `matchbox-ch-elm:1156-fhir-core-7`. If `1156-fhir-core-7` is missing or the branch changed, rebuild it as in
  footnote ⁷ of `claude-jmeter-check.md` (`docker create`/`cp`/`commit`, no registry access needed: the Docker
  credential helper hung on 2026-10-10, a normal `docker build` couldn't pull the base image).
- Port 8080 free, machine idle, Docker disk ≥ 3 GB free (see `claude-jmeter-check.md`, step 1).
- Profilers: the images contain JDK 26 with `jcmd` and `jfr` (JDK Flight Recorder, no install needed). Analyze the
  recordings with the `jfr` of the image (`docker exec … jfr …`): the host's Homebrew `jfr` is JDK 25 and may not read
  JDK 26 recordings. async-profiler isn't installed (optional, step 4).
- Always measure both images the same way, alternating, and compare differences, not absolute numbers. A single run
  varies by up to ±20%; the 10% gap held over 2 runs each.

## Step 1: split the server time (which of the 4 parts grows?)

Cheapest first: find out whether the 9 ms are in `doValidate()` or before it.

- Option A, no code change: JFR (step 2) and look at the share of samples below
  `MatchboxEngineSupport.getMatchboxEngine` versus `MatchboxEngine.validate`.
- Option B, small code change on both branches (in a worktree for `spring-boot-4-hapi-pr8189`): put
  `sw.startTask(...)` around each of the 4 parts in `ValidationProvider` and add the task times as extensions to the
  OperationOutcome (like `total`), then extend `memory.jmx` with JSON extractors for them. Gives per-part medians over
  8,000 requests.

If the gap is in part 2 (engine lookup), it is matchbox code (`MatchboxEngineSupport`, ported to R6 types), not the
validator: look at what changed there in `git diff spring-boot-4-hapi-pr8189..fhir-core-7`.

## Step 2: JFR recording under load, both images

For each image, in a fresh container:

```bash
LABEL=fhir-core-7   # or pr636
docker run -d --name prof-$LABEL -p 8080:80 \
  -e JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseStringDeduplication -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints" \
  matchbox-ch-elm:1156-$LABEL
until curl -sf http://localhost:8080/matchboxv3/actuator/health | grep -q UP; do sleep 2; done
cd jmeter && ./jmeter.sh                      # warm-up run, JIT and caches (results not used)
docker exec prof-$LABEL jcmd 1 JFR.start name=prof settings=profile filename=/tmp/$LABEL.jfr
./jmeter.sh                                   # measured run
docker exec prof-$LABEL jcmd 1 JFR.stop name=prof
docker cp prof-$LABEL:/tmp/$LABEL.jfr ../jmeter/profile-$LABEL.jfr
cp -Rp report profile-$LABEL && cp -p memory.jtl profile-$LABEL.jtl   # check the run was like the earlier ones
docker rm -f prof-$LABEL
```

(`-XX:+DebugNonSafepoints` makes the CPU samples more precise. `jmeter.sh` deletes `memory.jtl` and `report/` first.)

Then for each recording, with the `jfr` of the image (e.g. run a throwaway container with the file mounted):

```bash
docker run --rm -v $PWD:/w --entrypoint jfr matchbox:fhir-core-7 view --width 200 hot-methods /w/profile-$LABEL.jfr
docker run --rm -v $PWD:/w --entrypoint jfr matchbox:fhir-core-7 view --width 200 allocation-by-class /w/profile-$LABEL.jfr
docker run --rm -v $PWD:/w --entrypoint jfr matchbox:fhir-core-7 view gc /w/profile-$LABEL.jfr
docker run --rm -v $PWD:/w --entrypoint jfr matchbox:fhir-core-7 summary /w/profile-$LABEL.jfr
```

## Step 3: compare the profiles

`hot-methods` only shows top frames. For the comparison, aggregate the execution samples by **inclusive** frame:

```bash
docker run --rm -v $PWD:/w --entrypoint jfr matchbox:fhir-core-7 print --json --events jdk.ExecutionSample \
  /w/profile-$LABEL.jfr > profile-$LABEL.samples.json
```

Write a small Python script (keep it in `jmeter/`, e.g. `jfr_compare.py`) that, for both files:

- counts the samples of the `http-nio` request threads only (the validation work), so the totals are comparable
- for each sample, adds 1 to every distinct method on its stack (inclusive), and to the top frame (self)
- prints per method and per package prefix (`org.hl7.fhir.validation`, `org.hl7.fhir.services.fhirpath`,
  `org.hl7.fhir.services.elementmodel`, `org.hl7.fhir.model`, `org.hl7.fhir.standalone`, `ch.ahdis`, `ca.uhn`,
  `java.util`) the share of samples in each profile and the difference, sorted by the difference
- maps the renamed classes onto each other for the comparison (r5 → R6, see the appendix of `r5-r6.md` in
  org.hl7.fhir.core 7.0.1, e.g. `org.hl7.fhir.r5.fhirpath.FHIRPathEngine` ↔ `org.hl7.fhir.services.fhirpath.FHIRPathEngine`,
  `StructureMapUtilities` ↔ `StructureMapTools`, `r5.elementmodel` ↔ `services.elementmodel`, `r5.model.X` ↔
  `model.core.X`), otherwise every method looks new

Also compare from `jfr view gc` / `summary`: GC pause total, allocation rate (`jdk.ObjectAllocationSample`), and the
number of `jdk.ThreadPark` / `jdk.JavaMonitorEnter` events (lock contention: `synchronized` getResource of the
proxies, `getMatchboxEngine`, the context lock).

Expected result: a short list of methods/packages whose share grows by a few percentage points in 7.0.1.

## Step 4 (optional): differential flame graph

If the JFR comparison isn't conclusive, async-profiler gives flame graphs that can be diffed. It needs a download
(GitHub release `async-profiler-<ver>-linux-arm64.tar.gz`, the Docker VM is arm64), copied into the container with
`docker cp`, then `asprof -d 120 -e cpu -o collapsed -f /tmp/$LABEL.collapsed 1` during the measured run, and a diff
of the two collapsed stack files (e.g. `difffolded.pl` from FlameGraph, after the r5 → R6 renaming as in step 3).
`jfr` recordings can also be converted with async-profiler's `jfrconv` to collapsed stacks.

## Hypotheses to check against the profile

In the order we'd look at them; each with what would show it.

1. **Model context checks on the typed model** (new in 7). Every `Base` carries an `IModelContext`; setters and
   `BaseList.add` stamp children (`setModelContext`, `isCompatibleModelContext`), `copy(EnumSet)` and
   `changeModelContext` walk trees. Hot frames in `org.hl7.fhir.model.Base`, `BaseList`, `ModelContext*`. Matters
   where the validator builds typed objects per request (snapshots, `copy()` of definitions, ValueSet expansions,
   Coding/CodeableConcept for terminology checks, OperationOutcome).
2. **Property access without the name hash** (new in 7): `getNamedValue(String, boolean)` and
   `getChildValues(String, boolean)` replace `getProperty(int hash, …)`; string `switch` instead of `int` switch, and
   `getChildValues` returns immutable lists. Hot frames in generated `getNamedValue`/`getNamedProperty` of
   `org.hl7.fhir.model.core.*`, called from FHIRPath on typed resources (StructureDefinitions, ElementDefinition
   invariants) and from `ObjectConverter`.
3. **Per-request setup**: `ValidationEngine.getValidator()` creates an `InstanceValidator` (and its
   `FHIRPathEngine`, `ProfileUtilities`, policy advisor) per request. Look at the inclusive share of the
   `InstanceValidator.<init>` / `FHIRPathEngine.<init>` / `getValidator` frames. If it grew, check what the 7.0.1
   constructors do (e.g. iterate over all StructureDefinitions or the type names).
4. **Validator changes 6.10.4 → 7.0.1** (133 files in `org.hl7.fhir.validation/src/main` changed): new or more
   expensive checks. Compare the inclusive shares of the `validate*` methods of `InstanceValidator` and the type
   validators (`BundleValidator`, `CodeSystemValidator`, …); for a method that grew, `git log -p 6.10.4..7.0.1 -- <file>`
   in `../org.hl7.fhir.core` (read the tags, don't touch the branch, it has local commits).
5. **Terminology**: more cache misses or more internal expansions/validations. Compare the share of
   `ValueSetValidator`, `ValueSetExpander`, `TerminologyCache` and of `BaseWorkerContext.validateCode*`. 7.0.1
   changed the cache (memory-only by default, see the TerminologyCache class documentation): check that matchbox's
   cache settings still lead to hits (matchbox calls `initTxCache`/`setCachingAllowed`).
6. **Lookups in the worker context**: `fetchResource` / `CanonicalResourceManager.get*` / version resolution
   (`VersionResolutionRules`, new or changed in 7) and the matchbox patches ported to it (#265 URL rewriting in
   `fetchResourceWithExceptionByVersion`, `getResourceVersion`, `synchronized CanonicalResourceProxy.getResource`).
   Hot frames in `org.hl7.fhir.standalone.context`, or monitor contention on the proxies with 4 threads.
7. **Allocation and GC**: a higher allocation rate (e.g. `BaseList`, immutable child lists, `ValidationInformation`)
   shows as more GC time with the same heap, without a single hot method. Compare allocation by class and GC
   totals. Live heap is 3% higher after the test (684 vs 663 MiB).
8. **matchbox's own changes on the branch**: `R6Model` conversions, `makeBundleProviderFromN` and the ported
   providers aren't on the `$validate` path, but `MatchboxEngineSupport.getMatchboxEngine` is (step 1, part 2). Hot
   `ch.ahdis` frames that don't exist in the pr636 profile point here.

## Step 5: confirm a finding

- Reproduce in isolation where possible: a JUnit test in `matchbox-engine` that loads the packages and validates the
  ch-elm DocumentReference a few thousand times (timed after a warm-up) gives a faster loop than the Docker/JMeter
  setup. The ch-elm image has the packages (`ch.fhir.ig.ch-elm#1.15.1` and its dependencies, listed as `package`
  extensions in the OperationOutcome); copy them out of the image's package cache with `docker cp` and load them with
  `MatchboxEngine.loadPackage(InputStream)` in dependency order. Run it on `spring-boot-4-hapi-pr8189` (worktree) and
  `fhir-core-7`.
- Fix or work around: a matchbox patch (add the file to `updatehapi.sh`) or a change to the matchbox code; if it's an
  upstream regression, a minimal reproduction for an issue/PR on hapifhir/org.hl7.fhir.core.
- Re-run `memory.jmx` on both images, alternating, 2 runs each, and add the rows to the results table in
  `claude-jmeter-check.md`.

## Deliverable of the analysis session

- Per-part server time (step 1) for both images.
- The table of methods/packages with the largest growth in sample share (step 3), with the r5 → R6 mapping applied.
- Which hypothesis it confirms, the evidence, and the proposed fix with its measured effect (or why the cost is
  inherent to core 7, e.g. the model context checks, and should be raised upstream).
- Results files stay local (git-ignored): `profile-*.jfr`, `profile-*.samples.json`, `profile-*/`, `profile-*.jtl`.

## Results (2026-10-10)

### Tools

- `JfrAgg.java`: aggregates a JFR recording (inclusive/self samples per method, allocation, native samples, GC, locks)
  without `jfr print`, which would write gigabytes with 2048-deep stacks. Run with the image's JDK:
  `docker run --rm -v $PWD:/w --entrypoint java matchbox-ch-elm:1156-fhir-core-7 /w/JfrAgg.java /w/profile-X.jfr > profile-X.agg.tsv`
- `jfr_compare.py base.agg.tsv new.agg.tsv [--grep regex]`: comparison in ms per request, r5 → R6 names mapped
- `thread_cpu.py cpu-<run> ...`: exact CPU per thread group from `jcmd 1 Thread.print` before and after a run
- `collapsed_compare.py base.collapsed new.collapsed [--grep regex]`: the same for async-profiler collapsed stacks
- `callers.py file.collapsed 'method-regex'`: callers of a method and the innermost validator/matchbox frame
- JFR needs `-XX:FlightRecorderOptions:stackdepth=2048` (the default 64 cuts the validator's recursion) and
  async-profiler 4.5 (`linux-arm64`, `docker cp` into the container, `asprof start -e itimer -i 5ms --threads 1`, then
  `asprof stop -o collapsed -f /tmp/X.collapsed 1`; `itimer` works without perf privileges)

### Exact CPU per request (no profiler, image default options, 2 runs each, alternating)

| CPU ms per request | pr636 r1 | pr636 r2 | fhir-core-7 r1 | fhir-core-7 r2 |
|---|---:|---:|---:|---:|
| request threads (`http-nio-*-exec`) | 83.1 | 79.8 | 88.4 | 87.2 |
| ForkJoinPool (`parallelStream` in `filterValidationMessages`) | 6.1 | 6.7 | 6.9 | 7.0 |
| GC threads | 3.3 | 2.6 | 3.0 | 3.0 |
| server time median | 93 | 87 | 95 | 95 |

The regression is CPU on the request threads (+5–6 ms per request). GC (+9% young collections, +12% allocation) adds
only ~0.03 ms per request of pause time. No lock contention (`ObjectMonitor::try_spin` is lower on 7.0.1), the same
number of exceptions (3 per request).

**JFR was misleading here**: `jdk.ExecutionSample` saw only ~58 of the ~85 ms of request thread CPU and showed 7.0.1
*lower* (57.1 vs 59.9 ms). async-profiler accounts for the full CPU (83.7 vs 86.2 ms) and is what the findings
below are based on.

### Step 1: parts of the server time (inclusive CPU per request, async-profiler / JFR)

| Part | 6.10.4 | 7.0.1 |
|---|---:|---:|
| `MatchboxEngineSupport.getMatchboxEngine` | 0.6 | 0.6 |
| `getValidator` (≈ `new OIDUtilities()` in the `InstanceValidator` constructor) | 2.9 | 2.3 |
| `InstanceValidator.validate` | 70.5 | 73.3 |
| `filterValidationMessages` (plus the ForkJoinPool threads) | 2.8 | 2.9 |
| OperationOutcome and response | 0.4 | 0.4 |

### Finding: `resolve()` of the ch-elm ConceptMaps converts them on every call (both versions)

The ch-elm Observation profiles have invariants such as
`'http://fhir.ch/ig/ch-elm/ConceptMap/ch-elm-results-to-foph-patient-name-representation'.resolve().group...`
(10 ConceptMaps, up to 224 KB; this one is referenced 54 times in the profiles). Each `resolve()` goes
`FHIRPathEngine.funcResolve` → `ValidatorHostServices.resolveReference` → `ValidationEngine.fetch` →
`new ObjectConverter(context).convert(resource)`, which composes the typed ConceptMap to JSON and parses it into the
element model, with no caching. Inclusive per request:

| | 6.10.4 | 7.0.1 |
|---|---:|---:|
| `ValidationEngine.fetch` → `ObjectConverter.convert` | 38.8 | 39.7 |
| … of which `JsonParserBase.compose` (typed ConceptMap → JSON) | 2.7 | 6.3 |
| … of which `Base.assertModelContext` (new in core 7, called by `compose`) | – | 3.3 |
| … of which the element model parse | ~36 | ~33 |

That is **~45% of the request CPU on both versions**. The 7.0.1 regression is mostly here: `JsonParserBase.compose` calls
`Base.assertModelContext`, which walks the whole ConceptMap through `listChildren()` and allocates an
`org.hl7.fhir.model.Property` per child (hypotheses 1 and 2: `Property.<init>`, `BaseList.get/size`,
`AbstractList$Itr`, `Objects.checkIndex` are the new self time, +4.5 ms in `org.hl7.fhir.model`; 4.7 MB/request of
`Property` allocation). The element model parser itself got faster in 7.0.1 (`ContextUtilities.isResource`,
`Property.determineType`, −2.9 ms), which hides part of it.

### Other per-request costs found (both versions, not the regression)

- `InstanceValidator` constructor: `new OIDUtilities()` parses the OID CSV from the jar on every request (2.3–2.9 ms
  plus ~1 ms of `ZipInflaterInputStream` in native code). matchbox creates an `InstanceValidator` per request and
  already patches `InstanceValidator` (`updatehapi.sh`), so the OIDs could be loaded once (static).
- `MatchboxEngine.filterValidationMessages`: compiles the suppressed warning patterns on every request and matches
  them with `parallelStream()` (~3 ms on the request thread plus the ForkJoinPool's ~6–7 ms CPU per request, the
  latter partly idle spinning). Compiling once and a sequential stream should remove most of it.

### Fixes and their effect

In matchbox (branch `fhir-core-7`):

1. `MatchboxEngine.fetch()` overrides `ValidationEngine.fetch()`: the resource converted to the element model is kept
   per engine (by resource instance, `WeakHashMap`), each call gets a copy (`Element.copy()` plus `numberChildren()`,
   the indexes aren't copied). The validator decorates the copy (parents, slices, messages). The element model isn't
   thread-safe (`NamedItemList` builds its name map lazily and unsynchronized), so the kept element is never handed
   out: it is only copied, under its lock, and the patched `Element.copyValues()` copies the narrative too (it shared
   the `XhtmlNode`), so a copy shares no mutable state. `FetchCacheTest`, with 8 threads × 500 concurrent fetches that
   navigate their copies through the name map and change them; handing out the kept element instead makes it fail
   (negative control).
2. `compileSuppressedWarnInfoPatterns()` keeps the compiled patterns until the set changes,
   `filterValidationMessages()`/`filterSlicingMessages()` match them with a sequential stream.
3. The patched `InstanceValidator` uses one static `OIDUtilities`.

Load test, the image without and with the fixes alternating, 2 runs each (`cpu_run.sh`, rows `cpu-base7-*` and
`cpu-fix7-*` in `claude-jmeter-check.md`), the same OperationOutcome:

| | without r1 | without r2 | with r1 | with r2 |
|---|---:|---:|---:|---:|
| CPU per request, request threads (ms) | 91.4 | 88.3 | 40.4 | 45.9 |
| CPU per request, ForkJoinPool (ms) | 6.5 | 6.9 | 0 | 0 |
| CPU per request, GC (ms) | 2.8 | 3.0 | 0.5 | 0.5 |
| server time median / p95 (ms) | 102 / 113 | 95 / 102 | 41 / 44 | 46 / 50 |
| duration of the 8,000 requests (s) | 220 | 209 | 100 | 111 |

Upstream ([hapifhir/org.hl7.fhir.core#2711](https://github.com/hapifhir/org.hl7.fhir.core/pull/2711)): the same cache in
`ValidationEngine.fetch()`, `OIDUtilities` loads the registry once, and the serialisers check the model context of
the root only (`Base.checkModelContext`) instead of walking the tree on every compose (`assertModelContext`).

### The "exceptions"

The 3 throwables per request of `jdk.ExceptionStatistics` aren't exceptions: logback creates a `Throwable` per log
line for the caller's line number (`%L` in the pattern), and a validation logs 3 lines at INFO
(`MatchboxEngineSupport` "Using cached validate engine", `MatchboxEngine` "Using profile for validation" and
"finished validation"). The `Throwable`s cost 0.03 ms, all logging 0.35 ms per request: not worth changing.
