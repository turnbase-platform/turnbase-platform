package io.turnbase.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code catalog-rest-api.md}'s two endpoints, end to end — proves IMP-M4-01's three Done-when
 * clauses: an Unverified game is absent from the catalog for everyone but its owner (and reachable
 * anonymously, since {@code SessionAuthenticationInterceptor} exempts {@code /api/games}); a game
 * carries exactly one badge from one column; the info doc is delivered as raw Markdown source,
 * never HTML.
 */
class CatalogControllerIT extends PostgresIntegrationTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private String baseUrl;

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

  @BeforeEach
  void bootApplication() {
    ConfigurableApplicationContext booted = boot();
    context = booted;
    int port = Integer.parseInt(booted.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    jdbcClient = booted.getBean(JdbcClient.class);
  }

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  private ConfigurableApplicationContext boot() {
    return SpringApplication.run(
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
  }

  @Test
  void listingRequiresNoSessionAndOmitsUnverifiedGamesFromOtherOwners() throws Exception {
    Registered owner = registerUser();
    seedGame(owner.userId(), "UNVERIFIED", true, "Secret Prototype", null);
    seedGame(null, "OFFICIAL", true, "Chess", null);

    List<Map<String, Object>> anonymousRows = contentOf(parse(get("/api/games")));

    assertThat(titlesOf(anonymousRows)).contains("Chess").doesNotContain("Secret Prototype");
  }

  @Test
  void ownerSeesTheirOwnUnverifiedGameInTheListing() throws Exception {
    Registered owner = registerUser();
    seedGame(owner.userId(), "UNVERIFIED", true, "Secret Prototype", null);

    List<Map<String, Object>> ownRows =
        contentOf(parse(getWithCookie("/api/games", owner.cookie())));

    assertThat(titlesOf(ownRows)).contains("Secret Prototype");
  }

  @Test
  void ownerFilterRestrictsToThatUsersGamesAndComposesWithVisibility() throws Exception {
    Registered alice = registerUser();
    Registered bob = registerUser();
    seedGame(alice.userId(), "VERIFIED", true, "Alice Listed", null);
    seedGame(alice.userId(), "VERIFIED", false, "Alice Unlisted", null);
    seedGame(alice.userId(), "UNVERIFIED", true, "Alice Unverified", null);
    seedGame(bob.userId(), "VERIFIED", true, "Bob Game", null);
    seedGame(null, "OFFICIAL", true, "Official Game", null);
    String path = "/api/games?owner=" + alice.username();

    assertThat(titlesOf(contentOf(parse(getWithCookie(path, alice.cookie())))))
        .containsExactlyInAnyOrder("Alice Listed", "Alice Unlisted", "Alice Unverified");
    assertThat(titlesOf(contentOf(parse(getWithCookie(path, bob.cookie())))))
        .containsExactly("Alice Listed");
    assertThat(titlesOf(contentOf(parse(get(path))))).containsExactly("Alice Listed");
  }

  @Test
  void ownerFilterOfAnUnknownUsernameIsAnEmptyPageNotA404() throws Exception {
    seedGame(null, "OFFICIAL", true, "Official Game", null);

    ResponseEntity<String> response = get("/api/games?owner=nobody-by-that-name");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(contentOf(parse(response))).isEmpty();
  }

  @Test
  void everyRowCarriesExactlyOneBadgeFromTheGameColumn() throws Exception {
    seedGame(null, "OFFICIAL", true, "Chess", null);

    List<Map<String, Object>> rows = contentOf(parse(get("/api/games")));

    Map<String, Object> chess =
        rows.stream().filter(row -> "Chess".equals(row.get("title"))).findFirst().orElseThrow();
    assertThat(chess.get("badge")).isEqualTo("OFFICIAL");
  }

  @Test
  void detailReturnsTheInfoDocAsRawMarkdownNeverHtml() throws Exception {
    UUID gameId = seedGame(null, "OFFICIAL", true, "Chess", "## How to play\n\n* move a piece");

    Map<String, Object> detail = parse(get("/api/games/" + gameId));

    assertThat(detail.get("infoDocMarkdown")).isEqualTo("## How to play\n\n* move a piece");
  }

  @Test
  void detailCarriesTheCompletedSessionCounterAndZeroWhenNeverCompleted() throws Exception {
    UUID gameId = seedGame(null, "OFFICIAL", true, "Chess", null);
    assertThat(parse(get("/api/games/" + gameId)).get("completedSessionCount")).isEqualTo(0);

    jdbcClient
        .sql("update game set completed_session_count = 1284 where game_id = :g")
        .param("g", gameId)
        .update();

    assertThat(parse(get("/api/games/" + gameId)).get("completedSessionCount")).isEqualTo(1284);
  }

  @Test
  void detailOmitsTheInfoDocAsNullWhenAbsent() throws Exception {
    UUID gameId = seedGame(null, "OFFICIAL", true, "Chess", null);

    Map<String, Object> detail = parse(get("/api/games/" + gameId));

    assertThat(detail).containsKey("infoDocMarkdown");
    assertThat(detail.get("infoDocMarkdown")).isNull();
  }

  @Test
  void detailOfAnUnknownGameIs404() {
    ResponseEntity<String> response = get("/api/games/" + UUID.randomUUID());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void detailOfAGameInvisibleToTheCallerIs404NotAnAuthFailure() throws Exception {
    Registered owner = registerUser();
    UUID gameId = seedGame(owner.userId(), "UNVERIFIED", true, "Secret Prototype", null);

    ResponseEntity<String> response = get("/api/games/" + gameId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void detailOfASoftDeletedGameIs410() throws Exception {
    UUID gameId = seedGame(null, "OFFICIAL", true, "Chess", null);
    jdbcClient
        .sql("update game set deleted_at = now() where game_id = :id")
        .param("id", gameId)
        .update();

    ResponseEntity<String> response = get("/api/games/" + gameId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
  }

  private UUID seedGame(
      @Nullable UUID ownerId,
      String badge,
      boolean listed,
      String title,
      @Nullable String infoDocMarkdown) {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, owner_user_id, title, short_description, info_doc_markdown,"
                + " badge, listed) values (:id, :owner, :title, 'A short description.', :infoDoc,"
                + " :badge, :listed)")
        .param("id", gameId)
        .param("owner", ownerId)
        .param("title", title)
        .param("infoDoc", infoDocMarkdown)
        .param("badge", badge)
        .param("listed", listed)
        .update();
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            insert into game_version (version_id, game_id, version, status, declared_size_bytes,
                upload_expires_at, min_players, max_players, supports_ai, supports_spectators,
                supports_mid_game_join, activated_at)
            values (:versionId, :gameId, '1.0.0', 'ACTIVE', 1024, now(), 2, 2, false, false, false,
                now())
            """)
        .param("versionId", versionId)
        .param("gameId", gameId)
        .update();
    jdbcClient
        .sql("update game set active_version_id = :versionId where game_id = :gameId")
        .param("versionId", versionId)
        .param("gameId", gameId)
        .update();
    return gameId;
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

  private ResponseEntity<String> post(String path, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> request = new HttpEntity<>(body, headers);
    return restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
  }

  private ResponseEntity<String> get(String path) {
    return restTemplate.exchange(baseUrl + path, HttpMethod.GET, HttpEntity.EMPTY, String.class);
  }

  private ResponseEntity<String> getWithCookie(String path, String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, cookie);
    HttpEntity<Void> request = new HttpEntity<>(headers);
    return restTemplate.exchange(baseUrl + path, HttpMethod.GET, request, String.class);
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(ResponseEntity<String> response) throws Exception {
    return objectMapper.readValue(response.getBody(), Map.class);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> contentOf(Map<String, Object> body) {
    return (List<Map<String, Object>>) java.util.Objects.requireNonNull(body.get("content"));
  }

  private List<String> titlesOf(List<Map<String, Object>> rows) {
    return rows.stream().map(row -> (String) row.get("title")).toList();
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
    public void handleError(
        java.net.URI url,
        org.springframework.http.HttpMethod method,
        org.springframework.http.client.ClientHttpResponse response) {
      // Never called: hasError() always returns false so assertions see the real status.
    }
  }
}
