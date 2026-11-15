#!/bin/bash
# Engine creation and validation with many IGs: matchbox-server/with-preload (12 IGs, 55 packages) on an empty database.
# usage: [DB_TEMPLATE=1] [LATENCY=2ms] ./preload_engines.sh <h2|postgres> <label> <image> [<runs>]
#   e.g. ./preload_engines.sh postgres mb4119-pg matchbox:local 3
# For each run: drops the database, installs the packages (--hapi.fhir.only_install_packages=true), starts matchbox on
# the initialized database (-m 4g, host port 8080), runs multi-ig.jmx with preload.csv (one example per IG and an R4
# core validation, 4 threads x 100 loops) with an R4 core validation every 200 ms beside it (probe.py), measures the validation of a ch-core profile with and without `ig`, by one
# and by 4 parallel clients (profile_lookup.py), saves one OperationOutcome per profile and the server log, and removes
# the containers. Results in ./<label>-r<n>/ (git-ignored, n continues after the existing runs of the label), compare
# them with ./engine_creation.py.
#   DB_TEMPLATE=1  installs the packages once per database (Docker volume mbpreload-<h2|postgres>-template, remove it
#                  when the installation changes) and starts each run on a copy of it
#   LATENCY=2ms    adds this delay to the packets that PostgreSQL sends (tc netem in its container), after the install
set -u
MODE=$1; LABEL=$2; IMG=$3; RUNS=${4:-1}
DB_TEMPLATE=${DB_TEMPLATE:-}; LATENCY=${LATENCY:-}
J=$(cd "$(dirname "$0")" && pwd)
CFG=$J/../matchbox-server/with-preload/application.yaml
JMETER=${JMETER:-/Applications/apache-jmeter-5.6.2/bin/jmeter.sh}
NET=mbpreload
ts() { python3 -c 'import time;print(time.time())'; }
since() { python3 -c "import time;print(round(time.time()-$1,1))"; }

N=1
for RUN in $(seq 1 "$RUNS"); do
while [ -e "$J/$LABEL-r$N" ]; do N=$((N+1)); done
OUT=$J/$LABEL-r$N; mkdir -p "$OUT/config"
log() { echo "$(date +%T) $*" | tee -a "$OUT/summary.txt"; }
M="docker run --network $NET -m 4g -v $OUT/config:/config"

# the configuration of with-preload, for H2 with the datasource of the default configuration
if [ "$MODE" = h2 ]; then
  sed -e "s#url: 'jdbc:postgresql://matchbox-db:5432/matchbox'#url: 'jdbc:h2:file:./database/h2'#" \
      -e 's#username: matchbox#username: sa#' -e 's#password: matchbox#password: null#' \
      -e 's#org.postgresql.Driver#org.h2.Driver#' -e 's#HapiFhirPostgresDialect#HapiFhirH2Dialect#' "$CFG" \
      > "$OUT/config/application.yaml"
else
  cp "$CFG" "$OUT/config/application.yaml"
fi

# 1. an empty database (or a copy of the template)
docker rm -f -v mbpreload mbpreload-install mbpreload-db >/dev/null 2>&1
docker volume rm -f mbpreload-h2 mbpreload-pgdata >/dev/null 2>&1
docker network create $NET >/dev/null 2>&1
TEMPLATE=mbpreload-$MODE-template
copy_volume() { docker run --rm -v "$1":/from -v "$2":/to alpine sh -c 'cp -a /from/. /to/'; }
if [ -n "$DB_TEMPLATE" ] && docker volume inspect "$TEMPLATE" >/dev/null 2>&1; then INSTALL=""; else INSTALL=1; fi
if [ "$MODE" = postgres ]; then
  docker volume create mbpreload-pgdata >/dev/null
  [ -z "$INSTALL" ] && copy_volume "$TEMPLATE" mbpreload-pgdata
  start_db() {
    docker run -d --name mbpreload-db --network $NET --network-alias matchbox-db -e POSTGRES_DB=matchbox \
      -e POSTGRES_USER=matchbox -e POSTGRES_PASSWORD=matchbox -v mbpreload-pgdata:/var/lib/postgresql postgres:18 >/dev/null
    until docker exec mbpreload-db pg_isready -U matchbox -d matchbox -q 2>/dev/null; do sleep 1; done; sleep 2
  }
  start_db
  DBV=""
else
  docker volume create mbpreload-h2 >/dev/null
  [ -z "$INSTALL" ] && copy_volume "$TEMPLATE" mbpreload-h2
  DBV="-v mbpreload-h2:/database"
