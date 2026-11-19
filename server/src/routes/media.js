import { Router } from 'express'
import { Readable } from 'node:stream'
import { db } from '../db/client.js'
import { broadcast } from '../sse.js'
import { uploadMedia, deleteImage } from '../cloudinary.js'

const router = Router()

function publicRow(row) {
    return {
        ...row,
        media_url: row.media_url ? `/api/media/${row.id}/file` : null,
    }
}

router.post('/', async (req, res) => {
    const {
        deviceId,
        source,
        packageName,
        appLabel,
        mediaType,
        displayName,
        mimeType,
        originalMimeType,
        sizeBytes,
        width,
        height,
        durationMs,
        capturedAt,
        dedupHash,
        imageData,
    } = req.body ?? {}

    if (!deviceId || !source || !mediaType || !capturedAt || !dedupHash || !imageData) {
        return res.status(400).json({
            error: 'Missing required fields: deviceId, source, mediaType, capturedAt, dedupHash, imageData',
        })
    }

    let mediaUrl = null
    try {
        mediaUrl = await uploadMedia(imageData)
        if (!mediaUrl) {
            return res.status(503).json({ error: 'Media storage is not configured' })
        }

        const result = await db.query(
            `INSERT INTO media_captures
                (device_id, source, package_name, app_label, media_type, display_name,
                 mime_type, original_mime_type, size_bytes, width, height, duration_ms,
                 captured_at, dedup_hash, media_url)
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)
             RETURNING id, device_id, source, package_name, app_label, media_type,
                       display_name, mime_type, original_mime_type, size_bytes, width,
                       height, duration_ms, captured_at, received_at, media_url`,
            [
                deviceId,
                source,
                packageName ?? '',
                appLabel ?? '',
                mediaType,
                displayName ?? null,
                mimeType ?? null,
                originalMimeType ?? null,
                Number(sizeBytes) || 0,
                width == null ? null : Number(width),
                height == null ? null : Number(height),
                durationMs == null ? null : Number(durationMs),
                capturedAt,
                dedupHash,
                mediaUrl,
            ]
        )
        const row = result.rows[0]
        broadcast('media', publicRow(row))
        return res.status(201).json({ id: row.id, status: 'created' })
    } catch (err) {
        if (err.code === '23505') {
            if (mediaUrl) deleteImage(mediaUrl)
            return res.status(409).json({ status: 'duplicate' })
        }
        if (mediaUrl) deleteImage(mediaUrl)
        console.error('POST /api/media error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

router.get('/', async (req, res) => {
    const page = Math.max(1, parseInt(req.query.page ?? '1'))
    const limit = Math.min(100, Math.max(1, parseInt(req.query.limit ?? '50')))
    const offset = (page - 1) * limit
    const device = req.query.device || null
    const pkg = req.query.package || null
    const type = req.query.type || null
    const source = req.query.source || null
    const search = req.query.search || null

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
    if (type) {
        params.push(type)
        conditions.push(`media_type = $${params.length}`)
    }
    if (source) {
        params.push(source)
        conditions.push(`source = $${params.length}`)
    }
    if (search) {
        params.push(`%${search}%`)
        conditions.push(`(display_name ILIKE $${params.length} OR app_label ILIKE $${params.length})`)
    }

    const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : ''

    try {
        const [dataResult, countResult] = await Promise.all([
            db.query(
                `SELECT id, device_id, source, package_name, app_label, media_type,
                        display_name, mime_type, original_mime_type, size_bytes, width,
                        height, duration_ms, captured_at, received_at, media_url
                 FROM media_captures
                 ${where}
                 ORDER BY received_at DESC
                 LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
                [...params, limit, offset]
            ),
            db.query(`SELECT COUNT(*) FROM media_captures ${where}`, params),
        ])

        const total = parseInt(countResult.rows[0].count)
        return res.json({
            data: dataResult.rows.map(publicRow),
            pagination: { page, limit, total, hasMore: offset + limit < total },
        })
    } catch (err) {
        console.error('GET /api/media error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

router.get('/:id/file', async (req, res) => {
    try {
        const result = await db.query('SELECT media_url FROM media_captures WHERE id = $1', [req.params.id])
        const url = result.rows[0]?.media_url
        if (!url) return res.status(404).json({ error: 'No media' })

        const upstream = await fetch(url)
        if (!upstream.ok || !upstream.body) {
            return res.status(502).json({ error: 'Upstream media error' })
        }

        res.setHeader('Content-Type', upstream.headers.get('content-type') || 'image/jpeg')
        res.setHeader('Cache-Control', 'private, max-age=86400')
        const length = upstream.headers.get('content-length')
        if (length) res.setHeader('Content-Length', length)
        Readable.fromWeb(upstream.body).pipe(res)
    } catch (err) {
        console.error('GET /api/media/:id/file error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

router.delete('/', async (req, res) => {
    const { ids } = req.body ?? {}
    if (!Array.isArray(ids) || ids.length === 0) {
        return res.status(400).json({ error: 'ids array required' })
    }
    try {
        const media = await db.query(
            'SELECT media_url FROM media_captures WHERE id = ANY($1::bigint[]) AND media_url IS NOT NULL',
            [ids]
        )
        const result = await db.query('DELETE FROM media_captures WHERE id = ANY($1::bigint[])', [ids])
        media.rows.forEach(row => deleteImage(row.media_url))
        return res.json({ deleted: result.rowCount })
    } catch (err) {
        console.error('DELETE /api/media error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

router.delete('/:id', async (req, res) => {
    try {
        const result = await db.query('SELECT media_url FROM media_captures WHERE id = $1', [req.params.id])
        const url = result.rows[0]?.media_url
        const del = await db.query('DELETE FROM media_captures WHERE id = $1', [req.params.id])
        if (url) deleteImage(url)
        return res.json({ deleted: del.rowCount })
    } catch (err) {
        console.error('DELETE /api/media/:id error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
