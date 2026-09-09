/**
 * STOMP-over-WebSocket connection infrastructure (`IMP-M2-02`, `conventions.md` §1's {@code
 * websocket/} feature, {@code IMP-GATE-42}): connection config, {@code CONNECT}-frame
 * authentication, the Tier 6 pod-local rate limiter ({@code rate-limiting.md}), and the connection
 * registry. Per-destination {@code SUBSCRIBE} authorization and {@code @MessageMapping} command
 * handlers are feature-specific and live in the feature they authorize (see {@link
 * io.turnbase.platform.websocket.SubscriptionAuthorizer}); this package knows nothing about rooms
 * or games.
 */
@NullMarked
package io.turnbase.platform.websocket;

import org.jspecify.annotations.NullMarked;
