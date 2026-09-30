# JMeter memory/performance check for matchbox releases

How to load test a new matchbox release with the ch-elm validation scenario and compare it with the earlier runs.
This file is written so a Claude Code session (or a person) can repeat the run without rediscovering the setup.

## What the test does

`memory.jmx`, run non-GUI via `./jmeter.sh`:

- 1× `GET /fhir/metadata`
- 4 threads × 2,000 loops = **8,000 `$validate` calls** of a ch-elm DocumentReference against
  `http://fhir.ch/ig/ch-elm/StructureDefinition/PublishDocumentReferenceStrict`
- after each validation, `GET /actuator/metrics/jvm.memory.used`

Per sample the `.jtl` records `memoryused` (bytes, heap and non-heap), `validationms` (the server's own validation
time from the OperationOutcome) and `matchbox` (the "powered by matchbox …, hapi-fhir … and org.hl7.fhir.core …"
string). `user.properties` turns memory and validation time into two custom graphs in the HTML report.

The server address is fixed in `memory.jmx` as `http://localhost:8080/matchboxv3`, so the server under test has to
run on **host port 8080**.

## Images

| What | Where |
|---|---|
| matchbox base image | `europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:v<version>` |
| ch-elm image | `europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox-ch-elm:<ch-elm version>` |
| ch-elm sources | `../matchbox-ch-elm` (Dockerfile `FROM` the matchbox base image, bundles `src/ch.fhir.ig.ch-elm.tgz` and `src/application.yaml`) |

The ch-elm image serves matchbox on **container port 80**, so run it with `-p 8080:80`.

## Procedure

### 1. Preconditions

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN          # port 8080 must be free (the ITB stack's itb-srv uses it:
                                          # docker stop itb-ui itb-srv itb-redis itb-mysql, ask first)
docker run --rm alpine df -h / | tail -1  # Docker VM disk: an image + container needs about 2.5 GB
gcloud auth print-access-token >/dev/null # registry auth; if expired the user must run `! gcloud auth login`
```

Keep the machine otherwise idle. A busy machine alone made the same 1.15.0 image run 2× slower
(30.3 vs 14.7 min).

### 2. Get the image to test

**Published ch-elm release:**

```bash
docker pull europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox-ch-elm:1.15.2
```

**New matchbox release, same ch-elm content** (isolates the matchbox change). Build the latest ch-elm commit on the new
base image without touching the ch-elm checkout:

```bash
S=<scratchpad>/ch-elm-src
git -C ../matchbox-ch-elm archive <commit> | tar -x -C $S
sed "s#^FROM .*#FROM europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:v4.1.18#" $S/Dockerfile \
  | docker build -t matchbox-ch-elm:<ig>-mb4.1.18 -f - $S
```

**Unreleased matchbox (branch or PR):** build the jar and base image in a worktree, then the ch-elm image on top of
it:

```bash
git fetch origin pull/<n>/head && git worktree add --detach <scratchpad>/pr<n> FETCH_HEAD
cd <scratchpad>/pr<n> && mvn -q -B -DskipTests clean package   # frontend static files are committed
cd matchbox-server && docker build -t matchbox:pr<n> .
# then build the ch-elm image as above with FROM matchbox:pr<n>
```

The ch-elm build installs the IG packages at build time (`--hapi.fhir.only_install_packages=true`), which takes
about 3–4 minutes.

### 3. Start the container and wait until it's up

Use a new container name per run. Don't reuse or remove the containers of earlier runs; their logs are evidence.

```bash
docker run -d --name matchbox-ch-elm-<label> -p 8080:80 <image>
until curl -sf http://localhost:8080/matchboxv3/actuator/health | grep -q UP; do sleep 2; done
docker logs matchbox-ch-elm-<label> 2>&1 | grep -m1 'powered by'
```

`docker ps` shows the ch-elm containers as **unhealthy**. That's harmless: the image's `HEALTHCHECK` calls port 8080
inside the container, but ch-elm serves on port 80.

### 4. Run the test

```bash
cd jmeter && ./jmeter.sh
```

`jmeter.sh` **deletes** `memory.jtl` and `report/` first, so copy the previous results away before starting. A run
takes 4–30 minutes; run it in the background.

### 5. Save the results

The folder name is the ch-elm version without dots, plus a suffix when the image isn't the published one
(`1152-pr596`, `1151-mb4114`, `1150-rerun`):

```bash
cp -Rp report <label> && cp -p memory.jtl <label>.jtl
docker inspect -f 'status={{.State.Status}} oom={{.State.OOMKilled}}' matchbox-ch-elm-<label>
docker rm -f matchbox-ch-elm-<label>   # frees about 550 MB; the results are already saved
```

Subfolders and `*.jtl` are git-ignored, so the results only exist locally.

### 6. Check that the results are valid

A fast run can also mean the server answered with an error instead of validating. Check:

- **zero failed samples**
- **response size** of `$validate` about the same as in comparable runs (the same IG gives the same size, e.g.
  34,845 bytes for ch-elm 1.15.1)
- **one manual validation** returns issues for the right profile and IG package. The OperationOutcome's first issue
  carries extensions `profile`, `package`, `validatorVersion` and `total`. Also check which IG is bundled: the ch-elm
  1.15.2 image still contains `ch.fhir.ig.ch-elm#1.15.1`.

### 7. Compare

