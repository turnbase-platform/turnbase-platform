package io.turnbase.platform.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.CreateSessionResponse;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.platform.game.GameSessionService.CreateSessionCommand;
import io.turnbase.platform.game.GameSessionService.GameSessionCreationException;
import io.turnbase.platform.game.GameSessionService.GameSessionCreationException.Reason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * What a start does when the Game Host is late, closes a pooled connection, or holds the call: it
 * waits, retries once, and sets a wedged host aside — and tells the caller which phase it is in.
 */
class GameSessionServiceTest {

  private static final String ADDRESS = "host-a:9090";

  private final HostRegistry registry = mock(HostRegistry.class);
  private final GameHostChannels channels = mock(GameHostChannels.class);
  private final EffectDelivery effects = mock(EffectDelivery.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
  private final List<StartPhase> phases = new ArrayList<>();
  private final AtomicInteger calls = new AtomicInteger();

  private @org.jspecify.annotations.Nullable Server server;
  private @org.jspecify.annotations.Nullable ManagedChannel channel;
  private Consumer<StreamObserver<CreateSessionResponse>> behaviour = GameSessionServiceTest::ok;

  @BeforeEach
  void startFakeHost() throws Exception {
    String name = "host-" + UUID.randomUUID();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                new GameHostServiceGrpc.GameHostServiceImplBase() {
                  @Override
                  public void createSession(
                      CreateSessionRequest request,
                      StreamObserver<CreateSessionResponse> observer) {
                    calls.incrementAndGet();
                    behaviour.accept(observer);
                  }
                })
            .build()
            .start();
    ManagedChannel built = InProcessChannelBuilder.forName(name).directExecutor().build();
    channel = built;
    when(channels.forAddress(ADDRESS)).thenReturn(built);
    when(registry.addressOf(anyString())).thenReturn(Optional.of(ADDRESS));
  }

  @AfterEach
  void stopFakeHost() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.shutdownNow();
    }
  }

  private GameSessionService service(Duration hostWait) {
    return new GameSessionService(
        registry,
        channels,
        redis,
        new OrchestrationProperties(
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(5),
            hostWait,
            Duration.ofSeconds(60)),
        effects);
  }

  private static CreateSessionCommand command() {
    return new CreateSessionCommand(
        UUID.randomUUID(), UUID.randomUUID(), "1.0.0", "x.Game", List.of(), "{}");
  }

  private static void ok(StreamObserver<CreateSessionResponse> observer) {
    observer.onNext(
        CreateSessionResponse.newBuilder()
            .setResult(ProcessResult.newBuilder().setStatus(ProcessStatus.PROCESS_STATUS_OK))
            .build());
    observer.onCompleted();
  }

  @Test
  void reportsLoadingGameThenSucceeds() {
    when(registry.selectHost(anyString(), anyString(), anySet())).thenReturn(Optional.of("a"));
    behaviour = GameSessionServiceTest::ok;

    service(Duration.ZERO).create(command(), phases::add);

    assertThat(phases).containsExactly(StartPhase.LOADING_GAME);
    assertThat(calls).hasValue(1);
  }

  @Test
  void waitsForAHostThatRegistersLate() {
    when(registry.selectHost(anyString(), anyString(), anySet()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of("a"));
    behaviour = GameSessionServiceTest::ok;

    service(Duration.ofSeconds(10)).create(command(), phases::add);

    assertThat(phases).containsExactly(StartPhase.WAITING_FOR_HOST, StartPhase.LOADING_GAME);
  }

  @Test
  void givesUpWithNoHostAvailableWhenNoneAppearsWithinTheWait() {
    when(registry.selectHost(anyString(), anyString(), anySet())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service(Duration.ofMillis(600)).create(command(), phases::add))
        .isInstanceOfSatisfying(
            GameSessionCreationException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.NO_HOST_AVAILABLE));
    assertThat(phases).containsExactly(StartPhase.WAITING_FOR_HOST);
  }

  @Test
  void retriesOnceAfterAClosedConnectionAndDialsAfresh() {
    when(registry.selectHost(anyString(), anyString(), anySet())).thenReturn(Optional.of("a"));
    behaviour =
        observer -> {
          if (calls.get() == 1) {
            observer.onError(Status.UNAVAILABLE.withDescription("Network closed").asException());
          } else {
            ok(observer);
          }
        };

    service(Duration.ZERO).create(command(), phases::add);

    assertThat(calls).hasValue(2);
    verify(channels).discard(ADDRESS);
    verify(registry, never()).quarantine(anyString());
    assertThat(phases)
        .containsExactly(StartPhase.LOADING_GAME, StartPhase.RETRYING, StartPhase.LOADING_GAME);
  }

  @Test
  void aHostThatNeverAnswersIsQuarantinedAndTheNextOneTried() {
    when(registry.selectHost(anyString(), anyString(), eq(java.util.Set.<String>of())))
        .thenReturn(Optional.of("a"));
    when(registry.selectHost(anyString(), anyString(), eq(java.util.Set.of("a"))))
        .thenReturn(Optional.of("b"));
    behaviour =
        observer -> {
          if (calls.get() == 1) {
            observer.onError(Status.DEADLINE_EXCEEDED.asException());
          } else {
            ok(observer);
          }
        };

    service(Duration.ZERO).create(command(), phases::add);

    verify(registry).quarantine("a");
    assertThat(calls).hasValue(2);
  }

  @Test
  void aWedgedHostWithNoSibling_failsAsUnresponsiveNotNoHost() {
    when(registry.selectHost(anyString(), anyString(), eq(java.util.Set.<String>of())))
        .thenReturn(Optional.of("a"));
    when(registry.selectHost(anyString(), anyString(), eq(java.util.Set.of("a"))))
        .thenReturn(Optional.empty());
    behaviour = observer -> observer.onError(Status.DEADLINE_EXCEEDED.asException());

    assertThatThrownBy(() -> service(Duration.ZERO).create(command(), phases::add))
        .isInstanceOfSatisfying(
            GameSessionCreationException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.HOST_UNRESPONSIVE));
    verify(registry).quarantine("a");
  }

  @Test
  void aThirdAttemptSucceedsAfterTwoWedgedHosts() {
    when(registry.selectHost(anyString(), anyString(), anySet())).thenReturn(Optional.of("a"));
    behaviour =
        observer -> {
          if (calls.get() < 3) {
            observer.onError(Status.DEADLINE_EXCEEDED.asException());
          } else {
            ok(observer);
          }
        };

    service(Duration.ZERO).create(command(), phases::add);

    assertThat(calls).hasValue(3);
  }

  @Test
  void anOnInitFailureIsNotRetried() {
    when(registry.selectHost(anyString(), anyString(), anySet())).thenReturn(Optional.of("a"));
    behaviour = observer -> observer.onError(Status.INTERNAL.asException());

    assertThatThrownBy(() -> service(Duration.ZERO).create(command(), phases::add))
        .isInstanceOfSatisfying(
            GameSessionCreationException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.SESSION_CREATION_FAILED));
    assertThat(calls).hasValue(1);
  }
}
