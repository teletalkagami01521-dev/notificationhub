const CACHE = 'nh-v4'
const STATIC = ['/', '/index.html', '/icon.svg', '/manifest.json']

self.addEventListener('install', e => {
  self.skipWaiting()
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(STATIC).catch(() => {})))
})

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => clients.claim())
  )
})

self.addEventListener('fetch', e => {
  const url = e.request.url
  if (!url.startsWith(self.location.origin)) return
  if (url.includes('/api/')) return

  // Network-first for navigations so a fresh deploy shows immediately;
  // fall back to cache only when offline.
  if (e.request.mode === 'navigate') {
    e.respondWith(
      fetch(e.request)
        .then(res => {
          if (res.ok) caches.open(CACHE).then(c => c.put(e.request, res.clone()))
          return res
        })
        .catch(() => caches.match(e.request).then(hit => hit || caches.match('/index.html')))
    )
    return
  }

  e.respondWith(
    caches.open(CACHE).then(cache =>
      cache.match(e.request).then(hit => {
        const fresh = fetch(e.request).then(res => {
          if (res.ok) cache.put(e.request, res.clone())
          return res
        }).catch(() => null)
        return hit || fresh
      })
    )
  )
})
