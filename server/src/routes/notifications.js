import { Router } from 'express'
import { db } from '../db/client.js'
import { addClient, broadcast } from '../sse.js'
import { sendFcm } from '../fcm.js'
import { uploadImage, deleteImage } from '../cloudinary.js'

const router = Router()

// Never expose the raw Cloudinary URL to clients. Images are served through the
// authenticated proxy at /api/notifications/:id/image instead.
function publicRow(r) {
    return { ...r, image_url: r.image_url ? `/api/notifications/${r.id}/image` : null }
}

// POST /api/notifications — called by Android client
router.post('/', async (req, res) => {
    const { deviceId, packageName, appLabel, title, textBody, bigText, subText, postedAt, dedupHash, imageData } = req.body

    if (!deviceId || !packageName || !postedAt || !dedupHash) {
        return res.status(400).json({ error: 'Missing required fields: deviceId, packageName, postedAt, dedupHash' })
    }

    try {
        // Upload image to Cloudinary before INSERT so the URL is included in the SSE broadcast
        let imageUrl = null
        if (imageData) {
            imageUrl = await uploadImage(imageData)
        }

        const result = await db.query(
            `INSERT INTO notifications (device_id, package_name, app_label, title, text_body, big_text, sub_text, posted_at, dedup_hash, image_url)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
             RETURNING id, device_id, package_name, app_label, title, text_body, big_text, sub_text, posted_at, received_at, image_url`,
            [deviceId, packageName, appLabel ?? '', title, textBody, bigText, subText, postedAt, dedupHash, imageUrl]
        )
        const row = result.rows[0]
        broadcast('notification', publicRow(row))
        await sendFcm(row)
        return res.status(201).json({ id: row.id, status: 'created' })
    } catch (err) {
        // Unique constraint violation = server already has this notification
        if (err.code === '23505') {
            return res.status(409).json({ status: 'duplicate' })
        }
        console.error('POST /api/notifications error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

// GET /api/notifications — paginated feed for the dashboard
router.get('/', async (req, res) => {
    const page    = Math.max(1, parseInt(req.query.page ?? '1'))
    const limit   = Math.min(100, Math.max(1, parseInt(req.query.limit ?? '50')))
    const offset  = (page - 1) * limit
    const device  = req.query.device  || null
    const pkg     = req.query.package || null
    const search  = req.query.search  || null
    const unread  = req.query.unread === 'true' ? true : null

    const conditions = ['is_archived = FALSE']
    const params = []

    if (device) {
        params.push(device)
        conditions.push(`device_id = $${params.length}`)
    }
    if (pkg) {
        params.push(pkg)
        conditions.push(`package_name = $${params.length}`)
    }
    if (search) {
        params.push(`%${search}%`)
        conditions.push(`(title ILIKE $${params.length} OR text_body ILIKE $${params.length})`)
    }
    if (unread) {
        conditions.push('is_read = FALSE')
    }

    const where = conditions.join(' AND ')

    try {
        const [dataResult, countResult] = await Promise.all([
            db.query(
                `SELECT id, device_id, package_name, app_label, title, text_body, sub_text, posted_at, received_at, is_read, image_url
                 FROM notifications
                 WHERE ${where}
                 ORDER BY received_at DESC
                 LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
                [...params, limit, offset]
            ),
            db.query(`SELECT COUNT(*) FROM notifications WHERE ${where}`, params)
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

// PATCH /api/notifications/:id/read
router.patch('/:id/read', async (req, res) => {
    try {
        await db.query('UPDATE notifications SET is_read = TRUE WHERE id = $1', [req.params.id])
        return res.json({ status: 'ok' })
    } catch (err) {
        return res.status(500).json({ error: err.message })
    }
})

// PATCH /api/notifications/:id/archive
router.patch('/:id/archive', async (req, res) => {
    try {
        await db.query('UPDATE notifications SET is_archived = TRUE WHERE id = $1', [req.params.id])
        return res.json({ status: 'ok' })
    } catch (err) {
        return res.status(500).json({ error: err.message })
    }
})

// PATCH /api/notifications/mark-all-read
router.patch('/mark-all-read', async (req, res) => {
    try {
        const result = await db.query('UPDATE notifications SET is_read = TRUE WHERE is_read = FALSE')
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
