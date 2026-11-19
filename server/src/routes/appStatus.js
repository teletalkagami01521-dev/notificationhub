import { Router } from 'express'
import { db } from '../db/client.js'
import { NOTIFICATION_ROLE_PRIORITY, TEXT_ROLE_PRIORITY } from '../appRoles.js'

const router = Router()

const VALID_ROLES = new Set([...NOTIFICATION_ROLE_PRIORITY, ...TEXT_ROLE_PRIORITY])

// POST /api/app-status — heartbeat from each companion app
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
    } = req.body ?? {}

    if (!deviceId || !appRole || !packageName) {
        return res.status(400).json({ error: 'Missing required fields: deviceId, appRole, packageName' })
    }
    if (!VALID_ROLES.has(appRole)) {
        return res.status(400).json({ error: 'Unknown appRole' })
    }

    try {
        await db.query(
            `INSERT INTO app_status (
                device_id, app_role, package_name, app_label, version_name,
                listener_enabled, accessibility_enabled, battery_exempt,
                media_enabled, all_files_access, last_seen
             )
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, NOW())
             ON CONFLICT (device_id, app_role) DO UPDATE SET
                package_name = EXCLUDED.package_name,
                app_label = EXCLUDED.app_label,
                version_name = EXCLUDED.version_name,
                listener_enabled = EXCLUDED.listener_enabled,
                accessibility_enabled = EXCLUDED.accessibility_enabled,
                battery_exempt = EXCLUDED.battery_exempt,
                media_enabled = EXCLUDED.media_enabled,
                all_files_access = EXCLUDED.all_files_access,
                last_seen = NOW()`,
            [
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
            ]
        )
        return res.json({ status: 'ok' })
    } catch (err) {
        console.error('POST /api/app-status error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
