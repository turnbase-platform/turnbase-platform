package io.turnbase.platform.room;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §1.1: "{@code podId} is per-incarnation, not per-name" — the instance
 * name plus a UUID generated at boot, held for the lifetime of this process. A stable name alone
 * would let a *restarted* instance match the claim script's "already mine" branch, skip adoption,
 * and produce room versions from a freshly-zeroed counter — the divergence flow 05 §C5 warns about.
 *
 * <p>When the deployment supplies {@code POD_IP} (the chart does), the name is {@code
 * <hostname>@<a-b-c-d>}, the pod's address with dashes. The frontend proxy routes {@code
 * /ws?instance=} by that part: a ready pod of a headless Service resolves as {@code
 * a-b-c-d.<service>.<namespace>.svc.cluster.local}, whereas a Deployment's pod name does not
 * resolve ({@code load-testing-phase-b.md} §4.3, {@code IMP-M18-09}).
 *
 * <p>{@link #bootAt()} is {@code room-lifecycle.md} §5.4 guard 3's reference point for the
 * reconciliation sweep's 120s startup-suppression window ({@code IMP-M2-10}).
 */
@Component
class PodIdentity {

  private final String id;
  private final Instant bootAt;

  @Autowired
  PodIdentity() {
    this(System.getenv("HOSTNAME"), System.getenv("POD_IP"));
  }

  PodIdentity(@Nullable String hostname, @Nullable String podIp) {
    String name = hostname == null || hostname.isBlank() ? "platform" : hostname;
    String address = podIp == null || podIp.isBlank() ? "" : "@" + podIp.trim().replace('.', '-');
    this.id = name + address + ":" + UUID.randomUUID();
    this.bootAt = Instant.now();
  }

  String id() {
    return id;
  }

  Instant bootAt() {
    return bootAt;
  }
}
