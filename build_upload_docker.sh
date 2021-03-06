#!/bin/bash
mvn clean compile package
export PROJECT_ID="$(gcloud config get-value project -q)"
export VERSION="v099"
docker build -t eu.gcr.io/${PROJECT_ID}/matchbox-nopreload:${VERSION} -t eu.gcr.io/${PROJECT_ID}/matchbox-nopreload:latest -f Dockerfile_nopreload .
docker push eu.gcr.io/${PROJECT_ID}/matchbox-nopreload:${VERSION}
docker build -t matchbox -t eu.gcr.io/${PROJECT_ID}/matchbox:${VERSION} -t eu.gcr.io/${PROJECT_ID}/matchbox:latest -f Dockerfile_withpreload .
docker push eu.gcr.io/${PROJECT_ID}/matchbox:${VERSION}
