export function auth(req, res, next) {
    // Accept key from header (Android client) or query param (browser EventSource)
    const key = req.headers['x-api-key'] ?? req.query.key
    if (!key || key !== process.env.API_KEY) {
        return res.status(401).json({ error: 'Unauthorized' })
    }
    next()
}
