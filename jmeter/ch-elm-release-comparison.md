# Matchbox releases: ch-elm and with-preload comparison

The two tables of the blog article "Matchbox: from 12 GB to 4 GB", and how to measure a further release for them. The
general setup (JMeter, images, port 8080, registry login) is in `claude-jmeter-check.md`; this file only lists what is
needed for these two tables.

## Results

### ch-elm (one IG, 8,000 validations)

| ch-elm image (Matchbox release) | 1.13.1 (4.0.16) | 1.14.1 (4.1.9) | 1.15.2 (4.1.17) | 1.15.3 (4.1.18) | 1.15.4 (4.1.19) |
|---|---|---|---|---|---|
| Heap limit of the image | 3 GB | 12 GB | 12 GB | 70% of the container memory | 70% of the container memory |
| Live heap after 8,000 validations | 2,300 MB | 3,420 MB | 1,560 MB | 660 MB | 660 MB |
| Server ready after | 36–37 s | 43–46 s | 65–67 s | 48–49 s | 29–32 s |
| Validation under load (median of 8,000) | 327 ms | 250 ms | 104 ms | 108 ms | 98 ms |
| Runs the load test in a 3 GB heap | Yes | No (live heap 3.4 GB) | Yes | Yes, also in 1 GB | Yes, also in 1 GB |

Local results (git-ignored): `startup-<label>.csv` and `<label>.jtl` with the labels `1131-rerun`, `1141-rerun`,
`1152-rerun`, `1153` and `1154` (`1154-rerun` for the startup), `1153-xmx3g`, `1154-xmx3g` and `1154-xmx1g`.
1.13.1 is a rebuild of ch-elm commit `f3dd030` on `matchbox:v4.0.16` (`matchbox-ch-elm:1.13.1-rebuild`); the
published image is no longer in the registry.

4.1.19 (2026-09-30): server ready 29.4–32.2 s in 5 of 6 runs; the first run after the image pull took 49.2 s (cold
start, not counted). Live heap after the load test 662 MiB. `-Xmx3g`: median 95 ms, `-Xmx1g`: median 127 ms, both
0 failures and no OutOfMemoryError.

### with-preload (12 Swiss IGs, 55 packages)

| with-preload: 12 Swiss IGs, 55 packages | Release 4.1.9 | Release 4.1.17 | Release 4.1.18 | Release 4.1.19 |
|---|---|---|---|---|
| Heap limit of the image | 12 GB | 12 GB | 70% of the container memory | 70% of the container memory |
| Live heap with all 12 engines | 10,630 MB | 4,756 MB | 1,331 MB | 1,271 MB |
| Memory held by the 12 IG engines alone | 8,123 MB | 3,465 MB | 249 MB | 229 MB |
| Duplicated between engines | n.a. | 2,278 MB | 1 MB | 1 MB |
| Creating an IG engine (median) | 19 s | 48 s | 16 s | 1 s |

The 4.1.18 and 4.1.19 columns were measured on 2026-09-30/10-01 with the procedure below. The 4.1.9 and 4.1.17 columns
are from 2026-09-26 (`preload-mb419.jtl`, `preload-mb4117.jtl` for the engine creation, heap dumps of that session);
measuring them again the same way would make the memory rows directly comparable.

Details of 4.1.18 / 4.1.19: duplicated 31 objects (0.6 MB) / 32 objects (1.2 MB); the median IG context retains
20 / 19 MB; first validation per IG median 15.9 / 1.2 s. The runbook script (`preload_engines.sh h2`, results in
`mb4118-h2-r1`, `mb4119-h2-r1`, `mb4119-h2-r2`) gives the same picture: first validation per IG median 15.4 s on
4.1.18 and 0.9–1.9 s on 4.1.19, engine creation of the 12 engines in total 56 s and 24–27 s, live heap after its
longer test 1,341 MiB and 1,287–1,338 MiB, the same issues on both releases.

## Measuring a new release

Use the published images, nothing has to be built: the ch-elm image of the release
(`europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox-ch-elm:<ch-elm version>`, its `FROM` is the Matchbox release)
and the Matchbox image (`europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:v<version>`). Find the newest tags with

