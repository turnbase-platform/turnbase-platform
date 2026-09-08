package io.turnbase.platform.social;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The public surface {@code account.ProfileService} calls to resolve FR-SOC-016's profile button
 * state and FR-SOC-017's {@code FRIENDS_ONLY} check ({@code social-rest-api.md} §9) — the only
 * thing another feature needs from the friend graph, and the reason this class exists separately
 * from {@link SocialService} rather than making the whole service public.
 */
@Component
public class RelationshipQueries {

  private final SocialRepository repository;

  RelationshipQueries(SocialRepository repository) {
    this.repository = repository;
  }

  /** FR-SOC-017's {@code FRIENDS_ONLY} predicate. */
  public boolean areFriends(UUID a, UUID b) {
    return repository.friendshipExists(a, b);
  }

  /** FR-SOC-016. {@code viewer} and {@code other} must be distinct — callers exclude the owner. */
  public RelationshipState relationshipOf(UUID viewer, UUID other) {
    if (repository.friendshipExists(viewer, other)) {
      return RelationshipState.FRIENDS;
    }
    return repository
        .pendingDirection(viewer, other)
        .map(
            direction ->
                direction == SocialRepository.Direction.OUTGOING
                    ? RelationshipState.REQUEST_SENT
                    : RelationshipState.REQUEST_RECEIVED)
        .orElse(RelationshipState.NONE);
  }

  /** {@code social-rest-api.md} §9. */
  public enum RelationshipState {
    NONE,
    FRIENDS,
    REQUEST_SENT,
    REQUEST_RECEIVED
  }
}
