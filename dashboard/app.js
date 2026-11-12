const App = (() => {
    const KEY_STORE = 'notifyhub_api_key'

    let apiKey = ''
    let eventSource = null
    let notifications = []
    let currentPage = 1
    let hasMore = false
    let filterApp = ''
    let filterSearch = ''
    const knownApps = new Set()

    // ── Init ───────────────────────────────────────────────────────────────────

    function init() {
        const stored = (() => { try { return localStorage.getItem(KEY_STORE) } catch { return null } })()
        if (stored) {
            apiKey = stored
            showMain()
            loadInitial()
            connectSSE()
        } else {
            document.getElementById('auth-gate').hidden = false
        }
    }

    function setKey() {
        const input = document.getElementById('key-input')
        const key = input.value.trim()
        if (!key) return
        apiKey = key
        try { localStorage.setItem(KEY_STORE, key) } catch {}
        document.getElementById('auth-gate').hidden = true
        showMain()
        loadInitial()
        connectSSE()
    }

    function showMain() {
        document.getElementById('main').hidden = false
    }

    // ── API ────────────────────────────────────────────────────────────────────

    async function apiFetch(path, opts = {}) {
        const url = new URL(path, window.location.origin)
        const res = await fetch(url, {
            ...opts,
            headers: { 'X-API-Key': apiKey, 'Content-Type': 'application/json', ...(opts.headers ?? {}) }
        })
        if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
        return res.json()
    }

    async function loadInitial() {
        currentPage = 1
        notifications = []
        document.getElementById('feed').innerHTML = ''
        try {
            await loadPage(1)
        } catch (err) {
            setStatus('error', 'Load failed')
            console.error(err)
        }
    }

    async function loadPage(page) {
        const params = new URLSearchParams({ page, limit: 50 })
        if (filterApp)    params.set('package', filterApp)
        if (filterSearch) params.set('search', filterSearch)

        const data = await apiFetch(`/api/notifications?${params}`)
        hasMore = data.pagination.hasMore
        currentPage = page

        data.data.forEach(n => prependOrAppend(n, false))
        document.getElementById('load-more').style.display = hasMore ? 'block' : 'none'
        updateEmptyState()
    }

    // ── SSE ────────────────────────────────────────────────────────────────────

    function connectSSE() {
        if (eventSource) eventSource.close()
        const url = `/api/notifications/stream?key=${encodeURIComponent(apiKey)}`
        eventSource = new EventSource(url)

        eventSource.addEventListener('notification', e => {
            try {
                const n = JSON.parse(e.data)
                // Insert at top only if not already filtered out
                if (matchesFilter(n)) {
                    prependOrAppend(n, true)
                    updateEmptyState()
                }
                addAppOption(n.package_name, n.app_label)
            } catch {}
        })

        eventSource.onopen = () => setStatus('connected', 'Live')
        eventSource.onerror = () => {
            setStatus('error', 'Reconnecting…')
            // Browser reconnects automatically; this is just a visual cue
        }
    }

    // ── Render ─────────────────────────────────────────────────────────────────

    function prependOrAppend(n, prepend) {
        if (notifications.find(x => x.id === n.id)) return
        notifications[prepend ? 'unshift' : 'push'](n)
        addAppOption(n.package_name, n.app_label)

        const card = buildCard(n)
        const feed = document.getElementById('feed')
        const empty = document.getElementById('empty-state')
        if (empty) empty.remove()

        if (prepend) {
            feed.insertAdjacentElement('afterbegin', card)
        } else {
            feed.appendChild(card)
        }
    }

    function buildCard(n) {
        const card = document.createElement('div')
        card.className = `notif-card${n.is_read ? ' read' : ''}`
        card.dataset.id = n.id

        card.innerHTML = `
            <div class="notif-dot"></div>
            <div class="notif-body">
                <div class="notif-meta">
                    <span class="notif-app">${esc(n.app_label || n.package_name)}</span>
                    <span class="notif-time">${relTime(n.received_at)}</span>
                </div>
                ${n.title ? `<div class="notif-title">${esc(n.title)}</div>` : ''}
                ${n.text_body ? `<div class="notif-text">${esc(n.text_body)}</div>` : ''}
                <div class="notif-actions">
                    <button class="btn btn-ghost" onclick="App.markRead(${n.id})">Mark read</button>
                    <button class="btn btn-ghost" onclick="App.archiveNotif(${n.id})">Archive</button>
                </div>
            </div>
        `
        return card
    }

    function esc(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
    }

    function relTime(isoStr) {
        const diff = Date.now() - new Date(isoStr).getTime()
        const s = Math.floor(diff / 1000)
        if (s < 60)  return `${s}s ago`
        if (s < 3600) return `${Math.floor(s / 60)}m ago`
        if (s < 86400) return `${Math.floor(s / 3600)}h ago`
        return new Date(isoStr).toLocaleDateString()
    }

    function addAppOption(packageName, appLabel) {
        if (knownApps.has(packageName)) return
        knownApps.add(packageName)
        const sel = document.getElementById('app-filter')
        const opt = document.createElement('option')
        opt.value = packageName
        opt.textContent = appLabel || packageName
        sel.appendChild(opt)
    }

    function updateEmptyState() {
        const feed = document.getElementById('feed')
        if (notifications.length === 0 && !feed.querySelector('.notif-card')) {
            const el = document.createElement('p')
            el.id = 'empty-state'
            el.textContent = 'No notifications yet. Waiting for data from your devices…'
            feed.appendChild(el)
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    async function markRead(id) {
        try {
            await apiFetch(`/api/notifications/${id}/read`, { method: 'PATCH' })
            const card = document.querySelector(`.notif-card[data-id="${id}"]`)
            if (card) card.classList.add('read')
            const n = notifications.find(x => x.id === id)
            if (n) n.is_read = true
        } catch {}
    }

    async function archiveNotif(id) {
        try {
            await apiFetch(`/api/notifications/${id}/archive`, { method: 'PATCH' })
            const card = document.querySelector(`.notif-card[data-id="${id}"]`)
            if (card) card.remove()
            notifications = notifications.filter(x => x.id !== id)
            updateEmptyState()
        } catch {}
    }

    async function markAllRead() {
        try {
            await apiFetch('/api/notifications/mark-all-read', { method: 'PATCH' })
            document.querySelectorAll('.notif-card').forEach(c => c.classList.add('read'))
            notifications.forEach(n => n.is_read = true)
        } catch {}
    }

    // ── Filters ────────────────────────────────────────────────────────────────

    function filterByApp(pkg) {
        filterApp = pkg
        loadInitial()
    }

    function filterBySearch(q) {
        filterSearch = q
        loadInitial()
    }

    function matchesFilter(n) {
        if (filterApp && n.package_name !== filterApp) return false
        if (filterSearch) {
            const q = filterSearch.toLowerCase()
            if (!n.title?.toLowerCase().includes(q) && !n.text_body?.toLowerCase().includes(q)) return false
        }
        return true
    }

    function loadMore() {
        loadPage(currentPage + 1)
    }

    // ── Status ─────────────────────────────────────────────────────────────────

    function setStatus(cls, text) {
        const el = document.getElementById('status')
        el.className = cls
        el.textContent = text
    }

    // Update relative timestamps every minute
    setInterval(() => {
        document.querySelectorAll('.notif-time').forEach((el, i) => {
            const n = notifications[notifications.length - 1 - i]
            if (n) el.textContent = relTime(n.received_at)
        })
    }, 60_000)

    return { init, setKey, markRead, archiveNotif, markAllRead, filterByApp, filterBySearch, loadMore }
})()

document.addEventListener('DOMContentLoaded', App.init)
