#!/bin/bash
# usage: profile_run.sh LABEL   (fhir-core-7 or pr636)
set -x
LABEL=$1
J=$(cd "$(dirname "$0")" && pwd)
cd $J
docker run -d --name prof-$LABEL -p 8080:80 \
  -e JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseStringDeduplication -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:FlightRecorderOptions:stackdepth=2048" \
  matchbox-ch-elm:1156-$LABEL
until curl -sf http://localhost:8080/matchboxv3/actuator/health | grep -q UP; do sleep 2; done
date; ./jmeter.sh > /dev/null 2>&1; date
cp -p memory.jtl profile-$LABEL-warmup.jtl
docker exec prof-$LABEL jcmd 1 JFR.start name=prof settings=profile filename=/tmp/$LABEL.jfr
date; ./jmeter.sh > /dev/null 2>&1; date
docker exec prof-$LABEL jcmd 1 JFR.stop name=prof
docker cp prof-$LABEL:/tmp/$LABEL.jfr $J/profile-$LABEL.jfr
cp -Rp report profile-$LABEL && cp -p memory.jtl profile-$LABEL.jtl
docker logs prof-$LABEL > profile-$LABEL.server.log 2>&1
docker rm -f prof-$LABEL