```bash
gcloud artifacts docker images list europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox-ch-elm --include-tags \
  --format='value(tags,createTime)' | sort -k2 | tail -3
```

If gcloud reports "Reauthentication failed", run `! gcloud auth login` first. Port 8080 must be free and the machine
idle; run the measurements one after another, never in parallel.

### ch-elm table

`E` is the ch-elm image, `<label>` the ch-elm version without dots (e.g. `1154`). All commands in `jmeter/`.

**Heap limit of the image:**

```bash
docker image inspect $E --format '{{json .Config.Entrypoint}} {{json .Config.Env}}'
```

`-Xmx` in the entrypoint or `JDK_JAVA_OPTIONS`; `-XX:MaxRAMPercentage=70` means 70% of the container memory.

**Server ready after:** `measure_startup.py`, 3 runs, twice (the first run after a pull is often a cold start):

```bash
python3 measure_startup.py $E <label> 3
python3 measure_startup.py $E <label>-rerun 3
```

Column `health_s` of `startup-<label>.csv` (time until `/actuator/health` is UP); give the range without the
cold start.

**Validation under load and live heap after 8,000 validations:** image defaults, `memory.jmx` (4 threads × 2,000):

```bash
docker run -d --name matchbox-ch-elm-<label> -p 8080:80 $E
until curl -sf http://localhost:8080/matchboxv3/actuator/health | grep -q UP; do sleep 2; done
./jmeter.sh && cp -Rp report <label> && cp -p memory.jtl <label>.jtl
docker exec matchbox-ch-elm-<label> jcmd 1 GC.run; sleep 2
docker exec matchbox-ch-elm-<label> jcmd 1 GC.heap_info | sed -n 2p    # "used ...K" = live heap
docker rm -f matchbox-ch-elm-<label>
```

