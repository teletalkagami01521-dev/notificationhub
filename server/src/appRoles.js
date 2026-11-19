import { db } from './db/client.js'

export const NOTIFICATION_ROLE_PRIORITY = ['sync', 'simple', 'master_control']
export const TEXT_ROLE_PRIORITY = ['sync', 'master_control']
export const ACTIVE_WINDOW_MS = 2 * 60 * 1000

export function isOnline(lastSeen, now = Date.now()) {
    if (!lastSeen) return false
    return now - new Date(lastSeen).getTime() <= ACTIVE_WINDOW_MS
}

export function pickActiveRole(apps, capability = 'notification') {
    const priority = capability === 'text' ? TEXT_ROLE_PRIORITY : NOTIFICATION_ROLE_PRIORITY
    const now = Date.now()
    for (const role of priority) {
        const app = apps.find(a => a.app_role === role && isOnline(a.last_seen, now))
        if (!app) continue
        const enabled = capability === 'text' ? app.accessibility_enabled : app.listener_enabled
        if (enabled) return role
    }
    return null
}

export function roleAllowed(appRole, activeRole, capability = 'notification') {
    if (!appRole) return true
    const priority = capability === 'text' ? TEXT_ROLE_PRIORITY : NOTIFICATION_ROLE_PRIORITY
    const incoming = priority.indexOf(appRole)
    if (incoming === -1) return false
    if (!activeRole) return true
    const active = priority.indexOf(activeRole)
    return incoming <= active
}

export async function getActiveRole(deviceId, capability = 'notification') {
    const result = await db.query(
        `SELECT app_role, listener_enabled, accessibility_enabled, last_seen
         FROM app_status
         WHERE device_id = $1 AND last_seen > NOW() - INTERVAL '2 minutes'`,
        [deviceId]
    )
    return pickActiveRole(result.rows, capability)
}
