import { Router } from 'express'
import { db } from '../db/client.js'
import { deleteImage } from '../cloudinary.js'

const router = Router()

// DELETE /api/history — permanently clear notifications, typed text, and captured media.
// Requires an explicit confirmation string so an accidental request cannot wipe data.
router.delete('/', async (req, res) => {
    if (req.body?.confirm !== 'DELETE ALL') {
        return res.status(400).json({ error: 'confirmation required' })
    }

    let client
    let imageUrls = []
    let notificationCount = 0
    let typedTextCount = 0
    let mediaCount = 0

    try {
        client = await db.connect()
        await client.query('BEGIN')
        const images = await client.query(
            'SELECT image_url FROM notifications WHERE image_url IS NOT NULL'
        )
        imageUrls = images.rows.map(row => row.image_url)

        const media = await client.query(
            'SELECT media_url FROM media_captures WHERE media_url IS NOT NULL'
        )
        imageUrls.push(...media.rows.map(row => row.media_url))

        const notifications = await client.query('DELETE FROM notifications')
        const typedText = await client.query('DELETE FROM typed_text')
        const mediaDeleted = await client.query('DELETE FROM media_captures')
        await client.query('DELETE FROM favorites')
        notificationCount = notifications.rowCount
        typedTextCount = typedText.rowCount
        mediaCount = mediaDeleted.rowCount

        await client.query('COMMIT')
    } catch (err) {
        await client?.query('ROLLBACK').catch(() => {})
        console.error('DELETE /api/history error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    } finally {
        client?.release()
    }

    imageUrls.forEach(url => deleteImage(url))
    return res.json({ notifications: notificationCount, typedText: typedTextCount, media: mediaCount })
})

export default router
