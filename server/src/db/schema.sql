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
    posted_at    TIMESTAMPTZ  NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    dedup_hash   VARCHAR(64)  NOT NULL,
    is_read      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_archived  BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Server-side deduplication: same device cannot post the same hash twice
    CONSTRAINT uq_device_dedup UNIQUE (device_id, dedup_hash)
);

CREATE INDEX IF NOT EXISTS idx_notif_received ON notifications (received_at DESC);
CREATE INDEX IF NOT EXISTS idx_notif_device   ON notifications (device_id);
CREATE INDEX IF NOT EXISTS idx_notif_package  ON notifications (package_name);
CREATE INDEX IF NOT EXISTS idx_notif_unread   ON notifications (is_read) WHERE is_read = FALSE;
