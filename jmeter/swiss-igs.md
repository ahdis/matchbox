# Swiss IGs: how many engines fit into a container

How many of the latest Swiss IGs from [fhir.ch](https://fhir.ch) a Matchbox container can load and validate against:
after the startup, one validation per IG, each creating the engine of its IG, and after each validation a full GC
with the live heap, the committed heap and the container memory. The engines stay cached (transient engines expire
60 minutes after their last use), so after the last IG all engines are in memory at the same time, like on a test
instance with many IGs.

## Files

| File | What |
|---|---|
| `swiss_igs.py` | prepares the test from `https://fhir.ch/package-registry.json`, and prints the report of a run |
| `swiss-igs.yaml` | generated: Matchbox configuration (H2) installing the IGs, their dependencies come with them |
| `swiss-igs.csv` | generated: per IG `profile`, example file, `ig` (URL-encoded `id#version`), name |
| `swiss-*.json` | generated: one example per IG |
| `swiss-igs.jmx` | JMeter: one thread, `$validate` of each row in turn, then the "live heap" OS process sampler |
| `swiss_igs.sh` | runs the test against an image with a container memory limit |

Results in `swiss-<label>/` (git-ignored): `summary.txt`, `test.jtl`, `server.log`, `install.log`, `nmt.txt`. The
downloaded packages are cached in `swiss-igs-packages/` (git-ignored).

## Prepare

```bash
cd jmeter && python3 swiss_igs.py              # --no-releases: only the latest version of each IG
```

- **IGs:** the latest version of every IG in the registry; when it is a pre-release (ballot), also the latest release
  (e.g. ch-core 7.0.0-ballot and 6.0.0), as a test instance has both. R5 IGs (ch-epl, ch-idmp) are skipped, and IGs
  with a dependency that can't be installed (ch-ems 1.9.0 → ch-core 1.0.0 → `ch.fhir.ig.ch-epr-term#current`; such
  a dependency makes the whole install fail). Patch wildcards (`ch.fhir.ig.ch-term#3.4.x`) are resolved like the
  registry does.
- **Example per IG:** from `package/example`, an example whose profile (`meta.profile`, or `exampleCanonical` in the
  ImplementationGuide resource) is a profile of the IG itself; the smallest one, Bundles last. IGs without profiles
  (ch-term, ch-epr-term) are installed but not validated.
- The validation uses `profile` and `ig`, so each request goes to the engine of exactly that IG version.

On 2026-10-01: 26 IG versions installed (80 packages parsed with the dependencies), 24 validated.

## Run

```bash
./swiss_igs.sh <label> <image> [<container memory, default 4g>] [<rounds, default 2>]
./swiss_igs.sh mb4119-4g europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:v4.1.19 4g
```

- Installs the IGs once into the Docker volume `mbswiss-h2-template` (about 4 minutes, again when `swiss-igs.yaml`
  changes) and starts each run on a copy of it.
- Starts the image with `-m <memory>` on host port 8080 with its own JVM options (`-XX:MaxRAMPercentage=70
  -XX:+ExitOnOutOfMemoryError …`, heap 70% of the container memory) plus `-XX:NativeMemoryTracking=summary` (`NMT=0`
  without).
- Round 1 creates one engine per IG, round 2 validates against the cached engines.
- After each validation: `jcmd 1 GC.run`, the used and committed heap (`GC.heap_info`) and `docker stats` memory
  (`MemUsage`: the cgroup memory without inactive page cache, what the memory limit applies to).
- At the end: the native memory summary, whether the container is still running, was OOM killed by the kernel
  (`oomkilled`) or ended with an `OutOfMemoryError` (`ExitOnOutOfMemoryError`, exit code 3).

The report again: `python3 swiss_igs.py report swiss-<label>`.

## Results (matchbox 4.1.19, 2026-10-01)

| Container memory (heap) | IGs validated | Live heap after the last IG | Committed heap | Container memory | Result |
|---|---|---|---|---|---|
| 4 GB (2.87 GB) | 24 of 24 | 1,693 MB | 2,868 MB | 3.74 GiB | all 48 validations passed |
| 3 GB (2.15 GB) | 24 of 24 | 1,740 MB | 2,152 MB | 2.97 GiB | all 48 validations passed |
| 2.5 GB (1.75 GB) | 22 of 24 | 1,735 MB | 1,750 MB | 2.41 GiB | `OutOfMemoryError` at the 23rd IG (ch-ems 2.0.0-ballot), JVM exited |

Live heap after the startup 398 MB; then per IG (4 GB run, MB): ch-core 7.0.0-ballot +88, ch-core 6.0.0 +64, ch-emed
+32, ch-allergyintolerance +48, ch-vacd 7.0.0-ballot / 6.0.0 +21 / +25, ch-orf +30, ch-lab-order +17, ch-etoc +21,
**ch-atc +361** (old dependency versions that no other IG uses), ch-epr-ppqm +5, ch-epr-mhealth +76, ch-elm +77,
ch-lab-report +5, ch-emed-epr +38, ch-ig +146, ch-epr-fhir +105, ch-ips −11, ch-rad-order +19, ch-epreg +41, ch-emr
+20, ch-alis-connect +11, ch-ems 2.0.0-ballot +37, ch-umzh-connect +36. Engine creation 0.3–5.5 s per IG.

- **24 Swiss IGs need about 1.7 GB of live heap.** With 4 GB, the heap (2.87 GB) has 1.2 GB left, room for more IG
  versions; how many depends on their dependencies: an IG that shares its dependencies with the loaded IGs costs
  5–80 MB, one with its own old dependency versions (ch-atc) 360 MB.
- **The JVM keeps the heap it has committed** (G1 doesn't return it): with 4 GB the full 2.87 GB heap is committed
  after ch-atc, although only 1.1–1.7 GB is live. Outside the heap the JVM commits about 500 MB (NMT, 4 GB run:
  metaspace 190 MB, GC 118 MB, code 63 MB, symbols 41 MB, string deduplication 16 MB, NMT 16 MB), and the container
  shows another 0.3–0.4 GiB (native allocations outside NMT, page cache of the H2 database and the jar). The container
  is at 3.74 GiB of 4 GiB, and 2.97 of 3 GiB: little headroom for parallel requests, large documents or more
  threads, which grow memory outside the heap.
- **The heap is the limit:** with 2.5 GB the 23rd engine didn't fit (`OutOfMemoryError`, the container exits with
  `ExitOnOutOfMemoryError`), not the container limit.
