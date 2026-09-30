package io.turnbase.platform.game;

import java.util.Optional;
import java.util.UUID;

/**
 * The room's answer to "who is in this session right now" — implemented by the {@code room}
 * feature, which owns the participant tables, so fan-out reads durable membership instead of
 * trusting anything the game produced.
 */
public interface SessionRosterSource {

  /** Empty when no room claims {@code sessionId} as its active session. */
  Optional<SessionRoster> rosterOf(UUID sessionId);
}
