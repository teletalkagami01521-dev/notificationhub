import { Router } from 'express'
import { generateOtp, verifyOtp } from '../auth/otp.js'
import { sendOtpEmail } from '../auth/email.js'
import { createToken } from '../auth/token.js'

const ALLOWED_EMAIL = 'teletalkagami01521@gmail.com'

const router = Router()

// POST /api/auth/request-otp — no auth required
router.post('/request-otp', async (req, res) => {
    const email = (req.body.email || '').trim().toLowerCase()
    if (email !== ALLOWED_EMAIL) {
        return res.status(403).json({ error: 'Not authorized' })
    }
    const code = generateOtp(email)
    try {
        await sendOtpEmail(email, code)
        return res.json({ ok: true })
    } catch (err) {
        console.error('[auth] Email send failed:', err.message)
        return res.status(500).json({ error: 'Failed to send email' })
    }
})

// POST /api/auth/verify-otp — no auth required
router.post('/verify-otp', (req, res) => {
    try {
        const email = (req.body.email || '').trim().toLowerCase()
        const code  = String(req.body.code || '').trim()
        if (!email || !code) return res.status(400).json({ error: 'Missing fields' })
        if (!verifyOtp(email, code)) return res.status(401).json({ error: 'Invalid or expired code' })
        const token = createToken(email)
        return res.json({ token })
    } catch (err) {
        console.error('[auth] verify-otp error:', err.message)
        return res.status(500).json({ error: err.message })
    }
})

export default router
