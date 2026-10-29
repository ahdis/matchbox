#!/bin/bash
# usage: cpu_heap_run.sh IMAGE_LABEL RUN_LABEL
# As cpu_run.sh (CPU per thread group, see thread_cpu.py), and after the measured run the live heap after a full GC
# (cpu-RUN.heap.txt) and the class histogram of the element model and the typed model (cpu-RUN.histo.txt), plus the
# server log (cpu-RUN.server.log).
set -x
LABEL=$1; RUN=$2
J=$(cd "$(dirname "$0")" && pwd)
cd $J
docker run -d --name cpu-$RUN -p 8080:80 matchbox-ch-elm:1156-$LABEL
until curl -sf http://localhost:8080/matchboxv3/actuator/health | grep -q UP; do sleep 2; done
./jmeter.sh > /dev/null 2>&1
docker exec cpu-$RUN jcmd 1 Thread.print > cpu-$RUN.before.txt
date +%s > cpu-$RUN.time
./jmeter.sh > /dev/null 2>&1
date +%s >> cpu-$RUN.time
docker exec cpu-$RUN jcmd 1 Thread.print > cpu-$RUN.after.txt
cp -p memory.jtl cpu-$RUN.jtl
docker exec cpu-$RUN jcmd 1 GC.run > /dev/null
docker exec cpu-$RUN jcmd 1 GC.heap_info > cpu-$RUN.heap.txt
docker exec cpu-$RUN jcmd 1 GC.class_histogram \
  | grep -E 'Total|org\.hl7\.fhir\.services\.elementmodel\.(Element|NamedItemList)$|org\.hl7\.fhir\.model\.(Property|core\.ConceptMap)$' \
  > cpu-$RUN.histo.txt
docker logs cpu-$RUN > cpu-$RUN.server.log 2>&1
docker rm -f cpu-$RUN