```python
import csv, statistics as s, collections
for name in ['1141.jtl', '1152.jtl', '<new>.jtl']:
    rows = list(csv.DictReader(open(name)))
    ts = [int(r['timeStamp']) for r in rows]
    val = [r for r in rows if r['label'] == '$validate']
    j = [r for r in rows if r['label'].startswith('jvm') and r['memoryused'] not in ('null', '')]
    mem = sorted(float(r['memoryused']) / 1e9 for r in j)
    vm = sorted(float(r['validationms']) for r in j)
    print(f"{name:16} {(max(ts) - min(ts)) / 60000:5.1f} min | validation median {s.median(vm):4.0f} "
          f"p95 {vm[int(.95 * len(vm))]:4.0f} ms | memory median {s.median(mem):.2f} max {mem[-1]:.2f} GB | "
          f"bytes {s.median(int(r['bytes']) for r in val)} | fails {sum(r['success'] != 'true' for r in rows)} | "
          f"{collections.Counter(r['matchbox'] for r in rows).most_common(1)[0][0]}")
```

Then add a row to the results table below.

### 8. Startup, first and second validation

`measure_startup.py` measures what a load test hides: for each run it starts a fresh container on port 8080 and
records the time until `/actuator/health` is UP, the time to create the ch-elm engine (from the log), the client and
server time of the first, second and third validation (the request of `memory.jmx`), and the live heap after a full GC
before and after these validations. The container is removed after each run.

```bash
cd jmeter && python3 measure_startup.py <image> <label> [runs] ["<JDK_JAVA_OPTIONS>"]
```

It writes `startup-<label>.csv` (git-ignored). Run it 3 times per image, with nothing else running.

### 9. Several IGs: shared or duplicated dependencies

`multi-ig.jmx` checks the memory with several validation engines. `multi-ig.csv` rotates through profiles and example
files (`multi-ig-*.json`) of `ch.fhir.ig.ch-core#6.0.0`, `ch.fhir.ig.ch-epr-fhir#5.0.0` and the R4 core (main engine):
4 threads × 100 loops.

Build an image with the configuration of `matchbox-server/with-ch` (packages installed at build time):

```dockerfile
ARG BASE
FROM ${BASE}
COPY application.yaml /config/application.yaml
RUN java -Xmx3G -jar /matchbox.jar --hapi.fhir.only_install_packages=true
```

Run it with `-p 8080:8080` (this configuration serves on port 8080), then `./jmeter_multi_ig.sh`, then a heap dump
(`jcmd 1 GC.heap_dump`). In Eclipse MAT, group the `CanonicalResourceManager$CachedCanonicalResource` objects by
package, version and URL: one object per resource means that the engines share it, several objects mean that it's
loaded again in each engine.

Other configurations: `./jmeter_multi_ig.sh -Jcsv=<file>.csv -Jloops=<loops per thread>`. The CSV lists a profile and
an example file (absolute path) per row; one row per IG creates one validation engine per IG. For `with-preload`, an
example of each IG's own profiles can be taken from the `package/example` folder of its package.

### 10. Many IGs: engine creation (with-preload)

`preload_engines.sh` runs `matchbox-server/with-preload` (12 IGs, 55 packages with the dependencies) on an empty
database, like a new installation: it installs the packages (`--hapi.fhir.only_install_packages=true`), starts
matchbox on the initialized database (`-m 4g`, host port 8080), runs `multi-ig.jmx` with `preload.csv` (an example of
each IG from the `package/example` folder of its package, `preload-*.json`, and an R4 core validation; 4 threads × 100
loops), saves one OperationOutcome per profile and the server log, and removes the containers and the database.

```bash
cd jmeter
./preload_engines.sh <h2|postgres> <label> <image> [<runs>]   # results in <label>-r<n>/ (git-ignored)
python3 engine_creation.py <label>-r1 <label>-r2 ...
```

Options (environment variables):

- `DB_TEMPLATE=1`: install the packages only once per database into a Docker volume (`mbpreload-h2-template`,
  `mbpreload-postgres-template`) and start each run on a copy of it. The install takes most of the time of a run (7 min
  on H2), and the installed database is the same for images that install the same way. Remove the volume
  (`docker volume rm mbpreload-h2-template`) when the installation changes.
- `LATENCY=2ms` (postgres): add this delay to the packets that PostgreSQL sends (`tc netem` in the network namespace of
  its container, with `alpine` and `iproute2-tc`), after the install, so every round trip to the database takes 2 ms
  more.

Besides `multi-ig.jmx`, each run

