import { Router } from 'express'
import { db } from '../db/client.js'
import { addClient, broadcast } from '../sse.js'
import { sendFcm } from '../fcm.js'
import { uploadImage, deleteImage } from '../cloudinary.js'
import { NOTIFICATION_ROLE_PRIORITY } from '../appRoles.js'

const router = Router()
const BLOCKED_PHRASE = 'choose input method'

function normalizeIds(value) {
    if (!Array.isArray(value)) return null
    const ids = value.map(String).filter(id => /^\d+$/.test(id)).slice(0, 1000)
    return ids.length ? ids : null
}

function isBlockedSystemNotification(packageName, appLabel, title, textBody, bigText, subText) {
    if (packageName !== 'android' && String(appLabel ?? '').trim().toLowerCase() !== 'android system') return false
    if (String(title ?? '').trim().toLowerCase() === BLOCKED_PHRASE) return true
    return `${textBody ?? ''} ${bigText ?? ''} ${subText ?? ''}`.toLowerCase().includes(BLOCKED_PHRASE)
}

// Same-content repeats are throttled. System packages only get an additional
// short marker-based cooldown so a real message from those packages still passes.
const CONTENT_THROTTLED_PACKAGES = new Set([
    'com.daraz.android',
    'com.google.android.gms',
])
const SYSTEM_SPAM_PREFIXES = ['com.miui.', 'com.xiaomi.']
const SYSTEM_SPAM_MARKERS = [
    'choose input method', 'security scan', 'security check', 'antivirus', 'virus',
    'cleanup', 'clean master', 'junk file', 'battery saver', 'power saver',
    'battery optimization', 'boost', 'optimize', 'optimization', 'memory usage',
    'storage full', 'data usage', 'network speed', 'app lock', 'privacy protection',
    'permission request', 'system update', 'update available', 'recommended for you',
    'suggestion',
]
const MARKER_THROTTLE_MS = 10 * 60 * 1000

function isSystemSpamPackage(packageName) {
    return SYSTEM_SPAM_PREFIXES.some(prefix => String(packageName ?? '').startsWith(prefix))
}

function systemSpamMarkers(title, textBody, bigText, subText) {
    const haystack = `${title ?? ''} ${textBody ?? ''} ${bigText ?? ''} ${subText ?? ''}`.toLowerCase()
    return SYSTEM_SPAM_MARKERS.filter(marker => haystack.includes(marker))
}

function sameContent(a, b) {
    return (a ?? null) === (b ?? null)
}

async function hasEarlierSameMinuteDuplicate(row) {
    const postedAt = row.posted_at instanceof Date ? row.posted_at.toISOString() : row.posted_at
    const result = await db.query(
        `SELECT 1
           FROM notifications
          WHERE device_id = $1
            AND package_name = $2
            AND date_trunc('minute', posted_at) = date_trunc('minute', $3::timestamptz)
            AND COALESCE(app_label, '') = COALESCE($4, '')
            AND COALESCE(title, '') = COALESCE($5, '')
            AND COALESCE(text_body, '') = COALESCE($6, '')
            AND COALESCE(big_text, '') = COALESCE($7, '')
            AND COALESCE(sub_text, '') = COALESCE($8, '')
            AND COALESCE(sender_name, '') = COALESCE($9, '')
            AND COALESCE(chat_body, '') = COALESCE($10, '')
            AND COALESCE(image_source, '') = COALESCE($11, '')
            AND photo_intent = $12::boolean
            AND (image_url IS NOT NULL) = $13::boolean
            AND COALESCE(conversation_title, '') = COALESCE($14, '')
            AND id < $15
          LIMIT 1`,
        [
            row.device_id,
            row.package_name,
            postedAt,
            row.app_label,
            row.title,
            row.text_body,
            row.big_text,
            row.sub_text,
            row.sender_name,
            row.chat_body,
            row.image_source,
            row.photo_intent,
            row.image_url != null,
            row.conversation_title,
            row.id,
        ]
    )
    return result.rowCount > 0
}

