# Platform Monolith — plain Java 21, no Truffle/Espresso (that's turnbase-game-host only).
#
# Single-stage: the jar is built by `./mvnw verify`/`package` before `docker build` runs (CI
# runs both in the same job), not inside the image. Building here too would mean resolving
# io.github.turnbase-platform:turnbase-grpc a second time in a network-isolated container, which during the
# conventions.md §5 bootstrap window (IMP-GATE-07) has no registry to resolve it from at all.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# observability.md §4: the OpenTelemetry Java agent, attached below via JAVA_TOOL_OPTIONS. A
# runtime artifact fetched at build time, not a Maven dependency — pinned to an exact release
# per conventions.md. ADD (not a manual curl) needs no HTTP client baked into this minimal
# JRE image.
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar

RUN useradd --system --create-home --shell /usr/sbin/nologin turnbase && chown turnbase:turnbase /app
USER turnbase

COPY target/turnbase-platform-*.jar app.jar

# local-environment.md §1: 8080 serves HTTP, WS and game assets; 8081 is management, whose
# bind address (loopback under compose, unset under Kubernetes) is set by
# MANAGEMENT_SERVER_ADDRESS, not baked into the image.
EXPOSE 8080 8081

# observability.md §4. Metrics and logs are deliberately not OTLP here — FR-MET-007's
# Prometheus scrape and §3's Promtail pipeline own those two signals; only traces go through
# this agent. management.otlp.tracing.endpoint/management.tracing.sampling.probability
# (application.yml) carry the same two values for Micrometer's own tracer — the agent's
# variables are the ones that decide where spans from its own auto-instrumentation land.
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/opentelemetry-javaagent.jar" \
    OTEL_SERVICE_NAME=platform \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo:4317 \
    OTEL_EXPORTER_OTLP_PROTOCOL=grpc \
    OTEL_TRACES_SAMPLER=parentbased_traceidratio \
    OTEL_TRACES_SAMPLER_ARG=0.1 \
    OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none

ENTRYPOINT ["java", "-jar", "app.jar"]
