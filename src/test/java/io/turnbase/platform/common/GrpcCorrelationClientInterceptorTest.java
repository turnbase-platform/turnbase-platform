package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.CreateSessionResponse;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * IMP-GATE-18: proves the {@link GrpcCorrelationClientInterceptor} mechanism against a controlled
 * in-process gRPC call — there is no live gRPC client in this repo yet (IMP-M3-05) — by capturing
 * the headers a hand-written {@link ServerInterceptor} receives on the other end, the same role
 * turnbase-game-host's real {@code GrpcCorrelationServerInterceptor} plays in production.
 */
class GrpcCorrelationClientInterceptorTest {

  private static final String SERVER_NAME = "grpc-correlation-client-interceptor-test";

  private @Nullable Server server;
  private @Nullable ManagedChannel channel;

  @AfterEach
  void shutdown() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.shutdownNow();
    }
    MDC.clear();
  }

  @Test
  void copiesPresentMdcKeysOntoMetadataAndOmitsAbsentOnes() throws IOException {
    Map<String, String> capturedHeaders = new ConcurrentHashMap<>();
    server =
        InProcessServerBuilder.forName(SERVER_NAME)
            .directExecutor()
            .addService(new CapturingGameHostService())
            .intercept(capturingServerInterceptor(capturedHeaders))
            .build()
            .start();
    channel =
        InProcessChannelBuilder.forName(SERVER_NAME)
            .directExecutor()
            .intercept(new GrpcCorrelationClientInterceptor())
            .build();

    MDC.put("requestId", "req-1");
    MDC.put("roomId", "room-1");
    try {
      GameHostServiceGrpc.newBlockingStub(channel)
          .createSession(CreateSessionRequest.newBuilder().build());
    } finally {
      MDC.clear();
    }

    assertThat(capturedHeaders)
        .containsEntry("requestId", "req-1")
        .containsEntry("roomId", "room-1");
    assertThat(capturedHeaders).doesNotContainKeys("userId", "sessionId", "gameId");
  }

  private static ServerInterceptor capturingServerInterceptor(Map<String, String> sink) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        for (GrpcCorrelationKeys key : GrpcCorrelationKeys.values()) {
          String value = headers.get(key.metadataKey());
          if (value != null) {
            sink.put(key.mdcKey(), value);
          }
        }
        return next.startCall(call, headers);
      }
    };
  }

  private static final class CapturingGameHostService
      extends GameHostServiceGrpc.GameHostServiceImplBase {
    @Override
    public void createSession(
        CreateSessionRequest request,
        io.grpc.stub.StreamObserver<CreateSessionResponse> responseObserver) {
      responseObserver.onNext(CreateSessionResponse.getDefaultInstance());
      responseObserver.onCompleted();
    }
  }
}
