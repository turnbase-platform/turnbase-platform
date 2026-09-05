-- IMP-M0-06: Flyway baseline.
--
-- Transcribed from the six schema documents under docs/specs/platform/ in the parent repository,
-- in the order and structure fixed by docs/specs/deployment/schema-migrations.md §5 and
-- ADR-0011. Those documents are normative for names and reasons; this file is what the database
-- is (ADR-0011 §4). Do not edit one without the other.

-- ---------------------------------------------------------------------------------------------
-- 1. Extensions (ADR-0011 §6)
-- ---------------------------------------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gin;

-- ---------------------------------------------------------------------------------------------
-- 2. Standalone sequence (data-model-accounts.md §1)
-- ---------------------------------------------------------------------------------------------

CREATE SEQUENCE guest_serial_seq AS bigint MINVALUE 0 START WITH 0;

-- ---------------------------------------------------------------------------------------------
-- 3. Tables, in dependency order (schema-migrations.md §5)
-- ---------------------------------------------------------------------------------------------

-- data-model-accounts.md §1
CREATE TABLE app_user (
  user_id              uuid PRIMARY KEY,
  kind                 text        NOT NULL,   -- REGISTERED | GUEST
  username             text        NOT NULL,
  username_skeleton    text        NOT NULL,
  email                text        NULL,
  email_source         text        NULL,       -- SELF | OAUTH
  guest_serial         bigint      NULL,

  username_changed_at  timestamptz NULL,
  email_changed_at     timestamptz NULL,

  created_at           timestamptz NOT NULL DEFAULT now(),
  deleted_at           timestamptz NULL,
  deletion_reason      text        NULL,       -- SELF_SERVICE | GUEST_EXPIRED
  purgeable_after      timestamptz NULL,

  CONSTRAINT app_user_kind_ck CHECK (kind IN ('REGISTERED','GUEST')),
  CONSTRAINT app_user_username_len_ck CHECK (char_length(username) BETWEEN 3 AND 32),
  CONSTRAINT app_user_guest_serial_ck CHECK ((kind = 'GUEST') = (guest_serial IS NOT NULL)),
  CONSTRAINT app_user_email_ck CHECK (
    kind = 'GUEST' OR deleted_at IS NOT NULL
    OR (email IS NOT NULL AND email_source IS NOT NULL)
  )
);

