package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * {@code IMP-M2-01}'s own Done-when, end to end: create returns {@code 201} with {@code Location}
 * and the full resource (clause 1); the listing predicate (clause 2); invite-code resolution is
 * never a {@code 404} (clause 3); join is one endpoint by role and leave is the {@code DELETE}
 * (clause 4); a redirected claim that never resolves is a {@code 503} (clause 5).
 */
class RoomControllerIT extends PostgresIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private @Nullable ConfigurableApplicationContext secondContext;
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
    if (secondContext != null) {
      secondContext.close();
    }
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
        "--turnbase.room.spectator-capacity=2",
        "--spring.data.redis.url=" + redisUrl(),
        "--turnbase.rate-limit.auth.failures-before-lockout=1000000",
        "--turnbase.rate-limit.minting.guest.capacity=1000000",
        "--turnbase.rate-limit.minting.registration.capacity=1000000");
  }

  @Test
  void createReturnsTheFullResourceWithLocationAndInstanceHeader() throws Exception {
    Registered host = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);

    ResponseEntity<String> response =
        postWithCookie(
            "/api/rooms",
            host.cookie(),
            "{\"gameId\":\"%s\",\"visibility\":\"PUBLIC\",\"spectatingEnabled\":true}"
                .formatted(gameId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isNotNull();
    assertThat(response.getHeaders().getFirst("X-TurnBase-Room-Instance")).isNotBlank();
    Map<String, Object> body = parse(response);
    assertThat(body.get("hostUsername")).isEqualTo(host.username());
    assertThat(body.get("inviteCode")).isNotNull();
    assertThat(body.get("state")).isEqualTo("LOBBY");
    assertThat(numberOf(body, "playerCount")).isEqualTo(1);
    assertThat(numberOf(body, "playerCapacity")).isEqualTo(2);
  }

  @Test
  void inviteOnlyRoomIsAbsentFromTheListButResolvesByCode() throws Exception {
    Registered host = registerUser();
    Registered other = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    Map<String, Object> created =
        parse(
            postWithCookie(
                "/api/rooms",
                host.cookie(),
                "{\"gameId\":\"%s\",\"visibility\":\"INVITE_ONLY\"}".formatted(gameId)));
    String inviteCode = (String) created.get("inviteCode");

    Map<String, Object> listing = parse(getWithCookie("/api/rooms", other.cookie()));
    assertThat(roomsForGame(contentOf(listing), gameId)).isEmpty();

    Map<String, Object> resolved =
        parse(getWithCookie("/api/rooms?inviteCode=" + inviteCode, other.cookie()));
    List<Map<String, Object>> resolvedRows = contentOf(resolved);
    assertThat(resolvedRows).hasSize(1);
    assertThat(resolvedRows.get(0).get("roomId")).isEqualTo(created.get("roomId"));
  }

  @Test
  void aMemberOfAnInviteOnlyRoomReEntersItWithoutTheCode() throws Exception {
    Registered host = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createRoom(host, gameId, "INVITE_ONLY");

    ResponseEntity<String> rejoin =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants", host.cookie(), "{\"role\":\"PLAYER\"}");

    assertThat(rejoin.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ResponseEntity<String> read = getWithCookie("/api/rooms/" + roomId, host.cookie());
    assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(read).get("viewerRole")).isEqualTo("PLAYER");
  }

  @Test
  void anInviteeReadsAndJoinsAnInviteOnlyRoomWithoutTheCodeAndTheInvitationIsResolved()
      throws Exception {
    Registered host = registerUser();
    Registered invitee = registerUser();
    befriend(host, invitee);
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createRoom(host, gameId, "INVITE_ONLY");
    postWithCookie(
        "/api/rooms/" + roomId + "/invitations",
        host.cookie(),
        "{\"inviteeUsername\":\"%s\"}".formatted(invitee.username()));

    assertThat(getWithCookie("/api/rooms/" + roomId, invitee.cookie()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    ResponseEntity<String> join =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants", invitee.cookie(), "{\"role\":\"PLAYER\"}");

    assertThat(join.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Long pending =
        jdbcClient
            .sql("select count(*) from room_invitation where room_id = :r")
            .param("r", roomId)
            .query(Long.class)
            .single();
    assertThat(pending).isZero();
  }

  @Test
  void anotherCallerWithoutTheCodeStillGets404OnAnInviteOnlyRoom() throws Exception {
    Registered host = registerUser();
    Registered stranger = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createRoom(host, gameId, "INVITE_ONLY");

    assertThat(getWithCookie("/api/rooms/" + roomId, stranger.cookie()).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            postWithCookie(
                    "/api/rooms/" + roomId + "/participants",
                    stranger.cookie(),
                    "{\"role\":\"PLAYER\"}")
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            roomsForGame(contentOf(parse(getWithCookie("/api/rooms", stranger.cookie()))), gameId))
        .isEmpty();
  }

  private UUID createRoom(Registered host, UUID gameId, String visibility) throws Exception {
    ResponseEntity<String> response =
        postWithCookie(
            "/api/rooms",
            host.cookie(),
            "{\"gameId\":\"%s\",\"visibility\":\"%s\"}".formatted(gameId, visibility));
    return UUID.fromString((String) Objects.requireNonNull(parse(response).get("roomId")));
  }

  @Test
  void aWrongInviteCodeResolvesToAnEmptyListNeverA404() throws Exception {
    Registered viewer = registerUser();

    ResponseEntity<String> response =
        getWithCookie("/api/rooms?inviteCode=doesnotexist", viewer.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(contentOf(parse(response))).isEmpty();
  }

  @Test
  void friendsOnlyRoomIsVisibleOnlyToAFriend() throws Exception {
    Registered host = registerUser();
    Registered friend = registerUser();
    Registered stranger = registerUser();
    befriend(host, friend);
    UUID gameId = seedTwoPlayerGame(true, true);
    postWithCookie(
        "/api/rooms",
        host.cookie(),
        "{\"gameId\":\"%s\",\"visibility\":\"FRIENDS_ONLY\"}".formatted(gameId));

    List<Map<String, Object>> friendView =
        roomsForGame(contentOf(parse(getWithCookie("/api/rooms", friend.cookie()))), gameId);
    List<Map<String, Object>> strangerView =
        roomsForGame(contentOf(parse(getWithCookie("/api/rooms", stranger.cookie()))), gameId);

    assertThat(friendView).hasSize(1);
    assertThat(strangerView).isEmpty();
  }

  @Test
  void joinInsertsAParticipantAndLeaveIsIdempotent() throws Exception {
    Registered host = registerUser();
    Registered joiner = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createPublicRoom(host, gameId);

    ResponseEntity<String> joinResponse =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants", joiner.cookie(), "{\"role\":\"PLAYER\"}");
    assertThat(joinResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> joinBody = parse(joinResponse);
    Map<String, Object> room = mapOf(joinBody, "room");
    assertThat(numberOf(room, "playerCount")).isEqualTo(2);
    assertThat(joinBody.get("releasedRoom")).isNull();

    ResponseEntity<String> firstLeave =
        deleteWithCookie("/api/rooms/" + roomId + "/participants/me", joiner.cookie());
    ResponseEntity<String> secondLeave =
        deleteWithCookie("/api/rooms/" + roomId + "/participants/me", joiner.cookie());
    assertThat(firstLeave.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(secondLeave.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    Long remaining =
        jdbcClient
            .sql(
                "select count(*) from room_participant where room_id = :roomId and user_id ="
                    + " :userId and left_at is null")
            .param("roomId", roomId)
            .param("userId", joiner.userId())
            .query(Long.class)
            .single();
    assertThat(remaining).isZero();
  }

  @Test
  void joiningANewRoomReleasesTheCallersPreviousRoom() throws Exception {
    Registered host = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID firstRoomId = createPublicRoom(host, gameId);
    UUID secondRoomId = createPublicRoom(host, gameId);

    ResponseEntity<String> secondJoin =
        postWithCookie(
            "/api/rooms/" + secondRoomId + "/participants", host.cookie(), "{\"role\":\"PLAYER\"}");

    // Creating the second room already released the host from the first (FR-JOIN-007), so the
    // no-op re-join to the second room's own creator reports no further release.
    assertThat(secondJoin.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Long stillActiveInFirst =
        jdbcClient
            .sql(
                "select count(*) from room_participant where room_id = :roomId and user_id ="
                    + " :userId and left_at is null")
            .param("roomId", firstRoomId)
            .param("userId", host.userId())
            .query(Long.class)
            .single();
    assertThat(stillActiveInFirst).isZero();
  }

  /**
   * {@code IMP-M7-05} (MR-02): the three ways a Lobby's last human goes — socket loss, implicit
   * release by creating or joining another room, explicit leave — each close the room at once, and
   * no listed room is left with {@code playerCount} 0.
   */
  @Test
  void aLobbyWhoseLastHumanLeavesByAnyPathIsClosedAndUnlisted() throws Exception {
    UUID gameId = seedTwoPlayerGame(true, true);
    RoomService roomService = Objects.requireNonNull(context).getBean(RoomService.class);

    // Path 1 — socket loss: the host connected, then the socket dropped.
    Registered socketHost = registerUser();
    UUID socketRoom = createPublicRoom(socketHost, gameId);
    setConnectionCount(socketRoom, socketHost.userId(), 1);
    roomService.onDisconnect(socketRoom, socketHost.userId());

    // Path 2 — implicit release by creating another room.
    Registered creator = registerUser();
    UUID releasedByCreate = createPublicRoom(creator, gameId);
    UUID creatorSecond = createPublicRoom(creator, gameId);

    // Path 2b — implicit release by joining another room.
    Registered joiner = registerUser();
    Registered otherHost = registerUser();
    UUID releasedByJoin = createPublicRoom(joiner, gameId);
    UUID target = createPublicRoom(otherHost, gameId);
    postWithCookie(
        "/api/rooms/" + target + "/participants", joiner.cookie(), "{\"role\":\"PLAYER\"}");

    // Path 3 — explicit leave.
    Registered leaver = registerUser();
    UUID explicitRoom = createPublicRoom(leaver, gameId);
    deleteWithCookie("/api/rooms/" + explicitRoom + "/participants/me", leaver.cookie());

    for (UUID closed : List.of(socketRoom, releasedByCreate, releasedByJoin, explicitRoom)) {
      assertThat(
              jdbcClient
                  .sql("select state from room where room_id = :id")
                  .param("id", closed)
                  .query(String.class)
                  .single())
          .as("room %s", closed)
          .isEqualTo("CLOSED");
      assertThat(getWithCookie("/api/rooms/" + closed, otherHost.cookie()).getStatusCode())
          .as("GET %s", closed)
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
    List<Map<String, Object>> listed =
        roomsForGame(contentOf(parse(getWithCookie("/api/rooms", otherHost.cookie()))), gameId);
    List<Object> listedIds = listed.stream().map(r -> r.get("roomId")).toList();
    assertThat(listedIds)
        .contains(creatorSecond.toString())
        .doesNotContain(
            socketRoom.toString(),
            releasedByCreate.toString(),
            releasedByJoin.toString(),
            explicitRoom.toString());
    assertThat(listed).allSatisfy(r -> assertThat(numberOf(r, "playerCount")).isPositive());
  }

  /**
   * {@code IMP-M7-07}: {@code reservedSlot} names the caller's live grace reservation whatever the
   * filters, is {@code null} for everyone else, and is {@code null} once the grace is due.
   */
  @Test
  void viewerRoleNamesTheCallersMembershipAndAGetNeverEnrolsAnyone() throws Exception {
    UUID gameId = seedTwoPlayerGame(true, true);
    Registered host = registerUser();
    Registered player = registerUser();
    Registered spectator = registerUser();
    Registered visitor = registerUser();
    Registered leaver = registerUser();
    UUID roomId =
        UUID.fromString(
            (String)
                Objects.requireNonNull(
                    parse(
                            postWithCookie(
                                "/api/rooms",
                                host.cookie(),
                                ("{\"gameId\":\"%s\",\"visibility\":\"PUBLIC\","
                                        + "\"spectatingEnabled\":true}")
                                    .formatted(gameId)))
                        .get("roomId")));
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", player.cookie(), "{\"role\":\"PLAYER\"}");
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", spectator.cookie(), "{\"role\":\"SPECTATOR\"}");
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", leaver.cookie(), "{\"role\":\"SPECTATOR\"}");
    deleteWithCookie("/api/rooms/" + roomId + "/participants/me", leaver.cookie());
    // a grace-period reservation still counts as PLAYER
    jdbcClient
        .sql(
            "update room_participant set grace_reservation_id = gen_random_uuid(),"
                + " grace_expires_at = now() + interval '60 seconds'"
                + " where room_id = :id and user_id = :userId")
        .param("id", roomId)
        .param("userId", player.userId())
        .update();
    long versionBefore =
        numberOf(parse(getWithCookie("/api/rooms/" + roomId, host.cookie())), "version");
    long participantsBefore = participantRows(roomId);

    assertThat(viewerRole(roomId, host)).isEqualTo("PLAYER");
    assertThat(viewerRole(roomId, player)).isEqualTo("PLAYER");
    assertThat(viewerRole(roomId, spectator)).isEqualTo("SPECTATOR");
    assertThat(viewerRole(roomId, visitor)).isNull();
    assertThat(viewerRole(roomId, leaver)).isNull();

    Map<String, Object> after = parse(getWithCookie("/api/rooms/" + roomId, visitor.cookie()));
    assertThat(after).containsKey("viewerRole");
    assertThat(numberOf(after, "version")).isEqualTo((int) versionBefore);
    assertThat(participantRows(roomId)).isEqualTo(participantsBefore);

    Map<String, Object> listing = parse(getWithCookie("/api/rooms", host.cookie()));
    assertThat(roomsForGame(contentOf(listing), gameId)).isNotEmpty();
    assertThat(roomsForGame(contentOf(listing), gameId))
        .allSatisfy(row -> assertThat(row).doesNotContainKey("viewerRole"));
  }

  @org.jspecify.annotations.Nullable
  private String viewerRole(UUID roomId, Registered viewer) throws Exception {
    return (String) parse(getWithCookie("/api/rooms/" + roomId, viewer.cookie())).get("viewerRole");
  }

  private long participantRows(UUID roomId) {
    return jdbcClient
        .sql("select count(*) from room_participant where room_id = :id")
        .param("id", roomId)
        .query(Long.class)
        .single();
  }

  @Test
  void reservedSlotIsPopulatedForTheHolderRegardlessOfFiltersAndNullOtherwise() throws Exception {
    UUID gameId = seedTwoPlayerGame(true, true);
    Registered host = registerUser();
    Registered holder = registerUser();
    UUID roomId = createPublicRoom(host, gameId);
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", holder.cookie(), "{\"role\":\"PLAYER\"}");
    jdbcClient
        .sql("update room set state = 'IN_PROGRESS' where room_id = :id")
        .param("id", roomId)
        .update();
    jdbcClient
        .sql(
            "update room_participant set grace_reservation_id = gen_random_uuid(),"
                + " grace_expires_at = now() + interval '60 seconds'"
                + " where room_id = :id and user_id = :userId")
        .param("id", roomId)
        .param("userId", holder.userId())
        .update();

    for (String path : List.of("/api/rooms", "/api/rooms?search=no-such-game-xyz&status=LOBBY")) {
      Map<String, Object> body = parse(getWithCookie(path, holder.cookie()));
      @SuppressWarnings("unchecked")
      Map<String, Object> slot = (Map<String, Object>) body.get("reservedSlot");
      Objects.requireNonNull(slot, path);
      assertThat(slot.get("roomId")).isEqualTo(roomId.toString());
      assertThat(slot.get("gameName")).isNotNull();
      assertThat(numberOf(slot, "graceRemainingSeconds")).isBetween(1, 60);
    }

    Map<String, Object> hostBody = parse(getWithCookie("/api/rooms", host.cookie()));
    assertThat(hostBody).containsKey("reservedSlot");
    assertThat(hostBody.get("reservedSlot")).isNull();

    jdbcClient
        .sql(
            "update room_participant set grace_expires_at = now() - interval '1 second'"
                + " where room_id = :id and user_id = :userId")
        .param("id", roomId)
        .param("userId", holder.userId())
        .update();
    assertThat(parse(getWithCookie("/api/rooms", holder.cookie())).get("reservedSlot")).isNull();
  }

  /** {@code IMP-M7-05}: with another human present, the departing host's role transfers instead. */
  /**
   * FR-LIFE-004 / FR-EXPIRY-002 (MR-03): a confirmed leave during In Progress queues the same
   * {@code player_left} engine input grace expiry does, reason {@code left}; a spectator's leave
   * queues nothing.
   */
  @Test
  void leavingMidGameQueuesPlayerLeftForTheEngineButASpectatorDoesNot() throws Exception {
    UUID gameId = seedTwoPlayerGame(true, true);
    Registered host = registerUser();
    Registered guest = registerUser();
    Registered spectator = registerUser();
    UUID roomId = createPublicRoom(host, gameId);
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", guest.cookie(), "{\"role\":\"PLAYER\"}");
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", spectator.cookie(), "{\"role\":\"SPECTATOR\"}");
    UUID sessionId = UUID.randomUUID();
    jdbcClient
        .sql(
            "update room set state = 'IN_PROGRESS', active_session_id = :sessionId"
                + " where room_id = :roomId")
        .param("sessionId", sessionId)
        .param("roomId", roomId)
        .update();

    deleteWithCookie("/api/rooms/" + roomId + "/participants/me", spectator.cookie());
    assertThat(outboxPayloads(sessionId)).isEmpty();

    deleteWithCookie("/api/rooms/" + roomId + "/participants/me", guest.cookie());
    List<String> payloads = outboxPayloads(sessionId);
    assertThat(payloads).hasSize(1);
    assertThat(payloads.get(0)).contains("\"player_left\"", "\"left\"", guest.userId().toString());
  }

  private List<String> outboxPayloads(UUID sessionId) {
    return jdbcClient
        .sql("select payload::text from game_input_outbox where session_id = :sessionId")
        .param("sessionId", sessionId)
        .query(String.class)
        .list();
  }

  @Test
  void aHostLeavingByAnyPathWithAnotherHumanPresentTransfersTheRole() throws Exception {
    UUID gameId = seedTwoPlayerGame(true, true);
    RoomService roomService = Objects.requireNonNull(context).getBean(RoomService.class);

    Registered host = registerUser();
    Registered guest = registerUser();
    UUID socketRoom = createPublicRoom(host, gameId);
    postWithCookie(
        "/api/rooms/" + socketRoom + "/participants", guest.cookie(), "{\"role\":\"PLAYER\"}");
    setConnectionCount(socketRoom, host.userId(), 1);
    setConnectionCount(socketRoom, guest.userId(), 1);
    roomService.onDisconnect(socketRoom, host.userId());

    assertThat(roomRow(socketRoom).get("state")).isEqualTo("LOBBY");
    assertThat(roomRow(socketRoom).get("host_user_id")).isEqualTo(guest.userId());

    Registered host2 = registerUser();
    Registered guest2 = registerUser();
    UUID implicitRoom = createPublicRoom(host2, gameId);
    postWithCookie(
        "/api/rooms/" + implicitRoom + "/participants", guest2.cookie(), "{\"role\":\"PLAYER\"}");
    createPublicRoom(host2, gameId);

    assertThat(roomRow(implicitRoom).get("state")).isEqualTo("LOBBY");
    assertThat(roomRow(implicitRoom).get("host_user_id")).isEqualTo(guest2.userId());
  }

  private void setConnectionCount(UUID roomId, UUID userId, int count) {
    jdbcClient
        .sql(
            "update room_participant set connection_count = :count"
                + " where room_id = :roomId and user_id = :userId")
        .param("count", count)
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
  }

  private Map<String, Object> roomRow(UUID roomId) {
    return jdbcClient
        .sql("select state, host_user_id from room where room_id = :id")
        .param("id", roomId)
        .query()
        .singleRow();
  }

  /**
   * {@code IMP-M2-09}, {@code IMP-GATE-53} (FR-XFER-002): the host is the room's only human player,
   * so their own leave closes the room immediately with {@code NO_PLAYERS_REMAINING} rather than an
   * ordinary {@code PLAYER_LEFT}.
   */
  @Test
  void aSoleHostLeavingClosesTheRoomImmediately() throws Exception {
    Registered host = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createPublicRoom(host, gameId);

    ResponseEntity<String> leave =
        deleteWithCookie("/api/rooms/" + roomId + "/participants/me", host.cookie());

    assertThat(leave.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    Map<String, Object> room =
        jdbcClient
            .sql("select state, closed_reason, active_session_id from room where room_id = :roomId")
            .param("roomId", roomId)
            .query()
            .singleRow();
    assertThat(room.get("state")).isEqualTo("CLOSED");
    assertThat(room.get("closed_reason")).isEqualTo("NO_PLAYERS_REMAINING");
    assertThat(room.get("active_session_id")).isNull();
  }

  /**
   * {@code IMP-M8-04} (RR-04, {@code 09203c03}): three members' sockets close within one scheduling
   * instant. The leaves are serialised per room, so the host's departure (players behind it) is
   * committed first and the last one out is not the host — the room closes anyway.
   */
  @Test
  void membersLeavingTogetherCloseTheRoomAndNeverLeaveItListedEmpty() throws Exception {
    UUID gameId = seedTwoPlayerGame(true, true);
    RoomService roomService = Objects.requireNonNull(context).getBean(RoomService.class);
    for (int attempt = 0; attempt < 5; attempt++) {
      Registered host = registerUser();
      Registered b = registerUser();
      Registered c = registerUser();
      UUID roomId = createPublicRoom(host, gameId);
      postWithCookie("/api/rooms/" + roomId + "/participants", b.cookie(), "{\"role\":\"PLAYER\"}");
      postWithCookie("/api/rooms/" + roomId + "/participants", c.cookie(), "{\"role\":\"PLAYER\"}");
      for (Registered r : List.of(host, b, c)) {
        setConnectionCount(roomId, r.userId(), 1);
      }

      List<java.util.concurrent.CompletableFuture<Void>> leaves =
          List.of(host, b, c).stream()
              .map(
                  r ->
                      java.util.concurrent.CompletableFuture.runAsync(
                          () -> roomService.onDisconnect(roomId, r.userId())))
              .toList();
      leaves.forEach(java.util.concurrent.CompletableFuture::join);

      Map<String, Object> room =
          jdbcClient
              .sql("select state, closed_reason from room where room_id = :id")
              .param("id", roomId)
              .query()
              .singleRow();
      assertThat(room.get("state")).isEqualTo("CLOSED");
      assertThat(room.get("closed_reason")).isEqualTo("NO_PLAYERS_REMAINING");
    }
    ResponseEntity<String> list = getWithCookie("/api/rooms?size=100", registerUser().cookie());
    assertThat(contentOf(parse(list))).noneMatch(row -> numberOf(row, "playerCount") == 0);
  }

  /**
   * The same outcome when the host's departure is committed before the others', deterministically.
   */
  @Test
  void theLastPlayerOutClosesTheRoomEvenWhenTheHostLeftFirst() throws Exception {
    UUID gameId = seedTwoPlayerGame(true, true);
    Registered host = registerUser();
    Registered guest = registerUser();
    UUID roomId = createPublicRoom(host, gameId);
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", guest.cookie(), "{\"role\":\"PLAYER\"}");

    deleteWithCookie("/api/rooms/" + roomId + "/participants/me", host.cookie());
    // The transfer to the guest has not run in the race this models; make the guest the leaver.
    jdbcClient
        .sql("update room set host_user_id = :h where room_id = :id")
        .param("h", host.userId())
        .param("id", roomId)
        .update();
    deleteWithCookie("/api/rooms/" + roomId + "/participants/me", guest.cookie());

    assertThat(roomRow(roomId).get("state")).isEqualTo("CLOSED");
  }

  /**
   * {@code IMP-M8-04} (RR-04, {@code fe4d83bc}): both players are in grace when the game ends, so
   * the room returns to Lobby with members long gone. Outstanding reservations resolve as
   * departures (FR-EXPIRY-006) and the room closes in the same step. Each disconnected player was
   * given the full configured grace period, not a shorter one for the pair.
   */
  @Test
  void aGameEndingWithEveryPlayerInGraceClosesTheRoom() throws Exception {
    UUID gameId = seedTwoPlayerGame(true, true);
    RoomService roomService = Objects.requireNonNull(context).getBean(RoomService.class);
    Registered host = registerUser();
    Registered guest = registerUser();
    UUID roomId = createPublicRoom(host, gameId);
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", guest.cookie(), "{\"role\":\"PLAYER\"}");
    UUID sessionId = UUID.randomUUID();
    jdbcClient
        .sql("update room set state = 'IN_PROGRESS', active_session_id = :s where room_id = :id")
        .param("s", sessionId)
        .param("id", roomId)
        .update();
    setConnectionCount(roomId, host.userId(), 1);
    setConnectionCount(roomId, guest.userId(), 1);
    roomService.onDisconnect(roomId, host.userId());
    roomService.onDisconnect(roomId, guest.userId());

    List<Double> graceSeconds =
        jdbcClient
            .sql(
                "select extract(epoch from grace_expires_at - now())::float8 from room_participant"
                    + " where room_id = :id and grace_expires_at is not null")
            .param("id", roomId)
            .query(Double.class)
            .list();
    assertThat(graceSeconds).hasSize(2).allSatisfy(s -> assertThat(s).isBetween(50.0, 61.0));

    Objects.requireNonNull(context)
        .getBean(RoomSessionDetacher.class)
        .detach(roomId, sessionId, "GAME_OVER", "LOBBY");

    Map<String, Object> room =
        jdbcClient
            .sql("select state, closed_reason from room where room_id = :id")
            .param("id", roomId)
            .query()
            .singleRow();
    assertThat(room.get("state")).isEqualTo("CLOSED");
    assertThat(room.get("closed_reason")).isEqualTo("NO_PLAYERS_REMAINING");
  }

  /** {@code IMP-GATE-50}: a departed row is reused, never left to collide with a fresh insert. */
  @Test
  void rejoiningARoomAfterLeavingReusesTheParticipantRowInsteadOfCollidingOnItsKey()
      throws Exception {
    Registered host = registerUser();
    Registered joiner = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createPublicRoom(host, gameId);
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", joiner.cookie(), "{\"role\":\"PLAYER\"}");
    deleteWithCookie("/api/rooms/" + roomId + "/participants/me", joiner.cookie());

    ResponseEntity<String> rejoin =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants", joiner.cookie(), "{\"role\":\"PLAYER\"}");

    assertThat(rejoin.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> room = mapOf(parse(rejoin), "room");
    assertThat(numberOf(room, "playerCount")).isEqualTo(2);
    Map<String, Object> row =
        jdbcClient
            .sql(
                "select left_at, leave_reason from room_participant"
                    + " where room_id = :roomId and user_id = :userId")
            .param("roomId", roomId)
            .param("userId", joiner.userId())
            .query()
            .singleRow();
    assertThat(row.get("left_at")).isNull();
    assertThat(row.get("leave_reason")).isNull();
  }

  /**
   * {@code IMP-M2-08} Done-when: a rejoin after the grace period has expired is an ordinary join,
   * subject to every capacity rule — not a restore of the old reservation.
   */
  @Test
  void aRejoinAfterGraceExpiryIsAnOrdinaryJoinSubjectToCapacity() throws Exception {
    Registered host = registerUser();
    Registered disconnected = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createPublicRoom(host, gameId);
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", disconnected.cookie(), "{\"role\":\"PLAYER\"}");
    jdbcClient
        .sql("update room set state = 'IN_PROGRESS' where room_id = :roomId")
        .param("roomId", roomId)
        .update();
    // Simulate the sweep having already resolved this player's grace reservation (room-lifecycle.md
    // §3.2/§3.3): slot released, no reservation left.
    jdbcClient
        .sql(
            "update room_participant set left_at = now(), leave_reason = 'GRACE_EXPIRED',"
                + " lost_announced_at = now(), grace_reservation_id = null, grace_expires_at = null"
                + " where room_id = :roomId and user_id = :userId")
        .param("roomId", roomId)
        .param("userId", disconnected.userId())
        .update();

    ResponseEntity<String> rejoin =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants",
            disconnected.cookie(),
            "{\"role\":\"PLAYER\"}");

    assertThat(rejoin.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> row =
        jdbcClient
            .sql(
                "select left_at, join_order from room_participant"
                    + " where room_id = :roomId and user_id = :userId")
            .param("roomId", roomId)
            .param("userId", disconnected.userId())
            .query()
            .singleRow();
    assertThat(row.get("left_at")).isNull();
    // A genuinely new join, not a restore: join_order advanced past the original join (host=1,
    // this player's first join=2, this rejoin=3).
    assertThat(((Number) Objects.requireNonNull(row.get("join_order"))).longValue()).isEqualTo(3L);
  }

  /** The capacity-rule half of the same clause: no free, unblocked slot means the rejoin fails. */
  @Test
  void aRejoinAfterGraceExpiryStillFailsWhenNoSlotIsFree() throws Exception {
    Registered host = registerUser();
    Registered disconnected = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createPublicRoom(host, gameId);
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", disconnected.cookie(), "{\"role\":\"PLAYER\"}");
    jdbcClient
        .sql("update room set state = 'IN_PROGRESS' where room_id = :roomId")
        .param("roomId", roomId)
        .update();
    jdbcClient
        .sql(
            "update room_participant set left_at = now(), leave_reason = 'GRACE_EXPIRED',"
                + " lost_announced_at = now(), grace_reservation_id = null, grace_expires_at = null"
                + " where room_id = :roomId and user_id = :userId")
        .param("roomId", roomId)
        .param("userId", disconnected.userId())
        .update();
    // The vacated slot is immediately taken by an unrelated third player before the disconnected
    // one returns — capacity applies exactly as it would to any other late join.
    Registered third = registerUser();
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", third.cookie(), "{\"role\":\"PLAYER\"}");

    ResponseEntity<String> rejoin =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants",
            disconnected.cookie(),
            "{\"role\":\"PLAYER\"}");

    assertThat(rejoin.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void aFullRoomRejectsAFurtherPlayerJoinWith422() throws Exception {
    Registered host = registerUser();
    Registered second = registerUser();
    Registered third = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createPublicRoom(host, gameId);
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", second.cookie(), "{\"role\":\"PLAYER\"}");

    ResponseEntity<String> thirdJoin =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants", third.cookie(), "{\"role\":\"PLAYER\"}");

    assertThat(thirdJoin.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void aSecondInstanceRedirectedForeverEventuallyReturns503() throws Exception {
    Registered host = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createPublicRoom(host, gameId);

    ConfigurableApplicationContext bootedSecond = boot();
    secondContext = bootedSecond;
    int secondPort =
        Integer.parseInt(bootedSecond.getEnvironment().getProperty("local.server.port"));
    String secondBaseUrl = "http://127.0.0.1:" + secondPort;
    Registered joiner = registerUser();

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, joiner.cookie());
    ResponseEntity<String> response =
        restTemplate.exchange(
            secondBaseUrl + "/api/rooms/" + roomId + "/participants",
            HttpMethod.POST,
            new HttpEntity<>("{\"role\":\"PLAYER\"}", headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
  }

  private void befriend(Registered a, Registered b) throws Exception {
    ResponseEntity<String> sendResponse =
        postWithCookie(
            "/api/friend-requests",
            a.cookie(),
            "{\"recipientUsername\":\"%s\"}".formatted(b.username()));
    Map<String, Object> friendRequest = mapOf(parse(sendResponse), "friendRequest");
    postWithCookie(
        "/api/friend-requests/" + friendRequest.get("requestId") + "/accept", b.cookie(), "{}");
  }

  private UUID createPublicRoom(Registered host, UUID gameId) throws Exception {
    ResponseEntity<String> response =
        postWithCookie(
            "/api/rooms",
            host.cookie(),
            "{\"gameId\":\"%s\",\"visibility\":\"PUBLIC\"}".formatted(gameId));
    return UUID.fromString((String) Objects.requireNonNull(parse(response).get("roomId")));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> contentOf(Map<String, Object> envelope) {
    return (List<Map<String, Object>>) Objects.requireNonNull(envelope.get("content"));
  }

  /**
   * The Postgres container is shared across every test method in this class (Testcontainers
   * restarts it per class, not per method), so an earlier test's rooms are still listable.
   * Narrowing to the game this test seeded keeps each assertion about only its own rooms.
   */
  private static List<Map<String, Object>> roomsForGame(
      List<Map<String, Object>> rows, UUID gameId) {
    return rows.stream().filter(row -> gameId.toString().equals(row.get("gameId"))).toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapOf(Map<String, Object> body, String key) {
    return (Map<String, Object>) Objects.requireNonNull(body.get(key));
  }

  private static int numberOf(Map<String, Object> body, String key) {
    return ((Number) Objects.requireNonNull(body.get(key))).intValue();
  }

  /** Two teams of one, so player capacity is exactly two — deterministic for capacity tests. */
  private UUID seedTwoPlayerGame(boolean supportsSpectators, boolean supportsMidGameJoin) {
    UUID gameId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, title, short_description, badge, listed)"
                + " values (:gameId, 'Test Game', 'A game for tests', 'OFFICIAL', true)")
        .param("gameId", gameId)
        .update();
    jdbcClient
        .sql(
            """
            insert into game_version (version_id, game_id, version, status, manifest,
                declared_size_bytes, upload_expires_at, min_players, max_players,
                supports_ai, supports_spectators, supports_mid_game_join, activated_at)
            values (:versionId, :gameId, '1.0.0', 'ACTIVE', :manifest::jsonb,
                1024, now(), 2, 2, false, :supportsSpectators, :supportsMidGameJoin, now())
            """)
        .param("versionId", versionId)
        .param("gameId", gameId)
        .param(
            "manifest",
            """
            {"teamTemplates":[
              {"name":"A","size":{"min":1,"max":1},"count":{"min":1,"max":1}},
              {"name":"B","size":{"min":1,"max":1},"count":{"min":1,"max":1}}
            ]}
            """)
        .param("supportsSpectators", supportsSpectators)
        .param("supportsMidGameJoin", supportsMidGameJoin)
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
    return withBody(path, HttpMethod.POST, null, body);
  }

  private ResponseEntity<String> postWithCookie(String path, String cookie, String body) {
    return withBody(path, HttpMethod.POST, cookie, body);
  }

  private ResponseEntity<String> deleteWithCookie(String path, String cookie) {
    return withBody(path, HttpMethod.DELETE, cookie, null);
  }

  private ResponseEntity<String> withBody(
      String path, HttpMethod method, @Nullable String cookie, @Nullable String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (cookie != null) {
      headers.add(HttpHeaders.COOKIE, cookie);
    }
    HttpEntity<String> request = new HttpEntity<>(body, headers);
    return restTemplate.exchange(baseUrl + path, method, request, String.class);
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
}
