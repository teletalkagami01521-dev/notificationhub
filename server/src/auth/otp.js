import crypto from 'crypto'

// In-memory OTP store: email -> { code, expires, attempts }
const store = new Map()

export function generateOtp(email) {
    const code = String(crypto.randomInt(100000, 1000000)) // 6 digits
    store.set(email, { code, expires: Date.now() + 10 * 60 * 1000, attempts: 0 })
    return code
}

export function verifyOtp(email, code) {
    const rec = store.get(email)
    if (!rec) return false
    if (Date.now() > rec.expires) { store.delete(email); return false }
    rec.attempts++
    if (rec.attempts > 5) { store.delete(email); return false }
    if (rec.code !== String(code)) return false
    store.delete(email) // one-time use
    return true
}
