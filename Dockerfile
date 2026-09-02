# Platform Monolith — plain Java 21, no Truffle/Espresso (that's turnbase-game-host only).
#
# Single-stage: the jar is built by `./mvnw verify`/`package` before `docker build` runs (CI
# runs both in the same job), not inside the image. Building here too would mean resolving
# io.turnbase:turnbase-grpc a second time in a network-isolated container, which during the
# conventions.md §5 bootstrap window (IMP-GATE-07) has no registry to resolve it from at all.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin turnbase
USER turnbase

COPY target/turnbase-platform-*.jar app.jar

# local-environment.md §1: 8080 serves HTTP, WS and game assets; 8081 is management, whose
# bind address (loopback under compose, unset under Kubernetes) is set by
# MANAGEMENT_SERVER_ADDRESS, not baked into the image.
EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