// Never expose the raw Cloudinary URL to clients. Images are served through the
// authenticated proxy at /api/notifications/:id/image instead.
function publicRow(r) {
    let relatedThumbnails = []
    try {
        const parsed = JSON.parse(r.related_media_thumbnails || '[]')
        if (Array.isArray(parsed)) relatedThumbnails = parsed.filter(url => typeof url === 'string')
    } catch {}
    return {
        ...r,
        image_url: r.image_url ? `/api/notifications/${r.id}/image` : null,
        related_media_url: r.related_media_url ? `/api/media/${r.related_media_id}/file` : null,
        related_media_thumbnails: relatedThumbnails.map((_, index) =>
            `/api/media/${r.related_media_id}/frames/${index}/file`),
        repeat_count: Number(r.repeat_count ?? 1),
        repeat_ids: Array.isArray(r.repeat_ids) ? r.repeat_ids.map(String) : [String(r.id)],
        repeat_items: Array.isArray(r.repeat_items) ? r.repeat_items.map(item => ({
            ...item,
            id: String(item.id),
            image_url: item.image_url ? `/api/notifications/${item.id}/image` : null,
            is_read: item.is_read !== false,
        })) : undefined,
        repeat_unread_count: Number(r.repeat_unread_count ?? (r.is_read === false ? 1 : 0)),
    }
}

