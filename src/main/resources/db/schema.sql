--
-- PostgreSQL database dump
--

\restrict S3UbB1M4DHr5T7WEpK1IXSrm5dw7Uc4xagKSsdIVc6lhz90kDendncFRlrzxsVy

-- Dumped from database version 16.11
-- Dumped by pg_dump version 16.11

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: btree_gin; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS btree_gin WITH SCHEMA public;


--
-- Name: EXTENSION btree_gin; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION btree_gin IS 'support for indexing common datatypes in GIN';


--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: app_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_user (
    user_id uuid NOT NULL,
    kind text NOT NULL,
    username text NOT NULL,
    username_skeleton text NOT NULL,
    email text,
    email_source text,
    guest_serial bigint,
    username_changed_at timestamp with time zone,
    email_changed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    deletion_reason text,
    purgeable_after timestamp with time zone,
    CONSTRAINT app_user_email_ck CHECK (((kind = 'GUEST'::text) OR (deleted_at IS NOT NULL) OR ((email IS NOT NULL) AND (email_source IS NOT NULL)))),
    CONSTRAINT app_user_guest_serial_ck CHECK (((kind = 'GUEST'::text) = (guest_serial IS NOT NULL))),
    CONSTRAINT app_user_kind_ck CHECK ((kind = ANY (ARRAY['REGISTERED'::text, 'GUEST'::text]))),
    CONSTRAINT app_user_username_len_ck CHECK (((char_length(username) >= 3) AND (char_length(username) <= 32)))
);


--
-- Name: friend_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.friend_request (
    request_id uuid NOT NULL,
    sender_id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    sender_kind text DEFAULT 'REGISTERED'::text NOT NULL,
    recipient_kind text DEFAULT 'REGISTERED'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT friend_request_recipient_registered_ck CHECK ((recipient_kind = 'REGISTERED'::text)),
    CONSTRAINT friend_request_self_ck CHECK ((sender_id <> recipient_id)),
    CONSTRAINT friend_request_sender_registered_ck CHECK ((sender_kind = 'REGISTERED'::text))
);


--
-- Name: friendship; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.friendship (
    user_id_low uuid NOT NULL,
    user_id_high uuid NOT NULL,
    kind_low text DEFAULT 'REGISTERED'::text NOT NULL,
    kind_high text DEFAULT 'REGISTERED'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT friendship_high_registered_ck CHECK ((kind_high = 'REGISTERED'::text)),
    CONSTRAINT friendship_low_registered_ck CHECK ((kind_low = 'REGISTERED'::text)),
    CONSTRAINT friendship_order_ck CHECK ((user_id_low < user_id_high))
);


--
-- Name: game; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game (
    game_id uuid NOT NULL,
    owner_user_id uuid,
    title text NOT NULL,
    short_description text NOT NULL,
    info_doc_markdown text,
    badge text DEFAULT 'UNVERIFIED'::text NOT NULL,
    listed boolean DEFAULT false NOT NULL,
    active_version_id uuid,
    completed_session_count bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_at timestamp with time zone,
    deleted_at timestamp with time zone,
    CONSTRAINT game_badge_ck CHECK ((badge = ANY (ARRAY['OFFICIAL'::text, 'VERIFIED'::text, 'UNVERIFIED'::text]))),
    CONSTRAINT game_official_ownerless_ck CHECK (((badge = 'OFFICIAL'::text) = (owner_user_id IS NULL)))
);


--
-- Name: game_history_entry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game_history_entry (
    entry_id bigint NOT NULL,
    session_id uuid NOT NULL,
    user_id uuid,
    subject_token text,
    game_id uuid NOT NULL,
    game_name_snapshot text NOT NULL,
    completed_at timestamp with time zone NOT NULL,
    placement integer NOT NULL,
    team_count integer NOT NULL,
    draw boolean NOT NULL,
    player_stats jsonb DEFAULT '{}'::jsonb NOT NULL,
    team_stats jsonb DEFAULT '{}'::jsonb NOT NULL,
    pseudonymized_at timestamp with time zone,
    CONSTRAINT ghe_placement_ck CHECK (((placement >= 1) AND (placement <= team_count))),
    CONSTRAINT ghe_subject_ck CHECK (((user_id IS NULL) = (subject_token IS NOT NULL)))
);


