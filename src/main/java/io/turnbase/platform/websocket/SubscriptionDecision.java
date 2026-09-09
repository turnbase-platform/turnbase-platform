package io.turnbase.platform.websocket;

/**
 * What a {@link SubscriptionAuthorizer} decided about one {@code SUBSCRIBE} frame. {@code
 * websocket.md} § Authorization draws the same distinction this type carries: most unauthorized
 * subscriptions are silently dropped, but a room whose ownership cannot be confirmed needs to tell
 * the client to retry ({@code room-websocket.md} § Error codes, {@code ROOM_UNAVAILABLE_RETRY}) —
 * that is not "unauthorized," it is "ask again shortly."
 */
public sealed interface SubscriptionDecision {

  /** The subscription proceeds. */
  record Allow() implements SubscriptionDecision {}

  /** {@code websocket.md} § Authorization: no data sent, no error returned. */
  record SilentlyDrop() implements SubscriptionDecision {}

  /**
   * The subscription is refused, and the client is told so on {@code /user/queue/errors} — {@code
   * retryAfterMs} is meaningful only for retryable codes (e.g. {@code ROOM_UNAVAILABLE_RETRY}) and
   * is {@code 0} otherwise.
   */
  record RejectWithError(String code, String message, long retryAfterMs)
      implements SubscriptionDecision {}
}
