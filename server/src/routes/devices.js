import { Router } from 'express'
import { db } from '../db/client.js'
import { deleteImage } from '../cloudinary.js'

const router = Router()

// GET /api/devices — distinct devices for the dashboard selector
router.get('/', async (_req, res) => {
    try {
        const result = await db.query(
            `SELECT id, id AS name, MAX(last_seen) AS last_seen, SUM(cnt)::int AS notification_count
             FROM (
                 SELECT device_id AS id, MAX(received_at) AS last_seen, COUNT(*) AS cnt
                 FROM notifications
                 GROUP BY device_id
                 UNION ALL
                 SELECT device_id AS id, MAX(captured_at) AS last_seen, COUNT(*) AS cnt
                 FROM typed_text
                 GROUP BY device_id
             ) d
             GROUP BY id
             ORDER BY last_seen DESC`
        )
        return res.json(result.rows)
    } catch (err) {
        console.error('GET /api/devices error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

// DELETE /api/devices/:id — permanently remove a device and all its data
router.delete('/:id', async (req, res) => {
    const { id } = req.params
    try {
        const images = await db.query(
            'SELECT image_url FROM notifications WHERE device_id = $1 AND image_url IS NOT NULL',
            [id]
        )
        const [notifResult, textResult] = await Promise.all([
            db.query('DELETE FROM notifications WHERE device_id = $1', [id]),
            db.query('DELETE FROM typed_text WHERE device_id = $1', [id]),
        ])
        images.rows.forEach(row => deleteImage(row.image_url))
        return res.json({
            deleted: notifResult.rowCount + textResult.rowCount,
            notifications: notifResult.rowCount,
            typedText: textResult.rowCount,
        })
    } catch (err) {
        console.error('DELETE /api/devices/:id error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