// POST /api/notifications — called by Android client
router.post('/', async (req, res) => {
    const { deviceId, packageName, appLabel, title, textBody, bigText, subText, senderName, chatBody, postedAt, dedupHash, imageData, imageSource, photoIntent, relatedNotificationHash, appRole, notificationKey, channelId, category, groupKey, sortKey, conversationTitle, flags, extrasJson } = req.body
    const cleanSender = String(senderName ?? '').trim().slice(0, 255)
    const cleanChatBody = chatBody == null ? null : String(chatBody).trim().slice(0, 4000)
    const cleanImageSource = imageSource == null ? null : String(imageSource).trim().slice(0, 64)
    const cleanRelatedHash = relatedNotificationHash == null ? null : String(relatedNotificationHash).trim().slice(0, 64)
    const cleanPhotoIntent = photoIntent === true || String(photoIntent).toLowerCase() === 'true'
    const cleanNotificationKey = notificationKey == null ? null : String(notificationKey).slice(0, 255) || null
    const cleanChannelId = channelId == null ? null : String(channelId).slice(0, 255) || null
    const cleanCategory = category == null ? null : String(category).slice(0, 255) || null
    const cleanGroupKey = groupKey == null ? null : String(groupKey).slice(0, 500) || null
    const cleanSortKey = sortKey == null ? null : String(sortKey).slice(0, 500) || null
    const cleanConversationTitle = conversationTitle == null ? null : String(conversationTitle).slice(0, 1000) || null
    const cleanFlags = Number(flags) || 0
    const cleanExtrasJson = extrasJson == null ? null : String(extrasJson).slice(0, 200000)
    let uploadedImageUrl = null

    if (!deviceId || !packageName || !postedAt || !dedupHash) {
        return res.status(400).json({ error: 'Missing required fields: deviceId, packageName, postedAt, dedupHash' })
    }
    if (appRole && !NOTIFICATION_ROLE_PRIORITY.includes(appRole)) {
        return res.status(400).json({ error: 'Unknown appRole' })
    }
    if (isBlockedSystemNotification(packageName, appLabel, title, textBody, bigText, subText)) {
        return res.status(202).json({ status: 'ignored' })
    }

    try {
        const throttleContent = CONTENT_THROTTLED_PACKAGES.has(packageName) || isSystemSpamPackage(packageName)
        if (throttleContent) {
            const recent = await db.query(
                `SELECT title, text_body, big_text, sub_text, sender_name, chat_body, received_at
                   FROM notifications
                  WHERE device_id = $1 AND package_name = $2
                    AND received_at > now() - interval '30 minutes'
                  LIMIT 200`,
                [deviceId, packageName]
            )
            const duplicate = recent.rows.some(row =>
                sameContent(row.title, title) &&
                sameContent(row.text_body, textBody) &&
                sameContent(row.big_text, bigText) &&
                sameContent(row.sub_text, subText) &&
                sameContent(row.sender_name, cleanSender) &&
                sameContent(row.chat_body, cleanChatBody)
            )
            if (duplicate) return res.status(409).json({ status: 'throttled' })

            if (isSystemSpamPackage(packageName)) {
                const markers = systemSpamMarkers(title, textBody, bigText, subText)
                const recentMarker = markers.length && recent.rows.some(row => {
                    if (Date.now() - new Date(row.received_at).getTime() > MARKER_THROTTLE_MS) return false
                    const rowMarkers = systemSpamMarkers(row.title, row.text_body, row.big_text, row.sub_text)
                    return rowMarkers.some(marker => markers.includes(marker))
                })
                if (recentMarker) return res.status(409).json({ status: 'throttled' })
            }
        }

        let imageUrl = null
        if (imageData) {
            imageUrl = await uploadImage(imageData)
            uploadedImageUrl = imageUrl
            if (!imageUrl) {
                console.error('notification image upload failed', { packageName, dedupHash })
                return res.status(503).json({ error: 'Image upload unavailable', status: 'image_upload_failed' })
            }
        }

        const result = await db.query(
            `INSERT INTO notifications (device_id, package_name, app_label, title, text_body, big_text, sub_text, sender_name, chat_body, posted_at, dedup_hash, image_url, image_source, photo_intent, related_notification_hash, notification_key, channel_id, category, group_key, sort_key, conversation_title, flags, extras_json)
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23)
             RETURNING *`,
            [deviceId, packageName, appLabel ?? '', title, textBody, bigText, subText, cleanSender, cleanChatBody, postedAt, dedupHash, imageUrl, cleanImageSource, cleanPhotoIntent, cleanRelatedHash, cleanNotificationKey, cleanChannelId, cleanCategory, cleanGroupKey, cleanSortKey, cleanConversationTitle, cleanFlags, cleanExtrasJson]
        )
        const row = result.rows[0]
        broadcast('notification', publicRow(row))
        if (!(await hasEarlierSameMinuteDuplicate(row))) await sendFcm(row)
        return res.status(201).json({ id: row.id, status: 'created' })
    } catch (err) {
        // Unique constraint violation = server already has this notification
        if (err.code === '23505') {
            if (uploadedImageUrl) await deleteImage(uploadedImageUrl).catch(() => {})
            return res.status(409).json({ status: 'duplicate' })
        }
        console.error('POST /api/notifications error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

// GET /api/notifications — paginated feed for the dashboard. Identical repeats
// inside the same minute are collapsed here, never in the Android client.
router.get('/', async (req, res) => {
    const page    = Math.max(1, parseInt(req.query.page ?? '1'))
    const limit   = Math.min(100, Math.max(1, parseInt(req.query.limit ?? '50')))
    const offset  = (page - 1) * limit
    const device  = req.query.device  || null
    const pkg     = req.query.package || null
    const search  = req.query.search  || null
    const unread  = req.query.unread === 'true' ? true : null
    const raw     = req.query.raw === 'true'

    const conditions = ['n.is_archived = FALSE']
    const params = []

    if (device) {
        params.push(device)
        conditions.push(`n.device_id = $${params.length}`)
    }
    if (pkg) {
        params.push(pkg)
        conditions.push(`n.package_name = $${params.length}`)
    }
    if (search) {
        params.push(`%${search}%`)
        conditions.push(`(n.title ILIKE $${params.length} OR n.text_body ILIKE $${params.length} OR n.big_text ILIKE $${params.length} OR n.sub_text ILIKE $${params.length} OR n.sender_name ILIKE $${params.length} OR n.chat_body ILIKE $${params.length} OR n.app_label ILIKE $${params.length} OR n.package_name ILIKE $${params.length})`)
    }
    if (unread) {
        conditions.push('n.is_read = FALSE')
    }

    const where = conditions.join(' AND ')
    const groupExpressions = `date_trunc('minute', f.posted_at), f.device_id, f.package_name,
        COALESCE(f.app_label, ''), COALESCE(f.title, ''), COALESCE(f.text_body, ''),
        COALESCE(f.big_text, ''), COALESCE(f.sub_text, ''), COALESCE(f.sender_name, ''),
        COALESCE(f.chat_body, ''), COALESCE(f.image_source, ''), f.photo_intent,
        (f.image_url IS NOT NULL), COALESCE(f.conversation_title, '')`
    const groupColumns = `date_trunc('minute', f.posted_at) AS group_minute,
        f.device_id AS device_id, f.package_name AS package_name,
        COALESCE(f.app_label, '') AS app_label, COALESCE(f.title, '') AS title,
        COALESCE(f.text_body, '') AS text_body, COALESCE(f.big_text, '') AS big_text,
        COALESCE(f.sub_text, '') AS sub_text, COALESCE(f.sender_name, '') AS sender_name,
        COALESCE(f.chat_body, '') AS chat_body, COALESCE(f.image_source, '') AS image_source,
        f.photo_intent AS photo_intent, (f.image_url IS NOT NULL) AS has_image,
        COALESCE(f.conversation_title, '') AS conversation_title`

    try {
        let dataSql, countSql
        if (raw) {
            dataSql = `SELECT n.id, n.device_id, n.package_name, n.app_label, n.title, n.text_body, n.big_text, n.sub_text, n.sender_name, n.chat_body, n.posted_at, n.received_at, n.dedup_hash, n.is_read, n.image_url, n.image_source, n.photo_intent, n.related_notification_hash, n.notification_key, n.channel_id, n.category, n.group_key, n.sort_key, n.conversation_title, n.flags, n.extras_json,
                         rm.id AS related_media_id, rm.media_url AS related_media_url,
                         (SELECT mt.thumbnail_urls FROM media_captures mt WHERE mt.id = rm.id) AS related_media_thumbnails
                  FROM notifications n
                  LEFT JOIN LATERAL (
                      SELECT id, media_url FROM media_captures
                      WHERE related_notification_hash = n.dedup_hash
                      ORDER BY captured_at ASC, id ASC LIMIT 1
                  ) rm ON TRUE
                  WHERE ${where}
                  ORDER BY n.posted_at DESC, n.received_at DESC
                  LIMIT $${params.length + 1} OFFSET $${params.length + 2}`
            countSql = `SELECT COUNT(*) FROM notifications n WHERE ${where}`
        } else {
            dataSql = `WITH filtered AS (
                         SELECT n.*
                         FROM notifications n
                         WHERE ${where}
                       ), groups AS (
                         SELECT ${groupColumns},
                                (ARRAY_AGG(id ORDER BY posted_at DESC, received_at DESC, id DESC))[1] AS id,
                                ARRAY_AGG(id ORDER BY posted_at DESC, received_at DESC, id DESC) AS repeat_ids,
                                 ARRAY_AGG(dedup_hash ORDER BY posted_at DESC, received_at DESC, id DESC) AS repeat_hashes,
                                 JSON_AGG(JSON_BUILD_OBJECT(
                                     'id', id, 'posted_at', posted_at, 'received_at', received_at,
                                     'is_read', is_read, 'dedup_hash', dedup_hash, 'image_url', image_url,
                                     'title', title, 'text_body', text_body, 'sender_name', sender_name,
                                     'chat_body', chat_body, 'big_text', big_text, 'sub_text', sub_text,
                                     'notification_key', notification_key
                                 ) ORDER BY posted_at DESC, received_at DESC, id DESC) AS repeat_items,
                                 COUNT(*)::int AS repeat_count,
                                COUNT(*) FILTER (WHERE is_read = FALSE)::int AS repeat_unread_count,
                                MAX(posted_at) AS posted_at,
                                MAX(received_at) AS received_at,
                                (ARRAY_AGG(image_url ORDER BY (image_url IS NOT NULL) DESC, posted_at DESC, id DESC))[1] AS image_url
                          FROM filtered f
                          GROUP BY 1,2,3,4,5,6,7,8,9,10,11,12,13,14
                        ), representative AS (
                          SELECT g.id, g.device_id, g.package_name, g.app_label, g.title, g.text_body, g.big_text, g.sub_text, g.sender_name, g.chat_body, g.posted_at, g.received_at, n.dedup_hash, (g.repeat_unread_count = 0) AS is_read, g.image_url, g.image_source, g.photo_intent, n.related_notification_hash, n.notification_key, n.channel_id, n.category, n.group_key, n.sort_key, n.conversation_title, n.flags, n.extras_json, g.repeat_ids, g.repeat_hashes, g.repeat_items, g.repeat_count, g.repeat_unread_count
                          FROM groups g
                          JOIN notifications n ON n.id = g.id
                       )
                       SELECT r.id, r.device_id, r.package_name, r.app_label, r.title, r.text_body, r.big_text, r.sub_text, r.sender_name, r.chat_body, r.posted_at, r.received_at, r.dedup_hash, r.is_read, r.image_url, r.image_source, r.photo_intent, r.related_notification_hash, r.notification_key, r.channel_id, r.category, r.group_key, r.sort_key, r.conversation_title, r.flags, r.extras_json, r.repeat_ids, r.repeat_items, r.repeat_count, r.repeat_unread_count,
                              rm.id AS related_media_id, rm.media_url AS related_media_url,
                              (SELECT mt.thumbnail_urls FROM media_captures mt WHERE mt.id = rm.id) AS related_media_thumbnails
                       FROM representative r
                       LEFT JOIN LATERAL (
                           SELECT id, media_url FROM media_captures
                           WHERE related_notification_hash = ANY(r.repeat_hashes)
                           ORDER BY captured_at ASC, id ASC LIMIT 1
                       ) rm ON TRUE
                       ORDER BY r.posted_at DESC, r.received_at DESC
                       LIMIT $${params.length + 1} OFFSET $${params.length + 2}`
            countSql = `WITH filtered AS (
                          SELECT n.* FROM notifications n WHERE ${where}
                        ), groups AS (
                           SELECT 1 FROM filtered f GROUP BY ${groupExpressions}
                         )
                        SELECT COUNT(*) FROM groups`
        }

        const [dataResult, countResult] = await Promise.all([
            db.query(dataSql, [...params, limit, offset]),
            db.query(countSql, params)
        ])

        const total = parseInt(countResult.rows[0].count)
        return res.json({
            data: dataResult.rows.map(publicRow),
            pagination: { page, limit, total, hasMore: offset + limit < total }
        })
    } catch (err) {
        console.error('GET /api/notifications error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

// PATCH /api/notifications/read — bulk mark-read for collapsed render rows
router.patch('/read', async (req, res) => {
    const ids = normalizeIds(req.body?.ids)
    if (!ids) return res.status(400).json({ error: 'ids array required' })
    try {
        await db.query('UPDATE notifications SET is_read = TRUE WHERE id = ANY($1::bigint[])', [ids])
        broadcast('notification:read', { ids })
        return res.json({ status: 'ok', updated: ids.length })
    } catch (err) {
        return res.status(500).json({ error: err.message })
    }
})

// PATCH /api/notifications/archive — bulk archive for collapsed render rows
router.patch('/archive', async (req, res) => {
    const ids = normalizeIds(req.body?.ids)
    if (!ids) return res.status(400).json({ error: 'ids array required' })
    try {
        await db.query('UPDATE notifications SET is_archived = TRUE WHERE id = ANY($1::bigint[])', [ids])
        broadcast('notification:archived', { ids })
        return res.json({ status: 'ok', updated: ids.length })
    } catch (err) {
        return res.status(500).json({ error: err.message })
    }
})

// PATCH /api/notifications/:id/read
router.patch('/:id/read', async (req, res) => {
    try {
        await db.query('UPDATE notifications SET is_read = TRUE WHERE id = $1', [req.params.id])
        broadcast('notification:read', { ids: [String(req.params.id)] })
        return res.json({ status: 'ok' })
    } catch (err) {
        return res.status(500).json({ error: err.message })
    }
})

// PATCH /api/notifications/:id/archive
router.patch('/:id/archive', async (req, res) => {
    try {
        await db.query('UPDATE notifications SET is_archived = TRUE WHERE id = $1', [req.params.id])
        broadcast('notification:archived', { ids: [String(req.params.id)] })
        return res.json({ status: 'ok' })
    } catch (err) {
        return res.status(500).json({ error: err.message })
    }
})

// PATCH /api/notifications/mark-all-read
router.patch('/mark-all-read', async (req, res) => {
    try {
        const result = await db.query('UPDATE notifications SET is_read = TRUE WHERE is_read = FALSE')
        broadcast('notification:readAll', { updated: result.rowCount })
        return res.json({ updated: result.rowCount })
    } catch (err) {
        return res.status(500).json({ error: err.message })
    }
})

// GET /api/stream — SSE endpoint for real-time dashboard updates
router.get('/stream', (req, res) => {
    res.writeHead(200, {
        'Content-Type':  'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection':    'keep-alive',
        'X-Accel-Buffering': 'no'   // disable nginx buffering
    })
    res.write(': connected\n\n')
    addClient(res)
    res.write(`event: connected\ndata: ${JSON.stringify({ at: new Date().toISOString() })}\n\n`)
})

// DELETE /api/notifications — bulk hard-delete by id list
router.delete('/', async (req, res) => {
    const { ids } = req.body ?? {}
    if (!Array.isArray(ids) || ids.length === 0) return res.status(400).json({ error: 'ids array required' })
    try {
        // Fetch image URLs before deleting so we can clean up Cloudinary
        const imgResult = await db.query(
            'SELECT image_url FROM notifications WHERE id = ANY($1::bigint[]) AND image_url IS NOT NULL',
            [ids]
        )
        const result = await db.query('DELETE FROM notifications WHERE id = ANY($1::bigint[])', [ids])
        // Fire-and-forget Cloudinary cleanup
        imgResult.rows.forEach(row => deleteImage(row.image_url))
        broadcast('notification:deleted', { ids })
        return res.json({ deleted: result.rowCount })
    } catch (err) {
        console.error('DELETE /api/notifications error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

// GET /api/notifications/:id/image — authenticated image proxy.
// The browser never sees the raw Cloudinary URL; only callers with a valid
// dashboard token (or API key) can fetch the bytes.
router.get('/:id/image', async (req, res) => {
    try {
        const result = await db.query('SELECT image_url FROM notifications WHERE id = $1', [req.params.id])
        const url = result.rows[0]?.image_url
        if (!url) return res.status(404).json({ error: 'No image' })

        const upstream = await fetch(url)
        if (!upstream.ok) return res.status(502).json({ error: 'Upstream image error' })

        res.setHeader('Content-Type', upstream.headers.get('content-type') || 'image/jpeg')
        res.setHeader('Cache-Control', 'private, max-age=86400')
        const buf = Buffer.from(await upstream.arrayBuffer())
        return res.end(buf)
    } catch (err) {
        console.error('GET /api/notifications/:id/image error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

// DELETE /api/notifications/:id — permanent single delete + Cloudinary cleanup
router.delete('/:id', async (req, res) => {
    try {
        const result = await db.query(
            'SELECT image_url FROM notifications WHERE id = $1', [req.params.id]
        )
        const url = result.rows[0]?.image_url
        const del = await db.query('DELETE FROM notifications WHERE id = $1', [req.params.id])
        if (url) deleteImage(url)
        broadcast('notification:deleted', { ids: [req.params.id] })
        return res.json({ deleted: del.rowCount })
    } catch (err) {
        console.error('DELETE /api/notifications/:id error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

// POST /api/notifications/purge-images — remove Cloudinary images and clear the
// stored URL. scope='archived' (default) only touches archived rows; 'all' clears
// every image. Notification rows themselves are kept.
router.post('/purge-images', async (req, res) => {
    const scope = req.body?.scope === 'all' ? 'all' : 'archived'
    try {
        const where = scope === 'all'
            ? 'image_url IS NOT NULL'
            : 'image_url IS NOT NULL AND is_archived = TRUE'
        const result = await db.query(`SELECT id, image_url FROM notifications WHERE ${where}`)
        for (const row of result.rows) deleteImage(row.image_url)
        const ids = result.rows.map(r => r.id)
        if (ids.length) {
            await db.query('UPDATE notifications SET image_url = NULL WHERE id = ANY($1::bigint[])', [ids])
        }
        return res.json({ purged: ids.length, scope })
    } catch (err) {
        console.error('POST /api/notifications/purge-images error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