fi
log "mode=$MODE label=$LABEL-r$N image=$IMG ($(docker image inspect -f '{{.Id}}' "$IMG" | cut -c8-19))"

# 2. install the packages
if [ -n "$INSTALL" ]; then
  t0=$(ts)
  $M $DBV --name mbpreload-install "$IMG" --hapi.fhir.only_install_packages=true > "$OUT/install.log" 2>&1
  log "install exit=$? seconds=$(since "$t0")"
  docker rm -f mbpreload-install >/dev/null 2>&1
  if [ -n "$DB_TEMPLATE" ]; then
    docker volume create "$TEMPLATE" >/dev/null
    if [ "$MODE" = postgres ]; then
      docker stop mbpreload-db >/dev/null; copy_volume mbpreload-pgdata "$TEMPLATE"; docker rm -f mbpreload-db >/dev/null
      start_db
    else
      copy_volume mbpreload-h2 "$TEMPLATE"
    fi
    log "saved the installed database as $TEMPLATE"
  fi
else
  log "database copied from $TEMPLATE"
fi
if [ -n "$LATENCY" ] && [ "$MODE" = postgres ]; then
  docker run --rm --network container:mbpreload-db --cap-add NET_ADMIN alpine \
    sh -c "apk add -q iproute2-tc >/dev/null && tc qdisc add dev eth0 root netem delay $LATENCY" \
    && log "latency to postgres +$LATENCY"
fi

# 3. start matchbox on the initialized database
t0=$(ts)
$M $DBV -d --name mbpreload -p 8080:8080 "$IMG" >/dev/null
until curl -sf http://localhost:8080/matchboxv3/actuator/health | grep -q UP; do
  sleep 0.5
  docker inspect -f '{{.State.Running}}' mbpreload | grep -q true || { log "matchbox died"; docker logs mbpreload > "$OUT/server.log" 2>&1; exit 1; }
done
log "healthy after seconds=$(since "$t0")"
docker exec mbpreload jcmd 1 GC.run >/dev/null; sleep 2
log "live heap after startup: $(docker exec mbpreload jcmd 1 GC.heap_info | grep -o 'used [0-9]*K' | head -1)"

# 4. load test: the first request per profile creates the engine of its IG; meanwhile, an R4 core validation
#    (main engine) every 200 ms shows whether the requests for an existing engine wait for the creation of others
python3 "$J/probe.py" http://localhost:8080/matchboxv3 "$J/multi-ig-Patient-MaxMuster.json" "$OUT/probe.csv" 200 &
PROBE=$!
(cd "$J" && "$JMETER" -n -q ./user.properties -t ./multi-ig.jmx -l "$OUT/test.jtl" -j "$OUT/jmeter.log" \
  -Jcsv=preload.csv -Jloops=100 > "$OUT/jmeter.out" 2>&1)
log "jmeter exit=$?"
kill $PROBE 2>/dev/null

# 4b. the validation of a profile that isn't in the main engine, with and without `ig`, by 1 and by 4 clients
python3 "$J/profile_lookup.py" http://localhost:8080/matchboxv3 \
  http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-practitioner "$J/preload-Practitioner-SchreibKraft.json" \
  'ch.fhir.ig.ch-core#6.0.0' 200 4 > "$OUT/lookup.txt" 2>&1
log "profile lookup exit=$?"; grep -v '^json: ' "$OUT/lookup.txt" | tee -a "$OUT/summary.txt"

# 5. one OperationOutcome per profile, to compare the issues between runs and images
i=0; tail -n +2 "$J/preload.csv" | while IFS=, read -r prof body; do i=$((i+1))
  curl -s -X POST -H 'Content-Type: application/fhir+json' -H 'Accept: application/fhir+json' --data-binary "@$J/$body" \
    "http://localhost:8080/matchboxv3/fhir/\$validate?profile=$prof" > "$OUT/oo-$i.json"; done

docker exec mbpreload jcmd 1 GC.run >/dev/null; sleep 2
log "live heap after test: $(docker exec mbpreload jcmd 1 GC.heap_info | grep -o 'used [0-9]*K' | head -1)"
log "oom=$(docker inspect -f '{{.State.OOMKilled}}' mbpreload)"
docker logs mbpreload > "$OUT/server.log" 2>&1
docker rm -f -v mbpreload mbpreload-db >/dev/null 2>&1
docker volume rm -f mbpreload-h2 mbpreload-pgdata >/dev/null 2>&1
log done
done
