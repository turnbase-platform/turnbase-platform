package io.turnbase.platform.room;

import io.turnbase.platform.room.RoomRecords.HostTransferredPayload;
import io.turnbase.platform.room.RoomRecords.ParameterSchema;
import io.turnbase.platform.room.RoomRecords.ParametersChangedPayload;
import io.turnbase.platform.room.RoomRecords.PlayerKickedPayload;
import io.turnbase.platform.room.RoomRecords.PlayerReassignedPayload;
import io.turnbase.platform.room.RoomRecords.RoomHostAndState;
import io.turnbase.platform.room.RoomRecords.RoomParametersRow;
import io.turnbase.platform.room.RoomRecords.TargetMember;
import io.turnbase.platform.room.RoomRecords.TeamAddedPayload;
import io.turnbase.platform.room.RoomRecords.TeamOccupancyRow;
import io.turnbase.platform.room.RoomRecords.TeamRemovedPayload;
import io.turnbase.platform.room.RoomRecords.TeamResizedPayload;
import io.turnbase.platform.room.RoomRecords.TeamTemplateInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The host commands. Split from the former {@code RoomRepository} by {@code IMP-M20-14}; SQL and
 * transactions are as they were.
 */
@Repository
class RoomHostCommandRepository {

  private final JdbcClient jdbcClient;
  private final JsonMapper objectMapper;
  private final RoomGameStartRepository gameStart;
  private final RoomOutboxRepository outbox;
  private final RoomReadRepository reads;
  private final RoomRepository rooms;

