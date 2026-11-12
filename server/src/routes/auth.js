import { Router } from 'express'
import { createToken } from '../auth/token.js'

const ALLOWED_EMAIL = 'teletalkagami01521@gmail.com'
const router = Router()

router.post('/login', (req, res) => {
    try {
        const email    = (req.body.email    || '').trim().toLowerCase()
        const password = (req.body.password || '').trim()
        if (email !== ALLOWED_EMAIL) return res.status(403).json({ error: 'Not authorized' })
        if (!process.env.DASHBOARD_PASSWORD || password !== process.env.DASHBOARD_PASSWORD)
            return res.status(401).json({ error: 'Wrong password' })
        const token = createToken(email)
        return res.json({ token })
    } catch (err) {
        console.error('[auth] login error:', err.message)
        return res.status(500).json({ error: err.message })
    }
})

export default router
