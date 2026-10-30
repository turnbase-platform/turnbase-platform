package io.turnbase.platform.game;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.ContextKey;
import io.turnbase.grpc.gamehost.v1.DeregisterRequest;
import io.turnbase.grpc.gamehost.v1.DeregisterResponse;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc;
import io.turnbase.grpc.gamehost.v1.HeartbeatRequest;
import io.turnbase.grpc.gamehost.v1.HeartbeatResponse;
import io.turnbase.grpc.gamehost.v1.RegisterRequest;
import io.turnbase.grpc.gamehost.v1.RegisterResponse;
import io.turnbase.grpc.gamehost.v1.ReportContextsRequest;
import io.turnbase.grpc.gamehost.v1.ReportContextsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Server side of {@code GameHostRegistryService} ({@code orchestration-gamehost-protocol.md}). Each
 * RPC mutates only Redis-backed state through {@link HostRegistry}, so any replica behind the load
 * balancer can service any call and a dropped {@code Heartbeat} stream is never authoritative — the
 * lease in Redis is.
 */
@Component
class GameHostRegistryGrpcService
    extends GameHostRegistryServiceGrpc.GameHostRegistryServiceImplBase {

  private static final Logger log = LoggerFactory.getLogger(GameHostRegistryGrpcService.class);

  private final HostRegistry registry;
  private final OrchestrationProperties properties;

  GameHostRegistryGrpcService(HostRegistry registry, OrchestrationProperties properties) {
    this.registry = registry;
    this.properties = properties;
  }

  @Override
  public void register(RegisterRequest request, StreamObserver<RegisterResponse> response) {
    if (request.getInstanceId().isBlank() || request.getAddress().isBlank()) {
      response.onError(
          Status.INVALID_ARGUMENT
              .withDescription("instance_id and address are required")
              .asRuntimeException());
      return;
    }
    registry.register(request.getInstanceId(), request.getAddress(), request.getRuntimeTypesList());
    log.info(
        "Game Host registered instanceId={} address={}",
        request.getInstanceId(),
        request.getAddress());
    response.onNext(
        RegisterResponse.newBuilder()
            .setHeartbeatIntervalSeconds((int) properties.heartbeatInterval().toSeconds())
            .setLeaseTtlSeconds((int) properties.leaseTtl().toSeconds())
            .build());
    response.onCompleted();
  }

  @Override
  public StreamObserver<HeartbeatRequest> heartbeat(StreamObserver<HeartbeatResponse> response) {
    return new StreamObserver<>() {
      @Override
      public void onNext(HeartbeatRequest request) {
        if (request.getDraining() && !registry.isDraining(request.getInstanceId())) {
          log.info("Game Host draining instanceId={}", request.getInstanceId());
        }
        boolean known =
            registry.heartbeat(
                request.getInstanceId(),
                request.getActiveSessions(),
                request.getActiveContexts(),
                request.getMemoryUsedPercent(),
                request.getDraining());
        if (!known) {
          // The entry is gone (deregistered, or Redis was reset): the host must Register again,
          // which a reconnecting client does on any stream error. One message goes first because
          // Tomcat's HTTP/2 stack through grpc-servlet drops the status of a trailers-only
          // response — a call that fails before its first message reaches the client as INTERNAL.
          response.onNext(HeartbeatResponse.getDefaultInstance());
          response.onError(
              Status.NOT_FOUND.withDescription("instance is not registered").asRuntimeException());
          return;
        }
        response.onNext(
            HeartbeatResponse.newBuilder()
                .setDrain(registry.isDraining(request.getInstanceId()))
                .build());
      }

      @Override
      public void onError(Throwable t) {
        // A dropped stream changes nothing: the lease expires by itself if no heartbeat follows.
        log.debug("Heartbeat stream ended with an error", t);
      }

      @Override
      public void onCompleted() {
        response.onCompleted();
      }
    };
  }

  @Override
  public void reportContexts(
      ReportContextsRequest request, StreamObserver<ReportContextsResponse> response) {
    registry.reportContexts(
        request.getInstanceId(),
        request.getContextsList().stream()
            .map(GameHostRegistryGrpcService::contextMember)
            .toList());
    response.onNext(ReportContextsResponse.getDefaultInstance());
    response.onCompleted();
  }

  @Override
  public void deregister(DeregisterRequest request, StreamObserver<DeregisterResponse> response) {
    registry.deregister(request.getInstanceId());
    log.info("Game Host deregistered instanceId={}", request.getInstanceId());
    response.onNext(DeregisterResponse.getDefaultInstance());
    response.onCompleted();
  }

  private static String contextMember(ContextKey key) {
    return key.getGameId() + ":" + key.getGameVersion();
  }
}