  RoomHostCommandRepository(
      JdbcClient jdbcClient,
      JsonMapper objectMapper,
      RoomGameStartRepository gameStart,
      RoomOutboxRepository outbox,
      RoomReadRepository reads,
      RoomRepository rooms) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.gameStart = gameStart;
    this.outbox = outbox;
    this.reads = reads;
    this.rooms = rooms;
  }

  /** {@code ADD_TEAM}, FR-TEAM-002. */
  TeamAddedPayload addTeam(UUID roomId, UUID hostUserId, String templateName, int configuredSize) {
    rooms.requireLobby(requireHost(roomId, hostUserId));
    List<TeamTemplateInfo> templates = reads.teamTemplatesForRoom(roomId);
    int templateIndex = -1;
    TeamTemplateInfo template = null;
    for (int i = 0; i < templates.size(); i++) {
      if (templates.get(i).name().equals(templateName)) {
        templateIndex = i;
        template = templates.get(i);
        break;
      }
    }
    if (template == null) {
      throw new RoomCommandException(
          "VALIDATION_FAILED",
          "No such team template.",
          Map.of("field", "templateName", "value", templateName));
    }
    if (configuredSize < template.sizeMin() || configuredSize > template.sizeMax()) {
      throw new RoomCommandException(
          "SIZE_OUT_OF_RANGE",
          "configuredSize must be within [%d, %d]."
              .formatted(template.sizeMin(), template.sizeMax()));
    }
    long currentCount = countTeamsForTemplate(roomId, templateIndex);
    if (currentCount >= template.countMax()) {
      throw new RoomCommandException(
          "TEMPLATE_MAX_TEAMS", "This template already has the maximum number of teams.");
    }
    UUID teamId = UUID.randomUUID();
    int teamIndex = (int) currentCount;
    jdbcClient
        .sql(
            """
            insert into room_team (team_id, room_id, template_index, team_index, configured_size)
            values (:teamId, :roomId, :templateIndex, :teamIndex, :configuredSize)
            """)
        .param("teamId", teamId)
        .param("roomId", roomId)
        .param("templateIndex", templateIndex)
        .param("teamIndex", teamIndex)
        .param("configuredSize", configuredSize)
        .update();
    return new TeamAddedPayload(teamId, templateName, configuredSize, teamIndex);
  }

  /** {@code REMOVE_TEAM}, FR-TEAM-003. */
  TeamRemovedPayload removeTeam(UUID roomId, UUID hostUserId, UUID teamId) {
    rooms.requireLobby(requireHost(roomId, hostUserId));
    requireTeamInRoom(roomId, teamId);
    long occupied = teamOccupancy(roomId, teamId);
    if (occupied > 0) {
      throw new RoomCommandException("TEAM_NOT_EMPTY", "This team still has members.");
    }
    jdbcClient
        .sql("delete from room_team where team_id = :teamId and room_id = :roomId")
        .param("teamId", teamId)
        .param("roomId", roomId)
        .update();
    return new TeamRemovedPayload(teamId);
  }

  /** {@code RESIZE_TEAM}, FR-TEAM-004. */
  TeamResizedPayload resizeTeam(UUID roomId, UUID hostUserId, UUID teamId, int newSize) {
    rooms.requireLobby(requireHost(roomId, hostUserId));
    int templateIndex = requireTeamInRoom(roomId, teamId);
    TeamTemplateInfo template = reads.teamTemplatesForRoom(roomId).get(templateIndex);
    if (newSize < template.sizeMin() || newSize > template.sizeMax()) {
      throw new RoomCommandException(
          "SIZE_OUT_OF_RANGE",
          "newSize must be within [%d, %d].".formatted(template.sizeMin(), template.sizeMax()));
    }
    long occupied = teamOccupancy(roomId, teamId);
    if (newSize < occupied) {
      throw new RoomCommandException(
          "OVER_CAPACITY", "newSize would reduce capacity below the current member count.");
    }
    jdbcClient
        .sql("update room_team set configured_size = :newSize where team_id = :teamId")
        .param("newSize", newSize)
        .param("teamId", teamId)
        .update();
    return new TeamResizedPayload(teamId, newSize);
  }

  /** {@code REASSIGN_PLAYER}, FR-TEAM-006/007. */
  PlayerReassignedPayload reassignPlayer(
      UUID roomId, UUID hostUserId, UUID playerId, UUID toTeamId) {
    rooms.requireLobby(requireHost(roomId, hostUserId));
    UUID fromTeamId =
        jdbcClient
            .sql(
                "select team_id from room_participant where room_id = :roomId"
                    + " and user_id = :playerId and role = 'PLAYER' and left_at is null")
            .param("roomId", roomId)
            .param("playerId", playerId)
            .query(UUID.class)
            .optional()
            .orElseThrow(
                () -> new RoomCommandException("PLAYER_NOT_FOUND", "No such player in this room."));
    int configuredSize =
        jdbcClient
            .sql(
                "select configured_size from room_team where team_id = :teamId and room_id ="
                    + " :roomId")
            .param("teamId", toTeamId)
            .param("roomId", roomId)
            .query(Integer.class)
            .optional()
            .orElseThrow(
                () ->
                    new RoomCommandException(
                        "VALIDATION_FAILED",
                        "No such team.",
                        Map.of("field", "toTeamId", "value", toTeamId.toString())));
    if (teamOccupancy(roomId, toTeamId) >= configuredSize) {
      throw new RoomCommandException("TEAM_FULL", "The target team has no available slot.");
    }
    jdbcClient
        .sql(
            "update room_participant set team_id = :teamId, ready = false"
                + " where room_id = :roomId and user_id = :playerId and left_at is null")
        .param("teamId", toTeamId)
        .param("roomId", roomId)
        .param("playerId", playerId)
        .update();
    return new PlayerReassignedPayload(playerId, fromTeamId, toTeamId, true);
  }

  /**
   * {@code ADD_AI_PLAYER} — wire-present, behaviour-absent (IR-12). Always fails once the sender is
   * confirmed to be the host; no v1 game may declare {@code capabilities.ai}. Returns rather than
   * throws so the caller — inside a {@code RoomMutation} lambda that must otherwise return a {@code
   * MutationOutcome} — can express "always fails" as a single {@code throw} statement.
   */
  RoomCommandException addAiPlayer(UUID roomId, UUID hostUserId) {
    requireHost(roomId, hostUserId);
    return new RoomCommandException(
        "AI_NOT_SUPPORTED", "AI players are not supported in this version of the platform.");
  }

  /** Returns the room's current state; throws {@code NOT_HOST} if {@code userId} is not it. */
  private String requireHost(UUID roomId, UUID userId) {
    RoomHostAndState row =
        jdbcClient
            .sql("select host_user_id, state from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(
                (rs, rowNum) ->
                    new RoomHostAndState(
                        rs.getObject("host_user_id", UUID.class), rs.getString("state")))
            .optional()
            .orElseThrow(() -> new IllegalStateException("room " + roomId + " vanished"));
    if (!userId.equals(row.hostUserId())) {
      throw new RoomCommandException("NOT_HOST", "Only the room's host may send this command.");
    }
    return row.state();
  }

  /** Validates {@code teamId} belongs to {@code roomId}; returns its {@code template_index}. */
  private int requireTeamInRoom(UUID roomId, UUID teamId) {
    return jdbcClient
        .sql("select template_index from room_team where team_id = :teamId and room_id = :roomId")
        .param("teamId", teamId)
        .param("roomId", roomId)
        .query(Integer.class)
        .optional()
        .orElseThrow(
            () ->
                new RoomCommandException(
                    "VALIDATION_FAILED",
                    "No such team.",
                    Map.of("field", "teamId", "value", teamId.toString())));
  }

  private long countTeamsForTemplate(UUID roomId, int templateIndex) {
    Long count =
        jdbcClient
            .sql(
                "select count(*) from room_team where room_id = :roomId"
                    + " and template_index = :templateIndex")
            .param("roomId", roomId)
            .param("templateIndex", templateIndex)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  /** FR-DBQ-002: counted among the participants of {@code roomId} only. */
  private long teamOccupancy(UUID roomId, UUID teamId) {
    Long count =
        jdbcClient
            .sql(
                "select count(*) from room_participant where room_id = :roomId"
                    + " and team_id = :teamId and role = 'PLAYER' and left_at is null")
            .param("roomId", roomId)
            .param("teamId", teamId)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  // ---------------------------------------------------------------------
  // Host commands (room-websocket.md, IMP-M2-06's own share: KICK_PLAYER, CHANGE_PARAMETERS,
  // START_GAME, TRANSFER_HOST).
  // ---------------------------------------------------------------------

  /**
   * {@code KICK_PLAYER}, FR-HOST-006. Valid in any room state, against a player, spectator, or AI
   * member — but no AI seat can ever exist (IR-12), so only the first two are reachable. The
   * removal itself is the same {@code left_at}/{@code leave_reason} shape every other departure
   * uses; {@code lost_announced_at} is stamped {@code now()} because this method only ever runs
   * under {@link RoomEventExecutor}'s lock, i.e. on the owning instance, which announces inline.
   */
  PlayerKickedPayload kickPlayer(UUID roomId, UUID hostUserId, UUID targetPlayerId) {
    String roomState = requireHost(roomId, hostUserId);
    TargetMember target =
        jdbcClient
            .sql(
                "select role, team_id from room_participant"
                    + " where room_id = :roomId and user_id = :targetPlayerId and left_at is null")
            .param("roomId", roomId)
            .param("targetPlayerId", targetPlayerId)
            .query(
                (rs, rowNum) ->
                    new TargetMember(rs.getString("role"), rs.getObject("team_id", UUID.class)))
            .optional()
            .orElseThrow(
                () -> new RoomCommandException("PLAYER_NOT_FOUND", "No such player in this room."));

    jdbcClient
        .sql(
            """
            update room_participant
               set left_at = now(), leave_reason = 'KICKED', lost_announced_at = now(),
                   grace_reservation_id = null, grace_expires_at = null
             where room_id = :roomId and user_id = :targetPlayerId and left_at is null
            """)
        .param("roomId", roomId)
        .param("targetPlayerId", targetPlayerId)
        .update();

    // game-contracts.md FR-IN-005 — only a human PLAYER's removal is a game-backend input; a
    // spectator was never represented to the game backend in the first place, and no session
    // exists for the outbox row to reference until IMP-M3-05 (data-model.md §4.2, session_id NOT
    // NULL) — the same conditional shape as room-lifecycle.md §3.3's grace-expiry insert.
    if ("IN_PROGRESS".equals(roomState) && "PLAYER".equals(target.role())) {
      outbox.insertPlayerLeftOutbox(roomId, targetPlayerId, target.teamId(), "kicked", true);
    }
    return new PlayerKickedPayload(targetPlayerId, hostUserId);
  }

  /**
   * {@code CHANGE_PARAMETERS}, FR-HOST-007. {@code input-validation.md}'s whitelist: every key
   * validated against the manifest's declared schema, then merged into the existing {@code
   * room.parameters} — a key omitted from the command retains its stored value — and the full
   * merged set persisted and returned for broadcast (FR-HOST-008 relies on this being the durable
   * copy, not something reconstructed from the manifest's defaults).
   */
  ParametersChangedPayload changeParameters(
      UUID roomId, UUID hostUserId, Map<String, JsonNode> requested) {
    rooms.requireLobby(requireHost(roomId, hostUserId));
    List<ParameterSchema> schemas = gameStart.parameterSchemasForRoom(roomId);
    Map<String, ParameterSchema> byKey = new java.util.LinkedHashMap<>();
    for (ParameterSchema schema : schemas) {
      byKey.put(schema.key(), schema);
    }

    RoomParametersRow row =
        jdbcClient
            .sql("select parameters from room where room_id = :roomId")
            .param("roomId", roomId)
            .query((rs, rowNum) -> new RoomParametersRow(rs.getString("parameters")))
            .single();
    Map<String, Object> merged =
        new java.util.LinkedHashMap<>(reads.readParameters(row.parametersJson()));

    for (var entry : requested.entrySet()) {
      String key = entry.getKey();
      ParameterSchema schema = byKey.get(key);
      if (schema == null) {
        throw new RoomCommandException(
            "UNKNOWN_PARAMETER", "No such parameter.", Map.of("field", key));
      }
      merged.put(key, validatedParameterValue(schema, entry.getValue()));
    }

    String mergedJson;
    try {
      mergedJson = objectMapper.writeValueAsString(merged);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize room.parameters", e);
    }
    jdbcClient
        .sql("update room set parameters = :parameters::jsonb where room_id = :roomId")
        .param("parameters", mergedJson)
        .param("roomId", roomId)
        .update();
    return new ParametersChangedPayload(merged);
  }

  /** {@code input-validation.md}'s whitelist table, one parameter at a time. */
  private Object validatedParameterValue(ParameterSchema schema, JsonNode value) {
    String key = schema.key();
    switch (schema.type()) {
      case "integer" -> {
        if (!value.isIntegralNumber()) {
          throw parameterTypeMismatch(key);
        }
        long v = value.asLong();
        if (v < schema.min() || v > schema.max()) {
          throw parameterOutOfRange(key);
        }
        return v;
      }
      case "decimal" -> {
        if (!value.isNumber()) {
          throw parameterTypeMismatch(key);
        }
        double v = value.asDouble();
        if (v < schema.min() || v > schema.max()) {
          throw parameterOutOfRange(key);
        }
        return v;
      }
      case "boolean" -> {
        if (!value.isBoolean()) {
          throw parameterTypeMismatch(key);
        }
        return value.asBoolean();
      }
      case "string" -> {
        if (!value.isTextual()) {
          throw parameterTypeMismatch(key);
        }
        String v = value.asText();
        if (schema.maxLength() != null && v.length() > schema.maxLength()) {
          throw parameterOutOfRange(key);
        }
        return v;
      }
      case "enum" -> {
        if (!value.isTextual()) {
          throw parameterTypeMismatch(key);
        }
        String v = value.asText();
        if (schema.options() == null || !schema.options().contains(v)) {
          throw parameterOutOfRange(key);
        }
        return v;
      }
      default -> throw new IllegalStateException("Unrecognised parameter type: " + schema.type());
    }
  }

  private static RoomCommandException parameterTypeMismatch(String key) {
    return new RoomCommandException(
        "PARAMETER_TYPE_MISMATCH", "Value does not match the declared type.", Map.of("field", key));
  }

  private static RoomCommandException parameterOutOfRange(String key) {
    return new RoomCommandException(
        "PARAMETER_OUT_OF_RANGE", "Value is outside the declared bounds.", Map.of("field", key));
  }

  /**
   * {@code START_GAME}, FR-HOST-009. FR-TEAM-009's three conditions, evaluated atomically under the
   * room lock; every unmet condition is collected before throwing, not just the first
   * (`START_CONDITIONS_NOT_MET`'s `details` "lists the unmet conditions").
   */
  void checkStartConditions(UUID roomId, UUID hostUserId) {
    rooms.requireLobby(requireHost(roomId, hostUserId));
    List<String> failed = new ArrayList<>();

    List<TeamTemplateInfo> templates = reads.teamTemplatesForRoom(roomId);
    List<TeamOccupancyRow> teamRows =
        jdbcClient
            .sql(
                """
                select t.team_id, t.template_index, coalesce(rc.occupied, 0) as occupied
                from room_team t
                left join (select team_id, count(*) as occupied from room_participant
                           where room_id = :roomId and role = 'PLAYER' and left_at is null
                           group by team_id) rc
                  on rc.team_id = t.team_id
                where t.room_id = :roomId
                """)
            .param("roomId", roomId)
            .query(
                (rs, rowNum) ->
                    new TeamOccupancyRow(
                        rs.getObject("team_id", UUID.class),
                        rs.getInt("template_index"),
                        rs.getLong("occupied")))
            .list();

    Map<Integer, Long> teamCountByTemplate = new java.util.LinkedHashMap<>();
    for (TeamOccupancyRow row : teamRows) {
      teamCountByTemplate.merge(row.templateIndex(), 1L, Long::sum);
      TeamTemplateInfo template = templates.get(row.templateIndex());
      if (row.occupied() < template.sizeMin() || row.occupied() > template.sizeMax()) {
        failed.add(
            "Team %s has %d player(s), needs between %d and %d."
                .formatted(row.teamId(), row.occupied(), template.sizeMin(), template.sizeMax()));
      }
    }
    for (int i = 0; i < templates.size(); i++) {
      TeamTemplateInfo template = templates.get(i);
      long count = teamCountByTemplate.getOrDefault(i, 0L);
      if (count < template.countMin() || count > template.countMax()) {
        failed.add(
            "Template \"%s\" has %d team(s), needs between %d and %d."
                .formatted(template.name(), count, template.countMin(), template.countMax()));
      }
    }

    List<String> notReady =
        jdbcClient
            .sql(
                """
                select au.username from room_participant rp join app_user au on au.user_id = rp.user_id
                where rp.room_id = :roomId and rp.role = 'PLAYER' and rp.left_at is null
                  and rp.user_id <> :hostUserId and rp.ready = false
                """)
            .param("roomId", roomId)
            .param("hostUserId", hostUserId)
            .query(String.class)
            .list();
    for (String username : notReady) {
      failed.add("Player \"%s\" is not ready.".formatted(username));
    }

    if (!failed.isEmpty()) {
      throw new RoomCommandException(
          "START_CONDITIONS_NOT_MET",
          "The team-validity conditions for starting the game are not met.",
          Map.of("failedConditions", failed));
    }
  }

  /**
   * {@code TRANSFER_HOST}, FR-XFER-004. The former host keeps their slot and player role — only
   * {@code room.host_user_id} moves.
   */
  HostTransferredPayload transferHost(UUID roomId, UUID hostUserId, UUID newHostId) {
    requireHost(roomId, hostUserId);
    Optional<String> targetRole =
        jdbcClient
            .sql(
                "select role from room_participant"
                    + " where room_id = :roomId and user_id = :newHostId and left_at is null")
            .param("roomId", roomId)
            .param("newHostId", newHostId)
            .query(String.class)
            .optional();
    if (targetRole.isEmpty() || !"PLAYER".equals(targetRole.get())) {
      throw new RoomCommandException(
          "TARGET_NOT_PLAYER", "The new host must be a current human player.");
    }
    jdbcClient
        .sql("update room set host_user_id = :newHostId where room_id = :roomId")
        .param("newHostId", newHostId)
        .param("roomId", roomId)
        .update();
    return new HostTransferredPayload(newHostId, hostUserId, "VOLUNTARY");
  }
}