- validates an R4 core Patient (main engine, exists from the start) every 200 ms (`probe.py`, `probe.csv`): whether
  the requests for an existing engine wait while other engines are created (#616),
- measures the validation of the ch-core practitioner with `profile` alone and with `profile` and
  `ig=ch.fhir.ig.ch-core#6.0.0`, 200 validations by 1 client and 200 by 4 parallel clients, once the engines exist
  (`profile_lookup.py`, `lookup.txt`): the cost of finding the IG of a profile.

`postgres` uses the configuration of `with-preload` and a `postgres:18` container, `h2` the same configuration with the
H2 datasource of the default configuration (file on a Docker volume). The first request per IG creates the engine of
the IG; up to 4.1.19, engine creation is synchronized, so the requests of the other threads wait for it.
`engine_creation.py` reads the engine creation per IG from the server log (from `Creating new cached validate engine`
to `Terminology server` in the same thread), the first validation per IG (including the waiting), the R4 core
validations of `probe.py` until the last engine exists ("R4 core meanwhile") and the validation time once the engines
exist from the `.jtl`, prints the `profile_lookup.py` results per run, and compares the issues of the OperationOutcomes
with those of the first run given. Alternate the
images and databases between runs (a run takes about 4–5 minutes, most of it for the install).

## Reading the numbers

- **Check the heap limit of each image**
  (`docker image inspect <image> --format '{{json .Config.Entrypoint}} {{json .Config.Env}}'`).
  matchbox ≤ 4.0.x images use `-Xmx3072M`, and 4.1.x images use `-Xmx12g` (in `JDK_JAVA_OPTIONS` from PR #596 on).
- **Peak memory isn't the requirement.** With 12 GB allowed, the JVM grows the heap before collecting garbage, so the
  peak mostly shows garbage collector behaviour. 1.13.1 ran the same 8,000 validations within its 3 GB cap. To measure
  the real requirement, run with a smaller heap, e.g.
  `-e JDK_JAVA_OPTIONS="-Xmx3g -XX:+ExitOnOutOfMemoryError"` (images from PR #596 on).
- **`jvm.memory.used`** includes non-heap memory (metaspace, code cache), so it can exceed `-Xmx` slightly.
- **Single runs vary** by roughly ±20%. Treat smaller differences as noise, or repeat the run.
- **Changing ch-elm and matchbox together** confounds the two. To isolate matchbox, build the same ch-elm commit on
  different base images (step 2).

## Results

All runs: 8,000 validations, 0 failures. Runs from 25 Sep 2026 on the same machine, idle, with the ITB stack stopped.

| Folder | ch-elm image | matchbox / HAPI / core | Heap limit | Run duration | Validation median / p95 (ms) | Memory median / peak (GB) | Date |
|---|---|---|---|---|---|---|---|
| `1131` | 1.13.1 ¹ | 4.0.16 / 8.0.0 / 6.7.10 | 3 GB | 11.3 min | 327 / 366 | 3.15 / 3.33 | 2026-09-25 |
| `1141` | 1.14.1 | 4.1.9 / 8.8.0 / 6.9.8 | 12 GB | 7.7 min | 213 / 292 | 7.90 / 11.62 | 2026-09-25 |
| `1143` | 1.14.3 | 4.1.11 / 8.8.0 / 6.9.11 | 12 GB | 15.8 min | 453 / 572 | 8.45 / 11.87 | 2026-09-25 |
| `1150` | 1.15.0 | 4.1.12 / 8.10.1 / 6.10.0 | 12 GB | 30.3 min ² | 819 / 1,536 | 7.87 / 11.81 | 2026-08-12 |
| `1150-rerun` | 1.15.0 | 4.1.12 / 8.10.1 / 6.10.0 | 12 GB | 14.7 min | 428 / 495 | 7.97 / 11.58 | 2026-09-25 |
| `1151` | 1.15.1 | 4.1.13 / 8.10.1 / 6.10.0 | 12 GB | 19.0 min | 542 / 742 | 8.07 / 11.59 | 2026-09-25 |
| `1151-mb4114` | 1.15.1 on 4.1.14 ³ | 4.1.14 / 8.10.1 / 6.10.3 | 12 GB | 4.5 min | 116 / 154 | 4.13 / 7.83 | 2026-09-25 |
| `1151-mb4115` | 1.15.1 on 4.1.15 ³ | 4.1.15 / 8.12.0 / 6.10.4 | 12 GB | 3.8 min | 94 / 121 | 3.62 / 5.79 | 2026-09-25 |
| `1151-mb4116` | 1.15.1 on 4.1.16 ³ | 4.1.16 / 8.12.0 / 6.10.4 | 12 GB | 3.7 min | 95 / 116 | 3.67 / 5.81 | 2026-09-25 |
| `1152` | 1.15.2 | 4.1.17 / 8.12.1 / 6.10.4 | 12 GB | 3.7 min | 95 / 122 | 4.19 / 7.87 | 2026-09-25 |
| `1152-pr596` | 1.15.2 on PR #596 ³ | 4.1.17 / 8.12.1 / 6.10.4 | 12 GB | 3.7 min | 93 / 113 | 3.60 / 5.69 | 2026-09-25 |
| `1152-pr596-xmx3g` | 1.15.2 on PR #596 ³ | 4.1.17 / 8.12.1 / 6.10.4 | 3 GB ⁴ | 4.0 min | 105 / 131 | 2.67 / 3.35 | 2026-09-25 |
| `1152-pr596-xmx3g-dedup` | 1.15.2 on PR #596 ³ | 4.1.17 / 8.12.1 / 6.10.4 | 3 GB ⁵ | 4.0 min | 103 / 127 | 2.53 / 3.35 | 2026-09-25 |
| `1152-fix1-xmx3g-dedup` | 1.15.2 on branch `jmeter-check-runbook` ³ | 4.1.17 + fix / 8.12.1 / 6.10.4 | 3 GB ⁵ | 4.0 min | 104 / 126 | 2.46 / 3.36 | 2026-09-25 |

¹ Rebuilt from ch-elm commit `f3dd030` on `matchbox:v4.0.16`; the published 1.13.1 image is no longer in the registry.
² Machine was busy during this run.
³ Local build: ch-elm commit `3deaf40` with only the `FROM` line changed.
⁴ `-e JDK_JAVA_OPTIONS="-Xmx3g -XX:+ExitOnOutOfMemoryError"`; the JVM never ran out of memory.
⁵ As ⁴ plus `-XX:+UseStringDeduplication`.

### Startup, first and second validation (lazy loading)

`measure_startup.py`, 3 runs each, image default `JDK_JAVA_OPTIONS` (`-Xmx12g`, string deduplication), ch-elm 1.15.2
content. Ranges over the 3 runs:

| Image | Healthy after | ch-elm engine created in | 1st / 2nd / 3rd validation | Live heap after startup / after 3 validations |
|---|---|---|---|---|
| PR #598 (baseline) | 53–57 s | 25.5–26.3 s | 820–900 / 150–170 / 130–140 ms | 1,032–1,043 / 1,033–1,043 MB |
| Lazy loading of all types ⁶ | 43–52 s | 17.0–20.0 s | **2,290–2,430** / 155–180 / 130–155 ms | 695–701 / 771–777 MB |
| Lazy loading of terminology, IG packages | 46–52 s | 19.3–21.5 s | 840–890 / 155–160 / 128–136 ms | 808–814 / 810–817 MB |
| Lazy loading of terminology, also core and classpath packages | 39–46 s | 15.9–18.1 s | 840–900 / 160–195 / 140–165 ms | 702–707 / 704–707 MB |
| **+ no R4 core StructureDefinitions for the JPA search parameter extractor** | **39–49 s** | **16.0–18.4 s** | 858–891 / 160–180 / 136–148 ms | **659–660 / 663–665 MB** |

JMeter load test with `-Xmx3g` and string deduplication, 0 failures and the same issues in all runs:

| Image | Duration | Validation median / p95 | Live heap after the test |
|---|---|---|---|
| PR #598 | 4.0 min | 104 / 126 ms | 1,161 MB |
| Lazy loading of terminology, IG packages | 3.9 min | 99 / 126 ms | 829 MB |
| Lazy loading of terminology, also core and classpath packages | 3.8 min | 99 / 116 ms | **720 MB** |

⁶ With StructureDefinitions as proxies, the first validation parses them all: the FHIRPathEngine constructor, and then
`ContextUtilities.getStructures()` and other places, iterate over all StructureDefinitions. The core validator parses
all StructureDefinitions at startup (`ValidationEngine.prepare()`). So only CodeSystem, ValueSet, NamingSystem and
ConceptMap are loaded lazily.

The last row: the FHIRPathEngine of HAPI's JPA search parameter extractors (`SearchParamExtractorR4/R4B/R5`) listed all
StructureDefinitions in its constructor, which made HAPI's `DefaultProfileValidationSupport` parse and keep all 649 R4
core StructureDefinitions (40 MB). `NoAllStructureDefinitionsValidationSupport` returns an empty list for it; single
type definitions are still fetched when a search parameter expression needs them.

### Smaller heaps (lazy loading of terminology, also core and classpath packages)

JMeter load test with `-XX:+UseStringDeduplication` and `-XX:+ExitOnOutOfMemoryError`, a fresh container per heap
size, 0 failures and no OutOfMemoryError in all runs. GC time and old generation from `jstat -gcutil 1`.

| Folder | `-Xmx` | Duration | Validation median / p95 | GC time during the test | Old generation after the test | Live heap after full GC |
|---|---|---|---|---|---|---|
| `1152-lazy5-xmx3g` | 3 GB | 3.7 min | 93 / 115 ms | 4.3 s | 81% | 720 MB |
| `1152-lazy5-xmx2g` | 2 GB | 3.9 min | 101 / 125 ms | 7.7 s | 88% | 778 MB |
| `1152-lazy5-xmx1g` | 1 GB | 4.9 min | 132 / 148 ms | 49 s | 94% | 758 MB |

2 GB costs about 8% of validation time. 1 GB works, but about 40% slower, with 3 full GCs during startup and little
headroom for larger documents, more parallel requests or more IGs.

### Several IGs (with-ch: ch-core and ch-epr-fhir)

`multi-ig.jmx`, 400 validations, 0 failures, same issues on both images:

| | PR #598 | Lazy loading | + shared package cache |
|---|---|---|---|
| Live heap after startup (main engine) | 547 MB | 398 MB | 395 MB |
| Live heap after 400 validations (main, ch-core and ch-epr-fhir engines) | 1,160 MB | 766 MB | **687 MB** |
| Retained by the ch-core / ch-epr-fhir engine alone | 197 / 206 MB | 94 / 91 MB | **14 / 20 MB** |
| Resources loaded twice (in both IG engines) | 14,595, 175 MB | 14,599, 53 MB | **34, 1 MB** |
| First validation per IG, incl. engine creation: ch-core / ch-epr-fhir | 10.6 / 25.8 s | 7.2 / 17.2 s | 7.2 / **14.1 s** |

The packages of the main engine (R4 core, hl7.terminology.r4 7.3.0, extensions 5.3.0, xver) exist once: the IG
engines are copies of the main engine and share its resources. The dependencies that both IGs have in common (ch-core,
ch-term, hl7.terminology.r4 6.3.0/6.5.0/7.0.1, extensions 5.2.0/5.3.0-ballot-tc1) are loaded separately in each IG
engine. With lazy loading their terminology resources stay unparsed, what remains duplicated are the
StructureDefinitions (53 MB).

`SharedPackageResourcesCache` shares them too: when an engine needs a package (id#version) that another engine has
loaded, it registers the same resource and proxy objects instead of loading and parsing the package again. The cache
keeps only weak references; the worker contexts of the engines that use a package keep it alive
(`BaseWorkerContext.retain()`), so it's released when the last of these engines is dropped (e.g. when transient engines
expire after 60 minutes). Uninstalling an IG evicts it from the cache.

### Many IGs (with-preload, 12 IGs with profiles, 55 packages with the dependencies)

`matchbox-server/with-preload` with the latest released versions (2026-09-26) on H2, one example per IG (12 IG engines)
plus an R4 core validation, 400 validations, 0 failures:

| | PR #598 | PR #600 | PR #600, `-Xmx3g` | PR #600, `-Xmx2g` |
|---|---|---|---|---|
| Live heap after startup | 549 MB | 397 MB | 395 MB | 390 MB |
| Live heap after all 12 engines and 400 validations | **3,410 MB** | **1,195 MB** | 1,331 MB | 1,345 MB |
| Retained by the 12 IG engines alone, total (median per engine) | 2,550 MB (188 MB) | 177 MB (14 MB) | | |
| Resource objects loaded in several engines | 133,472 (1,674 MB) | 83 (1 MB) | | |
| First validation per IG incl. engine creation, median / max | 38.0 / 50.3 s | 13.0 / 27.1 s | 13.1 / 27.0 s | 13.4 / 27.6 s |
| Old generation after the test / GC time | 81% / 4.0 s | 36% / 1.7 s | 92% / 2.1 s | 93% / 2.8 s |

With PR #600, a 3 GB heap is more than twice the live heap of 12 IG engines; 2 GB passes but is tight. Without it, 3 GB
would not suffice (3.4 GB live heap).

The image's default `-XX:MaxRAMPercentage=70` in a container with a 4 GB limit (`-m 4g`, heap 2.8 GB): 400 validations,
0 failures, 12 engines, not OOM killed, container memory 3.54 GiB of 4 GiB (`preload-4g`).

### H2 or PostgreSQL (with-preload, matchbox 4.1.18)

`matchbox-server/with-preload` on `matchbox:v4.1.18` (`-m 4g`), once with its PostgreSQL configuration
(`postgres:latest`, 18.6, in the same Docker network) and once with only the datasource changed to
`jdbc:h2:file:./database/h2` (`HapiFhirH2Dialect`, file on a Docker volume). Each run starts with an empty database:
install the 55 packages (`--hapi.fhir.only_install_packages=true`), start a new container on the initialized database,
then `multi-ig.jmx` with one example per IG (from `package/example` of each package) and an R4 core validation, 400
validations. 3 runs per database, alternating; results in `db-<database>-r<n>` (git-ignored). Engine creation is
measured in the server log from `Creating new cached validate engine` to `Terminology server` in the same thread.

| | PostgreSQL (3 runs) | H2 file (3 runs) |
|---|---|---|
| Install of the 55 packages | 181–184 s | 171–189 s |
| Database size | 411 MB | 883–896 MB |
| Healthy after start on the initialized database | 28–29 s | 28–29 s |
| Live heap after startup | 373–374 MB | 387–389 MB |
| Engine creation per IG, median / max (12 engines, total) | 4.1–4.2 / 17.6 s (69–70 s) | 4.1–4.3 / 17.4–17.6 s (69–70 s) |
| First validation per IG incl. waiting, median / max | 19.3–19.8 / 36 s | 19.4–19.7 / 36 s |
| Validation once the engines exist, median / p95 | 20–21 / 28–29 ms | 19–21 / 29–31 ms |
| Live heap after the test | 1.25–1.29 GB | 1.26–1.30 GB |
| Container memory after the test | 3.7 GiB + 210 MiB PostgreSQL | 3.7–3.8 GiB |

0 failures, no OOM, the same OperationOutcomes in all runs (apart from durations and the session id). The database is
not on the critical path when it runs next to matchbox: engine creation is CPU bound (parsing the packages), and the
H2 page cache in the JVM costs about 15 MB of heap. PostgreSQL compresses the package binaries in `hfj_res_ver` (TOAST,
364 MB), H2 needs twice the disk.

**Latency to the database matters, the database product doesn't.** With 2 ms more round trip to PostgreSQL (`tc
netem` on the PostgreSQL container, `db-pg-lat2ms`), engine creation grew only by 5% (73 s for 12 engines), but every
validation went from 20 to 50 ms (server time 16 → 46 ms). A `$validate` with a `profile` that isn't in the main
engine and without `ig` looks up the IG of the profile in the database on every request
(`MatchboxEngineSupport.loadPackageAssetByUrl()`: the package resource by canonical, its package version and the
Binary's `hfj_resource` row, in a transaction, about 6 round trips), and does so inside the `synchronized`
`getMatchboxEngine()`, so the lookups of parallel requests queue. ch-core practitioner, curl:

| | sequential | 4 parallel clients |
|---|---|---|
| `profile`, PostgreSQL next to matchbox | 18 ms | 20 ms |
| `profile`, +2 ms latency | 30 ms | **48 ms** |
| `profile` and `ig=ch.fhir.ig.ch-core#6.0.0`, +2 ms latency | 14 ms | 16 ms |

With `ig`, matchbox doesn't access the database for a validation; the engine (same session id) and the issues are
the same. The synchronized engine lookup also makes every request wait while an engine is created (up to 17.6 s here),
also those for IGs whose engine exists.

**`HapiFhirPostgresDialect` instead of `PostgreSQLDialect`** (`db-pghapi-r<n>`, `db-pghapi-lat2ms`, 3 runs + 1 with
2 ms latency, each on an empty database): the same install time (181–183 s), database size (411 MB), startup (28 s),
engine creation (69 s for 12 engines), validation time (21–22 ms, 51 ms with 2 ms latency), live heap (1.26–1.28 GB)
and OperationOutcomes. The dialect only differs in `supportsColumnCheck()` (false) and `getDriverType()`: a new
database has no check constraints with the values of enum columns (Hibernate's dialect creates 24, e.g. on
`bt2_work_chunk.stat`, which newer HAPI FHIR versions that add values violate). HAPI's own services still see
Hibernate's dialect ("Dialect is not a HAPI FHIR dialect", "Database schema check skipped"):
`JpaHibernatePropertiesProvider` was the version of the jpaserver-starter of matchbox 3.0.0 and resolved the dialect
from the JDBC metadata, also for `HapiFhirH2Dialect` in the default configuration. Since the port of the upstream
version (4.1.19), it uses `hibernate.dialect` when it's set, and both warnings are gone.

### Engine creation (with-preload, #609)

`preload_engines.sh` (step 10), 3 runs per image and database, alternating, each on an empty database (2026-09-29).
`main` is `d33e1acde` (4.1.19 development, after #608), `#609` the same with the changes of #609. Results in
`i609-<image>-<database>-r<n>` (git-ignored).

| | 4.1.18 (H2 / PostgreSQL) ¹ | main (H2 / PostgreSQL) | #609 (H2 / PostgreSQL) |
|---|---|---|---|
| Engine creation per IG, median / max | 4.1–4.3 / 17.4–17.8 s | 3.3–3.5 / 14.4–15.1 s | **0.6–0.7 / 8.6–9.3 s** |
| Engine creation of the 12 engines, total | 69–70 s | 57–59 s | **20–22 s** |
| ch-core engine (all its packages loaded by other engines) | 3.6–3.9 s | 3.0–3.3 s | **0.2–0.3 s** |
| First validation per IG incl. waiting, median / max | 19.1–19.8 / 36 s | 15.8–16.4 / 29–31 s | **3.0–3.6 / 13–15 s** |
| Validation once the engines exist, median | 19–21 ms | 18–19 ms | 18–20 ms ² |
| Live heap after the test | 1.26–1.30 GB | 1.33–1.39 GB | 1.21–1.30 GB |
| Install of the 55 packages / healthy after | 171–189 / 28–29 s | 152–165 / 22–27 s | 152–162 / 23–34 s |

0 failures, no OOM, and the same issues in all 12 runs. H2 and PostgreSQL give the same times.

¹ The runs of the section above, on `postgres:latest` (18.6).
² One run (H2) 27 ms.

Two changes:

- **The package archives are only read to load resources.** For every package and dependency,
  `IgLoaderFromJpaPackageCache.loadIg()` read the package archive from the database and unpacked it
  (`JpaPackageCache.loadPackageFromCacheOnly()`), only to get its name, version and dependencies, and then checked
  whether another engine had already loaded the package. A package that wasn't loaded yet was read and unpacked a
  second time. Now the version is resolved in the database without reading the archive
  (`JpaPackageCache.findPackageVersionFromCacheOnly()`), the name, version, dependencies and internal dependencies are
  kept in `SharedPackageResourcesCache` (by the id of the archive's Binary, so a package version that is installed
  again, e.g. a ci-build, is read again), and the archive is read at most once.
- **Registering a version of `hl7.terminology` no longer gets slower with each further version in the context.** For
  each resource of a `hl7.terminology` package, `CanonicalResourceManager.see()` looked through all resources of the
  type for a resource of the core package with the same URL ("UTG support prior to version 5"). With JFR, more than
  half of the samples of loading 4 versions of `hl7.terminology.r4` into a copy of the main engine were in this loop
  (`HashMap$KeyIterator`); the time per version grew from 0.92 to 1.48 s (6.3.0, 7.0.1, 6.5.0, 6.2.0, including the
  unpacking). It now looks up the resources with the URL in `listForUrl`, as in core `3b4427401` (not yet released):
  0.51–0.58 s for each version. In the server, a version of `hl7.terminology.r4` that isn't shared yet is registered
  in 0.3–0.4 s instead of 1.6–2.0 s, and one that another engine has loaded in a few ms.

What remains of the engine creation is loading the packages that no other engine has loaded yet: ch-atc (8.6 s)
depends on older versions that only it uses (`hl7.fhir.uv.extensions.r4` 5.1.0 and 1.0.0, `hl7.fhir.uv.extensions.r5`
5.1.0, `hl7.terminology.r4` 5.5.0, 5.3.0 and 3.1.0, `hl7.terminology` 6.1.0, ch-core 5.0.0, …), and the
StructureDefinitions of the extensions packages (about 1.1 s per version) are parsed up front.

### Narrative not parsed (#614)

The narrative of the package resources is dropped after parsing (#566), but its XHTML was still parsed: about half of
the parse time of a terminology resource, and about 28% for IG profiles with generated narrative (the core packages
have none). Now the narrative is removed from the JSON before parsing, and the JSON is parsed with Gson instead of R4's
`JsonTrackingParser`: in the server by `PackageResourceParser` (all IG packages), in the engine by the core loaders
with the matchbox patch option `skipNarrative` (core package and classpath packages of the main engine).

Images: `issue614-base` is main after #610 (`d6b02d301`), `issue614-full` the same with #614 (server part and loader
patch). 2026-09-29, **the machine was not idle** (other work during the runs, more during the ch-elm and with-ch runs);
the runs alternate between the images, so the tendencies hold, single values vary more than usual. 0 failures, no
OOM, the same issues in all runs.

**Many IGs** (`preload_engines.sh h2`, 3 runs each, results in `i614b-<image>-h2-r<n>`):

| | base | #614 |
|---|---|---|
| Healthy after start on the initialized database | 27–28 s | **24–26 s** |
| Engine creation of the 12 engines, total | 20–22 s | **15–17 s** |
| Engine creation per IG, median / max (ch-atc) | 0.6–0.7 / 8.6–9.2 s | 0.5–0.6 / **6.1–6.4 s** |
| ch-allergyintolerance / ch-epr-fhir engine | 4.7–5.9 / 1.4–1.5 s | **3.9–4.1 / 1.0–1.2 s** |
| First validation per IG incl. waiting, median / max | 3.2–3.5 / 13.3–15.3 s | 2.6–3.3 / **10.0–10.4 s** |
| Validation once the engines exist, median | 18–20 ms | 19–24 ms |
| Live heap after startup / after the test | 394–397 MB / 1.23–1.33 GB | 395–398 MB / 1.22–1.24 GB |

The startup is faster because the main engine parses the core and classpath packages faster (loader patch). The
engines whose packages other engines have already loaded don't change (ch-core 0.2–0.3 s); the gain is in the engines
that parse packages no other engine has loaded (ch-atc and its old dependency versions).

**ch-elm 1.15.3** (commit `0071e49` on both images; `measure_startup.py`, 1 run per label, alternating, 3 each,
`startup-i614b-<image>-r<n>.csv`):

| | base | #614 |
|---|---|---|
| Healthy after | 34–36 s | 32–37 s |
| ch-elm engine created in | 8.7–9.2 s | **7.4–7.8 s** (9.4 s in a run that was slower throughout) |
| 1st / 2nd / 3rd validation | 889–941 / 171–201 / 155–219 ms | 879–1,083 / 171–253 / 154–254 ms |
| Live heap after startup / after 3 validations | 663–664 / 670–671 MB | 662–667 / 670–674 MB |

`memory.jmx` (8,000 validations, `-Xmx3g`, string deduplication, `1153-i614b-<image>`): 3.9 / 3.9 min, validation median
99 / 99 ms, p95 122 / 123 ms, live heap after the test 680 / 679 MB, response 34,596 / 34,594 bytes. Unchanged, as
expected: the narrative was already dropped after parsing.

**Several IGs** (with-ch, `multi-ig.jmx`, 400 validations, 2 runs each, `withch-i614b-<image>-r<n>`; both images
slower than usual here, the machine was busy):

| | base | #614 |
|---|---|---|
| Healthy after | 30–49 s | 28–39 s |
| ch-core / ch-epr-fhir engine created in | 4.6–5.5 / 4.9–6.1 s | **4.2–4.6 / 3.9–4.0 s** |
| First validation per profile (ch-core-patient, ch-mhd-documentreference-comprehensive, ch-core-composition, PpqmConsentTemplate201) | 5.2–6.2, 10.5–12.5, 9.4–11.5, 9.1–11.2 s | 5.0–5.4, 9.3–10.4, 8.0–9.0, 7.7–8.6 s |
| Live heap after startup / after the test | 402–405 / 701–703 MB | 400–401 / 698–704 MB |

**Main engine alone** (`new MatchboxEngineBuilder().getEngineR4()` 6 times in one JVM, 2 runs each, engine jar
without / with the loader patch): 4.55–4.60 → 3.55–3.68 s once warm, 7.0 → 6.1 s for the first. The core loaders parse
the classpath packages (R4 core, extensions 5.3.0, xver-r5.r4; 4,259 resources) in 1.37 instead of 2.24 s, here
mostly because of Gson, as these packages have no narrative.

### Engine lock and IG lookup (#616)

`MatchboxEngineSupport.getMatchboxEngine()` was `synchronized`: every request that needed an engine waited while any
engine was created, and the IG of a `profile` that isn't in the main engine was looked up in the database on every
request, inside the lock. Approaches:

- **Only cache the IG lookup, keep `synchronized`**: removes the database round trips, but a request for an existing
  engine still waits up to the creation time of another IG's engine (8 s for ch-atc here).
- **`ConcurrentHashMap.computeIfAbsent` on the engine cache**: holds the lock of the map bin for the whole creation
  (also blocking other keys in the bin), can't be nested, and doesn't exclude uninstall and reload.
- **Striped locks per engine key**: works, but still needs a lock against reload and uninstall.
- **Chosen: a read/write lock plus one future per engine being created.** Requests hold the read lock; the requests for
  an engine that is being created wait for its future, the others don't wait. The write lock is held to (re)create the
  main engine (startup, `$load-all`, `reload`), to evict the engines of an uninstalled package, and in the
  `onlyOneEngine` mode (IGs are loaded into the main engine per request). The IG of a canonical (also "not found") is
  cached until a package version is installed or uninstalled: `JpaPackageCache` publishes an
  `InstalledPackagesChangedEvent` after the transaction that adds or removes a package version, whatever the way the
  package is installed (`$load-all`, ImplementationGuide create/update, `$install-npm-package`, dependencies); a lookup
  that overlaps with it goes to the replaced map. Engines that are created at the same time load a shared package once
  (`SharedPackageResourcesCache.loadingLock()`): 51 package loads in every run, as before.

`preload_engines.sh` with `DB_TEMPLATE=1` (one install per database, 186–189 s), alternating, 2026-09-29, machine idle.
`main` is image `issue614-full` (`0d0e8f854` has the same code), `#616` this change. 0 failures, no OOM, the same
issues in all 14 runs. Results in `i616-<image>-<database>-r<n>` (git-ignored).

| | main | #616 |
|---|---|---|
| **H2** (3 runs each) | | |
| R4 core validation (main engine) while the IG engines are created, median / max (`probe.py`) | 103–226 ms / **8.0–8.2 s** | 36–40 / **224–278 ms** |
| First validation per IG incl. waiting, median / max | 3.1–3.2 / 12.1–12.4 s | **1.6–2.2 / 8.4–8.9 s** |
| Engine creation per IG, median / max ¹ | 0.6 / 7.3–7.5 s | 1.6–2.1 / 8.3–8.8 s |
| Validation once the engines exist, median (server) | 21 ms (16) | 19–20 ms (14–15) |
| ch-core practitioner, `profile`, 1 / 4 clients, median (p95) | 15.4–15.8 (18) / 16.6–17.4 (20–27) ms | 14.4–14.6 (17) / 16.4–16.7 (19–20) ms |
| ch-core practitioner, `profile` and `ig`, 1 / 4 clients, median | 13.2–13.4 / 15.3–15.6 ms | 13.3–13.6 / 15.3–15.8 ms |
| Live heap after startup / after the test ² | 395–398 MB / 1.23–1.24 GB | 396–399 MB / 1.27–1.31 GB |
| **PostgreSQL, +2 ms latency** (2 runs each) | | |
| R4 core validation while the IG engines are created, max | 8.6–8.8 s | **221–249 ms** |
| First validation per IG incl. waiting, median / max | 3.5 / 13.1 s | **1.6–1.9 / 8.7–8.9 s** |
| Validation once the engines exist (JMeter, `profile` only), median (server) | 50 ms (46) | **18–20 ms (14–15)** |
| ch-core practitioner, `profile`, 1 / 4 clients, median (p95) | 28.8–29.3 (31–32) / **55.4–55.5 (66–67)** ms | 14.4–14.6 (17) / **16.4–17.1 (20–22)** ms |
| ch-core practitioner, `profile` and `ig`, 1 / 4 clients, median | 13.3–13.5 / 15.1–15.3 ms | 13.3–13.5 / 15.1–16.1 ms |
| **PostgreSQL** (1 run each) | | |
| R4 core validation while the IG engines are created, max | 8.1 s | 249 ms |
| First validation per IG incl. waiting, median / max | 4.9 / 12.3 s | 2.1 / 8.6 s |
| ch-core practitioner, `profile`, 1 / 4 clients, median | 16.2 / 17.3 ms | 15.1 / 16.8 ms |

¹ The engines are now created in parallel (4 JMeter threads), sharing the CPU and waiting for the packages that
another engine is loading, so each creation takes longer, but they overlap: the sum (18–21 → 29–31 s) is no longer
the wall-clock time. The last engine (ch-atc) is ready after its own creation time instead of after the creation of
the engines before it.
² After the test, #616 has −5 to +90 MB (on average +50 MB) of live heap (1.23–1.24 → 1.27–1.31 GB on H2, 1.20–1.23 →
1.23–1.27 GB with latency), with the same package loads; not investigated further (heap dump).

Requests for existing engines no longer wait for engine creation (max 8 s → 0.2–0.3 s, the rest is CPU contention
with the engines being created). With the IG of a profile cached, a `$validate` with `profile` alone is as fast as
with `ig`, also with 2 ms of latency to the database and 4 parallel clients (55 → 17 ms); without latency the lookup
cost only 1–2 ms.

**ch-elm 1.15.3** (commit `0071e49` on the #616 image, `matchbox-ch-elm:1153-i616`, fresh container each):
`jmeter_fast.sh` (1 thread × 2,000) 0 errors, median 96 / p95 101 ms; `memory.jmx` (4 threads × 2,000) 0 errors,
median 114 / p95 127 ms, 4.0 min, live heap after the test 675 MB (#614 image `1153-i614b-full`: 109 / 131 ms,
3.9 min, 679–680 MB). All validations of both runs on the same engine (one session id), no errors in the server log.
The ch-elm engine is created at startup, so these runs check concurrent requests on an existing engine; the parallel
creation of engines is covered by the with-preload runs above (3–4 requests per run waited for an engine that another
request was creating).

### Findings so far

- **4.1.9 → 4.1.11: validation 2× slower** (213 → 453 ms). HAPI stays at 8.8.0; core 6.9.8 → 6.9.11 is the likely
  cause.
- **4.1.13 → 4.1.14: validation 4.7× faster** (542 → 116 ms) with the same ch-elm package, and the peak memory
  drops from about 11.6 to 7.8 GB. 4.1.14 brought core 6.10.3, the revert of the `findProfile()` workaround (#487) and
  no longer keeping narratives in the engine context (#566).
- **4.1.14 → 4.1.15: another 20% faster** (116 → 94 ms), with core 6.10.4, HAPI 8.12.0 and the engine copy fix (#538).
  4.1.16 and 4.1.17 are the same as 4.1.15.
- The `$validate` response size changes slightly between releases (34,848 bytes on 4.1.13, 34,634 on 4.1.14–4.1.16,
  34,845 on 4.1.17), so the outcomes aren't byte-identical, but they're close.
- **PR #596** (JVM options through `JDK_JAVA_OPTIONS`, exec-form entrypoint) performs the same as 4.1.17.
- **4.1.17 fits in a 3 GB heap:** all 8,000 validations passed at only about 12% slower (105 vs 93 ms median),
  and about 3× faster than 1.13.1 with the same 3 GB cap.
- **Live heap of 4.1.17 with ch-elm: 1.49 GiB** after a full GC (`jcmd 1 GC.heap_info`). A heap dump analysed in
  Eclipse MAT shows 977 MB of parsed conformance resources (44,650, all parsed up front; 7 versions of
  hl7.terminology.r4 and 5 of hl7.fhir.uv.extensions.r4, which are needed) and 210 MB of raw package files of
  hl7.fhir.uv.xver-r5.r4 and hl7.fhir.r4.core, kept alive by `BytesFromPackageProvider` entries in the main engine's
  `SimpleWorkerContext.binaries`.
- **`-XX:+UseStringDeduplication` lowers the live heap by 15%** (1.49 → 1.27 GiB; 4.85 million strings, 223 MB
  deduplicated) at no measurable cost in validation time.
- **Releasing the package content held by `BytesFromPackageProvider`** (matchbox patch in `BaseWorkerContext`) lowers
  the live heap by another 171 MB (1.27 → 1.11 GiB, with string deduplication); no `NpmPackage` is left on the heap.
- **Lazy loading of the terminology resources** of the IG packages (`IgLoaderFromJpaPackageCache`) lowers the live
  heap by another 225–330 MB, and the ch-elm engine is created about 6 s faster; the first validation takes the same
  time. Doing the same for the core package and the classpath packages of the main engine (hl7.terminology.r4 7.3.0,
  extensions, xver, CDA) saves another 105 MB and 4–6 s of startup. The core terminology resources are pinned to the
  core versions when they're parsed (`MetadataCoreVersionPinner`), like `SimpleWorkerContext.finishLoading()` does.
- **Before: lazy loading.** All 44,650 conformance resources are parsed up front (977 MB), because
  `IgLoaderFromJpaPackageCache` parses and caches every resource itself and the classpath packages are in-memory
  `NpmPackage`s, for which core's lazy `PackageResourceLoader` path is disabled (`canLazyLoad()` is false). The core
  validator, also in its HTTP server mode, registers proxies and parses a resource only when it's first needed.
