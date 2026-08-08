# payments-api runtime image.
#
# Three stages, for three different reasons:
#
#   deps    resolves the dependency graph on its own layer, so a source-only change does not
#           re-download the world. It is a separate stage rather than a separate RUN because
#           the pom is the only input that should invalidate it.
#   build   compiles and packages. Tests do not run here: the suite needs PostgreSQL, Redis and
#           Kafka via Testcontainers, and a build that quietly skipped them would look identical
#           to one that passed. CI runs the real suite against real dependencies; the image build
#           is not the place to pretend otherwise.
#   runtime carries a JRE and the application. No JDK, no Maven, no source, no dependency cache.
#
# Base images are pinned by digest, not by tag. `25-jre` is a moving target, and an image that
# cannot say what it was built from cannot be reasoned about after an incident.

# ---------------------------------------------------------------------------------------------
# Stage 1 — dependency resolution
# ---------------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25@sha256:1b1fc6d0168ea616afd1c861d6f32ec37c9ec2ffe88a0351b3771dd4ad86b0d8 AS deps

WORKDIR /build
COPY pom.xml ./
RUN mvn -B -Dmaven.repo.local=/build/.m2 dependency:go-offline

# ---------------------------------------------------------------------------------------------
# Stage 2 — compile, package, and split the jar into layers
# ---------------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25@sha256:1b1fc6d0168ea616afd1c861d6f32ec37c9ec2ffe88a0351b3771dd4ad86b0d8 AS build

WORKDIR /build
COPY --from=deps /build/.m2 /build/.m2
COPY pom.xml ./
COPY src ./src
RUN mvn -B -Dmaven.repo.local=/build/.m2 -DskipTests package

# Spring Boot's tools jarmode splits the archive so that dependencies, which change rarely, land
# in an earlier layer than application classes, which change every commit. Without this the whole
# fat jar is one layer and every deploy ships ~60 MB of unchanged libraries.
# The jar is staged outside the destination: `extract` refuses to write into a directory that is
# not empty, and the archive being extracted counts.
RUN cp target/*.jar /build/application.jar \
    && java -Djarmode=tools -jar /build/application.jar extract --layers --launcher --destination /build/extracted

# ---------------------------------------------------------------------------------------------
# Stage 3 — runtime
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre@sha256:f19dbf0a22d0b3658fda48ce7d7181df05ad14bda151dd5ad12cc09d1451c70e AS runtime

# curl is required by the container healthcheck below. It is the only addition to the base image.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# A dedicated non-root account that does not own the application directory. A process that is
# compromised at runtime should not be able to rewrite the code it is running.
RUN groupadd --system --gid 10001 thaap \
    && useradd --system --uid 10001 --gid thaap --no-create-home --shell /usr/sbin/nologin thaap

WORKDIR /app

# Copied in layer order, least-changing first.
#
# Owned by root while the process runs as thaap, so the application cannot rewrite its own code.
# Ownership is the control here, not permission bits: a blanket --chmod=444 also strips the
# execute bit from directories, and an unenterable directory is not a hardened one — the JVM
# simply cannot read its classes.
COPY --from=build --chown=root:root /build/extracted/dependencies/ ./
COPY --from=build --chown=root:root /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=root:root /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=root:root /build/extracted/application/ ./

USER thaap:thaap

# Timestamps are minted at database precision so Linux and PostgreSQL agree (see the Phase 5 fix
# in commit fe93eb1). Pinning UTC keeps the container from introducing a third opinion.
ENV TZ=UTC \
    LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED"

EXPOSE 8080

# Bound to readiness, not liveness. Readiness aggregates sixteen indicators; liveness only says
# the process exists. A task that is up but cannot reach PostgreSQL should be replaced, not left
# in the load balancer answering requests it cannot serve.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=4 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1

# Layer-extracted applications are started through the loader rather than with `-jar`: there is
# no fat jar left to point at.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
