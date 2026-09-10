package io.turnbase.platform.room;

import java.util.UUID;

/**
 * {@code room-websocket.md} `START_GAME` — the seam between the STOMP command handler this item
 * builds and the real session-creation machinery {@code IMP-M3-05} (Orchestration client and
 * session registry) builds. Called once {@code START_GAME}'s FR-TEAM-009 preconditions pass, under
 * the room lock.
 *
 * <p>{@code IMP-M3-05} replaces the no-op implementation with one that creates the session via the
 * orchestration client, persists {@code room.active_session_id}, and broadcasts {@code
 * GAME_STARTED} with the live {@code sessionId}/{@code gameFrontendUrl} ({@code IMP-GATE-46}) —
 * none of which exists yet, so this item proves only that the call happens, not what it produces.
 */
interface GameSessionStarter {

  void start(UUID roomId, UUID hostUserId);
}
