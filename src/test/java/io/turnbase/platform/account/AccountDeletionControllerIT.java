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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * {@code IMP-M1-08}'s own Done-when clause, end to end against a real application boot and
 * Postgres: {@code DELETE /api/users/me} against {@code data-model-accounts.md} §5's disposition
 * table — irreversible via a random token (ADR-0004), every listed object disposed of, nothing
 * relying on a cascade.
 */
class AccountDeletionControllerIT extends PostgresIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private String baseUrl;
  private RecordingSessionRevokedEventListener revokedEvents;

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

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
              "--turnbase.admin.password=admin"
            });
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    jdbcClient = context.getBean(JdbcClient.class);
    revokedEvents = context.getBean(RecordingSessionRevokedEventListener.class);
  }

  @AfterEach
  void stopApplication() {
    context.close();
  }

  @Test
  void deletionErasesEveryDispositionTableObjectAndPublishesTheEvent() throws Exception {
    Registered user = registerUser();
    UUID sessionId =
        jdbcClient
            .sql("select session_id from user_session where user_id = :id")
            .param("id", user.userId())
            .query(UUID.class)
            .single();

    Registered friend = registerUser();
    friendUp(user.userId(), friend.userId());

    Registered requestSender = registerUser();
    UUID requestId = sendFriendRequest(requestSender.userId(), user.userId());

    UUID ownedGameId = createOwnedGame(user.userId());
    UUID openReviewId = openReview(ownedGameId);

    UUID otherOwnerGameId = createOwnedGame(friend.userId());
    UUID sessionCompletionId = completeSession(otherOwnerGameId);
    insertHistoryEntry(sessionCompletionId, user.userId(), otherOwnerGameId);

    UUID roomId = createRoom(otherOwnerGameId);
    seatParticipant(roomId, user.userId());

    UUID invitationId = sendRoomInvitation(roomId, user.userId(), friend.userId());

    ResponseEntity<String> response = deleteWithCookie(user.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    List<String> setCookies =
        java.util.Objects.requireNonNull(response.getHeaders().get(HttpHeaders.SET_COOKIE));
    assertThat(setCookies).anySatisfy(c -> assertThat(c).contains("turnbase_session=;"));

    Map<String, Object> userRow =
        jdbcClient
            .sql(
                "select deleted_at, deletion_reason, email, email_source, username from app_user"
                    + " where user_id = :id")
            .param("id", user.userId())
            .query()
            .singleRow();
    assertThat(userRow.get("deleted_at")).isNotNull();
    assertThat(userRow.get("deletion_reason")).isEqualTo("SELF_SERVICE");
    assertThat(userRow.get("email")).isNull();
    assertThat(userRow.get("email_source")).isNull();
    assertThat(userRow.get("username")).isEqualTo(user.username()); // FR-ACCT-006 reservation

    assertThat(count("user_auth_method", user.userId())).isZero();
    assertThat(count("user_privacy_settings", user.userId())).isZero();
    assertThat(
            jdbcClient
                .sql(
                    "select count(*) from friendship where user_id_low = :id or user_id_high ="
                        + " :id")
                .param("id", user.userId())
                .query(Integer.class)
                .single())
        .isZero();
    assertThat(
            jdbcClient
                .sql("select count(*) from friend_request where request_id = :id")
                .param("id", requestId)
                .query(Integer.class)
                .single())
        .isZero();
    assertThat(
            jdbcClient
                .sql("select count(*) from room_invitation where invitation_id = :id")
                .param("id", invitationId)
                .query(Integer.class)
                .single())
        .isZero();
    assertThat(count("notification", user.userId())).isZero();

    Map<String, Object> reviewRow =
        jdbcClient
            .sql("select decided_at, decision, decided_by from game_review where review_id = :id")
            .param("id", openReviewId)
            .query()
            .singleRow();
    assertThat(reviewRow.get("decided_at")).isNotNull();
    assertThat(reviewRow.get("decision")).isEqualTo("WITHDRAWN");
    assertThat(reviewRow.get("decided_by")).isEqualTo("system");

    Map<String, Object> gameRow =
        jdbcClient
            .sql("select archived_at from game where game_id = :id")
            .param("id", ownedGameId)
            .query()
            .singleRow();
    assertThat(gameRow.get("archived_at")).isNotNull();

    Map<String, Object> historyRow =
        jdbcClient
            .sql(
                "select user_id, subject_token, pseudonymized_at from game_history_entry where"
                    + " session_id = :id")
            .param("id", sessionCompletionId)
            .query()
            .singleRow();
    assertThat(historyRow.get("user_id")).isNull();
    assertThat(historyRow.get("subject_token")).isNotNull();
    assertThat(historyRow.get("pseudonymized_at")).isNotNull();

    Map<String, Object> participantRow =
        jdbcClient
            .sql(
                "select left_at, leave_reason, lost_announced_at from room_participant where"
                    + " room_id = :roomId and user_id = :userId")
            .param("roomId", roomId)
            .param("userId", user.userId())
            .query()
            .singleRow();
    assertThat(participantRow.get("left_at")).isNotNull();
    assertThat(participantRow.get("leave_reason")).isEqualTo("LEFT");
    assertThat(participantRow.get("lost_announced_at")).isNull();

    assertThat(revokedEvents.events())
        .anySatisfy(
            event -> {
              assertThat(event.sessionId()).isEqualTo(sessionId);
              assertThat(event.userId()).isEqualTo(user.userId());
              assertThat(event.reason()).isEqualTo("ACCOUNT_DELETED");
            });
  }

  @Test
  void aSecondDeletionCallHasNoValidSessionLeft() throws Exception {
    Registered user = registerUser();

    deleteWithCookie(user.cookie());
    ResponseEntity<String> secondCall = deleteWithCookie(user.cookie());

    assertThat(secondCall.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aGuestCannotDeleteThisWay() throws Exception {
    ResponseEntity<String> guestResponse = post("/api/auth/guest");
    String cookie = sessionCookieHeader(guestResponse);

    ResponseEntity<String> response = deleteWithCookie(cookie);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private Integer count(String table, UUID userId) {
    return jdbcClient
        .sql("select count(*) from " + table + " where user_id = :id")
        .param("id", userId)
        .query(Integer.class)
        .single();
  }

  private void friendUp(UUID a, UUID b) {
    UUID low = a.compareTo(b) < 0 ? a : b;
    UUID high = a.compareTo(b) < 0 ? b : a;
    jdbcClient
        .sql("insert into friendship (user_id_low, user_id_high) values (:low, :high)")
        .param("low", low)
        .param("high", high)
        .update();
  }

  private UUID sendFriendRequest(UUID sender, UUID recipient) {
    UUID requestId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into friend_request (request_id, sender_id, recipient_id, expires_at) values"
                + " (:id, :sender, :recipient, now() + interval '7 days')")
        .param("id", requestId)
        .param("sender", sender)
        .param("recipient", recipient)
        .update();
    return requestId;
  }

  private UUID createOwnedGame(UUID ownerUserId) {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge) values"
                + " (:id, :owner, 'Test Game', 'desc', 'UNVERIFIED')")
        .param("id", gameId)
        .param("owner", ownerUserId)
        .update();
    return gameId;
  }

  private UUID openReview(UUID gameId) {
    UUID reviewId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game_version (version_id, game_id, version, status, declared_size_bytes,"
                + " upload_expires_at) values (:versionId, :gameId, '1.0.0', 'ACTIVE', 1024, now()"
                + " + interval '1 hour')")
        .param("versionId", versionId)
        .param("gameId", gameId)
        .update();
    jdbcClient
        .sql(
            "insert into game_review (review_id, game_id, version_id) values (:id, :gameId,"
                + " :versionId)")
        .param("id", reviewId)
        .param("gameId", gameId)
        .param("versionId", versionId)
        .update();
    return reviewId;
  }

  private UUID completeSession(UUID gameId) {
    UUID sessionId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game_session_completion (session_id, game_id, game_version,"
                + " completed_at, player_count, team_count, draw) values (:id, :gameId, '1.0.0',"
                + " now(), 2, 2, false)")
        .param("id", sessionId)
        .param("gameId", gameId)
        .update();
    return sessionId;
  }

  private void insertHistoryEntry(UUID sessionId, UUID userId, UUID gameId) {
    jdbcClient
        .sql(
            "insert into game_history_entry (session_id, user_id, game_id, game_name_snapshot,"
                + " completed_at, placement, team_count, draw) values (:sessionId, :userId,"
                + " :gameId, 'Test Game', now(), 1, 2, false)")
        .param("sessionId", sessionId)
        .param("userId", userId)
        .param("gameId", gameId)
        .update();
  }

  private UUID createRoom(UUID gameId) {
    UUID roomId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into room (room_id, game_id, game_version, visibility, state) values (:id,"
                + " :gameId, '1.0.0', 'PUBLIC', 'LOBBY')")
        .param("id", roomId)
        .param("gameId", gameId)
        .update();
    return roomId;
  }

  private void seatParticipant(UUID roomId, UUID userId) {
    jdbcClient
        .sql(
            "insert into room_participant (room_id, user_id, role, join_order) values (:roomId,"
                + " :userId, 'PLAYER', 0)")
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
  }

  private UUID sendRoomInvitation(UUID roomId, UUID inviter, UUID invitee) {
    UUID invitationId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into room_invitation (invitation_id, room_id, inviter_user_id,"
                + " invitee_user_id, expires_at) values (:id, :roomId, :inviter, :invitee, now()"
                + " + interval '7 days')")
        .param("id", invitationId)
        .param("roomId", roomId)
        .param("inviter", inviter)
        .param("invitee", invitee)
        .update();
    return invitationId;
  }

  private Registered registerUser() throws Exception {
    String username = uniqueUsername();
    ResponseEntity<String> response =
        post(
            "/api/auth/register",
            """
            {"username":"%s","email":"%s","password":"correct horse battery","confirmPassword":"correct horse battery"}
            """
                .formatted(username, uniqueEmail()));
    UUID userId = UUID.fromString((String) parse(response).get("userId"));
    return new Registered(userId, username, sessionCookieHeader(response));
  }

  private ResponseEntity<String> post(String path) {
    return post(path, null);
  }

  private ResponseEntity<String> post(String path, @Nullable String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    HttpEntity<String> request = new HttpEntity<>(body, headers);
    return restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
  }

  private ResponseEntity<String> deleteWithCookie(String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, cookie);
    HttpEntity<Void> request = new HttpEntity<>(headers);
    return restTemplate.exchange(
        baseUrl + "/api/users/me", HttpMethod.DELETE, request, String.class);
  }

  private String sessionCookieHeader(ResponseEntity<String> response) {
    List<String> setCookies =
        java.util.Objects.requireNonNull(response.getHeaders().get(HttpHeaders.SET_COOKIE));
    return setCookies.stream()
        .filter(c -> c.startsWith("turnbase_session="))
        .findFirst()
        .orElseThrow()
        .split(";", 2)[0];
  }

  private Map<String, Object> parse(ResponseEntity<String> response) throws Exception {
    return objectMapper.readValue(response.getBody(), Map.class);
  }

  private static String uniqueUsername() {
    return "user" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  private record Registered(UUID userId, String username, String cookie) {}

  private static final class NoOpResponseErrorHandler
      implements org.springframework.web.client.ResponseErrorHandler {
    @Override
    public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
      return false;
    }

    @Override
    public void handleError(org.springframework.http.client.ClientHttpResponse response) {
      // Never called: hasError() always returns false so assertions see the real status.
    }
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
