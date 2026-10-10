# turnbase-platform — CLAUDE.md

The Platform Monolith. Spring Boot 4.1+ on Java 21, Docker image `turnbase/platform`.

**This file is additive.** The specification corpus, the work-item registry, the code conventions and
the session workflow live in the parent repository and apply here unchanged:

| | |
|---|---|
| Registry | `../docs/implementation/registry.md` — find your `IMP-*` item, read exactly its read-list |
| Conventions | `../docs/implementation/conventions.md` — **normative**: layout, Spotless, tests, coverage, git |
| Architecture | `../docs/decisions/0014-modular-monolith-spring-modulith.md` — read before adding a package |

## What lands here

**Roughly forty of the 59 work items** — every `IMP-M1-*`, `IMP-M2-*`, `IMP-M4-*`, `IMP-M5-*`, most
of `IMP-M3-*`'s platform half, and `IMP-M0-03/06/09/10/12`. This is the largest repository in the
project by a wide margin, which is why its internal boundaries are enforced rather than described.

## Package layout — the rule that is a build error

Root package **`io.turnbase.platform`**, packaged **by feature**:

```
common/  account/  session/  social/  room/  chat/
game/    catalog/  upload/   history/ notification/  admin/
```

- Layer *inside* a feature (controller, service, repository). Never a top-level `controller` package.
- Cross-feature access goes through the target feature's **top-level API**. Anything under
  `<feature>/internal/` is private to it.
- `ApplicationModules.of(TurnBaseApplication.class).verify()` fails the build when that is broken, or
  when two features form a cycle.

**When Modulith fails**, the fix is almost never to relax the boundary. Either expose what the caller
actually needs on the target's public API, or — if that creates a cycle — invert it with a Spring
application event. Widening the module definition to make the error disappear removes the only thing
that was going to catch the next one.

## Before you commit

```bash
./mvnw spotless:apply     # NOT optional — CI fails on format, and a bounced pipeline costs a turn
./mvnw verify             # unit + Modulith verification + coverage
./mvnw verify -Pintegration   # Testcontainers: Postgres, Redis, MinIO
```

**Error Prone and NullAway fail the compile, not a later phase** ([ADR-0015](../docs/decisions/0015-static-analysis-error-prone-nullaway.md)).
Every package is `@NullMarked`, so a reference is non-null unless it says otherwise. When NullAway
objects, it has usually found a real ambiguity the code was leaving implicit — annotate the nullable
thing rather than reaching for a cast or an `assert`. Suppress at the site with a reason if you must;
never lower a check's severity build-wide.

`git config core.hooksPath .githooks` once, to get the `pre-push` fast-fail.

Integration tests are **Testcontainers, never `docker compose`**. A test that needs a stack someone
started by hand passes locally and fails on every clean machine. Compose belongs to `make e2e` alone.

## Traps specific to this repo

- **The management port differs between profiles.** `8081` is loopback-bound under compose and
  unbound under Kubernetes. Getting it wrong breaks the readiness probes, and it breaks them in the
  environment you are not currently looking at.
- **Readiness must not depend on the Game Host.** Coupling them lets a Game Host restart drop every
  WebSocket on the platform, including rooms running no game at all.
- **`turnbase-common` is pinned by exact version**, never `-SNAPSHOT` in a committed pom. The local
  loop is `-Dturnbase-common.version=…` on the command line (`conventions.md` §5).
- **The epoch is the fence token, not the room version** — all of M2 turns on this.
- **Commit before publish; lock outer, transaction inner, publish after commit.** In that nesting,
  never another.

## If a specification is wrong

**Stop. Do not reconcile it in the code.** A contradiction between two documents, or a decision the
documents leave to you, is a specification defect — fix the document in the parent repository first,
then write the code that follows from it. An unrecorded choice made here surfaces three milestones
later as a bug nobody can trace to a decision.
