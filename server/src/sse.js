const clients = new Set()

export function addClient(res) {
    clients.add(res)
    res.on('close', () => clients.delete(res))
}

export function broadcast(eventName, data) {
    const payload = `event: ${eventName}\ndata: ${JSON.stringify(data)}\n\n`
    for (const res of clients) {
        try {
            res.write(payload)
        } catch {
            clients.delete(res)
        }
    }
}

export function clientCount() {
    return clients.size
}

// Keepalive comment every 25 seconds to prevent proxy/CDN timeouts
setInterval(() => {
    for (const res of clients) {
        try {
            res.write(': ping\n\n')
        } catch {
            clients.delete(res)
        }
    }
}, 25_000)