CREATE UNIQUE INDEX app_user_skeleton_key   ON app_user (username_skeleton);
CREATE UNIQUE INDEX app_user_id_kind_key    ON app_user (user_id, kind);
CREATE UNIQUE INDEX app_user_guest_serial_key ON app_user (guest_serial) WHERE guest_serial IS NOT NULL;
CREATE UNIQUE INDEX app_user_email_key      ON app_user (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX app_user_username_trgm_idx     ON app_user USING gin (lower(username) gin_trgm_ops)
  WHERE kind = 'REGISTERED' AND deleted_at IS NULL;
CREATE INDEX app_user_guest_expiry_idx      ON app_user (created_at)
  WHERE kind = 'GUEST' AND deleted_at IS NULL;
CREATE INDEX app_user_guest_purge_idx       ON app_user (purgeable_after)
  WHERE kind = 'GUEST' AND purgeable_after IS NOT NULL;

-- data-model-accounts.md §2
CREATE TABLE user_auth_method (
  auth_method_id   uuid PRIMARY KEY,
  user_id          uuid        NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  method           text        NOT NULL,   -- PASSWORD | GOOGLE
  password_hash    text        NULL,       -- PHC-encoded Argon2id string
  provider_subject text        NULL,       -- OAuth 'sub' claim
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT user_auth_method_shape_ck CHECK (
    (method = 'PASSWORD' AND password_hash IS NOT NULL AND provider_subject IS NULL)
 OR (method = 'GOOGLE'   AND password_hash IS NULL     AND provider_subject IS NOT NULL)
  )
);

CREATE UNIQUE INDEX user_auth_method_per_user_key ON user_auth_method (user_id, method);
CREATE UNIQUE INDEX user_auth_method_subject_key
  ON user_auth_method (method, provider_subject) WHERE provider_subject IS NOT NULL;

-- data-model-accounts.md §3
CREATE TABLE user_session (
  session_id      uuid PRIMARY KEY,
  user_id         uuid        NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  issued_at       timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL,
  revoked_at      timestamptz NULL,
  revoked_reason  text        NULL,   -- DISPLACED | LOGOUT | ACCOUNT_DELETED | GUEST_EXPIRED | KEY_ROTATED
  key_id          text        NOT NULL,
  user_agent_hash text        NULL
);

CREATE UNIQUE INDEX user_session_active_key ON user_session (user_id) WHERE revoked_at IS NULL;
CREATE INDEX user_session_expiry_idx ON user_session (expires_at) WHERE revoked_at IS NULL;

-- data-model-accounts.md §4
CREATE TABLE user_privacy_settings (
  user_id                uuid PRIMARY KEY REFERENCES app_user(user_id) ON DELETE CASCADE,
  profile_private        boolean NOT NULL DEFAULT false,
  history_visibility     text    NOT NULL DEFAULT 'PUBLIC',
  stats_visibility       text    NOT NULL DEFAULT 'PUBLIC',
  games_visibility       text    NOT NULL DEFAULT 'PUBLIC',
  friends_visibility     text    NOT NULL DEFAULT 'PUBLIC',
  block_friend_requests  boolean NOT NULL DEFAULT false,
  updated_at             timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT ups_history_ck CHECK (history_visibility IN ('PUBLIC','HIDDEN')),
  CONSTRAINT ups_stats_ck   CHECK (stats_visibility   IN ('PUBLIC','HIDDEN')),
  CONSTRAINT ups_games_ck   CHECK (games_visibility   IN ('PUBLIC','HIDDEN')),
  CONSTRAINT ups_friends_ck CHECK (friends_visibility IN ('PUBLIC','FRIENDS_ONLY','HIDDEN'))
);

-- data-model-social.md §1
CREATE TABLE friendship (
  user_id_low   uuid NOT NULL,
  user_id_high  uuid NOT NULL,
  kind_low      text NOT NULL DEFAULT 'REGISTERED',
  kind_high     text NOT NULL DEFAULT 'REGISTERED',
  created_at    timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (user_id_low, user_id_high),
  CONSTRAINT friendship_order_ck CHECK (user_id_low < user_id_high),
  CONSTRAINT friendship_low_registered_ck  CHECK (kind_low  = 'REGISTERED'),
  CONSTRAINT friendship_high_registered_ck CHECK (kind_high = 'REGISTERED'),
  CONSTRAINT friendship_low_fk  FOREIGN KEY (user_id_low,  kind_low)
    REFERENCES app_user (user_id, kind) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT friendship_high_fk FOREIGN KEY (user_id_high, kind_high)
    REFERENCES app_user (user_id, kind) ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE INDEX friendship_high_idx ON friendship (user_id_high);

-- data-model-social.md §2
CREATE TABLE friend_request (
  request_id      uuid PRIMARY KEY,
  sender_id       uuid NOT NULL,
  recipient_id    uuid NOT NULL,
  sender_kind     text NOT NULL DEFAULT 'REGISTERED',
  recipient_kind  text NOT NULL DEFAULT 'REGISTERED',
  created_at      timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL,

  CONSTRAINT friend_request_self_ck CHECK (sender_id <> recipient_id),
  CONSTRAINT friend_request_sender_registered_ck    CHECK (sender_kind    = 'REGISTERED'),
  CONSTRAINT friend_request_recipient_registered_ck CHECK (recipient_kind = 'REGISTERED'),
  CONSTRAINT friend_request_sender_fk FOREIGN KEY (sender_id, sender_kind)
    REFERENCES app_user (user_id, kind) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT friend_request_recipient_fk FOREIGN KEY (recipient_id, recipient_kind)
    REFERENCES app_user (user_id, kind) ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE UNIQUE INDEX friend_request_pair_key ON friend_request (sender_id, recipient_id);
CREATE INDEX friend_request_recipient_idx   ON friend_request (recipient_id, created_at DESC);
CREATE INDEX friend_request_expiry_idx      ON friend_request (expires_at);

-- data-model-catalog.md §1
CREATE TABLE game (
  game_id                  uuid PRIMARY KEY,
  owner_user_id            uuid        NULL REFERENCES app_user(user_id),
  title                    text        NOT NULL,
  short_description        text        NOT NULL,
  info_doc_markdown        text        NULL,

  badge                    text        NOT NULL DEFAULT 'UNVERIFIED',
  listed                   boolean     NOT NULL DEFAULT false,

  active_version_id        uuid        NULL,
  completed_session_count  bigint      NOT NULL DEFAULT 0,

  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  archived_at              timestamptz NULL,
  deleted_at               timestamptz NULL,

  CONSTRAINT game_badge_ck CHECK (badge IN ('OFFICIAL','VERIFIED','UNVERIFIED')),
  CONSTRAINT game_official_ownerless_ck CHECK ((badge = 'OFFICIAL') = (owner_user_id IS NULL))
);

CREATE INDEX game_public_idx ON game (created_at DESC)
  WHERE deleted_at IS NULL AND archived_at IS NULL AND listed AND badge <> 'UNVERIFIED';
CREATE INDEX game_owner_idx ON game (owner_user_id) WHERE deleted_at IS NULL;
CREATE INDEX game_title_trgm_idx ON game USING gin (lower(title) gin_trgm_ops);

-- data-model-catalog.md §2
CREATE TABLE game_version (
  version_id            uuid PRIMARY KEY,
  game_id               uuid        NOT NULL REFERENCES game(game_id) ON DELETE CASCADE,
  version               text        NOT NULL,
  version_major         integer     NOT NULL GENERATED ALWAYS AS (split_part(version,'.',1)::integer) STORED,
  version_minor         integer     NOT NULL GENERATED ALWAYS AS (split_part(version,'.',2)::integer) STORED,
  version_patch         integer     NOT NULL GENERATED ALWAYS AS (split_part(version,'.',3)::integer) STORED,

  status                text        NOT NULL,
  manifest              jsonb       NULL,
  gdk_api_version       text        NULL,

  bundle_object_key     text        NULL,
  declared_size_bytes   bigint      NOT NULL,
  stored_size_bytes     bigint      NULL,
  upload_expires_at     timestamptz NOT NULL,
  validation_errors     jsonb       NULL,
  validation_warnings   jsonb       NULL,

  min_players           integer     NULL,
  max_players           integer     NULL,
  supports_ai           boolean     NULL,
  supports_spectators   boolean     NULL,
  supports_mid_game_join boolean    NULL,

  claimed_by            text        NULL,
  claimed_until         timestamptz NULL,

  created_at            timestamptz NOT NULL DEFAULT now(),
  activated_at          timestamptz NULL,
  superseded_at         timestamptz NULL,
  bundle_purged_at      timestamptz NULL,

  CONSTRAINT game_version_format_ck CHECK (version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
  CONSTRAINT game_version_status_ck CHECK (status IN
    ('PENDING','PROCESSING','ACTIVE','FAILED','EXPIRED','SUPERSEDED','DISCARDED'))
);

CREATE UNIQUE INDEX game_version_key ON game_version (game_id, version);
CREATE UNIQUE INDEX game_version_active_key ON game_version (game_id) WHERE status = 'ACTIVE';
CREATE INDEX game_version_order_idx ON game_version (game_id, version_major, version_minor, version_patch);
CREATE INDEX game_version_upload_expiry_idx ON game_version (upload_expires_at) WHERE status = 'PENDING';
CREATE INDEX game_version_stuck_idx ON game_version (claimed_until) WHERE status = 'PROCESSING';
CREATE INDEX game_version_players_idx ON game_version (min_players, max_players) WHERE status = 'ACTIVE';
CREATE INDEX game_version_gdk_idx ON game_version (gdk_api_version) WHERE status IN ('ACTIVE','SUPERSEDED');

-- data-model-catalog.md §3
CREATE TABLE game_tag (
  game_id  uuid NOT NULL REFERENCES game(game_id) ON DELETE CASCADE,
  tag      text NOT NULL,
  PRIMARY KEY (game_id, tag)
);

CREATE INDEX game_tag_trgm_idx ON game_tag USING gin (lower(tag) gin_trgm_ops);

-- data-model-catalog.md §4
CREATE TABLE game_review (
  review_id        uuid PRIMARY KEY,
  game_id          uuid        NOT NULL REFERENCES game(game_id) ON DELETE CASCADE,
  version_id       uuid        NOT NULL REFERENCES game_version(version_id),
  submitted_at     timestamptz NOT NULL DEFAULT now(),
  decided_at       timestamptz NULL,
  decision         text        NULL,   -- APPROVED | REJECTED | WITHDRAWN
  rejection_reason text        NULL,
  decided_by       text        NULL,   -- admin Basic-auth principal, or 'system'

  CONSTRAINT game_review_decision_ck CHECK (
    (decided_at IS NULL AND decision IS NULL AND rejection_reason IS NULL AND decided_by IS NULL)
 OR (decided_at IS NOT NULL AND decision IN ('APPROVED','REJECTED','WITHDRAWN') AND decided_by IS NOT NULL)
  ),
  CONSTRAINT game_review_reason_ck CHECK (decision <> 'REJECTED' OR rejection_reason IS NOT NULL)
);

CREATE UNIQUE INDEX game_review_open_key ON game_review (game_id) WHERE decided_at IS NULL;
CREATE INDEX game_review_queue_idx ON game_review (submitted_at) WHERE decided_at IS NULL;
CREATE INDEX game_review_approved_idx ON game_review (game_id, decided_at DESC)
  WHERE decision = 'APPROVED';

-- data-model.md §1
CREATE TABLE room (
  room_id             uuid PRIMARY KEY,
  game_id             uuid        NOT NULL REFERENCES game(game_id),
  game_version        text        NOT NULL,
  host_user_id        uuid        NULL REFERENCES app_user(user_id),
  visibility          text        NOT NULL,       -- PUBLIC | FRIENDS_ONLY | INVITE_ONLY
  state               text        NOT NULL,       -- LOBBY | IN_PROGRESS | CLOSED
  spectating_enabled  boolean     NOT NULL DEFAULT false,
  invite_code         text        NULL UNIQUE,
  parameters          jsonb       NOT NULL DEFAULT '{}'::jsonb,

  version             bigint      NOT NULL DEFAULT 0,
  active_session_id   uuid        NULL,

  owner_instance_id   text        NULL,
  owner_epoch         bigint      NOT NULL DEFAULT 0,
  ownerless_since     timestamptz NULL,
  migration_window_expires_at timestamptz NULL,

  last_activity_at    timestamptz NOT NULL DEFAULT now(),
  closed_at           timestamptz NULL,
  closed_reason       text        NULL,           -- see data-model.md § Closure reasons
  reconcile_flagged_at timestamptz NULL,

  created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX room_open_state_idx ON room (state) WHERE state <> 'CLOSED';
CREATE INDEX room_idle_lobby_idx ON room (last_activity_at) WHERE state = 'LOBBY';
CREATE INDEX room_closed_purge_idx ON room (closed_at) WHERE state = 'CLOSED';
CREATE INDEX room_active_session_idx ON room (active_session_id)
  WHERE active_session_id IS NOT NULL;
CREATE INDEX room_open_game_idx ON room (game_id, game_version) WHERE state <> 'CLOSED';

-- data-model.md §3
CREATE TABLE room_team (
  team_id          uuid PRIMARY KEY,
  room_id          uuid    NOT NULL REFERENCES room(room_id) ON DELETE CASCADE,
  template_index   integer NOT NULL,   -- index into the game's teamTemplates array
  team_index       integer NOT NULL,   -- ordinal within that template, for "{name} N" display
  configured_size  integer NOT NULL,   -- within [size.min, size.max]
  UNIQUE (room_id, template_index, team_index)
);

-- data-model.md §2
CREATE TABLE room_participant (
  room_id               uuid        NOT NULL REFERENCES room(room_id) ON DELETE CASCADE,
  user_id               uuid        NOT NULL REFERENCES app_user(user_id),
  role                  text        NOT NULL,      -- PLAYER | SPECTATOR
  team_id               uuid        NULL REFERENCES room_team(team_id),
  joined_at             timestamptz NOT NULL DEFAULT now(),
  join_order            integer     NOT NULL,

  connection_count      integer     NOT NULL DEFAULT 0,
  ready                 boolean     NOT NULL DEFAULT false,

  grace_reservation_id  uuid        NULL,
  grace_expires_at      timestamptz NULL,

  left_at               timestamptz NULL,
  leave_reason          text        NULL,          -- LEFT | KICKED | GRACE_EXPIRED | EARLY_RELEASE
                                                   -- | ROOM_CLOSED | GAME_ENDED
  lost_announced_at     timestamptz NULL,

  PRIMARY KEY (room_id, user_id)
);

CREATE INDEX room_participant_grace_due_idx
  ON room_participant (grace_expires_at)
  WHERE grace_expires_at IS NOT NULL AND left_at IS NULL;

CREATE INDEX room_participant_unannounced_idx
  ON room_participant (room_id)
  WHERE left_at IS NOT NULL AND lost_announced_at IS NULL;

-- data-model-notifications.md §2
CREATE TABLE room_invitation (
  invitation_id     uuid PRIMARY KEY,
  room_id           uuid        NOT NULL REFERENCES room(room_id) ON DELETE CASCADE,
  inviter_user_id   uuid        NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  invitee_user_id   uuid        NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  created_at        timestamptz NOT NULL DEFAULT now(),
  expires_at        timestamptz NOT NULL,

  CONSTRAINT room_invitation_self_ck CHECK (inviter_user_id <> invitee_user_id)
);

CREATE UNIQUE INDEX room_invitation_pair_key ON room_invitation (room_id, invitee_user_id);
CREATE INDEX room_invitation_invitee_idx ON room_invitation (invitee_user_id);
CREATE INDEX room_invitation_inviter_idx ON room_invitation (inviter_user_id);
CREATE INDEX room_invitation_expiry_idx  ON room_invitation (expires_at);

-- data-model.md §4.1
CREATE TABLE session_teardown (
  session_id      uuid PRIMARY KEY,
  room_id         uuid        NOT NULL,
  reason          text        NOT NULL,     -- maps 1:1 onto proto TerminationReason
  requested_at    timestamptz NOT NULL DEFAULT now(),
  attempts        integer     NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  last_error      text        NULL
);

CREATE INDEX session_teardown_due_idx ON session_teardown (next_attempt_at);

-- data-model.md §4.2
CREATE TABLE game_input_outbox (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  session_id    uuid        NOT NULL,
  sequence      bigint      NULL,        -- allocated once, before first dispatch
  payload       jsonb       NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  attempts      integer     NOT NULL DEFAULT 0,
  dispatched_at timestamptz NULL,        -- stamped on ACK, never on attempt
  last_error    text        NULL
);

CREATE INDEX game_input_outbox_pending_idx ON game_input_outbox (session_id, id)
  WHERE dispatched_at IS NULL;

-- data-model-history.md §1
CREATE TABLE game_session_completion (
  session_id    uuid PRIMARY KEY,
  game_id       uuid        NOT NULL,
  game_version  text        NOT NULL,
  completed_at  timestamptz NOT NULL,
  player_count  integer     NOT NULL,
  team_count    integer     NOT NULL,
  draw          boolean     NOT NULL,
  recorded_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX game_session_completion_game_idx ON game_session_completion (game_id, completed_at DESC);

-- data-model-history.md §2
CREATE TABLE game_history_entry (
  entry_id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  session_id          uuid        NOT NULL REFERENCES game_session_completion(session_id),
  user_id             uuid        NULL REFERENCES app_user(user_id),
  subject_token       text        NULL,

  game_id             uuid        NOT NULL,
  game_name_snapshot  text        NOT NULL,
  completed_at        timestamptz NOT NULL,

  placement           integer     NOT NULL,
  team_count          integer     NOT NULL,
  draw                boolean     NOT NULL,
  player_stats        jsonb       NOT NULL DEFAULT '{}'::jsonb,
  team_stats          jsonb       NOT NULL DEFAULT '{}'::jsonb,

  pseudonymized_at    timestamptz NULL,

  CONSTRAINT ghe_placement_ck CHECK (placement >= 1 AND placement <= team_count),
  CONSTRAINT ghe_subject_ck CHECK ((user_id IS NULL) = (subject_token IS NOT NULL))
);

CREATE UNIQUE INDEX ghe_session_user_key ON game_history_entry (session_id, user_id);
CREATE INDEX ghe_user_recent_idx ON game_history_entry (user_id, completed_at DESC)
  WHERE user_id IS NOT NULL;
CREATE INDEX ghe_user_game_idx ON game_history_entry (user_id, game_id)
  WHERE user_id IS NOT NULL;
CREATE INDEX ghe_user_name_trgm_idx ON game_history_entry
  USING gin (user_id, lower(game_name_snapshot) gin_trgm_ops)
  WHERE user_id IS NOT NULL;

-- data-model-notifications.md §1
-- invitation_id references room_invitation, created above (schema-migrations.md §5's table
-- order), so this is an ordinary inline FK rather than the deferred kind — the only deferred
-- (circular) foreign key in the baseline is game.active_version_id below.
CREATE TABLE notification (
  notification_id    uuid PRIMARY KEY,
  event_seq          bigint GENERATED ALWAYS AS IDENTITY,
  user_id            uuid        NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  type               text        NOT NULL,
  payload            jsonb       NOT NULL DEFAULT '{}'::jsonb,

  friend_request_id  uuid        NULL REFERENCES friend_request(request_id) ON DELETE CASCADE,
  invitation_id      uuid        NULL REFERENCES room_invitation(invitation_id) ON DELETE CASCADE,

  created_at         timestamptz NOT NULL DEFAULT now(),
  delivered_at       timestamptz NULL,
  read_at            timestamptz NULL,

  CONSTRAINT notification_single_subject_ck CHECK (
    (friend_request_id IS NULL) OR (invitation_id IS NULL)
  )
);

CREATE UNIQUE INDEX notification_seq_key   ON notification (event_seq);
CREATE INDEX notification_user_recent_idx  ON notification (user_id, created_at DESC);
CREATE INDEX notification_unread_idx       ON notification (user_id) WHERE read_at IS NULL;
CREATE INDEX notification_undelivered_idx  ON notification (user_id, event_seq)
  WHERE delivered_at IS NULL;
CREATE INDEX notification_purge_idx        ON notification (created_at)
  WHERE friend_request_id IS NULL AND invitation_id IS NULL;

CREATE INDEX notification_friend_request_idx ON notification (friend_request_id)
  WHERE friend_request_id IS NOT NULL;
CREATE INDEX notification_invitation_idx     ON notification (invitation_id)
  WHERE invitation_id IS NOT NULL;

-- schema-migrations.md §5: ShedLock's own fixed JdbcTemplateLockProvider schema, not a
-- TurnBase design choice (FR-JOB-005).
CREATE TABLE shedlock (
  name       varchar(64)  NOT NULL,
  lock_until timestamp(3) NOT NULL,
  locked_at  timestamp(3) NOT NULL,
  locked_by  varchar(255) NOT NULL,
  PRIMARY KEY (name)
);

-- ---------------------------------------------------------------------------------------------
-- 4. The one circular foreign key (schema-migrations.md §5 point 4)
-- ---------------------------------------------------------------------------------------------

ALTER TABLE game ADD CONSTRAINT game_active_version_fk
  FOREIGN KEY (active_version_id) REFERENCES game_version(version_id);
