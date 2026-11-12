import crypto from 'crypto'

const SECRET = () => process.env.JWT_SECRET || 'dev-secret-change-in-production'
const TTL_DAYS = 30

export function createToken(email) {
    const header  = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url')
    const payload = Buffer.from(JSON.stringify({
        email,
        exp: Math.floor(Date.now() / 1000) + TTL_DAYS * 86400,
    })).toString('base64url')
    const sig = crypto.createHmac('sha256', SECRET()).update(`${header}.${payload}`).digest('base64url')
    return `${header}.${payload}.${sig}`
}

export function verifyToken(token) {
    try {
        const parts = (token || '').split('.')
        if (parts.length !== 3) return null
        const [header, payload, sig] = parts
        const expected = crypto.createHmac('sha256', SECRET()).update(`${header}.${payload}`).digest('base64url')
        if (!crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) return null
        const claims = JSON.parse(Buffer.from(payload, 'base64url').toString())
        if (!claims.exp || claims.exp < Math.floor(Date.now() / 1000)) return null
        return claims
    } catch { return null }
}
