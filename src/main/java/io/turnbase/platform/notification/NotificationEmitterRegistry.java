package io.turnbase.platform.notification;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.session.SessionRevokedEvent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Every live {@code GET /api/notifications/stream} connection, keyed by a generated emitter id —
 * the SSE counterpart to {@code websocket.WebSocketConnectionRegistry}, whose structure this
 * mirrors: a primary map for the login-session-scoped close on {@link SessionRevokedEvent}
 * (`FR-SESS-010`, `IMP-GATE-28`), a secondary index by user id for routing an {@link
 * io.turnbase.platform.notification.AlertPublisher} push to the right connections, and the
 * `sse-notifications.md` § Keepalive comment on a fixed 30s tick.
 */
@Component
class NotificationEmitterRegistry {

  private static final long KEEPALIVE_INTERVAL_MS = 30_000;

  private final Map<String, AuthenticatedUser> connections = new ConcurrentHashMap<>();
  private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
  private final Map<UUID, Set<String>> emitterIdsByUser = new ConcurrentHashMap<>();

  /**
   * Registers {@code emitter} for {@code user} and wires cleanup on completion, timeout or error.
   */
  String register(AuthenticatedUser user, SseEmitter emitter) {
    String emitterId = UUID.randomUUID().toString();
    connections.put(emitterId, user);
    emitters.put(emitterId, emitter);
    emitterIdsByUser
        .computeIfAbsent(user.userId(), id -> ConcurrentHashMap.newKeySet())
        .add(emitterId);
    emitter.onCompletion(() -> unregister(emitterId));
    emitter.onTimeout(() -> unregister(emitterId));
    emitter.onError(e -> unregister(emitterId));
    return emitterId;
  }

  void unregister(String emitterId) {
    @Nullable AuthenticatedUser user = connections.remove(emitterId);
    emitters.remove(emitterId);
    if (user != null) {
      @Nullable Set<String> ids = emitterIdsByUser.get(user.userId());
      if (ids != null) {
        ids.remove(emitterId);
      }
    }
  }

  int activeConnectionCount() {
    return connections.size();
  }

  @Nullable AuthenticatedUser userOf(String emitterId) {
    return connections.get(emitterId);
  }

  /**
   * {@code AlertPublisher}'s real push: {@code event: alert}, no {@code id}, dropped for a user
   * with no open stream ({@code FR-ALERT-010}).
   */
  void publishAlert(UUID userId, String type, Map<String, Object> payload) {
    for (String emitterId : emitterIdsOf(userId)) {
      send(emitterId, dataOf(type, payload), "alert", null);
    }
  }

  /**
   * The offline-catch-up frame on stream open — {@code sse-notifications.md} § Offline delivery.
   * {@code event: notification}, {@code id:} is {@code eventSeq} (the SSE {@code Last-Event-ID}
   * value), and the type-specific fields are flattened into {@code data} alongside {@code type},
   * {@code notificationId} and {@code timestamp} — the shape of that section's own wire example,
   * distinct from {@code notification-rest-api.md} §2's nested {@code payload} envelope.
   */
  void sendCatchUpNotification(
      String emitterId,
      UUID notificationId,
      String type,
      Map<String, Object> payload,
      java.time.Instant createdAt,
      long eventSeq) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", type);
    data.put("notificationId", notificationId.toString());
    data.putAll(payload);
    data.put("timestamp", createdAt.toString());
    send(emitterId, data, "notification", String.valueOf(eventSeq));
  }

  /**
   * {@code FR-SESS-010} / `IMP-GATE-28`: every connection registered under the revoked login
   * session gets one final {@code event: alert} naming {@code revoked_reason}, then the stream is
   * closed — the SSE counterpart of {@code websocket.WebSocketConnectionRegistry#onSessionRevoked}.
   */
  @EventListener
  void onSessionRevoked(SessionRevokedEvent event) {
    connections.entrySet().stream()
        .filter(entry -> entry.getValue().sessionId().equals(event.sessionId()))
        .map(Map.Entry::getKey)
        .toList()
        .forEach(
            emitterId -> {
              send(
                  emitterId,
                  Map.of("type", "SESSION_DISPLACED", "revoked_reason", event.reason()),
                  "alert",
                  null);
              @Nullable SseEmitter emitter = emitters.get(emitterId);
              if (emitter != null) {
                emitter.complete();
              }
              unregister(emitterId);
            });
  }

  /** {@code sse-notifications.md} § Keepalive: a periodic comment every 30 seconds. */
  @Scheduled(fixedRate = KEEPALIVE_INTERVAL_MS)
  void keepalive() {
    for (String emitterId : Set.copyOf(emitters.keySet())) {
      @Nullable SseEmitter emitter = emitters.get(emitterId);
      if (emitter == null) {
        continue;
      }
      try {
        emitter.send(SseEmitter.event().comment("keepalive"));
      } catch (IOException e) {
        unregister(emitterId);
      }
    }
  }

  private Set<String> emitterIdsOf(UUID userId) {
    @Nullable Set<String> ids = emitterIdsByUser.get(userId);
    return ids == null ? Set.of() : Set.copyOf(ids);
  }

  private void send(
      String emitterId, Map<String, Object> data, String eventName, @Nullable String id) {
    @Nullable SseEmitter emitter = emitters.get(emitterId);
    if (emitter == null) {
      return;
    }
    try {
      SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventName).data(data);
      if (id != null) {
        event = event.id(id);
      }
      emitter.send(event);
    } catch (IOException e) {
      unregister(emitterId);
    }
  }

  private static Map<String, Object> dataOf(String type, Map<String, Object> payload) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", type);
    data.putAll(payload);
    return data;
  }
}
