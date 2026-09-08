package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import io.turnbase.platform.session.SessionRevokedEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * {@code IMP-M1-05}'s two-phase purge ({@code data-model-accounts.md} §1, FR-GUEST-005, {@code
 * platform-operations.md} FR-RET-002), run directly against {@link GuestPurgeJob} rather than
 * waiting out the real {@code guest-cleanup.interval} — {@code turnbase.retention.guest-account=0s}
 * makes a freshly-minted guest immediately eligible for the revoke phase, so no clock manipulation
 * is needed either.
 */
class GuestPurgeJobIT extends PostgresIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private String baseUrl;
  private GuestPurgeJob job;
  private RecordingSessionRevokedEventListener revokedEvents;

  @BeforeEach
  void bootApplication() {
    context =
        SpringApplication.run(
            new Class<?>[] {TurnBaseApplication.class, RecordingSessionRevokedEventListener.class},
            new String[] {
              "--server.port=0",
              "--management.server.port=0",
              "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
              "--spring.datasource.username=" + POSTGRES.getUsername(),
              "--spring.datasource.password=" + POSTGRES.getPassword(),
              "--turnbase.artifact-store.endpoint=" + MINIO.getS3URL(),
              "--turnbase.artifact-store.bucket=" + ARTIFACT_BUCKET,
              "--turnbase.artifact-store.access-key=" + MINIO.getUserName(),
              "--turnbase.artifact-store.secret-key=" + MINIO.getPassword(),
              "--turnbase.admin.username=admin",
              "--turnbase.admin.password=admin",
              "--turnbase.retention.guest-account=0s"
            });
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port + "/api/auth";
    jdbcClient = context.getBean(JdbcClient.class);
    job = context.getBean(GuestPurgeJob.class);
    revokedEvents = context.getBean(RecordingSessionRevokedEventListener.class);
  }

  @AfterEach
  void stopApplication() {
    context.close();
  }

  @Test
  void revokePhaseRevokesTheSessionMarksTheAccountAndPublishesTheEvent() throws Exception {
    // Seated in a live room so the purge phase — which runs in the same job.run() call — leaves
    // the row in place to inspect; an unreferenced guest is hard-deleted before this method could
    // ever read it back (see anUnreferencedExpiredGuestIsHardDeletedInTheSameRun).
    UUID userId = mintGuest();
    seatInALiveRoom(userId);

    job.run();

    Map<String, Object> userRow =
        jdbcClient
            .sql(
                "select deleted_at, deletion_reason, purgeable_after from app_user where user_id"
                    + " = :userId")
            .param("userId", userId)
            .query()
            .singleRow();
    assertThat(userRow.get("deleted_at")).isNotNull();
    assertThat(userRow.get("deletion_reason")).isEqualTo("GUEST_EXPIRED");
    assertThat(userRow.get("purgeable_after")).isNotNull();

    Integer activeSessionCount =
        jdbcClient
            .sql("select count(*) from user_session where user_id = :userId and revoked_at is null")
            .param("userId", userId)
            .query(Integer.class)
            .single();
    assertThat(activeSessionCount).isZero();

    assertThat(revokedEvents.events())
        .anySatisfy(
            event -> {
              assertThat(event.userId()).isEqualTo(userId);
              assertThat(event.reason()).isEqualTo("GUEST_EXPIRED");
            });
  }

  @Test
  void anUnreferencedExpiredGuestIsHardDeletedInTheSameRun() throws Exception {
    UUID userId = mintGuest();

    job.run();

    Integer remaining =
        jdbcClient
            .sql("select count(*) from app_user where user_id = :userId")
            .param("userId", userId)
            .query(Integer.class)
            .single();
    assertThat(remaining).isZero();

    Integer remainingSessions =
        jdbcClient
            .sql("select count(*) from user_session where user_id = :userId")
            .param("userId", userId)
            .query(Integer.class)
            .single();
    assertThat(remainingSessions).isZero();
  }

  @Test
  void aGuestSeatedInALiveRoomIsNeverHardDeleted() throws Exception {
    UUID userId = mintGuest();
    seatInALiveRoom(userId);

    job.run();

    Map<String, Object> userRow =
        jdbcClient
            .sql("select deleted_at from app_user where user_id = :userId")
            .param("userId", userId)
            .query()
            .singleRow();
    assertThat(userRow.get("deleted_at")).isNotNull(); // revoke phase still ran
    Integer remaining =
        jdbcClient
            .sql("select count(*) from app_user where user_id = :userId")
            .param("userId", userId)
            .query(Integer.class)
            .single();
    assertThat(remaining).isEqualTo(1); // but the row itself survives
  }

  private UUID mintGuest() throws Exception {
    ResponseEntity<String> response =
        restTemplate.exchange(baseUrl + "/guest", HttpMethod.POST, HttpEntity.EMPTY, String.class);
    Map<String, Object> body = objectMapper.readValue(response.getBody(), Map.class);
    return UUID.fromString((String) body.get("userId"));
  }

  /** Minimal FK chain: one official game, one lobby room, one seated participant. */
  private void seatInALiveRoom(UUID guestUserId) {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, title, short_description, badge) values (:gameId, 'Test"
                + " Game', 'desc', 'OFFICIAL')")
        .param("gameId", gameId)
        .update();

    UUID roomId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into room (room_id, game_id, game_version, visibility, state) values"
                + " (:roomId, :gameId, '1.0.0', 'PUBLIC', 'LOBBY')")
        .param("roomId", roomId)
        .param("gameId", gameId)
        .update();

    jdbcClient
        .sql(
            "insert into room_participant (room_id, user_id, role, join_order) values (:roomId,"
                + " :userId, 'PLAYER', 0)")
        .param("roomId", roomId)
        .param("userId", guestUserId)
        .update();
  }

  @Component
  static class RecordingSessionRevokedEventListener {
    private final List<SessionRevokedEvent> events =
        Collections.synchronizedList(new ArrayList<>());

    @EventListener
    void onSessionRevoked(SessionRevokedEvent event) {
      events.add(event);
    }

    List<SessionRevokedEvent> events() {
      return events;
    }
  }
}
