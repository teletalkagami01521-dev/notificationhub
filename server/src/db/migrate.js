import { db } from './client.js'

export async function migrate() {
    await db.query(`ALTER TABLE typed_text ADD COLUMN IF NOT EXISTS target_name TEXT NOT NULL DEFAULT ''`)
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
