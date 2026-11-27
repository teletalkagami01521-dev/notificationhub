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

const nullableBoolean = (value) => {
    if (value === null || value === undefined || value === '') return null
    if (typeof value === 'boolean') return value
    if (value === 'true') return true
    if (value === 'false') return false
    return null
}

const counter = (value) => {
    const parsed = Number(value)
    return Number.isFinite(parsed) && parsed >= 0 ? Math.floor(parsed) : 0
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
        typedTextAliveAt,
        typedTextLastEventAt,
        typedTextConnected,
        typedServiceBindTotal,
        typedEventsTotal,
        typedTextSentTotal,
        typedPlaceholderRejectedTotal,
        typedServiceReconnectTotal,
        watchdogLastFiredAt,
        notificationRebindTotal,
        listenerLastConnectedAt,
        listenerLastDisconnectedAt,
        notificationImagesTotal,
        notificationImagesFailedTotal,
    } = req.body ?? {}

    if (!deviceId || !appRole || !packageName) {
        return res.status(400).json({ error: 'Missing required fields: deviceId, appRole, packageName' })
    }
    if (!VALID_ROLES.has(appRole)) {
        return res.status(400).json({ error: 'Unknown appRole' })
    }

    const legacyValues = [
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

    const diagnosticsValues = [
        timestampOrNull(typedTextAliveAt),
        timestampOrNull(typedTextLastEventAt),
        nullableBoolean(typedTextConnected),
        counter(typedServiceBindTotal),
        counter(typedEventsTotal),
        counter(typedTextSentTotal),
        counter(typedPlaceholderRejectedTotal),
        counter(typedServiceReconnectTotal),
        timestampOrNull(watchdogLastFiredAt),
        counter(notificationRebindTotal),
        timestampOrNull(listenerLastConnectedAt),
        timestampOrNull(listenerLastDisconnectedAt),
        counter(notificationImagesTotal),
        counter(notificationImagesFailedTotal),
    ]

    const diagnosticFields = [
        'typedTextAliveAt',
        'typedTextLastEventAt',
        'typedTextConnected',
        'typedServiceBindTotal',
        'typedEventsTotal',
        'typedTextSentTotal',
        'typedPlaceholderRejectedTotal',
        'typedServiceReconnectTotal',
        'watchdogLastFiredAt',
        'notificationRebindTotal',
        'listenerLastConnectedAt',
        'listenerLastDisconnectedAt',
        'notificationImagesTotal',
        'notificationImagesFailedTotal',
    ]
    const hasDiagnostics = diagnosticFields.some((field) =>
        Object.prototype.hasOwnProperty.call(req.body ?? {}, field)
    )
    const diagnosticsAssignment = [
        'typed_text_alive_at',
        'typed_text_last_event_at',
        'typed_text_connected',
        'typed_service_bind_total',
        'typed_events_total',
        'typed_text_sent_total',
        'typed_placeholder_rejected_total',
        'typed_service_reconnect_total',
        'watchdog_last_fired_at',
        'notification_rebind_total',
        'listener_last_connected_at',
        'listener_last_disconnected_at',
        'notification_images_total',
        'notification_images_failed_total',
    ].map(column =>
        `${column} = CASE WHEN $${legacyValues.length + diagnosticsValues.length + 1}::boolean ` +
        `THEN EXCLUDED.${column} ELSE app_status.${column} END`
    ).join(',\n                ')

    const values = [...legacyValues, ...diagnosticsValues, hasDiagnostics]

    try {
        await db.query(
            `INSERT INTO app_status (
                device_id, app_role, package_name, app_label, version_name,
                listener_enabled, accessibility_enabled, battery_exempt,
                media_enabled, all_files_access, listener_connected,
                foreground_service_running, watchdog_enabled, boot_time,
                install_time, update_time, last_listener_event_at,
                typed_text_alive_at, typed_text_last_event_at, typed_text_connected,
                typed_service_bind_total, typed_events_total, typed_text_sent_total,
                typed_placeholder_rejected_total, typed_service_reconnect_total,
                watchdog_last_fired_at, notification_rebind_total,
                listener_last_connected_at, listener_last_disconnected_at,
                notification_images_total, notification_images_failed_total,
                last_seen
             )
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17,
                     $18, $19, $20, $21, $22, $23, $24, $25, $26, $27, $28, $29, $30, $31, NOW())
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
                ${diagnosticsAssignment},
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
            legacyValues
        )
        broadcast('status', { deviceId, appRole, at: new Date().toISOString() })
        return res.json({ status: 'ok' })
    } catch (err) {
        console.error('POST /api/app-status error:', err.message)
        return res.status(500).json({ error: 'Internal server error' })
    }
})

export default router
