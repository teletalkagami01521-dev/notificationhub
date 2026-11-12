import 'dotenv/config'
import express from 'express'
import path from 'path'
import { fileURLToPath } from 'url'
import { auth } from './middleware/auth.js'
import notificationsRouter from './routes/notifications.js'
import { startCleanupJob } from './cron.js'

const app = express()

app.use(express.json({ limit: '64kb' }))

// Serve the dashboard from the sibling dashboard/ directory
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const dashboardPath = path.join(__dirname, '..', '..', 'dashboard')
app.use(express.static(dashboardPath))

// Health check — no auth required
app.get('/health', (_req, res) => res.json({ status: 'ok', uptime: process.uptime() }))

// All /api/* routes require authentication
app.use('/api', auth)
app.use('/api/notifications', notificationsRouter)

startCleanupJob()

const PORT = process.env.PORT ?? 3000
app.listen(PORT, () => {
    console.log(`NotifyHub server running on port ${PORT}`)
    console.log(`Dashboard: http://localhost:${PORT}`)
})
