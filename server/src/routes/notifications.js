import { Router } from 'express'
import { db } from '../db/client.js'
import { addClient, broadcast } from '../sse.js'
import { sendFcm } from '../fcm.js'

const router = Router()

// POST /api/notifications — called by Android client
router.post('/', async (req, res) => {
    const { deviceId, packageName, appLabel, title, textBody, bigText, subText, postedAt, dedupHash } = req.body

    if (!deviceId || !packageName || !postedAt || !dedupHash) {
        return res.status(400).json({ error: 'Missing required fields: deviceId, packageName, postedAt, dedupHash' })
    }

    try {
        const result = await db.query(
            `INSERT INTO notifications (device_id, package_name, app_label, title, text_body, big_text, sub_text, posted_at, dedup_hash)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
             RETURNING id, device_id, package_name, app_label, title, text_body, big_text, sub_text, posted_at, received_at`,
            [deviceId, packageName, appLabel ?? '', title, textBody, bigText, subText, postedAt, dedupHash]
        )
        const row = result.rows[0]
        broadcast('notification', row)
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
                `SELECT id, device_id, package_name, app_label, title, text_body, sub_text, posted_at, received_at, is_read
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
            data: dataResult.rows,
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

export default router
