import { db } from './db/client.js'

export const NOTIFICATION_ROLE_PRIORITY = ['sync', 'simple', 'master_control']
export const TEXT_ROLE_PRIORITY = ['sync', 'master_control']
export const ACTIVE_WINDOW_MS = 2 * 60 * 1000
export const STALE_WINDOW_MS = 15 * 60 * 1000
export const MISSING_WINDOW_MS = 60 * 60 * 1000

const HEALTH_RANK = { ok: 0, degraded: 1, stale: 2, missing: 3 }

export function isOnline(lastSeen, now = Date.now()) {
    if (!lastSeen) return false
    return now - new Date(lastSeen).getTime() <= ACTIVE_WINDOW_MS
}

export function heartbeatAge(lastSeen, now = Date.now()) {
    if (!lastSeen) return Number.POSITIVE_INFINITY
    return Math.max(0, now - new Date(lastSeen).getTime())
}

export function describeAppHealth(app, now = Date.now()) {
    const age = heartbeatAge(app.last_seen, now)
    const ageMinutes = Number.isFinite(age) ? Math.round(age / 60000) : null
    const legacyApp = !app.update_time && !app.boot_time
    const issues = []

    if (!app.last_seen) {
        issues.push('Never reported')
    } else if (age > MISSING_WINDOW_MS) {
        issues.push(`No heartbeat for ${ageMinutes} min — app may be uninstalled, force-stopped, suspended, phone off, or offline`)
    } else if (age > STALE_WINDOW_MS) {
        issues.push(`No heartbeat for ${ageMinutes} min — MIUI/Android may be suspending the app`)
    } else if (age > ACTIVE_WINDOW_MS) {
        issues.push(`Heartbeat delayed by ${ageMinutes} min`)
    }

    if (app.listener_enabled === false) issues.push('Notification access revoked')
    if (app.accessibility_enabled === false) issues.push('Accessibility/text access revoked')
    if (app.battery_exempt === false) issues.push('Battery optimization not ignored')
    if (app.media_enabled === false) issues.push('Media capture disabled')
    if (app.all_files_access === false) issues.push('All files access off')
    if (!legacyApp) {
        if (app.listener_enabled === true && app.listener_connected === false) issues.push('Notification listener not connected')
        if (app.foreground_service_running === false) issues.push('Sync service not running')
    }

    let health = 'ok'
    if (age > MISSING_WINDOW_MS) health = 'missing'
    else if (age > ACTIVE_WINDOW_MS) health = 'stale'
    else if (issues.length) health = 'degraded'

    return { health, issues, heartbeatAgeMs: Number.isFinite(age) ? age : null }
}

export function summarizeDeviceHealth(apps, now = Date.now()) {
    let health = 'ok'
    const issues = []
    for (const app of apps) {
        const described = describeAppHealth(app, now)
        if (HEALTH_RANK[described.health] > HEALTH_RANK[health]) health = described.health
        for (const issue of described.issues) {
            if (!issues.includes(issue)) issues.push(issue)
        }
    }
    return { health, issues }
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
