package io.turnbase.platform.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.session.SessionRevokedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageChannel;
import tools.jackson.databind.json.JsonMapper;

/**
 * The connection cap ({@code rate-limiting.md} § Tier 6), the active-connections gauge, and closing
 * every socket a revoked session names (`FR-SESS-010`).
 */
class WebSocketConnectionRegistryTest {

  private final MessageChannel clientOutboundChannel = mock(MessageChannel.class);
  private final WebSocketConnectionRegistry registry =
      new WebSocketConnectionRegistry(
          clientOutboundChannel, JsonMapper.builder().build(), new SimpleMeterRegistry());

  {
    when(clientOutboundChannel.send(any())).thenReturn(true);
  }

  @Test
  void registrationsUnderTheCapSucceedAndCountTowardActiveConnections() {
    AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());

    assertThat(registry.tryRegister("s1", user, 2)).isTrue();
    assertThat(registry.tryRegister("s2", user, 2)).isTrue();
    assertThat(registry.activeConnectionCount()).isEqualTo(2);
  }

  @Test
  void aConnectionBeyondTheCapIsRefused() {
    AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
    registry.tryRegister("s1", user, 1);

    assertThat(registry.tryRegister("s2", user, 1)).isFalse();
    assertThat(registry.activeConnectionCount()).isEqualTo(1);
  }

  @Test
  void unregisteringFreesTheCapForThatUser() {
    AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
    registry.tryRegister("s1", user, 1);

    registry.unregister("s1");

    assertThat(registry.tryRegister("s2", user, 1)).isTrue();
  }

  @Test
  void onSessionRevokedClosesOnlyConnectionsUnderThatLoginSession() {
    UUID revokedSessionId = UUID.randomUUID();
    AuthenticatedUser revokedUser = new AuthenticatedUser(UUID.randomUUID(), revokedSessionId);
    AuthenticatedUser otherUser = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
    registry.tryRegister("revoked-tab-1", revokedUser, 4);
    registry.tryRegister("revoked-tab-2", revokedUser, 4);
    registry.tryRegister("unrelated", otherUser, 4);

    registry.onSessionRevoked(
        new SessionRevokedEvent(revokedSessionId, revokedUser.userId(), "LOGOUT"));

    verify(clientOutboundChannel, times(2)).send(any());
    assertThat(registry.userOf("unrelated")).isEqualTo(otherUser);
    assertThat(registry.userOf("revoked-tab-1")).isNull();
    assertThat(registry.userOf("revoked-tab-2")).isNull();
  }

  @Test
  void closeSessionUnregistersAndSendsAnErrorFrame() {
    AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
    registry.tryRegister("s1", user, 4);

    registry.closeSession("s1", "SOME_CODE", "closed for testing");

    assertThat(registry.userOf("s1")).isNull();
    verify(clientOutboundChannel).send(any());
  }

  @Test
  void aSessionRevokedEventWithNoMatchingConnectionSendsNothing() {
    registry.onSessionRevoked(
        new SessionRevokedEvent(UUID.randomUUID(), UUID.randomUUID(), "LOGOUT"));

    verify(clientOutboundChannel, never()).send(any());
  }
}
