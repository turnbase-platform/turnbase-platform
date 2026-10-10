package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/** IMP-M10-15: the room-list {@code search} filter matches the user's literal text. */
class RoomRepositorySearchIT extends PostgresIntegrationTest {

  private static JdbcClient jdbcClient;
  private static RoomRepository repository;

  @BeforeAll
  static void setUp() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
    jdbcClient =
        JdbcClient.create(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    JsonMapper mapper = JsonMapper.builder().build();
    repository =
        new RoomRepository(
            jdbcClient,
            mapper,
            new RoomInvitationRepository(jdbcClient, mapper, event -> {}),
            event -> {});
  }

  @BeforeEach
  void clean() {
    jdbcClient
        .sql(
            "truncate room_team, room_participant, room, game_tag, game_version, game, app_user cascade")
        .update();
  }

  @Test
  void percentAndUnderscoreInSearchAreLiterals() {
    UUID host = insertUser();
    roomFor(host, "Plain Game");
    roomFor(host, "100% Fun");
    roomFor(host, "snake_case");
    roomFor(host, "snakeXcase");

    assertThat(search("%")).containsExactly("100% Fun");
    assertThat(search("_")).containsExactly("snake_case");
    assertThat(search("e_c")).containsExactly("snake_case");
    assertThat(search("\\")).isEmpty();
  }

  private List<String> search(String text) {
    RoomListFilter filter =
        new RoomListFilter(text, null, null, null, null, null, Sort.unsorted(), 25, 0);
    return repository.listRooms(UUID.randomUUID(), filter, 10).stream()
        .map(RoomRepository.RoomListRow::gameName)
        .toList();
  }

  private void roomFor(UUID host, String title) {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge, listed)"
                + " values (:id, :host, :title, 'A short description.', 'VERIFIED', true)")
        .param("id", gameId)
        .param("title", title)
        .param("host", host)
        .update();
    jdbcClient
        .sql(
            """
            insert into game_version (version_id, game_id, version, status, declared_size_bytes,
              upload_expires_at, min_players, max_players, supports_ai, supports_spectators,
              supports_mid_game_join, activated_at)
            values (:id, :gameId, '1.0.0', 'ACTIVE', 1024, now(), 2, 4, true, true, false, now())
            """)
        .param("id", UUID.randomUUID())
        .param("gameId", gameId)
        .update();
    UUID roomId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into room (room_id, game_id, game_version, host_user_id, visibility, state)"
                + " values (:id, :gameId, '1.0.0', :host, 'PUBLIC', 'LOBBY')")
        .param("id", roomId)
        .param("gameId", gameId)
        .param("host", host)
        .update();
    jdbcClient
        .sql(
            "insert into room_team (team_id, room_id, template_index, team_index, configured_size)"
                + " values (:id, :roomId, 0, 0, 2)")
        .param("id", UUID.randomUUID())
        .param("roomId", roomId)
        .update();
  }

  private UUID insertUser() {
    UUID userId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into app_user (user_id, kind, username, username_skeleton, email, email_source)"
                + " values (:id, 'REGISTERED', :username, :username, :email, 'SELF')")
        .param("id", userId)
        .param("username", "host" + userId.toString().replace("-", "").substring(0, 10))
        .param("email", "host-" + userId + "@example.com")
        .update();
    return userId;
  }
}
