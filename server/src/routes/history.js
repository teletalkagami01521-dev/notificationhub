import { Router } from 'express'
import { db } from '../db/client.js'
import { deleteImage } from '../cloudinary.js'

const router = Router()

// DELETE /api/history — permanently clear all notifications and typed text.
// Requires an explicit confirmation string so an accidental request cannot wipe data.
router.delete('/', async (req, res) => {
    if (req.body?.confirm !== 'DELETE ALL') {
        return res.status(400).json({ error: 'confirmation required' })
    }

    let client
    let imageUrls = []
    let notificationCount = 0
    let typedTextCount = 0

    try {
        client = await db.connect()
        await client.query('BEGIN')
        const images = await client.query(
            'SELECT image_url FROM notifications WHERE image_url IS NOT NULL'
        )
        imageUrls = images.rows.map(row => row.image_url)

        const notifications = await client.query('DELETE FROM notifications')
        const typedText = await client.query('DELETE FROM typed_text')
        notificationCount = notifications.rowCount
        typedTextCount = typedText.rowCount

        await client.query('COMMIT')
    } catch (err) {
        await client?.query('ROLLBACK').catch(() => {})
        console.error('DELETE /api/history error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    } finally {
        client?.release()
    }

    imageUrls.forEach(url => deleteImage(url))
    return res.json({ notifications: notificationCount, typedText: typedTextCount })
})

export default router
