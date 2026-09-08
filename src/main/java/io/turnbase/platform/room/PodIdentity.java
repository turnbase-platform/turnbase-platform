package io.turnbase.platform.room;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §1.1: "{@code podId} is per-incarnation, not per-name" — the instance
 * name plus a UUID generated at boot, held for the lifetime of this process. A stable name alone
 * would let a *restarted* instance match the claim script's "already mine" branch, skip adoption,
 * and produce room versions from a freshly-zeroed counter — the divergence flow 05 §C5 warns about.
 */
@Component
class PodIdentity {

  private final String id;

  PodIdentity() {
    String name = System.getenv("HOSTNAME");
    this.id = (name == null || name.isBlank() ? "platform" : name) + ":" + UUID.randomUUID();
  }

  String id() {
    return id;
  }
}
