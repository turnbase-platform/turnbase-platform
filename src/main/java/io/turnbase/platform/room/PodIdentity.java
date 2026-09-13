package io.turnbase.platform.room;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §1.1: "{@code podId} is per-incarnation, not per-name" — the instance
 * name plus a UUID generated at boot, held for the lifetime of this process. A stable name alone
 * would let a *restarted* instance match the claim script's "already mine" branch, skip adoption,
 * and produce room versions from a freshly-zeroed counter — the divergence flow 05 §C5 warns about.
 *
 * <p>{@link #bootAt()} is {@code room-lifecycle.md} §5.4 guard 3's reference point for the
 * reconciliation sweep's 120s startup-suppression window ({@code IMP-M2-10}).
 */
@Component
class PodIdentity {

  private final String id;
  private final Instant bootAt;

  PodIdentity() {
    String name = System.getenv("HOSTNAME");
    this.id = (name == null || name.isBlank() ? "platform" : name) + ":" + UUID.randomUUID();
    this.bootAt = Instant.now();
  }

  String id() {
    return id;
  }

  Instant bootAt() {
    return bootAt;
  }
}
