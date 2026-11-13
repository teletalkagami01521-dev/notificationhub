# NotifyHub — System Documentation

## What It Does

A personal notification aggregation system. Every Android phone you own runs a silent background app that captures all status-bar notifications and forwards them in real time to a central server. A web dashboard lets you read, search, filter, and delete notifications from any browser. Firebase Cloud Messaging (FCM) can optionally push alerts to your primary phone.

---

## Architecture

```
Android Device(s)
  └── NotificationListenerService
        └── Room DB offline queue
              └── OkHttp POST ──▶ Express API (Render)
                                        │
                                   Neon PostgreSQL
                                        │
                               SSE stream ──▶ Web Dashboard (Vercel)
                                        │
                               Firebase Admin ──▶ FCM ──▶ Primary phone
```

---

## Services & Hosting

| Service | Role | Plan |
|---|---|---|
| **Render** | Node.js Express API server | Free (sleeps after 15 min inactivity) |
| **Neon** | PostgreSQL database | Free (500 MB, serverless) |
| **Vercel** | Static dashboard hosting | Free |
| **Firebase** | FCM push notifications | Free |
| **GitHub** | Source control + Render auto-deploy trigger | Free |
| **Cloudinary** | Image storage for notification picture previews | Free (25 GB) |

---

## Tech Stack

### Android App
- **Language**: Kotlin
- **Build system**: Gradle (Kotlin DSL)
- **Min SDK**: 26 (Android 8.0 Oreo) — NotificationListenerService is reliable from API 26+
- **Target SDK**: 34 (Android 14)
- **Key libraries**:
  - `OkHttp 4.12.0` — HTTP client for forwarding notifications
  - `Room 2.6.1` — local offline queue (SQLite)
  - `Kotlin Coroutines 1.7.3` — async retry loop in background service
  - `Firebase Messaging` — FCM device token registration

### Server
- **Runtime**: Node.js (ESM modules)
- **Framework**: Express 4
- **Database client**: `@neondatabase/serverless` (WebSocket-based Neon pooler)
- **Auth**: Custom middleware — `X-API-Key` header (Android), `Bearer <password>` (dashboard), `?token=` (SSE EventSource)
- **Real-time**: Server-Sent Events (SSE) with 25 s keepalive ping; no WebSocket needed
- **Push**: `firebase-admin` SDK for FCM
- **Hosted on**: Render (free web service, auto-deploys from GitHub `main`)

### Dashboard
- **Pure HTML/CSS/JS** — no framework, no bundler
- Single `index.html` deployed as a Vercel static site
- Fonts: Google Fonts (Inter + JetBrains Mono)
- Communicates with Render server via `fetch` + `EventSource`

### Database Schema
```sql
CREATE TABLE notifications (
  id           BIGSERIAL PRIMARY KEY,
  device_id    VARCHAR(64)  NOT NULL,
  package_name VARCHAR(255) NOT NULL,
  app_label    VARCHAR(255),
  title        TEXT,
  text_body    TEXT,
  big_text     TEXT,
  sub_text     TEXT,
  posted_at    TIMESTAMPTZ  NOT NULL,
  received_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  dedup_hash   VARCHAR(64)  UNIQUE NOT NULL,
  is_read      BOOLEAN      NOT NULL DEFAULT FALSE,
  is_archived  BOOLEAN      NOT NULL DEFAULT FALSE,
  image_url    TEXT                              -- Cloudinary URL, nullable
);
-- Migration (run once if table already exists):
-- ALTER TABLE notifications ADD COLUMN image_url TEXT;
```

---

## Android Permissions

