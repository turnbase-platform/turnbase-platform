package io.turnbase.platform.room;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.PageResponse;
import io.turnbase.platform.common.SortFieldValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code room-rest-api.md} §1's five endpoints — room membership only. */
@RestController
class RoomController {

  private static final String INSTANCE_HEADER = "X-TurnBase-Room-Instance";

  private static final Set<String> LIST_SORT_FIELDS =
      Set.of("gameName", "minPlayers", "maxPlayers", "playerCount", "status");

  private final RoomService roomService;
  private final SortFieldValidator sortFieldValidator;

  RoomController(RoomService roomService, SortFieldValidator sortFieldValidator) {
    this.roomService = roomService;
    this.sortFieldValidator = sortFieldValidator;
  }

  /** {@code room-rest-api.md} §2. */
  @PostMapping("/api/rooms")
  ResponseEntity<RoomResponse> create(
      @Valid @RequestBody CreateRoomRequest request, HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    RoomService.ClaimedResult<RoomResponse> result =
        roomService.create(principal.userId(), request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/rooms/" + result.body().roomId()))
        .header(INSTANCE_HEADER, result.podInstanceHeader())
        .body(result.body());
  }

  /** {@code room-rest-api.md} §3, §3.1. */
  @GetMapping("/api/rooms")
  ResponseEntity<PageResponse<?>> list(
      @RequestParam(required = false) @Nullable String inviteCode,
      @RequestParam(required = false) @Nullable String search,
      @RequestParam(required = false) @Nullable List<String> status,
      @RequestParam(required = false) @Nullable Integer minPlayers,
      @RequestParam(required = false) @Nullable Integer maxPlayers,
      @RequestParam(required = false) @Nullable Integer playerCountMin,
      @RequestParam(required = false) @Nullable Integer playerCountMax,
      Pageable pageable,
      HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);

    if (inviteCode != null) {
      RoomService.InviteCodeResult result =
          roomService.resolveInviteCode(inviteCode, principal.userId());
      ResponseEntity.BodyBuilder response = ResponseEntity.ok();
      if (result.podInstanceHeader() != null) {
        response = response.header(INSTANCE_HEADER, result.podInstanceHeader());
      }
      PageResponse<?> envelope = result.body();
      return response.body(envelope);
    }

    sortFieldValidator.validate(pageable.getSort(), LIST_SORT_FIELDS);
    RoomListFilter filter =
        new RoomListFilter(
            search,
            status,
            minPlayers,
            maxPlayers,
            playerCountMin,
            playerCountMax,
            pageable.getSort(),
            pageable.getPageSize(),
            (int) pageable.getOffset());
    PageResponse<?> envelope = roomService.list(principal.userId(), filter, pageable);
    return ResponseEntity.ok(envelope);
  }

  /** {@code room-rest-api.md} §4. */
  @GetMapping("/api/rooms/{roomId}")
  ResponseEntity<RoomResponse> get(@PathVariable UUID roomId, HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    RoomService.ClaimedResult<RoomResponse> result = roomService.get(roomId, principal.userId());
    return ResponseEntity.ok()
        .header(INSTANCE_HEADER, result.podInstanceHeader())
        .body(result.body());
  }

  /** {@code room-rest-api.md} §5. */
  @PostMapping("/api/rooms/{roomId}/participants")
  ResponseEntity<JoinRoomResponse> join(
      @PathVariable UUID roomId,
      @Valid @RequestBody JoinRoomRequest request,
      @RequestParam(required = false) @Nullable String inviteCode,
      HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    RoomService.ClaimedResult<JoinRoomResponse> result =
        roomService.join(principal.userId(), roomId, request, inviteCode);
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(INSTANCE_HEADER, result.podInstanceHeader())
        .body(result.body());
  }

  /** {@code room-rest-api.md} §6. */
  @DeleteMapping("/api/rooms/{roomId}/participants/me")
  ResponseEntity<Void> leave(@PathVariable UUID roomId, HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    roomService.leave(principal.userId(), roomId);
    return ResponseEntity.noContent().build();
  }

  private static AuthenticatedUser principalOf(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    if (principal instanceof AuthenticatedUser authenticatedUser) {
      return authenticatedUser;
    }
    throw new IllegalStateException(
        "SessionAuthenticationInterceptor must reject an unauthenticated request to this endpoint"
            + " before its handler runs");
  }
}
