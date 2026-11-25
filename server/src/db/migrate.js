import { db } from './client.js'

export async function migrate() {
    await db.query(`CREATE INDEX IF NOT EXISTS idx_notif_throttle ON notifications (device_id, package_name, received_at DESC)`)
    await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS target_name TEXT NOT NULL DEFAULT ''`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS listener_connected BOOLEAN NOT NULL DEFAULT false`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS foreground_service_running BOOLEAN NOT NULL DEFAULT false`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS watchdog_enabled BOOLEAN NOT NULL DEFAULT false`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS boot_time TIMESTAMPTZ`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS install_time TIMESTAMPTZ`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS update_time TIMESTAMPTZ`)
    await db.query(`ALTER TABLE app_status ADD COLUMN IF NOT EXISTS last_listener_event_at TIMESTAMPTZ`)
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
