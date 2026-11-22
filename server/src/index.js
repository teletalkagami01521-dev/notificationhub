import 'dotenv/config'
import express from 'express'
import path from 'path'
import { fileURLToPath } from 'url'
import { auth } from './middleware/auth.js'
import authRouter from './routes/auth.js'
import notificationsRouter from './routes/notifications.js'
import typedTextRouter from './routes/typedText.js'
import devicesRouter from './routes/devices.js'
import historyRouter from './routes/history.js'
import mediaRouter from './routes/media.js'
import appStatusRouter from './routes/appStatus.js'
import favoritesRouter from './routes/favorites.js'
import { migrate } from './db/migrate.js'
import { startCleanupJob } from './cron.js'

const app = express()

app.use(express.json({ limit: '15mb' }))  // allow compressed media thumbnails and notification images

// CORS — personal dashboard access from any origin
app.use((req, res, next) => {
    res.setHeader('Access-Control-Allow-Origin', '*')
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-API-Key, Authorization')
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, DELETE, OPTIONS')
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
app.use('/api/typed-text', typedTextRouter)
app.use('/api/media', mediaRouter)
app.use('/api/app-status', appStatusRouter)
app.use('/api/devices', devicesRouter)
app.use('/api/history', historyRouter)
app.use('/api/favorites', favoritesRouter)

// Auto-delete disabled — use manual delete from dashboard
// startCleanupJob()

const PORT = process.env.PORT ?? 3000
migrate()
    .then(() => app.listen(PORT, () => {
        console.log(`NotifyHub server running on port ${PORT}`)
        console.log(`Dashboard: http://localhost:${PORT}`)
    }))
    .catch(err => {
        console.error('Database migration failed:', err.message)
        process.exit(1)
    })
