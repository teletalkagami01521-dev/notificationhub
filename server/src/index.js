import 'dotenv/config'
import express from 'express'
import path from 'path'
import { fileURLToPath } from 'url'
import { auth } from './middleware/auth.js'
import authRouter from './routes/auth.js'
import notificationsRouter from './routes/notifications.js'
import { startCleanupJob } from './cron.js'
import { initOtpTable } from './auth/otp.js'

const app = express()

app.use(express.json({ limit: '64kb' }))

// CORS — personal dashboard access from any origin
app.use((req, res, next) => {
    res.setHeader('Access-Control-Allow-Origin', '*')
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-API-Key')
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, OPTIONS')
    if (req.method === 'OPTIONS') return res.sendStatus(204)
    next()
})

// Serve the dashboard from the sibling dashboard/ directory
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const dashboardPath = path.join(__dirname, '..', '..', 'dashboard')
app.use(express.static(dashboardPath))

// Health check — no auth required
app.get('/health', (_req, res) => res.json({ status: 'ok', uptime: process.uptime() }))

// Auth routes — public, no middleware
app.use('/api/auth', authRouter)

// All other /api/* routes require authentication
app.use('/api', auth)
app.use('/api/notifications', notificationsRouter)

startCleanupJob()
initOtpTable().catch(err => console.error('[otp] Table init failed:', err.message))

const PORT = process.env.PORT ?? 3000
app.listen(PORT, () => {
    console.log(`NotifyHub server running on port ${PORT}`)
    console.log(`Dashboard: http://localhost:${PORT}`)
})
