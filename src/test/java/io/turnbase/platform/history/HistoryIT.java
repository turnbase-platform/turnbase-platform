package io.turnbase.platform.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import io.turnbase.platform.game.GameOutcomeRecorder;
import io.turnbase.platform.game.GameOutcomeReport;
import io.turnbase.platform.game.SessionRoster;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;

/**
 * {@code IMP-M5-01}: the recording transaction ({@code flows/06} §B–C) and {@code GET
 * /api/users/{username}/history} ({@code history-rest-api.md}).
 */
class HistoryIT extends PostgresIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private ConfigurableApplicationContext context;
  private JdbcClient jdbc;
  private GameOutcomeRecorder recorder;
  private String baseUrl;

  {
    restTemplate.setErrorHandler(
        new org.springframework.web.client.ResponseErrorHandler() {
          @Override
          public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
            return false;
          }

          @Override
          public void handleError(org.springframework.http.client.ClientHttpResponse response) {}
        });
  }

  @BeforeEach
  void boot() {
    context =
        SpringApplication.run(
            TurnBaseApplication.class,
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
            "--spring.data.redis.url=" + redisUrl(),
            "--turnbase.rate-limit.auth.failures-before-lockout=1000000",
            "--turnbase.rate-limit.minting.guest.capacity=1000000",
            "--turnbase.rate-limit.minting.registration.capacity=1000000");
    baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    jdbc = context.getBean(JdbcClient.class);
    recorder = context.getBean(GameOutcomeRecorder.class);
  }

  @AfterEach
  void stop() {
    context.close();
  }

  @Test
  void recordsOneEntryPerRegisteredPlayerAndCountsTheSessionOnce() throws Exception {
    Registered alice = register();
    Registered bob = register();
    UUID guest = guest();
    UUID game = newGame("Chess");
    UUID teamA = UUID.randomUUID();
    UUID teamB = UUID.randomUUID();
    UUID teamC = UUID.randomUUID();
    SessionRoster roster =
        new SessionRoster(
            Map.of(alice.userId(), teamA, bob.userId(), teamB, guest, teamC), Set.of());
    GameOutcomeReport report =
        report(
            game,
            roster,
            List.of(
                team(teamA, 1, Map.of("captures", "9")),
                team(teamB, 2, Map.of()),
                team(teamC, 3, Map.of())),
            List.of(player(alice.userId(), Map.of("moves", "41"))),
            false);

    recorder.record(report);
    recorder.record(report); // FR-EFF-007: a re-delivered game_over changes nothing

    assertThat(
            count(
                "select count(*) from game_session_completion where game_id = '%s'"
                    .formatted(game)))
        .isEqualTo(1);
    assertThat(
            count("select completed_session_count from game where game_id = '%s'".formatted(game)))
        .isEqualTo(1);
    assertThat(
            count("select count(*) from game_history_entry where game_id = '%s'".formatted(game)))
        .isEqualTo(2); // no guest entry
    assertThat(getHistory(alice.username(), null, "").getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> entry = firstEntry(getHistory(alice.username(), null, ""));
    assertThat(entry.get("gameName")).isEqualTo("Chess");
    assertThat(entry.get("gameLinkable")).isEqualTo(true);
    assertThat(entry.get("outcomeLabel")).isEqualTo("Victory");
    assertThat(entry.get("playerStats")).isEqualTo(Map.of("moves", "41"));
    assertThat(entry.get("teamStats")).isEqualTo(Map.of("captures", "9"));
    Map<String, Object> bobEntry = firstEntry(getHistory(bob.username(), null, ""));
    assertThat(bobEntry.get("outcomeLabel")).isEqualTo("2nd place");
    assertThat(bobEntry).doesNotContainKeys("playerStats", "teamStats");
  }

  @Test
  void anInvalidOutcomeStillCountsTheSessionButWritesNoEntries() {
    Registered alice = register();
    UUID game = newGame("Broken");
    UUID teamA = UUID.randomUUID();
    UUID teamB = UUID.randomUUID();
    SessionRoster roster = new SessionRoster(Map.of(alice.userId(), teamA), Set.of());

    // Placements 1 and 3 with two teams: standard competition ranking, a plausible game bug.
    recorder.record(
        report(
            game,
            roster,
            List.of(team(teamA, 1, Map.of()), team(teamB, 3, Map.of())),
            List.of(),
            false));

    assertThat(
            count(
                "select count(*) from game_session_completion where game_id = '%s'"
                    .formatted(game)))
        .isEqualTo(1);
    assertThat(
            count("select completed_session_count from game where game_id = '%s'".formatted(game)))
        .isEqualTo(1);
    assertThat(
            count("select count(*) from game_history_entry where game_id = '%s'".formatted(game)))
        .isZero();
  }

  @Test
  void aPlayerResultNamingANonParticipantInvalidatesTheOutcome() {
    Registered alice = register();
    UUID game = newGame("Odd");
    UUID teamA = UUID.randomUUID();
    SessionRoster roster = new SessionRoster(Map.of(alice.userId(), teamA), Set.of());

    recorder.record(
        report(
            game,
            roster,
            List.of(team(teamA, 1, Map.of())),
            List.of(player(UUID.randomUUID(), Map.of())),
            false));

    assertThat(
            count("select count(*) from game_history_entry where game_id = '%s'".formatted(game)))
        .isZero();
    assertThat(
            count(
                "select count(*) from game_session_completion where game_id = '%s'"
                    .formatted(game)))
        .isEqualTo(1);
  }

  @Test
  void drawLabelAndDeletedGameAreServerComputed() throws Exception {
    Registered alice = register();
    UUID game = newGame("Gone");
    UUID teamA = UUID.randomUUID();
    UUID teamB = UUID.randomUUID();
    recorder.record(
        report(
            game,
            new SessionRoster(Map.of(alice.userId(), teamA), Set.of()),
            List.of(team(teamA, 1, Map.of()), team(teamB, 1, Map.of())),
            List.of(),
            true));
    jdbc.sql("update game set deleted_at = now(), title = 'Renamed' where game_id = :g")
        .param("g", game)
        .update();

    Map<String, Object> entry = firstEntry(getHistory(alice.username(), null, ""));

    assertThat(entry.get("outcomeLabel")).isEqualTo("Draw");
    assertThat(entry.get("gameLinkable")).isEqualTo(false);
    assertThat(entry.get("gameName")).isEqualTo("Gone"); // the snapshot, not the live title
  }

  @Test
  void searchIsCaseInsensitiveSubstringAndPaginatedNewestFirst() throws Exception {
    Registered alice = register();
    UUID chess = newGame("Chess Deluxe");
    UUID go = newGame("Go");
    for (int i = 0; i < 3; i++) {
      recordSolo(
          alice, i % 2 == 0 ? chess : go, Instant.parse("2026-09-0" + (i + 1) + "T10:00:00Z"));
    }

    Map<String, Object> search = body(getHistory(alice.username(), null, "?search=CHESS"));
    assertThat((List<?>) search.get("content")).hasSize(2);
    assertThat(pagination(search).get("totalElements")).isEqualTo(2);

    Map<String, Object> page = body(getHistory(alice.username(), null, "?size=2&page=1"));
    List<Map<String, Object>> content = content(page);
    assertThat(content).hasSize(2);
    assertThat(content.get(0).get("completedAt")).isEqualTo("2026-09-03T10:00:00Z");
    assertThat(pagination(page).get("totalPages")).isEqualTo(2);

    Map<String, Object> wildcard = body(getHistory(alice.username(), null, "?search=%25"));
    assertThat((List<?>) wildcard.get("content")).isEmpty(); // % is literal, not a wildcard
  }

  @Test
  void privacyIsReResolvedAndTheOwnerAlwaysSeesTheirOwn() throws Exception {
    Registered alice = register();
    Registered stranger = register();
    recordSolo(alice, newGame("Chess"), Instant.now());
    patch("/api/users/me/privacy", alice.cookie(), "{\"historyVisibility\":\"HIDDEN\"}");

    Map<String, Object> asStranger = body(getHistory(alice.username(), stranger.cookie(), ""));
    Map<String, Object> asAnonymous = body(getHistory(alice.username(), null, ""));
    Map<String, Object> asOwner = body(getHistory(alice.username(), alice.cookie(), ""));

    assertThat((List<?>) asStranger.get("content")).isEmpty();
    assertThat(pagination(asStranger).get("totalElements")).isEqualTo(0);
    assertThat((List<?>) asAnonymous.get("content")).isEmpty();
    assertThat((List<?>) asOwner.get("content")).hasSize(1);

    patch(
        "/api/users/me/privacy",
        alice.cookie(),
        "{\"historyVisibility\":\"PUBLIC\",\"profilePrivate\":true}");
    assertThat((List<?>) body(getHistory(alice.username(), null, "")).get("content")).isEmpty();
  }

  @Test
  void unknownUserIs404AndAGuestIsAnEmptyList() throws Exception {
    assertThat(getHistory("nosuchuser123", null, "").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void aSortParameterIsRejected() {
    Registered alice = register();
    assertThat(
            getHistory(alice.username(), null, "?sort=completedAt,asc")
                .getStatusCode()
                .is4xxClientError())
        .isTrue();
  }

  // --- helpers -------------------------------------------------------------------------------

  private void recordSolo(Registered user, UUID game, Instant at) {
    UUID team = UUID.randomUUID();
    recorder.record(
        new GameOutcomeReport(
            UUID.randomUUID(),
            game,
            "1.0.0",
            at,
            false,
            List.of(team(team, 1, Map.of())),
            List.of(),
            new SessionRoster(Map.of(user.userId(), team), Set.of())));
  }

  private GameOutcomeReport report(
      UUID game,
      SessionRoster roster,
      List<GameOutcomeReport.TeamOutcome> teams,
      List<GameOutcomeReport.PlayerOutcome> players,
      boolean draw) {
    return new GameOutcomeReport(
        UUID.randomUUID(), game, "1.0.0", Instant.now(), draw, teams, players, roster);
  }

  private static GameOutcomeReport.TeamOutcome team(
      UUID id, int placement, Map<String, String> stats) {
    return new GameOutcomeReport.TeamOutcome(id.toString(), placement, stats);
  }

  private static GameOutcomeReport.PlayerOutcome player(UUID id, Map<String, String> stats) {
    return new GameOutcomeReport.PlayerOutcome(id.toString(), stats);
  }

  private UUID newGame(String title) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge, listed)"
                + " values (:id, null, :title, 'x', 'OFFICIAL', false)")
        .param("id", id)
        .param("title", title)
        .update();
    return id;
  }

  private long count(String sql) {
    Long value = jdbc.sql(sql).query(Long.class).single();
    return value == null ? 0 : value;
  }

  private Registered register() {
    String username = "user" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    ResponseEntity<String> response =
        exchange(
            "/api/auth/register",
            HttpMethod.POST,
            null,
            """
            {"username":"%s","email":"%s@example.com","password":"correct horse battery","confirmPassword":"correct horse battery"}
            """
                .formatted(username, UUID.randomUUID()));
    try {
      UUID userId = UUID.fromString((String) body(response).get("userId"));
      return new Registered(userId, username, cookie(response));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID guest() throws Exception {
    return UUID.fromString(
        (String) body(exchange("/api/auth/guest", HttpMethod.POST, null, null)).get("userId"));
  }

  private ResponseEntity<String> getHistory(
      String username, @Nullable String cookie, String query) {
    return exchange("/api/users/" + username + "/history" + query, HttpMethod.GET, cookie, null);
  }

  private ResponseEntity<String> patch(String path, String cookie, String json) {
    return exchange(path, HttpMethod.PATCH, cookie, json);
  }

  private ResponseEntity<String> exchange(
      String path, HttpMethod method, @Nullable String cookie, @Nullable String json) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (cookie != null) {
      headers.add(HttpHeaders.COOKIE, cookie);
    }
    return restTemplate.exchange(
        baseUrl + path, method, new HttpEntity<>(json, headers), String.class);
  }

  private static String cookie(ResponseEntity<String> response) {
    return java.util.Objects.requireNonNull(response.getHeaders().get(HttpHeaders.SET_COOKIE))
        .stream()
        .filter(c -> c.startsWith("turnbase_session="))
        .findFirst()
        .orElseThrow()
        .split(";", 2)[0];
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> body(ResponseEntity<String> response) throws Exception {
    return objectMapper.readValue(java.util.Objects.requireNonNull(response.getBody()), Map.class);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> content(Map<String, Object> page) {
    return (List<Map<String, Object>>) java.util.Objects.requireNonNull(page.get("content"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> pagination(Map<String, Object> page) {
    return (Map<String, Object>) java.util.Objects.requireNonNull(page.get("pagination"));
  }

  private Map<String, Object> firstEntry(ResponseEntity<String> response) throws Exception {
    return content(body(response)).get(0);
  }

  private record Registered(UUID userId, String username, String cookie) {}
}
