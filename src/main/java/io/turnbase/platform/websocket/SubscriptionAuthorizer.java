package io.turnbase.platform.websocket;

import io.turnbase.platform.common.AuthenticatedUser;

/**
 * The extension point a feature implements to govern {@code SUBSCRIBE} to its own destinations —
 * {@code room.*} from `IMP-M2-02` onward, {@code game.*} from `IMP-M3-*}. This package deliberately
 * knows nothing about rooms or games: every bean of this type in the context is asked {@link
 * #supports}, and the first match's {@link #authorize} decides the frame's fate. A destination no
 * bean supports is allowed by default — {@code /user/queue/*} destinations are inherently
 * principal-scoped by Spring and need no feature-specific check.
 */
public interface SubscriptionAuthorizer {

  /** Whether this authorizer governs {@code destination} (e.g. an {@code AntPathMatcher} check). */
  boolean supports(String destination);

  /** Called only when {@link #supports} returned {@code true}. */
  SubscriptionDecision authorize(AuthenticatedUser user, String destination, String stompSessionId);
}
