#!/bin/bash
# usage: cpu_run.sh IMAGE_LABEL RUN_LABEL
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
docker rm -f cpu-$RUN
