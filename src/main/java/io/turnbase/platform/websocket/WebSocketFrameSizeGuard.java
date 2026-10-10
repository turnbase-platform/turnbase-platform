package io.turnbase.platform.websocket;

import org.jspecify.annotations.Nullable;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.stereotype.Component;

/**
 * {@code input-validation.md} § Size limits — the per-destination STOMP frame byte ceiling, checked
 * from the raw frame payload's length before any {@code @Payload} conversion. Classifies by
 * destination exactly like {@link WebSocketRateLimiter#classify}, and deliberately the earlier
 * check: the doc's own pipeline puts Size ahead of Authorize (which Tier 6 rate limiting is part
 * of). {@code IMP-GATE-47}: no item built this until {@code IMP-M2-11} (in-room chat) needed its
 * own 4 KB row and found none of the table's five rows had ever been wired — this guard covers all
 * five, including {@code IMP-M2-05}/{@code M2-06}'s member/host destinations retroactively and the
 * two game rows {@code IMP-M3-01}/{@code M3-06} inherit rather than re-decide.
 */
@Component
class WebSocketFrameSizeGuard {

  private static final int MEMBER_HOST_LIMIT_BYTES = 8 * 1024;
  private static final int CHAT_LIMIT_BYTES = 4 * 1024;
  private static final int ACTION_LIMIT_BYTES = 256 * 1024;
  private static final int SYNC_LIMIT_BYTES = 1024;

  @Nullable Violation check(
      @Nullable StompCommand command, @Nullable String destination, int payloadLengthBytes) {
    if (command != StompCommand.SEND || destination == null) {
      return null;
    }
    if (destination.startsWith("/app/room.") && destination.endsWith(".chat")) {
      return violation(payloadLengthBytes, CHAT_LIMIT_BYTES, "MESSAGE_TOO_LARGE");
    }
    if (destination.startsWith("/app/room.")
        && (destination.endsWith(".member") || destination.endsWith(".host"))) {
      return violation(payloadLengthBytes, MEMBER_HOST_LIMIT_BYTES, "MESSAGE_TOO_LARGE");
    }
    if (destination.startsWith("/app/game.") && destination.endsWith(".action")) {
      return violation(payloadLengthBytes, ACTION_LIMIT_BYTES, "ACTION_TOO_LARGE");
    }
    if (destination.startsWith("/app/game.") && destination.endsWith(".sync")) {
      return violation(payloadLengthBytes, SYNC_LIMIT_BYTES, "MESSAGE_TOO_LARGE");
    }
    return null;
  }

  private @Nullable Violation violation(int payloadLengthBytes, int limitBytes, String code) {
    return payloadLengthBytes > limitBytes ? new Violation(code, limitBytes) : null;
  }

  record Violation(String code, int limitBytes) {}
}