The validation time is the median of `validationms` (the server's own time) in `<label>.jtl`, with the script of
step 7 of `claude-jmeter-check.md`; check 0 failures and the `$validate` response size (34,594–34,597 bytes for
ch-elm 1.15.3 and 1.15.4).

**Runs the load test in a 3 GB heap:** the same with a smaller heap, `<h>` = `3g` and `1g`:

```bash
docker run -d --name matchbox-ch-elm-<label>-xmx<h> -p 8080:80 \
  -e JDK_JAVA_OPTIONS="-Xmx<h> -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication" $E
# then as above, saving to <label>-xmx<h>
docker inspect -f 'status={{.State.Status}} oom={{.State.OOMKilled}}' matchbox-ch-elm-<label>-xmx<h>
```

"Yes" when all 8,000 validations pass and the container is still running (`ExitOnOutOfMemoryError` would stop it).

### with-preload table

The Matchbox image `M` with the configuration of `matchbox-server/with-preload` on H2, `-m 4g`, one example per IG
(`preload.csv`, 12 IGs and an R4 core validation, 4 threads × 100 loops, 400 validations).

**Installed database:** install the 55 packages once into the Docker volume `mbpreload-h2-template` (also gives a
complete run of the runbook script for the release, `<version>` e.g. `4119`):

```bash
docker volume rm mbpreload-h2-template   # only when the installation changed
DB_TEMPLATE=1 ./preload_engines.sh h2 mb<version>-h2 $M 1
```

`mb<version>-h2-r1/config/application.yaml` is the H2 variant of the with-preload configuration.

**Load and heap dump:** a fresh copy of the database, the load test, a full GC and a heap dump, for each release to
compare (`D` = an absolute folder outside the repository for the dumps, about 2.2 GB each):

```bash
docker volume create mbhd-h2
docker run --rm -v mbpreload-h2-template:/from -v mbhd-h2:/to alpine sh -c 'cp -a /from/. /to/'
docker run -d --name mbhd -m 4g -v $PWD/mb<version>-h2-r1/config:/config -v mbhd-h2:/database -p 8080:8080 $M
until curl -sf http://localhost:8080/matchboxv3/actuator/health | grep -q UP; do sleep 1; done
/Applications/apache-jmeter-5.6.2/bin/jmeter.sh -n -q ./user.properties -t ./multi-ig.jmx -l hd<version>.jtl \
  -Jcsv=preload.csv -Jloops=100
docker exec mbhd jcmd 1 GC.run; sleep 2
docker exec mbhd jcmd 1 GC.heap_info | sed -n 2p                       # live heap with all 12 engines
docker exec mbhd jcmd 1 GC.heap_dump /tmp/h.hprof && docker cp mbhd:/tmp/h.hprof $D/hd<version>.hprof
docker rm -f -v mbhd && docker volume rm mbhd-h2
```

**Creating an IG engine (median):** the median over the first `$validate` per profile (13 values, each includes the
creation of the IG's engine):

```python
import csv, statistics
first = {}
for r in csv.DictReader(open('hd<version>.jtl')):
    if r['label'] == '$validate' and r['URL'] not in first:
        first[r['URL']] = int(r['elapsed'])
print(statistics.median(first.values()) / 1000, 's')
```

**Memory held by the 12 IG engines alone, duplicated between engines:** Eclipse MAT headless
(`/Applications/MemoryAnalyzer.app`). `-limit` is needed, otherwise the CSV stops at 500 rows; MAT hangs after an
error (e.g. a relative dump path), so keep the timeout. The first query parses the dump (a few minutes) and writes
the index files next to it; later queries reuse them. The result is in `$D/hd<version>_Query/pages/*.csv`; remove
`$D/hd<version>_Query*` before the next query.

```bash
mat() {   # mat <absolute hprof> <oql>
  timeout 1200 /Applications/MemoryAnalyzer.app/Contents/MacOS/MemoryAnalyzer -consolelog -nosplash \
    -application org.eclipse.mat.api.parse "$1" -format=csv -unzip -limit=5000000 \
    "-command=oql \"$2\"" org.eclipse.mat.api:query -vmargs -Xmx8g > "$1.mat.log" 2>&1
}
```

Retained heap per worker context:

```bash
mat $D/hd<version>.hprof 'SELECT c.@objectAddress AS addr, c.@retainedHeapSize AS retained, c.packages.size AS pkgs FROM INSTANCEOF org.hl7.fhir.r5.context.BaseWorkerContext c'
```

There are 15 contexts: the main engine (6 packages), two static templates (0 and 1 package) and the 12 IG engines
(11–28 packages). "Memory held by the 12 IG engines alone" is the sum of `retained` of the contexts with more than 6
packages. The engine objects (`ch.ahdis.matchbox.engine.MatchboxEngine`) themselves don't dominate their contexts, so
their retained heap is only a few MB.

Resources loaded in several engines:

```bash
mat $D/hd<version>.hprof 'SELECT toString(c.packageInfo.id) AS pkg, toString(c.packageInfo.version) AS pv, toString(c.proxy.url) AS purl, toString(c.resource.url.myStringValue) AS rurl, c.proxy.@objectAddress AS pa, c.resource.@objectAddress AS ra, c.proxy.@retainedHeapSize AS pr, c.resource.@retainedHeapSize AS rr, c.this$0.@objectAddress AS mgr FROM org.hl7.fhir.r5.context.CanonicalResourceManager$CachedCanonicalResource c'
```

```python
import csv, collections
groups = collections.defaultdict(dict)
for r in csv.DictReader(open('dup.csv')):   # the CSV of the query
    url = r['purl'] if r['purl'] not in ('null', '') else r['rurl']
    obj, size = (r['pa'], r['pr']) if r['pa'] else (r['ra'], r['rr'])
    groups[(r['pkg'], r['pv'], url)][obj] = int(size or 0)
extra = [s for objs in groups.values() if len(objs) > 1 for s in sorted(objs.values())[:-1]]
print(len(extra), 'objects', sum(extra) / 1e6, 'MB')
```

A resource (package, version, URL) whose entries in the engines point to different resource or proxy objects is
loaded more than once; the duplicates are all but one of these objects. Shared resources point to the same object.

Delete the dumps and MAT index files afterwards (`$D/hd<version>*`, about 3.7 GB per release).
