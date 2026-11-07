package io.turnbase.platform.game;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * IMP-M0-03's Done-when clause: the {@code io.github.turnbase-platform:turnbase-grpc} client stubs
 * resolve and compile against this repository, not just against a throwaway consumer. Real wiring
 * of the {@code GameHostService} dispatch path is IMP-M3-05; this only proves the artifact links.
 */
class GameHostClientStubResolutionTest {

  private @org.jspecify.annotations.Nullable ManagedChannel channel;

  @AfterEach
  void shutdown() {
    if (channel != null) {
      channel.shutdownNow();
    }
  }

  @Test
  void blockingStubBuildsFromTheGeneratedClientClass() {
    channel = ManagedChannelBuilder.forAddress("localhost", 9090).usePlaintext().build();

    GameHostServiceGrpc.GameHostServiceBlockingStub stub =
        GameHostServiceGrpc.newBlockingStub(channel);

    assertThat(stub).isNotNull();
  }
}
