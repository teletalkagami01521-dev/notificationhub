import crypto from 'crypto'
import { db } from '../db/client.js'

// Ensure the table exists (runs once on startup)
export async function initOtpTable() {
    await db.query(`
        CREATE TABLE IF NOT EXISTS otp_codes (
            email       TEXT PRIMARY KEY,
            code        TEXT NOT NULL,
            expires_at  TIMESTAMPTZ NOT NULL,
            attempts    INT NOT NULL DEFAULT 0
        )
    `)
}

export async function generateOtp(email) {
    const code = String(crypto.randomInt(100000, 1000000))
    const expiresAt = new Date(Date.now() + 10 * 60 * 1000)
    await db.query(
        `INSERT INTO otp_codes (email, code, expires_at, attempts)
         VALUES ($1, $2, $3, 0)
         ON CONFLICT (email) DO UPDATE
           SET code = $2, expires_at = $3, attempts = 0`,
        [email, code, expiresAt]
    )
    return code
}

export async function verifyOtp(email, code) {
    const { rows } = await db.query(
        'SELECT * FROM otp_codes WHERE email = $1',
        [email]
    )
    const rec = rows[0]
    if (!rec) return false
    if (new Date() > rec.expires_at) {
        await db.query('DELETE FROM otp_codes WHERE email = $1', [email])
        return false
    }
    const attempts = rec.attempts + 1
    if (attempts > 5) {
        await db.query('DELETE FROM otp_codes WHERE email = $1', [email])
        return false
    }
    await db.query('UPDATE otp_codes SET attempts = $1 WHERE email = $2', [attempts, email])
    if (rec.code !== String(code)) return false
    await db.query('DELETE FROM otp_codes WHERE email = $1', [email]) // one-time use
    return true
}
