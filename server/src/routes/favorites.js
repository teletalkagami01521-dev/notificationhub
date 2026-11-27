import { Router } from 'express'
import { db } from '../db/client.js'

const router = Router()
const KEYS = new Set(['n', 't', 'm'])

function mediaUrls(row) {
    const urls = []
    if (row.media_url) urls.push(row.media_url)
    try {
        const parsed = JSON.parse(row.thumbnail_urls || '[]')
        if (Array.isArray(parsed)) parsed.forEach(url => {
            if (typeof url === 'string' && !urls.includes(url)) urls.push(url)
        })
    } catch {}
    return urls
}

async function loadItem(itemKey, itemId) {
    if (itemKey === 'n') {
        const result = await db.query(
            `SELECT n.id, n.device_id, n.package_name, n.app_label, n.title, n.text_body, n.big_text, n.sub_text,
                    n.sender_name, n.chat_body, n.posted_at, n.received_at, n.dedup_hash, n.is_read, n.is_archived,
                    n.image_url, n.image_source, n.photo_intent, n.related_notification_hash,
                    rm.id AS related_media_id, rm.media_url AS related_media_url,
                    (SELECT mt.thumbnail_urls FROM media_captures mt WHERE mt.id = rm.id) AS related_media_thumbnails
             FROM notifications n
             LEFT JOIN LATERAL (
                 SELECT id, media_url FROM media_captures
                 WHERE related_notification_hash = n.dedup_hash
                 ORDER BY captured_at ASC, id ASC LIMIT 1
             ) rm ON TRUE
             WHERE n.id = $1`,
            [itemId]
        )
        const row = result.rows[0]
        if (!row) return null
        const thumbnails = mediaUrls({ media_url: row.related_media_url, thumbnail_urls: row.related_media_thumbnails })
        return {
            ...row,
            image_url: row.image_url ? `/api/notifications/${row.id}/image` : null,
            related_media_url: row.related_media_url ? `/api/media/${row.related_media_id}/file` : null,
            related_media_thumbnails: thumbnails.map((_, index) => `/api/media/${row.related_media_id}/frames/${index}/file`)
        }
    }
    if (itemKey === 't') {
        const result = await db.query(
            `SELECT id, device_id, package_name, app_label, target_name, raw_package, raw_app_label,
                    resolved_package, resolved_app_label, attribution_source, attribution_confidence,
                    target_source, text, captured_at, received_at
             FROM typed_text WHERE id = $1`,
            [itemId]
        )
        return result.rows[0] || null
    }
    const result = await db.query(
        `SELECT id, device_id, source, package_name, app_label, media_type, display_name,
                mime_type, original_mime_type, size_bytes, width, height, duration_ms,
                captured_at, received_at, dedup_hash, media_url, thumbnail_urls, related_notification_hash, action
         FROM media_captures WHERE id = $1`,
        [itemId]
    )
    const row = result.rows[0]
    if (!row) return null
    const urls = mediaUrls(row)
    const { thumbnail_urls, ...rest } = row
    return {
        ...rest,
        media_url: row.media_url ? `/api/media/${row.id}/file` : null,
        thumbnails: urls.map((_, index) => `/api/media/${row.id}/frames/${index}/file`)
    }
}

router.get('/', async (req, res) => {
    const device = req.query.device || null
    const params = []
    const where = device ? (params.push(device), 'WHERE device_id = $1') : ''
    try {
        const result = await db.query(
            `SELECT item_key, item_id FROM favorites ${where} ORDER BY created_at DESC`,
            params
        )
        const items = []
        for (const row of result.rows) {
            const item = await loadItem(row.item_key, row.item_id)
            if (item) items.push({ itemKey: row.item_key, itemId: row.item_id, item })
        }
        return res.json({ data: items })
    } catch (err) {
        console.error('GET /api/favorites error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

router.post('/', async (req, res) => {
    const { deviceId, itemKey, itemId } = req.body ?? {}
    if (!deviceId || !KEYS.has(itemKey) || !Number.isFinite(Number(itemId))) {
        return res.status(400).json({ error: 'deviceId, valid itemKey and itemId required' })
    }
    try {
        await db.query(
            `INSERT INTO favorites (device_id, item_key, item_id)
             VALUES ($1, $2, $3)
             ON CONFLICT (device_id, item_key, item_id) DO NOTHING`,
            [deviceId, itemKey, Number(itemId)]
        )
        return res.json({ status: 'ok' })
    } catch (err) {
        console.error('POST /api/favorites error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

router.delete('/', async (req, res) => {
    const { deviceId, itemKey, itemId } = req.body ?? {}
    if (!deviceId || !KEYS.has(itemKey) || !Number.isFinite(Number(itemId))) {
        return res.status(400).json({ error: 'deviceId, valid itemKey and itemId required' })
    }
    try {
        const result = await db.query(
            `DELETE FROM favorites WHERE device_id = $1 AND item_key = $2 AND item_id = $3`,
            [deviceId, itemKey, Number(itemId)]
        )
        return res.json({ deleted: result.rowCount })
    } catch (err) {
        console.error('DELETE /api/favorites error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
