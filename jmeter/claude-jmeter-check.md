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
| **Lazy loading of terminology, also core and classpath packages** | **39–46 s** | **15.9–18.1 s** | 840–900 / 160–195 / 140–165 ms | **702–707 / 704–707 MB** |

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
