package io.turnbase.platform.room;

import io.turnbase.platform.game.GameSessionService;
import io.turnbase.platform.room.RoomRecords.ParameterSchema;
import io.turnbase.platform.room.RoomRecords.PlayTarget;
import io.turnbase.platform.room.RoomRecords.TeamTemplateInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The start-game reads and the room parameters they validate. Split from the former {@code
 * RoomRepository} by {@code IMP-M20-14}; SQL and transactions are as they were.
 */
@Repository
class RoomGameStartRepository {

  private final JdbcClient jdbcClient;
  private final JsonMapper objectMapper;
  private final RoomReadRepository reads;

  RoomGameStartRepository(
      JdbcClient jdbcClient, JsonMapper objectMapper, RoomReadRepository reads) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.reads = reads;
  }

  /**
   * {@code flows/02-room-to-game-start.md} §E steps 5–6: the room's final lobby state as the {@code
   * CreateSession} roster. Team names are the instance display names ({@code
   * orchestration-gamehost-protocol.md} *Roster and team-name resolution*), every manifest
   * parameter is present (stored host values over the manifest's declared defaults), and the entry
   * point is the manifest's {@code backend.entryPoint} (IMP-GATE-79).
   */
  GameSessionService.CreateSessionCommand sessionCommand(UUID roomId, UUID sessionId) {
    record RoomGame(
        UUID gameId, String gameVersion, String manifest, @Nullable String parameters) {}
    RoomGame room =
        jdbcClient
            .sql(
                """
                select r.game_id, r.game_version, gv.manifest, r.parameters
                from room r join game_version gv
                  on gv.game_id = r.game_id and gv.version = r.game_version
                where r.room_id = :roomId
                """)
            .param("roomId", roomId)
            .query(
                (rs, rowNum) ->
                    new RoomGame(
                        rs.getObject("game_id", UUID.class),
                        rs.getString("game_version"),
                        rs.getString("manifest"),
                        rs.getString("parameters")))
            .single();
    JsonNode manifest;
    try {
      manifest = objectMapper.readTree(room.manifest());
    } catch (Exception e) {
      throw new IllegalStateException("Stored manifest is not valid JSON", e);
    }
    List<TeamTemplateInfo> templates = reads.parseTeamTemplates(room.manifest());

    Map<String, Object> parameters = new java.util.LinkedHashMap<>();
    for (JsonNode declared : manifest.path("parameters")) {
      parameters.put(
          declared.path("key").asText(),
          objectMapper.convertValue(declared.path("default"), Object.class));
    }
    parameters.putAll(reads.readParameters(room.parameters()));
    String parametersJson;
    try {
      parametersJson = objectMapper.writeValueAsString(parameters);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize session parameters", e);
    }

    record Member(UUID teamId, UUID playerId, String username) {}
    List<Member> members =
        jdbcClient
            .sql(
                """
                select rp.team_id, rp.user_id, au.username
                from room_participant rp join app_user au on au.user_id = rp.user_id
                where rp.room_id = :roomId and rp.role = 'PLAYER' and rp.left_at is null
                  and rp.team_id is not null
                order by rp.join_order
                """)
            .param("roomId", roomId)
            .query(
                (rs, rowNum) ->
                    new Member(
                        rs.getObject("team_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("username")))
            .list();
    List<GameSessionService.TeamSpec> teams =
        jdbcClient
            .sql(
                """
                select team_id, template_index, team_index from room_team
                where room_id = :roomId order by template_index, team_index
                """)
            .param("roomId", roomId)
            .query(
                (rs, rowNum) -> {
                  UUID teamId = rs.getObject("team_id", UUID.class);
                  String name =
                      templates.get(rs.getInt("template_index")).name()
                          + " "
                          + (rs.getInt("team_index") + 1);
                  return new GameSessionService.TeamSpec(
                      teamId,
                      name,
                      members.stream()
                          .filter(m -> m.teamId().equals(teamId))
                          .map(m -> new GameSessionService.PlayerSpec(m.playerId(), m.username()))
                          .toList());
                })
            .list();
    return new GameSessionService.CreateSessionCommand(
        sessionId,
        room.gameId(),
        room.gameVersion(),
        manifest.path("backend").path("entryPoint").asText(),
        teams,
        parametersJson);
  }

  /** {@code flows/02-room-to-game-start.md} §E step 9 (the version bump is the executor's). */
  void markGameStarted(UUID roomId, UUID sessionId) {
    int updated =
        jdbcClient
            .sql(
                """
                update room set state = 'IN_PROGRESS', active_session_id = :sessionId
                 where room_id = :roomId and state = 'LOBBY'
                """)
            .param("sessionId", sessionId)
            .param("roomId", roomId)
            .update();
    if (updated != 1) {
      throw new IllegalStateException("Room " + roomId + " left the Lobby during START_GAME");
    }
  }

  /** What {@code /play/{sessionId}} serves: the room's pinned bundle, while its session runs. */
  Optional<PlayTarget> playTarget(UUID sessionId) {
    return jdbcClient
        .sql(
            """
            select r.game_id, r.game_version, gv.manifest
            from room r join game_version gv
              on gv.game_id = r.game_id and gv.version = r.game_version
            where r.active_session_id = :sessionId and r.state = 'IN_PROGRESS'
            """)
        .param("sessionId", sessionId)
        .query(
            (rs, rowNum) -> {
              String entryPoint;
              try {
                entryPoint =
                    objectMapper
                        .readTree(rs.getString("manifest"))
                        .path("frontend")
                        .path("entryPoint")
                        .asText();
              } catch (Exception e) {
                throw new IllegalStateException("Stored manifest is not valid JSON", e);
              }
              return new PlayTarget(
                  rs.getObject("game_id", UUID.class), rs.getString("game_version"), entryPoint);
            })
        .optional();
  }

  UUID gameIdOf(UUID roomId) {
    return jdbcClient
        .sql("select game_id from room where room_id = :roomId")
        .param("roomId", roomId)
        .query(UUID.class)
        .single();
  }

  List<ParameterSchema> parameterSchemasForRoom(UUID roomId) {
    String manifest =
        jdbcClient
            .sql(
                """
                select gv.manifest
                from room r join game_version gv
                  on gv.game_id = r.game_id and gv.version = r.game_version
                where r.room_id = :roomId
                """)
            .param("roomId", roomId)
            .query(String.class)
            .single();
    return parseParameterSchemas(manifest);
  }

  private List<ParameterSchema> parseParameterSchemas(@Nullable String manifestJson) {
    if (manifestJson == null) {
      throw new IllegalStateException("An ACTIVE game_version must carry a validated manifest.");
    }
    JsonNode manifest;
    try {
      manifest = objectMapper.readTree(manifestJson);
    } catch (Exception e) {
      throw new IllegalStateException("Stored manifest is not valid JSON", e);
    }
    List<ParameterSchema> schemas = new ArrayList<>();
    for (JsonNode node : manifest.path("parameters")) {
      String type = node.path("type").asText();
      schemas.add(
          new ParameterSchema(
              node.path("key").asText(),
              type,
              node.path("min").asDouble(0),
              node.path("max").asDouble(0),
              node.hasNonNull("maxLength") ? node.path("maxLength").asInt() : null,
              node.has("options")
                  ? java.util.stream.StreamSupport.stream(node.path("options").spliterator(), false)
                      .map(JsonNode::asText)
                      .toList()
                  : null));
    }
    return schemas;
  }
}
