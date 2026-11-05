package io.turnbase.platform.seed;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import io.turnbase.platform.upload.TestBundles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;

/**
 * {@code local-environment.md} §6 / IMP-M0-12: a start under {@code local} seeds the users, the
 * friendship, the pending request and the four in-scope notification types, and drives every bundle
 * in {@code turnbase.seed.bundles-dir} through the real upload pipeline — the unpack is proven by
 * the game's own origin serving its frontend entry point. A second run changes nothing.
 *
 * <p>Keeps its own boot: the seed runs at startup, so the boot is the subject. (IMP-M20-10)
 *
 * <p>Requirements protected: {@code user-accounts.md} FR-AUTH-006; {@code
 * notifications-invitations.md} FR-NOTIF-001.
 */
class SeedDataIT extends PostgresIntegrationTest {

  private static final UUID GAME_ID = UUID.fromString("00000000-0000-4000-8000-0000000000e1");

  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private final JdbcClient jdbcClient =
      JdbcClient.create(
          new org.springframework.jdbc.datasource.DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

  @TempDir Path bundlesDir;

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

  @BeforeEach
  void resetSchema() {
    // The seed runs during boot, so each test needs a schema Flyway has not yet touched.
    jdbcClient.sql("drop schema public cascade").update();
    jdbcClient.sql("create schema public").update();
  }

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void seedsTheUsersTheFriendshipTheRequestAndTheFourNotificationTypes() {
    boot("local", null);

    assertThat(
            jdbcClient
                .sql("select username from app_user order by username")
                .query(String.class)
                .list())
        .containsExactly("alice", "bob", "carol", "dave");
    assertThat(count("friendship")).isEqualTo(1);
    assertThat(
            jdbcClient
                .sql(
                    "select u1.username || '->' || u2.username from friend_request r"
                        + " join app_user u1 on u1.user_id = r.sender_id"
                        + " join app_user u2 on u2.user_id = r.recipient_id")
                .query(String.class)
                .list())
        .containsExactly("carol->alice");
    assertThat(
            jdbcClient
                .sql("select type from notification order by type")
                .query(String.class)
                .list())
        .containsExactly(
            "FRIEND_ADDED", "FRIEND_REQUEST", "FRIEND_REQUEST_ACCEPTED", "FRIEND_REQUEST_DECLINED");
  }

  @Test
  void everySeededUserHasExactlyOnePrivacySettingsRow() {
    boot("local", null);

    assertThat(
            jdbcClient
                .sql(
                    "select u.username || ':' || count(p.user_id) from app_user u"
                        + " left join user_privacy_settings p on p.user_id = u.user_id"
                        + " where u.kind = 'REGISTERED' group by u.username order by u.username")
                .query(String.class)
                .list())
        .containsExactly("alice:1", "bob:1", "carol:1", "dave:1");
  }

  @Test
  void aSeededUserAndAnApiRegisteredUserCanBeReadAsThemselvesAndAsOneAnother() {
    boot("local", null);
    String base = "http://localhost:" + port();
    HttpHeaders json = new HttpHeaders();
    json.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> registered =
        restTemplate.postForEntity(
            base + "/api/auth/register",
            new HttpEntity<>(
                "{\"username\":\"erin\",\"email\":\"erin@example.test\","
                    + "\"password\":\"correct horse battery 1\","
                    + "\"confirmPassword\":\"correct horse battery 1\"}",
                json),
            String.class);
    assertThat(registered.getStatusCode().is2xxSuccessful()).isTrue();
    List<String> names = List.of("alice", "bob", "carol", "dave", "erin");

    for (String viewer : names) {
      String cookie =
          viewer.equals("erin")
              ? sessionCookie(registered)
              : sessionCookie(login(base, viewer, "password", json));
      HttpHeaders headers = new HttpHeaders();
      headers.add(HttpHeaders.COOKIE, cookie);
      assertThat(get(base + "/api/users/me", headers)).as("%s /me", viewer).isEqualTo(200);
      for (String target : names) {
        assertThat(get(base + "/api/users/" + target, headers))
            .as("%s views %s", viewer, target)
            .isEqualTo(200);
      }
    }
    assertThat(count("user_privacy_settings")).isEqualTo(5);
  }

  private ResponseEntity<String> login(
      String base, String username, String password, HttpHeaders json) {
    return restTemplate.postForEntity(
        base + "/api/auth/login",
        new HttpEntity<>(
            "{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}", json),
        String.class);
  }

  private static String sessionCookie(ResponseEntity<String> response) {
    return java.util.Objects.requireNonNull(response.getHeaders().get(HttpHeaders.SET_COOKIE))
        .stream()
        .map(c -> c.split(";", 2)[0])
        .collect(java.util.stream.Collectors.joining("; "));
  }

  private int get(String url, HttpHeaders headers) {
    return restTemplate
        .exchange(
            url, org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class)
        .getStatusCode()
        .value();
  }

  @Test
  void aBundleInTheSeedDirectoryIsUploadedThroughThePipelineAndItsFrontendIsServed()
      throws IOException {
    Files.write(bundlesDir.resolve(GAME_ID + ".zip"), TestBundles.validChessBundle("1.0.0"));

    boot("local", bundlesDir.toString());

    assertThat(
            jdbcClient
                .sql(
                    "select gv.status from game g join game_version gv"
                        + " on gv.version_id = g.active_version_id where g.game_id = :id")
                .param("id", GAME_ID)
                .query(String.class)
                .single())
        .isEqualTo("ACTIVE");
    assertThat(
            jdbcClient
                .sql(
                    "select badge || ':' || listed || ':' || coalesce(owner_user_id::text, 'null')"
                        + " from game where game_id = :id")
                .param("id", GAME_ID)
                .query(String.class)
                .single())
        .isEqualTo("OFFICIAL:true:null");

    ResponseEntity<byte[]> asset =
        restTemplate.getForEntity(
            "http://%s.games.localhost:%d/g/%s/1.0.0/frontend/main.js"
                .formatted(GAME_ID, port(), GAME_ID),
            byte[].class);
    assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void aSecondRunChangesNothingAndDoesNotFail() throws Exception {
    Files.write(bundlesDir.resolve(GAME_ID + ".zip"), TestBundles.validChessBundle("1.0.0"));
    ConfigurableApplicationContext booted = boot("local", bundlesDir.toString());
    long versions = count("game_version");
    long notifications = count("notification");

    booted.getBean(SeedRunner.class).run(new DefaultApplicationArguments());

    assertThat(count("app_user")).isEqualTo(4);
    assertThat(count("game")).isEqualTo(1);
    assertThat(count("game_version")).isEqualTo(versions).isEqualTo(1);
    assertThat(count("notification")).isEqualTo(notifications).isEqualTo(4);
    assertThat(count("friendship")).isEqualTo(1);
    assertThat(count("friend_request")).isEqualTo(1);
  }

  @Test
  void anUnsetBundleDirectorySeedsNoGamesAndDoesNotFail() {
    boot("local", null);

    assertThat(count("game")).isZero();
    assertThat(count("app_user")).isEqualTo(4);
  }

  @Test
  void nothingIsSeededOutsideTheLocalAndE2eProfiles() {
    boot("test", bundlesDir.toString());

    assertThat(count("app_user")).isZero();
    assertThat(count("game")).isZero();
  }

  private ConfigurableApplicationContext boot(String profile, @Nullable String bundlesDirValue) {
    List<String> args =
        new java.util.ArrayList<>(
            List.of(
                "--spring.profiles.active=" + profile,
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
                "--spring.data.redis.url=" + redisUrl()));
    if (bundlesDirValue != null) {
      args.add("--turnbase.seed.bundles-dir=" + bundlesDirValue);
    }
    ConfigurableApplicationContext booted =
        SpringApplication.run(TurnBaseApplication.class, args.toArray(String[]::new));
    context = booted;
    return booted;
  }

  private int port() {
    ConfigurableApplicationContext booted = java.util.Objects.requireNonNull(context);
    return Integer.parseInt(
        java.util.Objects.requireNonNull(booted.getEnvironment().getProperty("local.server.port")));
  }

  private long count(String table) {
    return jdbcClient.sql("select count(*) from " + table).query(Long.class).single();
  }

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
