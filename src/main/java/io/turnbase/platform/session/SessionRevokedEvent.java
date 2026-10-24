package io.turnbase.platform.session;

import java.util.UUID;

/**
 * Published after the transaction that revoked {@code sessionId} has committed (never before — this
 * repo's commit-before-publish rule). {@code data-model-accounts.md} §3 / {@code user-accounts.md}
 * FR-SESS-010: a revoked session row does nothing to a WebSocket or SSE connection already open, so
 * the connection registries that hold those (built by {@code IMP-M2-02} and {@code IMP-M5-03},
 * `IMP-GATE-28`) subscribe to this event to close them, after emitting a frame naming {@code
 * reason}.
 *
 * <p>This item only ever publishes {@code reason} of {@code DISPLACED} (login displacement,
 * FR-SESS-003) or {@code LOGOUT} (FR-SESS-011) — the other {@code user_session.revoked_reason}
 * values belong to the items that own those paths.
 *
 * <p>{@code remote} is true for a revocation another Platform instance raised and {@link
 * SessionRevocationRelay} re-published here (ADR-0020 §4, {@code IMP-M18-08}): the connection
 * registries close their sockets for it exactly as for a local one, and the relay does not send it
 * on again.
 */
public record SessionRevokedEvent(UUID sessionId, UUID userId, String reason, boolean remote) {

  public SessionRevokedEvent(UUID sessionId, UUID userId, String reason) {
    this(sessionId, userId, reason, false);
  }
}
