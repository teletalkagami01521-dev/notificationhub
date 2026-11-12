import { verifyToken } from '../auth/token.js'

export function auth(req, res, next) {
    // Android APK: X-API-Key header
    const apiKey = req.headers['x-api-key']
    if (apiKey && apiKey === process.env.API_KEY) return next()

    // Dashboard EventSource: ?token= query param (EventSource can't set headers)
    const qToken = req.query.token
    if (qToken && verifyToken(qToken)) return next()

    // Dashboard fetch: Authorization: Bearer <jwt>
    const bearer = req.headers['authorization']
    if (bearer?.startsWith('Bearer ')) {
        const claims = verifyToken(bearer.slice(7))
        if (claims) return next()
    }

    // Legacy: ?key= query param for EventSource (Android, keep for backward compat)
    const qKey = req.query.key
    if (qKey && qKey === process.env.API_KEY) return next()

    return res.status(401).json({ error: 'Unauthorized' })
}
