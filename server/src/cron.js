import cron from 'node-cron'
import { db } from './db/client.js'

export function startCleanupJob() {
    // Every day at 02:00 server time — delete non-archived notifications older than 10 days
    cron.schedule('0 2 * * *', async () => {
        try {
            const result = await db.query(
                "DELETE FROM notifications WHERE received_at < NOW() - INTERVAL '10 days' AND is_archived = FALSE"
            )
            console.log(`[cron] Cleanup: deleted ${result.rowCount} expired notifications`)
        } catch (err) {
            console.error('[cron] Cleanup failed:', err.message)
        }
    })
    console.log('[cron] Cleanup job scheduled (daily at 02:00)')
}
