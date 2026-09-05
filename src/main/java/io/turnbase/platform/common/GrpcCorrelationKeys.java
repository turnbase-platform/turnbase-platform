package io.turnbase.platform.common;

import io.grpc.Metadata;

/**
 * observability.md §3's five correlation IDs, as gRPC metadata keys. Mirrored in
 * turnbase-game-host's {@code io.turnbase.gamehost.grpc} package — the wire contract is the
 * plain-string metadata key name, not shared code (conventions.md §5: each side of a gRPC boundary
 * picks its own transport and support code).
 */
enum GrpcCorrelationKeys {
  REQUEST_ID("requestId"),
  USER_ID("userId"),
  SESSION_ID("sessionId"),
  ROOM_ID("roomId"),
  GAME_ID("gameId");

  private final String mdcKey;
  private final Metadata.Key<String> metadataKey;

  GrpcCorrelationKeys(String mdcKey) {
    this.mdcKey = mdcKey;
    this.metadataKey = Metadata.Key.of(mdcKey, Metadata.ASCII_STRING_MARSHALLER);
  }

  String mdcKey() {
    return mdcKey;
  }

  Metadata.Key<String> metadataKey() {
    return metadataKey;
  }
}
