# turnbase-platform

TurnBase Platform Monolith — Spring Boot 4.1+ on Java 21, packaged by feature
([ADR-0014](../docs/decisions/0014-modular-monolith-spring-modulith.md)). Ships as the Docker image
`turnbase/platform`.

## Build

```bash
./mvnw verify                      # Spotless, Error Prone + NullAway, Modulith verification, tests, coverage
./mvnw verify -Pintegration         # + Testcontainers-backed integration tests
./mvnw spotless:apply              # before opening a PR, not after CI rejects it
```

Toolchain is pinned in `.sdkmanrc` (`21.0.2-graalce`). Enable the local fast-fail once per clone:

```bash
git config core.hooksPath .githooks
```

**Bootstrap only:** `turnbase-common` has no tagged release yet, so `<turnbase-common.version>` pins
its current development version. Install it locally before building here:

```bash
cd ../turnbase-common && ./mvnw install -DskipTests
```

This step (and the equivalent one in CI) goes away the day `turnbase-common` is first tagged and the
version property here is bumped to the real release (`conventions.md` §5, IMP-GATE-07).

## Package layout

Root package `io.turnbase.platform`, packaged by feature:

```
common/  account/  session/  social/  room/  chat/
game/    catalog/  upload/   history/ notification/  admin/
```

`ApplicationModules.of(TurnBaseApplication.class).verify()` fails the build when one feature reaches
into another's internals, or when two features form a cycle. See `CLAUDE.md` for how to read a
Modulith failure.

## Run it

```bash
./mvnw spring-boot:run
```

`8080` serves HTTP/WebSocket/game assets; `8081` is the management port (health, readiness,
liveness). Readiness does not depend on the Game Host — see `local-environment.md` §3.
