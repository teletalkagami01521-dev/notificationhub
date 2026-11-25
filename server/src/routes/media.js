import { Router } from 'express'
import { Readable } from 'node:stream'
import { db } from '../db/client.js'
import { broadcast } from '../sse.js'
import { uploadMedia, deleteImage } from '../cloudinary.js'

const router = Router()

function storedUrls(row) {
    const mediaUrls = []
    if (row?.media_url) mediaUrls.push(row.media_url)
    try {
        const parsed = JSON.parse(row?.thumbnail_urls || '[]')
        if (Array.isArray(parsed)) parsed.forEach(url => {
            if (typeof url === 'string' && !mediaUrls.includes(url)) mediaUrls.push(url)
        })
    } catch {}
    return mediaUrls
}

function publicRow(row) {
    const { thumbnail_urls: thumbnailUrls, ...rest } = row
    const urls = storedUrls(row)
    return {
        ...rest,
        media_url: row.media_url ? `/api/media/${row.id}/file` : null,
        thumbnails: urls.map((_, index) => `/api/media/${row.id}/frames/${index}/file`),
    }
}

async function streamMedia(url, res) {
    let upstream
    try {
        upstream = await fetch(url)
    } catch {
        return res.status(502).json({ error: 'Upstream media error' })
    }
    if (!upstream.ok || !upstream.body) {
        return res.status(502).json({ error: 'Upstream media error' })
    }
    res.setHeader('Content-Type', upstream.headers.get('content-type') || 'image/jpeg')
    res.setHeader('Cache-Control', 'private, max-age=86400')
    const length = upstream.headers.get('content-length')
    if (length) res.setHeader('Content-Length', length)
    Readable.fromWeb(upstream.body).pipe(res)
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
        thumbnailData,
    } = req.body ?? {}

    if (!deviceId || !source || !mediaType || !capturedAt || !dedupHash || !imageData) {
        return res.status(400).json({
            error: 'Missing required fields: deviceId, source, mediaType, capturedAt, dedupHash, imageData',
        })
    }

    const frames = Array.isArray(thumbnailData) && thumbnailData.length ? thumbnailData : [imageData]
    const uploaded = []
    try {
        for (const data of frames) {
            if (typeof data !== 'string' || !data) continue
            const url = await uploadMedia(data)
            if (!url) {
                uploaded.forEach(deleteImage)
                return res.status(503).json({ error: 'Media storage is not configured' })
            }
            uploaded.push(url)
        }
        if (!uploaded.length) {
            return res.status(400).json({ error: 'No media frames supplied' })
        }

        const result = await db.query(
            `INSERT INTO media_captures
                (device_id, source, package_name, app_label, media_type, display_name,
                 mime_type, original_mime_type, size_bytes, width, height, duration_ms,
                 captured_at, dedup_hash, media_url, thumbnail_urls)
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)
             RETURNING id, device_id, source, package_name, app_label, media_type,
                       display_name, mime_type, original_mime_type, size_bytes, width,
                       height, duration_ms, captured_at, received_at, media_url, thumbnail_urls`,
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
                uploaded[0],
                JSON.stringify(uploaded),
            ]
        )
        uploaded.length = 0
        const row = result.rows[0]
        broadcast('media', publicRow(row))
        return res.status(201).json({ id: row.id, status: 'created' })
    } catch (err) {
        uploaded.forEach(deleteImage)
        if (err.code === '23505') {
            return res.status(409).json({ status: 'duplicate' })
        }
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
        conditions.push(`(display_name ILIKE $${params.length} OR app_label ILIKE $${params.length} OR package_name ILIKE $${params.length})`)
    }

    const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : ''

    try {
        const [dataResult, countResult] = await Promise.all([
            db.query(
                `SELECT id, device_id, source, package_name, app_label, media_type,
                        display_name, mime_type, original_mime_type, size_bytes, width,
                        height, duration_ms, captured_at, received_at, media_url, thumbnail_urls
                 FROM media_captures
                 ${where}
                 ORDER BY captured_at DESC, received_at DESC
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

router.get('/:id/frames/:index/file', async (req, res) => {
    const index = parseInt(req.params.index)
    if (!Number.isInteger(index) || index < 0) {
        return res.status(400).json({ error: 'Invalid frame index' })
    }
    try {
        const result = await db.query(
            'SELECT media_url, thumbnail_urls FROM media_captures WHERE id = $1',
            [req.params.id]
        )
        const url = storedUrls(result.rows[0])[index]
        if (!url) return res.status(404).json({ error: 'No media frame' })
        return await streamMedia(url, res)
    } catch (err) {
        console.error('GET /api/media/:id/frames/:index/file error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

router.get('/:id/file', async (req, res) => {
    try {
        const result = await db.query('SELECT media_url FROM media_captures WHERE id = $1', [req.params.id])
        const url = result.rows[0]?.media_url
        if (!url) return res.status(404).json({ error: 'No media' })
        return await streamMedia(url, res)
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
            'SELECT media_url, thumbnail_urls FROM media_captures WHERE id = ANY($1::bigint[])',
            [ids]
        )
        const result = await db.query('DELETE FROM media_captures WHERE id = ANY($1::bigint[])', [ids])
        media.rows.forEach(row => storedUrls(row).forEach(deleteImage))
        broadcast('media:deleted', { ids })
        return res.json({ deleted: result.rowCount })
    } catch (err) {
        console.error('DELETE /api/media error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

router.delete('/:id', async (req, res) => {
    try {
        const result = await db.query(
            'SELECT media_url, thumbnail_urls FROM media_captures WHERE id = $1',
            [req.params.id]
        )
        const del = await db.query('DELETE FROM media_captures WHERE id = $1', [req.params.id])
        storedUrls(result.rows[0]).forEach(deleteImage)
        broadcast('media:deleted', { ids: [req.params.id] })
        return res.json({ deleted: del.rowCount })
    } catch (err) {
        console.error('DELETE /api/media/:id error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
