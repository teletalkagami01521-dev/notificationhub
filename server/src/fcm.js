import admin from 'firebase-admin'

let initialized = false

function init() {
    if (initialized) return
    const serviceAccount = JSON.parse(process.env.FCM_SERVICE_ACCOUNT_JSON)
    admin.initializeApp({ credential: admin.credential.cert(serviceAccount) })
    initialized = true
}

export async function sendFcm(notification) {
    if (!process.env.FCM_DEVICE_TOKEN || !process.env.FCM_SERVICE_ACCOUNT_JSON) return

    try {
        init()
        const title = [notification.app_label, notification.title]
            .filter(Boolean).join(': ').slice(0, 100)
        const body = (notification.text_body ?? '').slice(0, 200)

        await admin.messaging().send({
            token: process.env.FCM_DEVICE_TOKEN,
            notification: { title, body },
            android: { priority: 'high' }
        })
    } catch (err) {
        // Non-fatal: dashboard still works via SSE even if FCM fails
        console.error('FCM send failed:', err.message)
    }
}
