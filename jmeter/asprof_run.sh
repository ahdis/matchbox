#!/bin/bash
# usage: asprof_run.sh LABEL
set -x
LABEL=$1
J=$(cd "$(dirname "$0")" && pwd)
AP=${ASYNC_PROFILER:?set ASYNC_PROFILER to the extracted async-profiler-<ver>-linux-arm64 folder}
cd $J
docker run -d --name ap-$LABEL -p 8080:80 matchbox-ch-elm:1156-$LABEL
until curl -sf http://localhost:8080/matchboxv3/actuator/health | grep -q UP; do sleep 2; done
docker cp $AP ap-$LABEL:/tmp/ap
./jmeter.sh > /dev/null 2>&1
docker exec ap-$LABEL /tmp/ap/bin/asprof start -e itimer -i 5ms --threads 1
./jmeter.sh > /dev/null 2>&1
docker exec ap-$LABEL /tmp/ap/bin/asprof stop -o collapsed -f /tmp/$LABEL.collapsed 1
docker cp ap-$LABEL:/tmp/$LABEL.collapsed $J/profile-$LABEL.collapsed
cp -p memory.jtl profile-ap-$LABEL.jtl
docker rm -f ap-$LABEL
