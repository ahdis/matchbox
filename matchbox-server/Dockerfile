FROM bellsoft/liberica-openjdk-debian:26
EXPOSE 8080

# Apply latest Debian security patches to the base image
RUN apt-get update \
 && apt-get upgrade -y \
 && rm -rf /var/lib/apt/lists/*

COPY ./target/matchbox.jar /matchbox.jar

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'
RUN mkdir -p /data/hapi/lucenefiles && chmod 775 /data/hapi/lucenefiles

# Create a new user and switch to it
RUN adduser --disabled-password --gecos '' matchbox
ENV HOME=/home/matchbox
RUN mkdir -p /database && chown matchbox:matchbox /database
RUN mkdir -p /config && chown matchbox:matchbox /config
RUN chown matchbox:matchbox /

USER matchbox

ENV HEALTHCHECK_URL=http://localhost:8080/matchboxv3/actuator/health

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -sf $HEALTHCHECK_URL || exit 1

# The heap is sized relative to the memory limit of the container (70%), see docs/docker.md
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication"

# CVE-2026-114938 (Java4Shell): no hardware-accelerated Base64 decoder until the JDK of the base image is fixed. Set in
# the entrypoint and not in JDK_JAVA_OPTIONS, so it also applies when JDK_JAVA_OPTIONS is replaced
ENTRYPOINT ["java", \
  "-XX:-UseBASE64Intrinsics", \
  "-Dfhir.settings.path=/config/fhir-settings.json", \
  "-Dspring.config.additional-location=optional:file:/config/application.yaml,optional:file:application.yaml", \
  "-jar", "/matchbox.jar"]

# used by .github/workflows/
