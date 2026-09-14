package io.turnbase.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.session.SessionRevokedEvent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Alert routing by user id (`AlertPublisher`'s real dispatch) and closing every stream a revoked
 * login session names (`FR-SESS-010`, `IMP-GATE-28`) — the SSE counterpart of {@code
 * websocket.WebSocketConnectionRegistryTest}.
 */
class NotificationEmitterRegistryTest {

  private final NotificationEmitterRegistry registry = new NotificationEmitterRegistry();

  @Test
  void anAlertIsPushedToEveryEmitterOfItsRecipient() throws Exception {
    AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
    SseEmitter emitterA = mock(SseEmitter.class);
    SseEmitter emitterB = mock(SseEmitter.class);
    registry.register(user, emitterA);
    registry.register(user, emitterB);

    registry.publishAlert(user.userId(), "INVITATION_ACCEPTED", Map.of("user", "bob"));

    verify(emitterA).send(any(SseEmitter.SseEventBuilder.class));
    verify(emitterB).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void anAlertForAUserWithNoOpenStreamIsDroppedSilently() {
    registry.publishAlert(UUID.randomUUID(), "INVITATION_DECLINED", Map.of("user", "bob"));
    // No exception, nothing to verify against — there is no emitter to have received anything.
  }

  @Test
  void anAlertIsNotSentToAnotherUsersStream() throws Exception {
    AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
    AuthenticatedUser other = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
    SseEmitter otherEmitter = mock(SseEmitter.class);
    registry.register(other, otherEmitter);

    registry.publishAlert(user.userId(), "INVITATION_ACCEPTED", Map.of("user", "bob"));

    verify(otherEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void onSessionRevokedClosesOnlyStreamsUnderThatLoginSession() throws Exception {
    UUID revokedSessionId = UUID.randomUUID();
    AuthenticatedUser revokedUser = new AuthenticatedUser(UUID.randomUUID(), revokedSessionId);
    AuthenticatedUser otherUser = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
    SseEmitter revokedEmitter = mock(SseEmitter.class);
    SseEmitter unrelatedEmitter = mock(SseEmitter.class);
    registry.register(revokedUser, revokedEmitter);
    registry.register(otherUser, unrelatedEmitter);

    registry.onSessionRevoked(
        new SessionRevokedEvent(revokedSessionId, revokedUser.userId(), "LOGOUT"));

    verify(revokedEmitter).send(any(SseEmitter.SseEventBuilder.class));
    verify(revokedEmitter).complete();
    verify(unrelatedEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    verify(unrelatedEmitter, never()).complete();
  }

  @Test
  void aSessionRevokedEventWithNoMatchingStreamSendsNothing() throws Exception {
    registry.onSessionRevoked(
        new SessionRevokedEvent(UUID.randomUUID(), UUID.randomUUID(), "LOGOUT"));
    // No emitter registered at all — nothing to verify against, and no exception either.
  }

  @Test
  void unregisteringRemovesTheEmitterFromFutureAlertRouting() throws Exception {
    AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
    SseEmitter emitter = mock(SseEmitter.class);
    String emitterId = registry.register(user, emitter);

    registry.unregister(emitterId);
    registry.publishAlert(user.userId(), "INVITATION_ACCEPTED", Map.of("user", "bob"));

    verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    assertThat(registry.userOf(emitterId)).isNull();
  }

  @Test
  void keepaliveSendsACommentToEveryOpenEmitter() throws Exception {
    AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
    SseEmitter emitter = mock(SseEmitter.class);
    registry.register(user, emitter);

    registry.keepalive();

    verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
  }
}