--
-- Name: game_history_entry_entry_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.game_history_entry ALTER COLUMN entry_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.game_history_entry_entry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: game_input_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game_input_outbox (
    id bigint NOT NULL,
    session_id uuid NOT NULL,
    sequence bigint,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    dispatched_at timestamp with time zone,
    last_error text
);


--
-- Name: game_input_outbox_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.game_input_outbox ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.game_input_outbox_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: game_review; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game_review (
    review_id uuid NOT NULL,
    game_id uuid NOT NULL,
    version_id uuid NOT NULL,
    submitted_at timestamp with time zone DEFAULT now() NOT NULL,
    decided_at timestamp with time zone,
    decision text,
    rejection_reason text,
    decided_by text,
    CONSTRAINT game_review_decision_ck CHECK ((((decided_at IS NULL) AND (decision IS NULL) AND (rejection_reason IS NULL) AND (decided_by IS NULL)) OR ((decided_at IS NOT NULL) AND (decision = ANY (ARRAY['APPROVED'::text, 'REJECTED'::text, 'WITHDRAWN'::text])) AND (decided_by IS NOT NULL)))),
    CONSTRAINT game_review_reason_ck CHECK (((decision <> 'REJECTED'::text) OR (rejection_reason IS NOT NULL)))
);


--
-- Name: game_session_completion; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game_session_completion (
    session_id uuid NOT NULL,
    game_id uuid NOT NULL,
    game_version text NOT NULL,
    completed_at timestamp with time zone NOT NULL,
    player_count integer NOT NULL,
    team_count integer NOT NULL,
    draw boolean NOT NULL,
    recorded_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: game_tag; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game_tag (
    game_id uuid NOT NULL,
    tag text NOT NULL
);


--
-- Name: game_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game_version (
    version_id uuid NOT NULL,
    game_id uuid NOT NULL,
    version text NOT NULL,
    version_major integer GENERATED ALWAYS AS ((split_part(version, '.'::text, 1))::integer) STORED NOT NULL,
    version_minor integer GENERATED ALWAYS AS ((split_part(version, '.'::text, 2))::integer) STORED NOT NULL,
    version_patch integer GENERATED ALWAYS AS ((split_part(version, '.'::text, 3))::integer) STORED NOT NULL,
    status text NOT NULL,
    manifest jsonb,
    gdk_api_version text,
    bundle_object_key text,
    declared_size_bytes bigint NOT NULL,
    stored_size_bytes bigint,
    upload_expires_at timestamp with time zone NOT NULL,
    validation_errors jsonb,
    validation_warnings jsonb,
    min_players integer,
    max_players integer,
    supports_ai boolean,
    supports_spectators boolean,
    supports_mid_game_join boolean,
    claimed_by text,
    claimed_until timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    activated_at timestamp with time zone,
    superseded_at timestamp with time zone,
    bundle_purged_at timestamp with time zone,
    CONSTRAINT game_version_format_ck CHECK ((version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'::text)),
    CONSTRAINT game_version_status_ck CHECK ((status = ANY (ARRAY['PENDING'::text, 'PROCESSING'::text, 'ACTIVE'::text, 'FAILED'::text, 'EXPIRED'::text, 'SUPERSEDED'::text, 'DISCARDED'::text])))
);


--
-- Name: guest_serial_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.guest_serial_seq
    START WITH 0
    INCREMENT BY 1
    MINVALUE 0
    NO MAXVALUE
    CACHE 1;


--
-- Name: notification; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification (
    notification_id uuid NOT NULL,
    event_seq bigint NOT NULL,
    user_id uuid NOT NULL,
    type text NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    friend_request_id uuid,
    invitation_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    delivered_at timestamp with time zone,
    read_at timestamp with time zone,
    CONSTRAINT notification_single_subject_ck CHECK (((friend_request_id IS NULL) OR (invitation_id IS NULL)))
);


--
-- Name: notification_event_seq_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.notification ALTER COLUMN event_seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.notification_event_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: orphaned_session_flag; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orphaned_session_flag (
    session_id uuid NOT NULL,
    first_observed_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: room; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.room (
    room_id uuid NOT NULL,
    game_id uuid NOT NULL,
    game_version text NOT NULL,
    host_user_id uuid,
    visibility text NOT NULL,
    state text NOT NULL,
    spectating_enabled boolean DEFAULT false NOT NULL,
    invite_code text,
    parameters jsonb DEFAULT '{}'::jsonb NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    active_session_id uuid,
    owner_instance_id text,
    owner_epoch bigint DEFAULT 0 NOT NULL,
    ownerless_since timestamp with time zone,
    migration_window_expires_at timestamp with time zone,
    last_activity_at timestamp with time zone DEFAULT now() NOT NULL,
    closed_at timestamp with time zone,
    closed_reason text,
    reconcile_flagged_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: room_invitation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.room_invitation (
    invitation_id uuid NOT NULL,
    room_id uuid NOT NULL,
    inviter_user_id uuid NOT NULL,
    invitee_user_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT room_invitation_self_ck CHECK ((inviter_user_id <> invitee_user_id))
);


--
-- Name: room_participant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.room_participant (
    room_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role text NOT NULL,
    team_id uuid,
    joined_at timestamp with time zone DEFAULT now() NOT NULL,
    join_order integer NOT NULL,
    connection_count integer DEFAULT 0 NOT NULL,
    ready boolean DEFAULT false NOT NULL,
    grace_reservation_id uuid,
    grace_expires_at timestamp with time zone,
    left_at timestamp with time zone,
    leave_reason text,
    lost_announced_at timestamp with time zone
);


--
-- Name: room_team; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.room_team (
    team_id uuid NOT NULL,
    room_id uuid NOT NULL,
    template_index integer NOT NULL,
    team_index integer NOT NULL,
    configured_size integer NOT NULL
);


--
-- Name: session_teardown; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.session_teardown (
    session_id uuid NOT NULL,
    room_id uuid NOT NULL,
    reason text NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    last_error text
);


--
-- Name: shedlock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.shedlock (
    name character varying(64) NOT NULL,
    lock_until timestamp(3) without time zone NOT NULL,
    locked_at timestamp(3) without time zone NOT NULL,
    locked_by character varying(255) NOT NULL
);


--
-- Name: user_auth_method; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_auth_method (
    auth_method_id uuid NOT NULL,
    user_id uuid NOT NULL,
    method text NOT NULL,
    password_hash text,
    provider_subject text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_auth_method_shape_ck CHECK ((((method = 'PASSWORD'::text) AND (password_hash IS NOT NULL) AND (provider_subject IS NULL)) OR ((method = 'GOOGLE'::text) AND (password_hash IS NULL) AND (provider_subject IS NOT NULL))))
);


--
-- Name: user_privacy_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_privacy_settings (
    user_id uuid NOT NULL,
    profile_private boolean DEFAULT false NOT NULL,
    history_visibility text DEFAULT 'PUBLIC'::text NOT NULL,
    stats_visibility text DEFAULT 'PUBLIC'::text NOT NULL,
    games_visibility text DEFAULT 'PUBLIC'::text NOT NULL,
    friends_visibility text DEFAULT 'PUBLIC'::text NOT NULL,
    block_friend_requests boolean DEFAULT false NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ups_friends_ck CHECK ((friends_visibility = ANY (ARRAY['PUBLIC'::text, 'FRIENDS_ONLY'::text, 'HIDDEN'::text]))),
    CONSTRAINT ups_games_ck CHECK ((games_visibility = ANY (ARRAY['PUBLIC'::text, 'HIDDEN'::text]))),
    CONSTRAINT ups_history_ck CHECK ((history_visibility = ANY (ARRAY['PUBLIC'::text, 'HIDDEN'::text]))),
    CONSTRAINT ups_stats_ck CHECK ((stats_visibility = ANY (ARRAY['PUBLIC'::text, 'HIDDEN'::text])))
);


--
-- Name: user_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_session (
    session_id uuid NOT NULL,
    user_id uuid NOT NULL,
    issued_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    revoked_reason text,
    key_id text NOT NULL,
    user_agent_hash text
);


--
-- Name: app_user app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (user_id);


--
-- Name: friend_request friend_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.friend_request
    ADD CONSTRAINT friend_request_pkey PRIMARY KEY (request_id);


--
-- Name: friendship friendship_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.friendship
    ADD CONSTRAINT friendship_pkey PRIMARY KEY (user_id_low, user_id_high);


--
-- Name: game_history_entry game_history_entry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_history_entry
    ADD CONSTRAINT game_history_entry_pkey PRIMARY KEY (entry_id);


--
-- Name: game_input_outbox game_input_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_input_outbox
    ADD CONSTRAINT game_input_outbox_pkey PRIMARY KEY (id);


--
-- Name: game game_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game
    ADD CONSTRAINT game_pkey PRIMARY KEY (game_id);


--
-- Name: game_review game_review_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_review
    ADD CONSTRAINT game_review_pkey PRIMARY KEY (review_id);


--
-- Name: game_session_completion game_session_completion_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_session_completion
    ADD CONSTRAINT game_session_completion_pkey PRIMARY KEY (session_id);


--
-- Name: game_tag game_tag_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_tag
    ADD CONSTRAINT game_tag_pkey PRIMARY KEY (game_id, tag);


--
-- Name: game_version game_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_version
    ADD CONSTRAINT game_version_pkey PRIMARY KEY (version_id);


--
-- Name: notification notification_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_pkey PRIMARY KEY (notification_id);


--
-- Name: orphaned_session_flag orphaned_session_flag_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orphaned_session_flag
    ADD CONSTRAINT orphaned_session_flag_pkey PRIMARY KEY (session_id);


--
-- Name: room_invitation room_invitation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_invitation
    ADD CONSTRAINT room_invitation_pkey PRIMARY KEY (invitation_id);


--
-- Name: room room_invite_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room
    ADD CONSTRAINT room_invite_code_key UNIQUE (invite_code);


--
-- Name: room_participant room_participant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_participant
    ADD CONSTRAINT room_participant_pkey PRIMARY KEY (room_id, user_id);


--
-- Name: room room_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room
    ADD CONSTRAINT room_pkey PRIMARY KEY (room_id);


--
-- Name: room_team room_team_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_team
    ADD CONSTRAINT room_team_pkey PRIMARY KEY (team_id);


--
-- Name: room_team room_team_room_id_template_index_team_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_team
    ADD CONSTRAINT room_team_room_id_template_index_team_index_key UNIQUE (room_id, template_index, team_index);


--
-- Name: session_teardown session_teardown_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.session_teardown
    ADD CONSTRAINT session_teardown_pkey PRIMARY KEY (session_id);


--
-- Name: shedlock shedlock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shedlock
    ADD CONSTRAINT shedlock_pkey PRIMARY KEY (name);


--
-- Name: user_auth_method user_auth_method_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_auth_method
    ADD CONSTRAINT user_auth_method_pkey PRIMARY KEY (auth_method_id);


--
-- Name: user_privacy_settings user_privacy_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_privacy_settings
    ADD CONSTRAINT user_privacy_settings_pkey PRIMARY KEY (user_id);


--
-- Name: user_session user_session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_session
    ADD CONSTRAINT user_session_pkey PRIMARY KEY (session_id);


--
-- Name: app_user_email_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX app_user_email_key ON public.app_user USING btree (lower(email)) WHERE (email IS NOT NULL);


--
-- Name: app_user_guest_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX app_user_guest_expiry_idx ON public.app_user USING btree (created_at) WHERE ((kind = 'GUEST'::text) AND (deleted_at IS NULL));


--
-- Name: app_user_guest_purge_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX app_user_guest_purge_idx ON public.app_user USING btree (purgeable_after) WHERE ((kind = 'GUEST'::text) AND (purgeable_after IS NOT NULL));


--
-- Name: app_user_guest_serial_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX app_user_guest_serial_key ON public.app_user USING btree (guest_serial) WHERE (guest_serial IS NOT NULL);


--
-- Name: app_user_id_kind_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX app_user_id_kind_key ON public.app_user USING btree (user_id, kind);


--
-- Name: app_user_skeleton_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX app_user_skeleton_key ON public.app_user USING btree (username_skeleton);


--
-- Name: app_user_username_trgm_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX app_user_username_trgm_idx ON public.app_user USING gin (lower(username) public.gin_trgm_ops) WHERE ((kind = 'REGISTERED'::text) AND (deleted_at IS NULL));


--
-- Name: friend_request_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX friend_request_expiry_idx ON public.friend_request USING btree (expires_at);


--
-- Name: friend_request_pair_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX friend_request_pair_key ON public.friend_request USING btree (sender_id, recipient_id);


--
-- Name: friend_request_recipient_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX friend_request_recipient_idx ON public.friend_request USING btree (recipient_id, created_at DESC);


--
-- Name: friendship_high_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX friendship_high_idx ON public.friendship USING btree (user_id_high);


--
-- Name: game_input_outbox_pending_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_input_outbox_pending_idx ON public.game_input_outbox USING btree (session_id, id) WHERE (dispatched_at IS NULL);


--
-- Name: game_owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_owner_idx ON public.game USING btree (owner_user_id) WHERE (deleted_at IS NULL);


--
-- Name: game_public_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_public_idx ON public.game USING btree (created_at DESC) WHERE ((deleted_at IS NULL) AND (archived_at IS NULL) AND listed AND (badge <> 'UNVERIFIED'::text));


--
-- Name: game_review_approved_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_review_approved_idx ON public.game_review USING btree (game_id, decided_at DESC) WHERE (decision = 'APPROVED'::text);


--
-- Name: game_review_open_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX game_review_open_key ON public.game_review USING btree (game_id) WHERE (decided_at IS NULL);


--
-- Name: game_review_queue_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_review_queue_idx ON public.game_review USING btree (submitted_at) WHERE (decided_at IS NULL);


--
-- Name: game_session_completion_game_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_session_completion_game_idx ON public.game_session_completion USING btree (game_id, completed_at DESC);


--
-- Name: game_tag_trgm_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_tag_trgm_idx ON public.game_tag USING gin (lower(tag) public.gin_trgm_ops);


--
-- Name: game_title_trgm_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_title_trgm_idx ON public.game USING gin (lower(title) public.gin_trgm_ops);


--
-- Name: game_version_active_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX game_version_active_key ON public.game_version USING btree (game_id) WHERE (status = 'ACTIVE'::text);


--
-- Name: game_version_gdk_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_version_gdk_idx ON public.game_version USING btree (gdk_api_version) WHERE (status = ANY (ARRAY['ACTIVE'::text, 'SUPERSEDED'::text]));


--
-- Name: game_version_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX game_version_key ON public.game_version USING btree (game_id, version);


--
-- Name: game_version_order_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_version_order_idx ON public.game_version USING btree (game_id, version_major, version_minor, version_patch);


--
-- Name: game_version_players_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_version_players_idx ON public.game_version USING btree (min_players, max_players) WHERE (status = 'ACTIVE'::text);


--
-- Name: game_version_stuck_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_version_stuck_idx ON public.game_version USING btree (claimed_until) WHERE (status = 'PROCESSING'::text);


--
-- Name: game_version_upload_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX game_version_upload_expiry_idx ON public.game_version USING btree (upload_expires_at) WHERE (status = 'PENDING'::text);


--
-- Name: ghe_session_user_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ghe_session_user_key ON public.game_history_entry USING btree (session_id, user_id);


--
-- Name: ghe_user_game_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ghe_user_game_idx ON public.game_history_entry USING btree (user_id, game_id) WHERE (user_id IS NOT NULL);


--
-- Name: ghe_user_name_trgm_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ghe_user_name_trgm_idx ON public.game_history_entry USING gin (user_id, lower(game_name_snapshot) public.gin_trgm_ops) WHERE (user_id IS NOT NULL);


--
-- Name: ghe_user_recent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ghe_user_recent_idx ON public.game_history_entry USING btree (user_id, completed_at DESC) WHERE (user_id IS NOT NULL);


--
-- Name: notification_friend_request_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notification_friend_request_idx ON public.notification USING btree (friend_request_id) WHERE (friend_request_id IS NOT NULL);


--
-- Name: notification_invitation_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notification_invitation_idx ON public.notification USING btree (invitation_id) WHERE (invitation_id IS NOT NULL);


--
-- Name: notification_purge_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notification_purge_idx ON public.notification USING btree (created_at) WHERE ((friend_request_id IS NULL) AND (invitation_id IS NULL));


--
-- Name: notification_seq_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX notification_seq_key ON public.notification USING btree (event_seq);


--
-- Name: notification_undelivered_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notification_undelivered_idx ON public.notification USING btree (user_id, event_seq) WHERE (delivered_at IS NULL);


--
-- Name: notification_unread_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notification_unread_idx ON public.notification USING btree (user_id) WHERE (read_at IS NULL);


--
-- Name: notification_user_recent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notification_user_recent_idx ON public.notification USING btree (user_id, created_at DESC);


--
-- Name: room_active_session_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_active_session_idx ON public.room USING btree (active_session_id) WHERE (active_session_id IS NOT NULL);


--
-- Name: room_closed_purge_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_closed_purge_idx ON public.room USING btree (closed_at) WHERE (state = 'CLOSED'::text);


--
-- Name: room_idle_lobby_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_idle_lobby_idx ON public.room USING btree (last_activity_at) WHERE (state = 'LOBBY'::text);


--
-- Name: room_invitation_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_invitation_expiry_idx ON public.room_invitation USING btree (expires_at);


--
-- Name: room_invitation_invitee_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_invitation_invitee_idx ON public.room_invitation USING btree (invitee_user_id);


--
-- Name: room_invitation_inviter_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_invitation_inviter_idx ON public.room_invitation USING btree (inviter_user_id);


--
-- Name: room_invitation_pair_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX room_invitation_pair_key ON public.room_invitation USING btree (room_id, invitee_user_id);


--
-- Name: room_open_game_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_open_game_idx ON public.room USING btree (game_id, game_version) WHERE (state <> 'CLOSED'::text);


--
-- Name: room_open_state_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_open_state_idx ON public.room USING btree (state) WHERE (state <> 'CLOSED'::text);


--
-- Name: room_participant_grace_room_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_participant_grace_room_idx ON public.room_participant USING btree (room_id, grace_expires_at) WHERE ((grace_expires_at IS NOT NULL) AND (left_at IS NULL));


--
-- Name: room_participant_live_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_participant_live_user_idx ON public.room_participant USING btree (user_id) WHERE (left_at IS NULL);


--
-- Name: room_participant_unannounced_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX room_participant_unannounced_idx ON public.room_participant USING btree (room_id) WHERE ((left_at IS NOT NULL) AND (lost_announced_at IS NULL));


--
-- Name: session_teardown_due_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX session_teardown_due_idx ON public.session_teardown USING btree (next_attempt_at);


--
-- Name: user_auth_method_per_user_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX user_auth_method_per_user_key ON public.user_auth_method USING btree (user_id, method);


--
-- Name: user_auth_method_subject_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX user_auth_method_subject_key ON public.user_auth_method USING btree (method, provider_subject) WHERE (provider_subject IS NOT NULL);


--
-- Name: user_session_active_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX user_session_active_key ON public.user_session USING btree (user_id) WHERE (revoked_at IS NULL);


--
-- Name: user_session_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX user_session_expiry_idx ON public.user_session USING btree (expires_at) WHERE (revoked_at IS NULL);


--
-- Name: friend_request friend_request_recipient_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.friend_request
    ADD CONSTRAINT friend_request_recipient_fk FOREIGN KEY (recipient_id, recipient_kind) REFERENCES public.app_user(user_id, kind) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: friend_request friend_request_sender_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.friend_request
    ADD CONSTRAINT friend_request_sender_fk FOREIGN KEY (sender_id, sender_kind) REFERENCES public.app_user(user_id, kind) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: friendship friendship_high_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.friendship
    ADD CONSTRAINT friendship_high_fk FOREIGN KEY (user_id_high, kind_high) REFERENCES public.app_user(user_id, kind) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: friendship friendship_low_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.friendship
    ADD CONSTRAINT friendship_low_fk FOREIGN KEY (user_id_low, kind_low) REFERENCES public.app_user(user_id, kind) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: game game_active_version_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game
    ADD CONSTRAINT game_active_version_fk FOREIGN KEY (active_version_id) REFERENCES public.game_version(version_id);


--
-- Name: game_history_entry game_history_entry_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_history_entry
    ADD CONSTRAINT game_history_entry_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.game_session_completion(session_id);


--
-- Name: game_history_entry game_history_entry_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_history_entry
    ADD CONSTRAINT game_history_entry_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(user_id);


--
-- Name: game game_owner_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game
    ADD CONSTRAINT game_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES public.app_user(user_id);


--
-- Name: game_review game_review_game_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_review
    ADD CONSTRAINT game_review_game_id_fkey FOREIGN KEY (game_id) REFERENCES public.game(game_id) ON DELETE CASCADE;


--
-- Name: game_review game_review_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_review
    ADD CONSTRAINT game_review_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.game_version(version_id);


--
-- Name: game_tag game_tag_game_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_tag
    ADD CONSTRAINT game_tag_game_id_fkey FOREIGN KEY (game_id) REFERENCES public.game(game_id) ON DELETE CASCADE;


--
-- Name: game_version game_version_game_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_version
    ADD CONSTRAINT game_version_game_id_fkey FOREIGN KEY (game_id) REFERENCES public.game(game_id) ON DELETE CASCADE;


--
-- Name: notification notification_friend_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_friend_request_id_fkey FOREIGN KEY (friend_request_id) REFERENCES public.friend_request(request_id) ON DELETE CASCADE;


--
-- Name: notification notification_invitation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_invitation_id_fkey FOREIGN KEY (invitation_id) REFERENCES public.room_invitation(invitation_id) ON DELETE CASCADE;


--
-- Name: notification notification_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(user_id) ON DELETE CASCADE;


--
-- Name: room room_game_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room
    ADD CONSTRAINT room_game_id_fkey FOREIGN KEY (game_id) REFERENCES public.game(game_id);


--
-- Name: room room_host_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room
    ADD CONSTRAINT room_host_user_id_fkey FOREIGN KEY (host_user_id) REFERENCES public.app_user(user_id);


--
-- Name: room_invitation room_invitation_invitee_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_invitation
    ADD CONSTRAINT room_invitation_invitee_user_id_fkey FOREIGN KEY (invitee_user_id) REFERENCES public.app_user(user_id) ON DELETE CASCADE;


--
-- Name: room_invitation room_invitation_inviter_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_invitation
    ADD CONSTRAINT room_invitation_inviter_user_id_fkey FOREIGN KEY (inviter_user_id) REFERENCES public.app_user(user_id) ON DELETE CASCADE;


--
-- Name: room_invitation room_invitation_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_invitation
    ADD CONSTRAINT room_invitation_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.room(room_id) ON DELETE CASCADE;


--
-- Name: room_participant room_participant_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_participant
    ADD CONSTRAINT room_participant_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.room(room_id) ON DELETE CASCADE;


--
-- Name: room_participant room_participant_team_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_participant
    ADD CONSTRAINT room_participant_team_id_fkey FOREIGN KEY (team_id) REFERENCES public.room_team(team_id);


--
-- Name: room_participant room_participant_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_participant
    ADD CONSTRAINT room_participant_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(user_id);


--
-- Name: room_team room_team_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_team
    ADD CONSTRAINT room_team_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.room(room_id) ON DELETE CASCADE;


--
-- Name: user_auth_method user_auth_method_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_auth_method
    ADD CONSTRAINT user_auth_method_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(user_id) ON DELETE CASCADE;


--
-- Name: user_privacy_settings user_privacy_settings_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_privacy_settings
    ADD CONSTRAINT user_privacy_settings_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(user_id) ON DELETE CASCADE;


--
-- Name: user_session user_session_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_session
    ADD CONSTRAINT user_session_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(user_id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

\unrestrict S3UbB1M4DHr5T7WEpK1IXSrm5dw7Uc4xagKSsdIVc6lhz90kDendncFRlrzxsVy

