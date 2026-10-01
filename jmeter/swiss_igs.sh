#!/bin/bash
# How many of the latest Swiss IGs (fhir.ch) fit into a container: one engine per IG, live heap after each.
# usage: ./swiss_igs.sh <label> <image> [<container memory, default 4g>] [<rounds, default 2>]
#   e.g. ./swiss_igs.sh mb4119-4g europe-west6-docker.pkg.dev/ahdis-ch/ahdis/matchbox:v4.1.19 4g
# Prepare the IGs first with `python3 swiss_igs.py` (swiss-igs.yaml, swiss-igs.csv, swiss-*.json).
# Installs the IGs of swiss-igs.yaml once into the Docker volume mbswiss-h2-template (again when swiss-igs.yaml
# changes), starts matchbox on a copy of it with `-m <memory>` (the image's default JVM options: heap 70% of it,
# ExitOnOutOfMemoryError) on host port 8080, and runs swiss-igs.jmx: one thread validates the example of each IG in
# turn (rounds x all IGs; the first round creates one engine per IG, the next ones use the cached engines), after
# each validation a full GC and the live heap and container memory. Results in ./swiss-<label>/ (git-ignored):
# test.jtl, server.log, summary.txt (also printed, `python3 swiss_igs.py report swiss-<label>`).
set -u
LABEL=$1; IMG=$2; MEM=${3:-4g}; ROUNDS=${4:-2}
J=$(cd "$(dirname "$0")" && pwd)
JMETER=${JMETER:-/Applications/apache-jmeter-5.6.2/bin/jmeter.sh}
OUT=$J/swiss-$LABEL; C=mbswiss; TEMPLATE=mbswiss-h2-template
[ -e "$OUT" ] && { echo "$OUT exists"; exit 1; }
mkdir -p "$OUT/config" && cp "$J/swiss-igs.yaml" "$OUT/config/application.yaml"
log() { echo "$(date +%T) $*" | tee -a "$OUT/summary.txt"; }
log "label=$LABEL image=$IMG ($(docker image inspect -f '{{.Id}}' "$IMG" | cut -c8-19)) memory=$MEM rounds=$ROUNDS"

# 1. the installed IGs: a template volume per content of swiss-igs.yaml
SHA=$(shasum "$J/swiss-igs.yaml" | cut -c1-12)
docker rm -f -v $C $C-install >/dev/null 2>&1; docker volume rm -f mbswiss-h2 >/dev/null 2>&1
if [ "$(docker run --rm -v $TEMPLATE:/db alpine cat /db/swiss-igs.sha 2>/dev/null)" != "$SHA" ]; then
  docker volume rm -f $TEMPLATE >/dev/null 2>&1; docker volume create $TEMPLATE >/dev/null
  t0=$(date +%s)
  docker run --name $C-install -m 8g -v "$OUT/config:/config" -v $TEMPLATE:/database "$IMG" \
    --hapi.fhir.only_install_packages=true > "$OUT/install.log" 2>&1
  log "install exit=$? seconds=$(( $(date +%s) - t0 ))"
  docker rm -f $C-install >/dev/null
  docker run --rm -v $TEMPLATE:/db alpine sh -c "echo $SHA > /db/swiss-igs.sha"
fi
docker volume create mbswiss-h2 >/dev/null
docker run --rm -v $TEMPLATE:/from -v mbswiss-h2:/to alpine sh -c 'cp -a /from/. /to/'

# 2. start matchbox with the memory limit, the image's JVM options plus native memory tracking (NMT=0: without)
OPTS=$(docker image inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$IMG" | sed -n 's/^JDK_JAVA_OPTIONS=//p')
[ "${NMT:-1}" = 1 ] && OPTS="$OPTS -XX:NativeMemoryTracking=summary"
log "JDK_JAVA_OPTIONS=$OPTS"
t0=$(date +%s)
docker run -d --name $C -m "$MEM" -e JDK_JAVA_OPTIONS="$OPTS" -v "$OUT/config:/config" -v mbswiss-h2:/database \
  -p 8080:8080 "$IMG" >/dev/null
until curl -sf http://localhost:8080/matchboxv3/actuator/health | grep -q UP; do
  sleep 1
  docker inspect -f '{{.State.Running}}' $C | grep -q true || { log "matchbox died during startup"; break; }
done
log "healthy after seconds=$(( $(date +%s) - t0 ))"
docker exec $C jcmd 1 GC.run >/dev/null 2>&1; sleep 1
log "live heap after startup: $(docker exec $C jcmd 1 GC.heap_info 2>/dev/null | grep -o 'used [0-9]*K' | head -1)" \
  "$(docker exec $C jcmd 1 GC.heap_info 2>/dev/null | grep -o 'total reserved [0-9]*K' | head -1)"

# 3. one validation per IG, live heap after each
ROWS=$(( $(wc -l < "$J/swiss-igs.csv") - 1 ))
(cd "$J" && "$JMETER" -n -t ./swiss-igs.jmx -l "$OUT/test.jtl" -j "$OUT/jmeter.log" \
  -Jloops=$(( ROWS * ROUNDS )) -Jcontainer=$C \
  -Jsample_variables=name,liveheapkb,heapcommittedkb,containermem,validationms,errors_matchNr,sessionid,matchbox \
  > "$OUT/jmeter.out" 2>&1)
log "jmeter exit=$?"
docker exec $C jcmd 1 VM.native_memory summary > "$OUT/nmt.txt" 2>&1 \
  && log "native memory (NMT), committed: $(grep -E '^Total' "$OUT/nmt.txt")" \
  && grep -E '^-' "$OUT/nmt.txt" | sed -E 's/^- +(.*[a-z)]) +\(reserved=[0-9]+KB, committed=([0-9]+)KB.*/\1:\2/' \
  | awk -F: '$2 > 20480 {printf "  %s %.0f MB\n", $1, $2 / 1024}' | tee -a "$OUT/summary.txt"

log "container: $(docker inspect -f 'running={{.State.Running}} exit={{.State.ExitCode}} oomkilled={{.State.OOMKilled}}' $C)"
docker logs $C > "$OUT/server.log" 2>&1
log "OutOfMemoryError in the server log: $(grep -c 'java.lang.OutOfMemoryError' "$OUT/server.log")"
docker rm -f -v $C >/dev/null 2>&1; docker volume rm -f mbswiss-h2 >/dev/null 2>&1
python3 "$J/swiss_igs.py" report "$OUT" | tee -a "$OUT/summary.txt"
