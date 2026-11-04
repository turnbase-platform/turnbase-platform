# turnbase-platform — code map

The Platform Monolith, packaged by feature under `io.turnbase.platform`. Spring Modulith fails the
build when one package reaches into another's internals (ADR-0014). This file names code; the
behaviour is specified in `docs/specs/` and traced end to end in `docs/specs/flows/`. It is checked
by `python3 scripts/check-corpus.py code-maps` (run from the umbrella repository): a renamed class
fails that check, so fix the map in the same change.

## Packages

| Package | Responsibility |
|---|---|
| `io.turnbase.platform.account` | Registration, login, guests, OAuth, profile, auth methods, privacy, account erasure |
| `io.turnbase.platform.session` | Server-side sessions, JWT issue/verify and key rotation, session-revocation fan-out |
| `io.turnbase.platform.room` | Room lifecycle, participants, invitations, ownership, STOMP room/game commands, outbox, reconciliation |
| `io.turnbase.platform.game` | Game execution orchestration: Game Host registry, gRPC clients, session create, input processing, effect fan-out |
| `io.turnbase.platform.chat` | In-room chat over STOMP |
| `io.turnbase.platform.websocket` | STOMP/WebSocket config, handshake and frame authentication, subscription authorisation, rate limits |
| `io.turnbase.platform.catalog` | Public game catalogue read model |
| `io.turnbase.platform.upload` | Game bundle upload slots, validation, publication, artifact store |
| `io.turnbase.platform.admin` | Admin review of uploaded games (basic-auth management context) |
| `io.turnbase.platform.history` | Game history and per-player statistics |
| `io.turnbase.platform.social` | Friends, friend requests, user search |
| `io.turnbase.platform.notification` | Persisted notifications, SSE stream, purge jobs |
| `io.turnbase.platform.seed` | Local/dev seed data and bundle seeding |
| `io.turnbase.platform.common` | Cross-cutting: error model, rate limiting, origin checks, correlation IDs, cookies, config properties |

## Flows

### Sign-in (guest or password)

1. `AuthController.guest` / `AuthController.login` receive the request (`/api/auth/...`).
2. `GuestService.mint` or `LoginService.login` create or verify the account.
3. `SessionService.issueSession` persists a session row (`UserSessionRepository`) and mints the token with `JwtService`.
4. `AuthController` sets the cookie (`SessionCookie`).
5. Later HTTP requests: `SessionAuthenticationFilter` → `SessionService.resolve`. WebSocket: `SessionCookieHandshakeInterceptor`, then `WebSocketChannelInterceptor` on CONNECT.

### Room create and join

1. `RoomController.create` → `RoomService.create` → `RoomRepository` (room, participant and outbox rows).
2. `RoomController.join` → `RoomService.join`; invite codes via `RoomService.resolveInviteCode` and `InviteCodeGenerator`.
3. The client subscribes; `RoomSubscriptionAuthorizer` (via `SubscriptionAuthorizer`) admits it and `RoomStateSnapshot` carries the state.
4. Member and host commands arrive on `RoomMemberCommandController` / `RoomHostCommandController`; `RoomService.toggleReady`, `RoomService.startGame` act on them; failures go out through `RoomCommandErrorSender`.
5. `RoomService.startGame` → `StartPhase` / `GameSessionService.create` (see the move flow); progress goes out via `RoomStartStatusPublisher`.

### A move

1. `GameActionController` receives the action on `game.{sessionId}.action`; `GameSubscriptionAuthorizer` guards the stream.
2. `GameInputOutbox` writes the input durably; `GameInputDispatcherImpl.allocateSequence` and `GameInputDispatcherImpl.dispatch` order and send it.
3. `GameInputService.process` calls the Game Host (`GameHostChannels`, `rpc ProcessInput`).
4. `MoveCommit` records the result; `EffectFanOut.deliver` sends effects to players and spectators; `GameLogStore` keeps the log.
5. On `game_over`, `GameInputService.process` calls `GameEndListener`; `RoomGameEndListener` returns the room to the Lobby and `GameOutcomeRecorder` / `HistoryRecordingService` write history.

### Game upload

1. `VersionUploadController` → `VersionUploadService.createSlot` stores a pending `GameVersionRow` and presigns the upload (`ArtifactStore`, `S3ArtifactStore.presignPut`).
2. The client PUTs the bundle to object storage, then calls confirm → `VersionUploadService.confirm`.
3. `BundleValidationService.validateAsync` runs the shared bundle validator (turnbase-sdk, its bundle-validator module).
4. `PublicationService` publishes the passing bundle (assets via `GameAssetController`); the catalogue reads it through `CatalogRepository`. `AdminReviewService` is the manual-review path.

### Game Host failure

1. Hosts register and heartbeat on `GameHostRegistryGrpcService` (`rpc Register`, `rpc Heartbeat`); `HostRegistry` keeps leases in Redis.
2. A dead or quarantined host drops out of `HostRegistry`; `GameSessionService.create` retries on another.
3. `GameInputService.process` reports exhausted retries to `SessionFailureListener`; `RoomSessionFailureListener` terminates the session (`SessionTerminator`, `SessionTerminationClient`).
4. A lost owner pod is adopted by `RoomOwnershipService`; `ReconciliationJob` repairs drift.