| Permission | Why |
|---|---|
| `INTERNET` | POST notifications to Render server |
| `FOREGROUND_SERVICE` | Keep the sync service alive in background |
| `FOREGROUND_SERVICE_DATA_SYNC` | Declares the foreground service type (API 34+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask user to whitelist app from Doze mode |
| `RECEIVE_BOOT_COMPLETED` | Auto-restart service after phone reboot |
| `POST_NOTIFICATIONS` | Show the silent foreground notification (required API 33+) |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | System-granted; allows reading all notifications |

---

## Android Version Compatibility

| API Level | Android Version | Status | Notes |
|---|---|---|---|
| 26–27 | 8.0–8.1 Oreo | ✅ Supported | Min SDK; NLS reliable here |
| 28 | 9.0 Pie | ✅ Supported | — |
| 29 | 10 | ✅ Supported | Background service restrictions tightened; `START_STICKY` handles it |
| 30 | 11 | ✅ Supported | Scoped storage changes don't affect us |
| 31–32 | 12–12L | ✅ Supported | Foreground service exact-alarm restrictions; not used here |
| 33 | 13 | ✅ Supported | `POST_NOTIFICATIONS` runtime permission required — app requests it in `MainActivity` |
| 34 | 14 | ✅ Supported | `FOREGROUND_SERVICE_DATA_SYNC` type required — declared in manifest |
| 35+ | 15+ | ✅ Expected | No known blockers |

### Known OEM Restrictions
These require **manual user action** — no code workaround:

| OEM / ROM | Issue | Fix |
|---|---|---|
| **MIUI / HyperOS** (Xiaomi) | Aggressive background app kill | Settings → Battery → App Battery Saver → NotifyHub → No restrictions |
| **ColorOS** (OPPO/Realme) | Auto-start blocked | Settings → App Management → NotifyHub → Auto-start → Allow |
| **One UI** (Samsung) | "Sleeping apps" kills background services | Battery → Background usage limits → remove NotifyHub |
| **Funtouch OS** (Vivo) | Background activity restriction | Same pattern — allow in battery settings |

---

## API Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/health` | None | Server uptime check / pre-warm |
| `POST` | `/api/auth/login` | None | Password login (returns JWT; not currently used by dashboard) |
| `GET` | `/api/notifications` | ✅ | Paginated feed; query: `page`, `limit`, `package`, `search`, `unread` |
| `POST` | `/api/notifications` | ✅ | Receive notification from Android |
| `DELETE` | `/api/notifications` | ✅ | Bulk hard-delete; body: `{ ids: [1,2,3] }` |
| `PATCH` | `/api/notifications/:id/read` | ✅ | Mark single notification read |
| `PATCH` | `/api/notifications/:id/archive` | ✅ | Archive (soft-delete) single notification |
| `PATCH` | `/api/notifications/mark-all-read` | ✅ | Mark all unread as read |
| `GET` | `/api/notifications/stream` | ✅ via `?token=` | SSE stream for real-time push |

---

## Deduplication

**Client-side (Android)**: `DedupCache` holds the last 200 SHA-256 hashes with a 90-second window. Same `packageName + title + text` within 90 s is dropped before sending.

**Server-side (DB)**: `dedup_hash` column has a `UNIQUE` constraint. If the same hash arrives again (e.g. after app restart), the INSERT returns `409 Conflict` and the Android client ignores it.

---

## Dashboard Features

- **Real-time feed** via SSE — new notifications appear instantly
- **Day-wise grouping** — Today / Yesterday / older dates, each collapsible
- **App filter** — left sidebar shows all apps with unread badge counts
- **Search** — full-text search across title, body, and app name
- **Mark read / Archive** — per-card actions
- **Mark all read** — bulk action
- **Select & Delete** — select individual or all, bulk hard-delete
- **Mobile responsive** — hamburger drawer sidebar on phones
- **Dark / light theme** — remembers preference in localStorage
- **Server pre-warm** — health ping on page load reduces Render cold-start delay

---

## Security Model

- Single-user personal tool — no multi-tenancy
- Android APK authenticates with `X-API-Key` header (server env var `API_KEY`)
- Dashboard uses a hardcoded `Bearer <password>` token (env var `DASHBOARD_PASSWORD`)
- SSE stream uses `?token=` query param (EventSource cannot set headers)
- CORS is `Access-Control-Allow-Origin: *` — acceptable for a personal tool
- No OTP / email auth — removed due to Render cold-start issues

---

## Notification Filtering (Android)

**Blocklist** (always skipped — system noise):
```
android, com.android.systemui, com.android.settings,
com.google.android.gms, com.android.phone, com.android.incallui,
com.miui.daemon, com.miui.securitycenter
```

**Ongoing notifications**: Captured (not skipped) — banking apps like bKash mark OTP notifications as `FLAG_ONGOING_EVENT` to keep them visible; skipping ongoing would miss OTPs.

**Empty notifications**: Skipped — if both `title` and `text` are null/blank.

---

## Image Preview

Notifications containing a picture (Instagram DMs, messaging app previews, etc.) are captured and stored as images:

1. **Android**: Extracts `Notification.EXTRA_PICTURE` Bitmap → compresses to JPEG 60% → skips if >500 KB → base64 encodes → sends in POST body as `imageData`
2. **Server** (`server/src/cloudinary.js`): Uploads base64 data to Cloudinary (signed REST API, no SDK) → stores `secure_url` in `image_url` column
3. **Dashboard**: Renders `<img class="card-img">` in notification card when `image_url` is present
4. **Cleanup**: On bulk delete, server fetches `image_url` values and fires Cloudinary `image/destroy` before deleting rows
- **Storage**: Cloudinary free tier (cloud: `m3z1qda9`) — 25 GB storage + 25 GB bandwidth/month
- **Note**: Images are best-effort real-time only — offline-queued notifications lose image data (not stored in Room DB)

---

## URLs

| Resource | URL |
|---|---|
| Dashboard | https://notifyhub-dashboard.vercel.app |
| API server | https://notificationhub-10g2.onrender.com |
| GitHub repo | https://github.com/teletalkagami01521-dev/notificationhub |
