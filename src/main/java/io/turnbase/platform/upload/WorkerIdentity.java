package io.turnbase.platform.upload;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@code claimed_by} value stamped on a {@code game_version} row's validation lease ({@code
 * bundle-format.md} §7.2 duty 2, {@code data-model-catalog.md} §2). Mirrors {@code
 * room.PodIdentity}'s per-incarnation shape (instance name plus a boot-time UUID) — not shared
 * across the module boundary because the two leases serve unrelated concerns and each is small
 * enough to own directly.
 */
@Component
class WorkerIdentity {

  private final String id;

  WorkerIdentity() {
    String name = System.getenv("HOSTNAME");
    this.id = (name == null || name.isBlank() ? "platform" : name) + ":" + UUID.randomUUID();
  }

  String id() {
    return id;
  }
}
