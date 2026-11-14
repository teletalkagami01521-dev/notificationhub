import { Router } from 'express'
import { db } from '../db/client.js'
import { broadcast } from '../sse.js'

const router = Router()

// POST /api/typed-text — called by the Android accessibility service
router.post('/', async (req, res) => {
    const { deviceId, packageName, appLabel, text, capturedAt } = req.body

    if (!deviceId || !packageName || !text || !capturedAt) {
        return res.status(400).json({ error: 'Missing required fields: deviceId, packageName, text, capturedAt' })
    }

    try {
        const result = await db.query(
            `INSERT INTO typed_text (device_id, package_name, app_label, text, captured_at)
             VALUES ($1, $2, $3, $4, $5)
             RETURNING id, device_id, package_name, app_label, text, captured_at, received_at`,
            [deviceId, packageName, appLabel ?? '', text, capturedAt]
        )
        const row = result.rows[0]
        broadcast('typedtext', row)
        return res.status(201).json({ id: row.id, status: 'created' })
    } catch (err) {
        console.error('POST /api/typed-text error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

// GET /api/typed-text — paginated history for later usage
router.get('/', async (req, res) => {
    const limit  = Math.min(200, Math.max(1, parseInt(req.query.limit ?? '50')))
    const offset = Math.max(0, parseInt(req.query.offset ?? '0'))
    const device = req.query.device  || null
    const pkg    = req.query.package || null
    const search = req.query.search  || null

    const conditions = []
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
        conditions.push(`text ILIKE $${params.length}`)
    }

    const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : ''

    try {
        const [dataResult, countResult] = await Promise.all([
            db.query(
                `SELECT id, device_id, package_name, app_label, text, captured_at, received_at
                 FROM typed_text
                 ${where}
                 ORDER BY received_at DESC
                 LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
                [...params, limit, offset]
            ),
            db.query(`SELECT COUNT(*) FROM typed_text ${where}`, params)
        ])

        const total = parseInt(countResult.rows[0].count)
        return res.json({
            data: dataResult.rows,
            pagination: { limit, offset, total, hasMore: offset + limit < total }
        })
    } catch (err) {
        console.error('GET /api/typed-text error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

// DELETE /api/typed-text — bulk hard-delete by id list
router.delete('/', async (req, res) => {
    const { ids } = req.body ?? {}
    if (!Array.isArray(ids) || ids.length === 0) return res.status(400).json({ error: 'ids array required' })
    try {
        const result = await db.query('DELETE FROM typed_text WHERE id = ANY($1::bigint[])', [ids])
        return res.json({ deleted: result.rowCount })
    } catch (err) {
        console.error('DELETE /api/typed-text error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
