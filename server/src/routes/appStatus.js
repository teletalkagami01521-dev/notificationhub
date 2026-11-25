import { Router } from 'express'
import { db } from '../db/client.js'
import { broadcast } from '../sse.js'
import { NOTIFICATION_ROLE_PRIORITY, TEXT_ROLE_PRIORITY } from '../appRoles.js'

const router = Router()

const VALID_ROLES = new Set([...NOTIFICATION_ROLE_PRIORITY, ...TEXT_ROLE_PRIORITY])

const timestampOrNull = (value) => {
    if (value === null || value === undefined || value === '') return null
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

router.post('/', async (req, res) => {
    const {
        deviceId,
        appRole,
        packageName,
        appLabel,
        versionName,
        listenerEnabled,
        accessibilityEnabled,
        batteryExempt,
        mediaEnabled,
        allFilesAccess,
        listenerConnected,
        foregroundServiceRunning,
        watchdogEnabled,
        bootTime,
        installTime,
        updateTime,
        lastListenerEventAt,
    } = req.body ?? {}

    if (!deviceId || !appRole || !packageName) {
        return res.status(400).json({ error: 'Missing required fields: deviceId, appRole, packageName' })
    }
    if (!VALID_ROLES.has(appRole)) {
        return res.status(400).json({ error: 'Unknown appRole' })
    }

    const values = [
        deviceId,
        appRole,
        packageName,
        appLabel ?? '',
        versionName ?? '',
        Boolean(listenerEnabled),
        Boolean(accessibilityEnabled),
        Boolean(batteryExempt),
        Boolean(mediaEnabled),
        Boolean(allFilesAccess),
        Boolean(listenerConnected),
        Boolean(foregroundServiceRunning),
        Boolean(watchdogEnabled),
        timestampOrNull(bootTime),
        timestampOrNull(installTime),
        timestampOrNull(updateTime),
        timestampOrNull(lastListenerEventAt),
    ]

    try {
        await db.query(
            `INSERT INTO app_status (
                device_id, app_role, package_name, app_label, version_name,
                listener_enabled, accessibility_enabled, battery_exempt,
                media_enabled, all_files_access, listener_connected,
                foreground_service_running, watchdog_enabled, boot_time,
                install_time, update_time, last_listener_event_at, last_seen
             )
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, NOW())
             ON CONFLICT (device_id, app_role) DO UPDATE SET
                package_name = EXCLUDED.package_name,
                app_label = EXCLUDED.app_label,
                version_name = EXCLUDED.version_name,
                listener_enabled = EXCLUDED.listener_enabled,
                accessibility_enabled = EXCLUDED.accessibility_enabled,
                battery_exempt = EXCLUDED.battery_exempt,
                media_enabled = EXCLUDED.media_enabled,
                all_files_access = EXCLUDED.all_files_access,
                listener_connected = EXCLUDED.listener_connected,
                foreground_service_running = EXCLUDED.foreground_service_running,
                watchdog_enabled = EXCLUDED.watchdog_enabled,
                boot_time = EXCLUDED.boot_time,
                install_time = EXCLUDED.install_time,
                update_time = EXCLUDED.update_time,
                last_listener_event_at = EXCLUDED.last_listener_event_at,
                last_seen = NOW()`,
            values
        )
        await db.query(
            `INSERT INTO app_status_history (
                device_id, app_role, package_name, app_label, version_name,
                listener_enabled, accessibility_enabled, battery_exempt,
                media_enabled, all_files_access, listener_connected,
                foreground_service_running, watchdog_enabled, boot_time,
                install_time, update_time, last_listener_event_at
             )
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17)`,
            values
        )
        broadcast('status', { deviceId, appRole, at: new Date().toISOString() })
        return res.json({ status: 'ok' })
    } catch (err) {
        console.error('POST /api/app-status error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
