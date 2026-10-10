package io.turnbase.platform.upload;

import io.turnbase.bundle.VersionOrdering;
import io.turnbase.platform.common.CheckError;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Direct SQL against {@code game} and {@code game_version} — both owned by the not-yet-built {@code
 * catalog}/{@code game} features (their packages are empty {@code package-info.java} markers,
 * IMP-M4-01/M4-05). Follows {@code room.RoomInvitationRepository}'s established precedent of
 * direct-SQL-against-another-feature's-table rather than a cross-module API that does not exist
 * yet.
 */
@Repository
class GameVersionRepository {

  private final JdbcClient jdbcClient;
  private final JsonMapper objectMapper;

  GameVersionRepository(JdbcClient jdbcClient, JsonMapper objectMapper) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
  }

  // ---------------------------------------------------------------- game row

  Optional<GameRow> findGame(UUID gameId) {
    return jdbcClient
        .sql(
            "select game_id, owner_user_id, badge, listed, active_version_id from game where game_id = :gameId")
        .param("gameId", gameId)
        .query(
            (rs, rowNum) ->
                new GameRow(
                    rs.getObject("game_id", UUID.class),
                    rs.getObject("owner_user_id", UUID.class),
                    rs.getString("badge"),
                    rs.getBoolean("listed"),
                    rs.getObject("active_version_id", UUID.class)))
        .optional();
  }

  /**
   * {@code bundle-format.md} §5.3 — a version's unpacked tree is servable exactly when its status
   * is {@code ACTIVE} or {@code SUPERSEDED} (the latter for FR-ASSET-007's version pinning: a room
   * mid-game must keep serving the version it started with) and its bundle has not been purged.
   */
  boolean isServable(UUID gameId, String version) {
    return jdbcClient
        .sql(
            "select 1 from game_version where game_id = :gameId and version = :version"
                + " and status in ('ACTIVE', 'SUPERSEDED') and bundle_purged_at is null")
        .param("gameId", gameId)
        .param("version", version)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /**
   * For A2's advisory ordering check — the three integers of the current {@code ACTIVE} row, if one
   * exists.
   */
  Optional<VersionOrdering.Version> findActiveVersionOrder(UUID gameId) {
    return jdbcClient
        .sql(
            "select version_major, version_minor, version_patch from game_version"
                + " where game_id = :gameId and status = 'ACTIVE'")
        .param("gameId", gameId)
        .query(
            (rs, rowNum) ->
                new VersionOrdering.Version(
                    rs.getInt("version_major"),
                    rs.getInt("version_minor"),
                    rs.getInt("version_patch")))
        .optional();
  }

  // ---------------------------------------------------------------- slot lookup

  Optional<GameVersionRow> findByGameAndVersion(UUID gameId, String version) {
    return jdbcClient
        .sql(ROW_SELECT + " where game_id = :gameId and version = :version")
        .param("gameId", gameId)
        .param("version", version)
        .query(this::mapRow)
        .optional();
  }

  Optional<GameVersionRow> findByVersionId(UUID versionId) {
    return jdbcClient
        .sql(ROW_SELECT + " where version_id = :versionId")
        .param("versionId", versionId)
        .query(this::mapRow)
        .optional();
  }

  // ---------------------------------------------------------------- Phase A: slot creation

  UUID insertPendingSlot(
      UUID gameId, String version, long declaredSizeBytes, Instant uploadExpiresAt) {
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            insert into game_version
              (version_id, game_id, version, status, bundle_object_key, declared_size_bytes,
               upload_expires_at)
            values (:versionId, :gameId, :version, 'PENDING', :bundleObjectKey, :declaredSizeBytes,
                    :uploadExpiresAt)
            """)
        .param("versionId", versionId)
        .param("gameId", gameId)
        .param("version", version)
        .param("bundleObjectKey", ObjectKeys.bundle(gameId, version))
        .param("declaredSizeBytes", declaredSizeBytes)
        .param("uploadExpiresAt", Timestamp.from(uploadExpiresAt))
        .update();
    return versionId;
  }

  /**
   * {@code bundle-format.md} §7.4: reuses a {@code FAILED}/{@code EXPIRED} row rather than
   * inserting a new one, so {@code game_version_key} never sees a conflict.
   */
  void reclaimSlot(UUID versionId, long declaredSizeBytes, Instant uploadExpiresAt) {
    jdbcClient
        .sql(
            """
            update game_version
               set status = 'PENDING',
                   declared_size_bytes = :declaredSizeBytes,
                   upload_expires_at = :uploadExpiresAt,
                   stored_size_bytes = null,
                   validation_errors = null,
                   validation_warnings = null,
                   claimed_by = null,
                   claimed_until = null,
                   manifest = null,
                   gdk_api_version = null,
                   bundle_purged_at = null
             where version_id = :versionId
            """)
        .param("versionId", versionId)
        .param("declaredSizeBytes", declaredSizeBytes)
        .param("uploadExpiresAt", Timestamp.from(uploadExpiresAt))
        .update();
  }

  // ---------------------------------------------------------------- Phase B: confirm

  /**
   * CAS {@code PENDING} (unexpired) → {@code PROCESSING}, stamping the lease. {@code false} means
   * the row was not in a confirmable state (already confirmed, or expired).
   */
  boolean claimForProcessing(UUID versionId, String claimedBy, Instant claimedUntil) {
    int updated =
        jdbcClient
            .sql(
                """
                update game_version
                   set status = 'PROCESSING', claimed_by = :claimedBy, claimed_until = :claimedUntil
                 where version_id = :versionId and status = 'PENDING' and upload_expires_at > now()
                """)
            .param("versionId", versionId)
            .param("claimedBy", claimedBy)
            .param("claimedUntil", Timestamp.from(claimedUntil))
            .update();
    return updated == 1;
  }

  void updateStoredSize(UUID versionId, long storedSizeBytes) {
    jdbcClient
        .sql("update game_version set stored_size_bytes = :size where version_id = :versionId")
        .param("size", storedSizeBytes)
        .param("versionId", versionId)
        .update();
  }

  // ---------------------------------------------------------------- Phases C-F outcome

  void markFailed(UUID versionId, List<CheckError> errors, List<CheckError> warnings) {
    jdbcClient
        .sql(
            """
            update game_version
               set status = 'FAILED', validation_errors = :errors::jsonb,
                   validation_warnings = :warnings::jsonb, claimed_by = null, claimed_until = null
             where version_id = :versionId
            """)
        .param("versionId", versionId)
        .param("errors", jsonOf(errors))
        .param("warnings", jsonOf(warnings))
        .update();
  }

  /**
   * An unexpired-window-passed {@code PENDING} row the sweep has not yet caught, moved to {@code
   * EXPIRED} inline so a confirm arriving just after {@code upload_expires_at} reads correctly
   * without waiting for the next sweep tick.
   */
  void markExpired(UUID versionId) {
    jdbcClient
        .sql(
            "update game_version set status = 'EXPIRED' where version_id = :versionId and status = 'PENDING'")
        .param("versionId", versionId)
        .update();
  }

  void markBundlePurged(UUID versionId) {
    jdbcClient
        .sql("update game_version set bundle_purged_at = now() where version_id = :versionId")
        .param("versionId", versionId)
        .update();
  }

  // ---------------------------------------------------------------- Phase G: activation

  /**
   * The §5.4 activation transaction, one row lock on {@code game}. Returns {@code empty} when the
   * authoritative ordering re-check fails (caller marks the version {@code FAILED} with {@code
   * version.ordering}), matching bundle-format.md §3.2 Phase G's only reportable failure besides
   * {@code pipeline.interrupted}.
   */
  @Transactional
  Optional<ActivationOutcome> activate(ActivationRequest request) {
    GameRow game =
        jdbcClient
            .sql(
                "select game_id, owner_user_id, badge, listed, active_version_id from game"
                    + " where game_id = :gameId for update")
            .param("gameId", request.gameId())
            .query(
                (rs, rowNum) ->
                    new GameRow(
                        rs.getObject("game_id", UUID.class),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getString("badge"),
                        rs.getBoolean("listed"),
                        rs.getObject("active_version_id", UUID.class)))
            .single();

    VersionOrdering.Version candidate = VersionOrdering.Version.parse(request.version());
    boolean firstActivation = game.activeVersionId() == null;
    if (!firstActivation) {
      Optional<VersionOrdering.Version> currentActive = findActiveVersionOrder(request.gameId());
      if (currentActive.isPresent()
          && VersionOrdering.compare(candidate, currentActive.get()) <= 0) {
        return Optional.empty();
      }
    }

    Instant now = Instant.now();
    if (!firstActivation) {
      jdbcClient
          .sql(
              "update game_version set status = 'SUPERSEDED', superseded_at = :now"
                  + " where version_id = :versionId")
          .param("now", Timestamp.from(now))
          .param("versionId", game.activeVersionId())
          .update();
    }

    jdbcClient
        .sql(
            """
            update game_version
               set status = 'ACTIVE', activated_at = :now, manifest = :manifest::jsonb,
                   gdk_api_version = :gdkApiVersion, min_players = :minPlayers,
                   max_players = :maxPlayers, supports_ai = :supportsAi,
                   supports_spectators = :supportsSpectators,
                   supports_mid_game_join = :supportsMidGameJoin,
                   claimed_by = null, claimed_until = null
             where version_id = :versionId
            """)
        .param("now", Timestamp.from(now))
        .param("manifest", request.manifestJson())
        .param("gdkApiVersion", request.gdkApiVersion())
        .param("minPlayers", request.minPlayers())
        .param("maxPlayers", request.maxPlayers())
        .param("supportsAi", request.supportsAi())
        .param("supportsSpectators", request.supportsSpectators())
        .param("supportsMidGameJoin", request.supportsMidGameJoin())
        .param("versionId", request.versionId())
        .update();

    // The badge transition is VERIFIED -> UNVERIFIED only. OFFICIAL and UNVERIFIED are untouched
    // (data-model-catalog.md §2, bundle-format.md §5.4) — an unconditional clear violates
    // game_official_ownerless_ck for every Official game, which is every MVP game under ADR-0006.
    if ("VERIFIED".equals(game.badge())) {
      jdbcClient
          .sql("update game set badge = 'UNVERIFIED' where game_id = :gameId")
          .param("gameId", request.gameId())
          .update();
    }

    if (firstActivation) {
      jdbcClient
          .sql(
              """
              update game
                 set active_version_id = :versionId, title = :title,
                     short_description = :shortDescription, info_doc_markdown = :infoDocMarkdown
               where game_id = :gameId
              """)
          .param("versionId", request.versionId())
          .param("title", request.title())
          .param("shortDescription", request.shortDescription())
          .param("infoDocMarkdown", request.infoDocMarkdown())
          .param("gameId", request.gameId())
          .update();
    } else {
      jdbcClient
          .sql("update game set active_version_id = :versionId where game_id = :gameId")
          .param("versionId", request.versionId())
          .param("gameId", request.gameId())
          .update();
    }

    jdbcClient
        .sql("delete from game_tag where game_id = :gameId")
        .param("gameId", request.gameId())
        .update();
    for (String tag : request.tags()) {
      jdbcClient
          .sql("insert into game_tag (game_id, tag) values (:gameId, :tag)")
          .param("gameId", request.gameId())
          .param("tag", tag)
          .update();
    }

    return Optional.of(new ActivationOutcome(firstActivation));
  }

  record ActivationOutcome(boolean firstActivation) {}

  record ActivationRequest(
      UUID gameId,
      UUID versionId,
      String version,
      String manifestJson,
      String gdkApiVersion,
      int minPlayers,
      int maxPlayers,
      boolean supportsAi,
      boolean supportsSpectators,
      boolean supportsMidGameJoin,
      List<String> tags,
      String title,
      String shortDescription,
      @Nullable String infoDocMarkdown) {}

  // ---------------------------------------------------------------- §7.2 sweep

  /**
   * Duty 1: {@code PENDING} rows past {@code upload_expires_at} → {@code EXPIRED}. Returns the
   * expired versions' object keys for duty 3's purge.
   */
  List<PurgeCandidate> expirePendingSlots() {
    return jdbcClient
        .sql(
            """
            with updated as (
              update game_version set status = 'EXPIRED'
               where status = 'PENDING' and upload_expires_at < now()
              returning version_id, game_id, version, bundle_object_key
            )
            select version_id, game_id, version, bundle_object_key from updated
            """)
        .query(this::mapPurgeCandidate)
        .list();
  }

  /**
   * Duty 2: {@code PROCESSING} rows whose lease lapsed → {@code FAILED} with a single {@code
   * pipeline.abandoned} entry — the fix for a died-mid-validation version number permanently
   * burning {@code (game_id, version)}.
   */
  List<PurgeCandidate> failAbandonedProcessing() {
    List<CheckError> abandoned =
        List.of(
            new CheckError(
                "pipeline.abandoned",
                "The validating instance did not renew its lease before claimed_until; the"
                    + " version was returned to a terminal state so the version number can be"
                    + " retried."));
    return jdbcClient
        .sql(
            """
            with updated as (
              update game_version set status = 'FAILED', validation_errors = :errors::jsonb,
                     claimed_by = null, claimed_until = null
               where status = 'PROCESSING' and claimed_until < now()
              returning version_id, game_id, version, bundle_object_key
            )
            select version_id, game_id, version, bundle_object_key from updated
            """)
        .param("errors", jsonOf(abandoned))
        .query(this::mapPurgeCandidate)
        .list();
  }

  /** Duty 3: every {@code FAILED}/{@code EXPIRED} row not yet purged. */
  List<PurgeCandidate> findUnpurgedTerminal() {
    return jdbcClient
        .sql(
            "select version_id, game_id, version, bundle_object_key from game_version"
                + " where status in ('FAILED','EXPIRED') and bundle_purged_at is null"
                + " and bundle_object_key is not null")
        .query(this::mapPurgeCandidate)
        .list();
  }

  /**
   * Duty 4: candidates for orphaned {@code unpacked/}/{@code artifacts/} objects — any version not
   * in {@code (ACTIVE, SUPERSEDED)}. Best-effort; a version that never got that far simply has
   * nothing under its prefix to delete.
   */
  List<PurgeCandidate> findOrphanCandidates() {
    return jdbcClient
        .sql(
            "select version_id, game_id, version, bundle_object_key from game_version"
                + " where status not in ('ACTIVE','SUPERSEDED')")
        .query(this::mapPurgeCandidate)
        .list();
  }

  record PurgeCandidate(
      UUID versionId, UUID gameId, String version, @Nullable String bundleObjectKey) {}

  private PurgeCandidate mapPurgeCandidate(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    return new PurgeCandidate(
        rs.getObject("version_id", UUID.class),
        rs.getObject("game_id", UUID.class),
        rs.getString("version"),
        rs.getString("bundle_object_key"));
  }

  // ---------------------------------------------------------------- mapping

  private static final String ROW_SELECT =
      "select version_id, game_id, version, status, manifest, gdk_api_version, bundle_object_key,"
          + " declared_size_bytes, stored_size_bytes, upload_expires_at, validation_errors,"
          + " validation_warnings, claimed_by, claimed_until, created_at from game_version";

  private GameVersionRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    Timestamp claimedUntil = rs.getTimestamp("claimed_until");
    return new GameVersionRow(
        rs.getObject("version_id", UUID.class),
        rs.getObject("game_id", UUID.class),
        rs.getString("version"),
        GameVersionStatus.valueOf(rs.getString("status")),
        rs.getString("manifest"),
        rs.getString("gdk_api_version"),
        rs.getString("bundle_object_key"),
        rs.getLong("declared_size_bytes"),
        (Long) rs.getObject("stored_size_bytes"),
        requireInstant(rs.getTimestamp("upload_expires_at")),
        checksOf(rs.getString("validation_errors")),
        checksOf(rs.getString("validation_warnings")),
        rs.getString("claimed_by"),
        claimedUntil == null ? null : claimedUntil.toInstant(),
        requireInstant(rs.getTimestamp("created_at")));
  }

  private static Instant requireInstant(Timestamp timestamp) {
    return timestamp.toInstant();
  }

  private List<CheckError> checksOf(@Nullable String json) {
    if (json == null) {
      return List.of();
    }
    try {
      JsonNode node = objectMapper.readTree(json);
      List<CheckError> result = new ArrayList<>();
      node.forEach(
          entry ->
              result.add(
                  new CheckError(entry.path("check").asText(), entry.path("message").asText())));
      return List.copyOf(result);
    } catch (Exception e) {
      throw new IllegalStateException("Malformed validation checks JSON in game_version", e);
    }
  }

  private String jsonOf(List<CheckError> checks) {
    try {
      return objectMapper.writeValueAsString(checks);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize validation checks", e);
    }
  }
}
