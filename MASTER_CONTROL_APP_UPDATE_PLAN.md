# Master Control App Update Plan (Future Build)

Status: **planning only**. No app-level source was changed in this pass.

This plan is for the next `master control` APK only. The current installed app is `com.storagesync.debug` version `8.17.4`, built from `android master control/`. The server/dashboard side is already updated and deployed in commit `3e302fb`, including `sender_name` support, event-time ordering, conservative throttling, SSE delete/read/status events, and the live-connection dot.

The plan is designed to work within Android/MIUI limits. Where the OS or a chat app does not expose data, the app must keep the existing fallback instead of guessing. That is the main rule for "will work": **prefer real structured data, fall back to current behavior, never fabricate messages or senders.**

**Important:** the target Redmi Note 10 Pro is **not connected during planning**, so no Android behavior can be verified live here. Every new capture path must therefore be guarded, diagnostic, reversible, and must degrade to the current 8.17.4 behavior on failure. Nothing in this plan may depend on "we tested it on the phone" unless that step is explicitly listed as an on-device acceptance gate in section 6.

Research basis for notification images: Android defines `EXTRA_PICTURE` (`android.picture`) as the `BigPictureStyle.bigPicture` bitmap only; it is not a general "any notification image" field. MessagingStyle images are carried as message `uri` + `type` bundles (`android.messages`, keys `text`, `time`, `sender_person`/`sender`, `type`, `uri`), and listeners can read those `content://` URIs only while the posting app's per-URI grant is live. API 31+ may also provide `EXTRA_PICTURE_ICON` (`android.pictureIcon`). Sources: https://developer.android.com/reference/android/app/Notification , https://developer.android.com/reference/android/app/Notification.MessagingStyle.Message , and AOSP Notification.java / NotificationManagerService.java message-URI grant logic. Git history check found no richer extractor in any current or historical tree: every version used the same `EXTRA_PICTURE`-only code. Earlier visible "photos with notifications" were most likely `BigPictureStyle` rows from other packages or media-gallery rows, not recovered Messenger notification bytes.

---

## 0. Non-negotiable build rules

1. Work only in `android master control/`. Do **not** switch to `android/` as the base.
   - `android/` is `com.notifyhub` `1.2-smart-filter`.
   - It has no media pipeline.
   - It blocks `com.android.systemui`, so notification-shade replies are explicitly not captured.
   - It removed `targetName` from typed-text payloads.
   - Port selected ideas from it, but do not replace the installed app with it.

2. Keep the identity and signing key of the installed `8.17.4` build.
   - Application id must remain `com.storagesync.debug`.
   - Sign with the same keystore/certificate as the currently installed `8.17.4` APK.
   - A different signing key changes the apparent app identity, can reset grants, and can change the `ANDROID_ID`-derived `deviceId`.

3. Bump versions:
   - `versionCode`: `81704` → `81705`
   - `versionName`: `8.17.4` → `8.17.5`

4. Preserve permissions and user setup:
   - Notification listener access
   - Accessibility/text capture access
   - All-files access
   - Media permissions
   - Downloads SAF tree URI
   - Battery exemption / autostart

5. Add a Room migration. Do not rely on `fallbackToDestructiveMigration()` for the new queue fields, because that would drop pending notifications/typed text on upgrade.

---

## 0a. Failsafe rules because the Redmi Note 10 Pro is not connected

These rules are mandatory. They turn the update from "hope it works" into a controlled rollout.

1. **Keep the old path alive.** Never replace `extractImageData(extras)` with a new extractor that can throw and kill notification forwarding. Wrap every new extraction branch in its own `try/catch`; on any error, skip only that branch and continue to the next.

2. **Feature flags with safe defaults.** Add `notifyhub_feature_flags` preferences:
   - `notification_image_v2 = true`
   - `notification_photo_intent = true`
   - `typed_text_v2 = true`
   - `diagnostics = true`
   - `media_owner_v2 = true`
   Each feature must be independently switchable off before install and from a hidden long-press on the setup screen. A disabled feature must restore exact 8.17.4 behavior.

3. **Never fabricate media.** If the notification text says "Sent a photo" but no bitmap/URI is readable and no matching local media file appears, store the text row with `photoIntent=true` and a dashboard marker like `photo mentioned — thumbnail unavailable`. Do not attach an avatar, icon, or placeholder image and call it the photo.

4. **Copy URI bytes immediately.** MessagingStyle `content://` URIs are only readable while the system grant is live. Open and copy bytes inside `onNotificationPosted`; never save a URI for later.

5. **Downscale instead of dropping.** Replace the current all-or-nothing `>512_000` byte drop with a bounded downscale/quality loop. Keep the final data URL under a defined cap (start with 1.5 MB base64; server JSON limit is 15 MB), but always try to send something before giving up.

6. **Queue the image with the notification.** A queued notification must retain `imageData`, `imageSource`, and `photoIntent`; `drainQueue()` must retry with the image. A row that was inserted with `image_url = NULL` because Cloudinary failed must be retryable, not permanently lost.

7. **Diagnostics without content.** Add counters and a local diagnostic log of extra names/types, candidate URI schemes, byte sizes, chosen source, and exception class names. Never log message text, image bytes, or full URIs. Include the counters in `AppStatusReporter` so the dashboard can prove the new code ran after install.

8. **Staged verification.** Section 6 is split into offline checks (possible here) and on-device gates (only after the Redmi is connected). No release APK or tag until every on-device gate passes.

9. **Rollback is always available.** Keep the signed 8.17.4 APK and a DB backup. Rollback means installing the old APK with the same signing key, not uninstalling and losing grants. If a feature flag is the problem, disable it before reinstalling the old APK.

---

## 1. Current state summary

| Requirement | Current installed app | Server/dashboard |
|---|---|---|
| Notification time, app, text | Working: `EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, `EXTRA_SUB_TEXT` | Working; now also stores/search-displays `sender_name`, `big_text`, `sub_text` |
| Notification photo | **Broken for Messenger**: DB has 358 Messenger notifications and 0 with `image_url`; `📷 Sent a photo to you` at `2026-11-26T05:11:59.826Z` has `image_url = NULL`. Other packages with `BigPictureStyle` bitmaps still work | Server upload/display already works when the app sends `imageData`; no image bytes exist to backfill old rows |
| Structured sender from Messenger/WhatsApp/Instagram/Snapchat | Not implemented; sender only appears if embedded in `title` | Ready to receive `senderName` and display it as `from <name>` |
| In-app typed text | Working, but progressive snapshots/duplicates and target often blank | Ready; 90-min target inheritance and event-time sorting deployed |
| Notification quick-reply typed text | Unreliable: placeholder `"Type a message…"` seen, real reply text missing | Server blocks known placeholders; cannot recover text the app never sends |
| System/placeholder throttling | Weak client-side filtering in installed build | Conservative server throttling deployed: exact input-method block, exact-content cooldown, short marker cooldown for MIUI/Xiaomi only |
| Media: public images, video frames, downloads, local trash | Implemented in source, likely working when grants are set | Working storage/gallery/feed |
| Screenshot as a labeled media type | Not implemented | No dedicated label |
| True MediaStore owner package | Not read | Path/filename heuristic only |
| Permanent deletion / cloud-only Google Photos trash | Impossible locally after evidence is gone | N/A |
| Accessibility service alive heartbeat | Not implemented | Only a Settings "enabled" flag; no proof the service is running |

---

## 2. Change set A — notification sender, real chat body, and notification photos

### Goal

When a Messenger/WhatsApp/Instagram/Snapchat notification arrives, send:

- app/package label — already working
- posted time — already working
- visible text — already working
- notification photo — **currently broken for Messenger; must be restored with fallbacks**
- structured sender name (`senderName`) — new
- best available real chat body — new fallback chain

### Files to change

- `android master control/app/src/main/java/com/notifyhub/data/PendingNotification.kt`
- `android master control/app/src/main/java/com/notifyhub/data/AppDatabase.kt`
- `android master control/app/src/main/java/com/notifyhub/App.kt`
- `android master control/app/src/main/java/com/notifyhub/NotificationForwarderService.kt`
- `android master control/app/src/main/java/com/notifyhub/NotificationSender.kt`
- `android master control/app/src/main/java/com/notifyhub/data/NotificationMediaIntent.kt` (new Room entity/DAO for A6)
- `android master control/app/src/main/java/com/notifyhub/utils/NotificationImageExtractor.kt` (new guarded extractor; keep old function as fallback)

### Exact changes

#### A1. Queue model and Room migration

In `PendingNotification.kt`, add:

```kotlin
val senderName: String? = null,
val chatBody: String? = null,
val imageData: String? = null,
val imageSource: String? = null,
val photoIntent: Boolean = false,
```

`imageSource` is a short diagnostic label such as `extra_picture`, `picture_icon`, `message_uri`, `extra_stream`, `large_icon_big`, or `media_correlated`. `photoIntent` means the notification text/type indicated a photo even if no bytes were found.

In `AppDatabase.kt`:

- bump `@Database(... version = 5 ...)`
- add `MIGRATION_4_5`

In `App.kt`:

- replace bare `.fallbackToDestructiveMigration()` with:
  - `.addMigrations(AppDatabase.MIGRATION_4_5)`
  - keep `.fallbackToDestructiveMigration()` only as a last-resort after the explicit migration

Migration SQL:

```sql
ALTER TABLE pending_notifications ADD COLUMN senderName TEXT
ALTER TABLE pending_notifications ADD COLUMN chatBody TEXT
ALTER TABLE pending_notifications ADD COLUMN imageData TEXT
ALTER TABLE pending_notifications ADD COLUMN imageSource TEXT
ALTER TABLE pending_notifications ADD COLUMN photoIntent INTEGER NOT NULL DEFAULT 0
```

Also add a unique index only after the stable hash change in A2:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_pending_notification_dedup
  ON pending_notifications(deviceId, dedupHash)
```

This preserves queued notifications during upgrade.

#### A2. Notification extraction

In `NotificationForwarderService.kt:32-61`, keep the current fields and add a helper:

```kotlin
private fun extractMessaging(extras: Bundle): Pair<String?, String?>
```

Fallback order for sender:

1. `Notification.EXTRA_CONVERSATION_TITLE`
2. `Notification.MessagingStyle` conversation title / `Person.name`
3. `Notification.EXTRA_MESSAGES` (`android.messages`) entry `Person.name`
4. current `EXTRA_TITLE`

Fallback order for body:

1. `EXTRA_BIG_TEXT`
2. joined real lines from `EXTRA_MESSAGES` / `android.messages` (each message's `text`)
3. `EXTRA_TEXT`

Never replace a real body with the literal word `"message"`. If the only available body is `"message"`, keep it but allow `chatBody` from `android.messages` to win.

Make `dedupHash` stable instead of `UUID.randomUUID()`:

```text
SHA256(deviceId | packageName | sbn.key | postTime | senderName | finalBody | imageSource | photoIntent)
```

Do **not** put the full `imageData` base64 into the hash. If the image bytes change but the source/intent stay the same, the hash stays stable. Reason: the server unique key is `(device_id, dedup_hash)`; a stable hash prevents duplicate rows on rebinding while allowing genuinely different messages through.

Important sequencing: run image extraction **before** computing the hash and building `PendingNotification`, so a retried queued item with image bytes does not collide with an earlier no-image row under the same hash.

#### A3. Payload

In `NotificationSender.kt:100-114`, add to the JSON:

```json
"senderName": "...",
"chatBody": "...",
"imageData": "data:image/jpeg;base64,...",
"imageSource": "message_uri",
"photoIntent": true
```

The deployed server already accepts `senderName` and `imageData`; `chatBody` can be sent as `bigText`/`textBody` too, so the dashboard renders without another server deploy for those fields. `imageSource` and `photoIntent` require the small deferred server columns in section 8; until then, send them but tolerate the server ignoring them.

#### A4. Restore notification photos — replace the `EXTRA_PICTURE`-only extractor

Current `NotificationForwarderService.kt:64-75` only does:

```kotlin
extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
```

That works for `BigPictureStyle` notifications (Google Photos, bKash, etc.) but not for Messenger's `📷 Sent a photo to you`, which is consistent with a MessagingStyle image message whose bytes live at a message `uri` with MIME `image/*`. This is the proven root cause of `id=7776` having `image_url = NULL`.

Replace `extractImageData(extras)` with a guarded function that takes `sbn` and `context`, then tries these sources in order. Each branch must be independently wrapped and must record `imageSource` on success:

1. `extra_picture` — existing `Notification.EXTRA_PICTURE` (`android.picture`) `Bitmap`. Keep this first so no currently working package regresses.
2. `picture_icon` — API 31+ `Notification.EXTRA_PICTURE_ICON` (`android.pictureIcon`) `Icon`; convert `Icon.TYPE_BITMAP`/`TYPE_ADAPTIVE_BITMAP` via `loadDrawable`, and if `TYPE_URI`, open the URI stream immediately.
3. `message_uri` — `Notification.EXTRA_MESSAGES` (`android.messages`) and `EXTRA_HISTORIC_MESSAGES` (`android.messages.historic`). Iterate newest-first. Raw bundle keys are:
   - `text` — CharSequence fallback text
   - `time` — Long timestamp
   - `sender_person` — Person on API 28+; AndroidX may also use `person`
   - `sender` — legacy CharSequence
   - `type` — MIME string, e.g. `image/jpeg`
   - `uri` — Parcelable `Uri` (**not** `dataUri`)
   - `extras` — nested Bundle on API 26+
   Accept `type.startsWith("image/")`, open with `contentResolver.openInputStream(uri)`, copy bytes immediately, and catch `SecurityException`, `FileNotFoundException`, and generic `Exception` so one bad URI cannot stop the notification.
4. `extra_stream` — `Notification.EXTRA_STREAM` if it is a readable `content://` URI with an image MIME. Do not fetch `https://` URLs.
5. `large_icon_big` / `large_icon` / `getLargeIcon()` — only when `photoIntent` is true **and** the icon is not obviously an avatar. Compare dimensions; skip square icons <= 128 px and anything whose content description looks like `avatar`, `profile`, `contact`, `profile picture`. Messenger avatars must never be attached as the sent photo.
6. `oem_bundle` — last resort only when `photoIntent` is true: recursively scan nested Bundles for `Bitmap`, `Icon`, and `Uri` values, depth <= 4, visited-set to avoid cycles, max 200 nodes. Reject non-image MIMEs and avatars using the same rules.

`photoIntent` detection:

```text
text/type matches:
  "sent a photo", "sent N photo", "sent photos", "ছবি", "ফটো", "image",
or any MessagingStyle message has type starting "image/"
or image content description is present
```

When `photoIntent=true` but no bytes are readable, still send the notification row with `photoIntent=true` and `image_url = NULL` rather than dropping it.

Useful evidence to preserve in diagnostics:

```text
extras.keySet() names only
message bundle key names only
candidate URI scheme only (content/file/http), not full URI
chosen imageSource
compressed byte size
exception class simpleName
```

#### A5. Image encoding, limits, queue, and retry

Replace the current "compress JPEG 60, drop if >512 KB" behavior:

```text
decode bytes/bitmap
max dimension pass: 1600 → 1280 → 1024
JPEG quality pass: 80 → 70 → 60 → 50
stop when data URL <= 1.5 MB; never exceed it
if still too large, keep the smallest attempt; do not drop entirely
```

Files to change:

- `NotificationForwarderService.kt` — call `extractNotificationImage(sbn)` before payload/hash creation.
- `PendingNotification.kt` — store `imageData`, `imageSource`, `photoIntent`.
- `NotificationSender.kt:22-29,59-75,100-114` — queue with image, retry with image, include all new fields in JSON.
- `App.kt`/`AppDatabase.kt` — explicit Room migration from A1.

Server contract change required in section 8:

- If `uploadImage(imageData)` returns `null`, do **not** insert the row with `image_url = NULL` and return 201. Return `503` so the app treats it as a retryable failure and keeps the image in Room.
- If the row is already inserted and later an image becomes available through correlation, use the `related_notification_hash` flow in A6 rather than a fragile `409` re-POST.

#### A6. Media-correlation failsafe for Messenger photos

This is the "do not miss it" fallback when Messenger's notification exposes only placeholder text and the photo bytes are unavailable to the listener.

Flow:

1. When `photoIntent=true` and no `imageData` was found, write a local Room row: `notification_media_intent` with `packageName`, `senderName`, `dedupHash`, `windowStart`, `windowEnd` (start 5 minutes before post, 20 minutes after), `matched=false`.
2. Existing public-media/AllFiles/Downloads capture keeps running. When a new image appears from the same package or a known Messenger path within that window, tag it with `relatedNotificationHash` and upload it through the normal media pipeline.
3. Known Messenger paths/signals to correlate:
   - `Android/media/com.facebook.orca/`
   - `Pictures/Messenger/`
   - `Download/*Messenger*`
   - `MediaStore.OWNER_PACKAGE_NAME = com.facebook.orca` where available
   - share intents whose referrer/source resolves to `com.facebook.orca`
4. The dashboard should show the correlated media thumbnail inline on the notification row when `notification.image_url` is still null. Server support in section 8 stores `related_notification_hash` on `media_captures`; until that exists, the media row still appears in the normal gallery/timeline.
5. If no matching media appears within 24 hours, mark the intent `expired` and keep the notification text marker. Never guess an image.

This fallback is especially important because the target phone is not connected: if Android/Messenger gives no readable notification bytes but does save the photo locally, the update still captures it without needing a second app change.

### Acceptance test

1. Send a Messenger notification whose title is a contact name. Dashboard row must show `from <contact>` in the preview.
2. If `EXTRA_TEXT` is `"message"` but `android.messages` contains the real line, the dashboard must show the real line.
3. If a chat app exposes no messaging extras, behavior must match today: title/text still appear, sender falls back to title.
4. After app upgrade, previously queued notifications must still drain — no queue loss or image loss.
5. Receive `📷 Sent a photo to you` from Messenger. Dashboard row must show the actual photo, with `image_source` in diagnostics either `message_uri`, `extra_picture`, `picture_icon`, `extra_stream`, or `large_icon_big`; never `avatar`.
6. Receive a photo notification while offline. After reconnect, the same row must gain the image; no duplicate row with `image_url = NULL`.
7. Receive a photo notification with no readable notification bytes but a locally saved Messenger photo. The media-correlation fallback must attach/link the photo within 20 minutes.
8. Receive a normal text notification with a round contact avatar as large icon. The app must **not** attach the avatar as a photo.
9. Inject a 4000 px bitmap. The downscale loop must send a valid bounded data URL instead of dropping it.
10. Disable `notification_image_v2`; behavior must match 8.17.4 exactly.

---

## 3. Change set B — typed text: quick reply + in-app typing

### Goal

Both of these must produce typed-text rows with the real text:

- typing inside Messenger/WhatsApp/Instagram/Snapchat
- replying from the notification shade remote input

Rows must use the time typing was first seen, not the send/flush time, and must not contain placeholder-only values.

### Important platform fact

There are two capture paths:

1. `TYPE_VIEW_TEXT_CHANGED` with `event.source.isEditable == true` — current in-app typing works this way.
2. `TYPE_WINDOW_CONTENT_CHANGED` + `rootInActiveWindow.findFocus(FOCUS_INPUT)` — this is what saw `"Type a message…"` in the shade.

MIUI remote-input text changes often do not arrive as editable `TYPE_VIEW_TEXT_CHANGED`. The fix is to read the focused editable node on every content change, not only once, and to flush the last real text when the field is cleared after send.

### Files to change

- `android master control/app/src/main/java/com/notifyhub/TypedTextService.kt`
- `android master control/app/src/main/java/com/notifyhub/data/PendingTypedText.kt`
- `android master control/app/src/main/java/com/notifyhub/NotificationSender.kt`
- `android master control/app/src/main/java/com/notifyhub/ServiceHealth.kt`
- `android master control/app/src/main/java/com/notifyhub/AppStatusReporter.kt`

### Exact changes

#### B1. Do not block SystemUI

The installed build does not block `com.android.systemui`; keep that. Do **not** import the `BLOCKLIST` from `android/`, because it blocks shade replies.

Block only known noisy packages for ordinary text capture, but allow an exception when a focused editable node contains real text in a messaging context.

#### B2. Read the focused editable node on every content change

Current `onContentChanged()` reads the focused node and upserts. Keep it, but add:

- `node.hintText` / `contentDescription` only for placeholder rejection
- read `node.text` every time; do not only capture once
- if text is blank because the user pressed send, flush the pending final text before clearing

Pseudo-flow:

```text
onContentChanged:
  node = focused editable input
  text = node.text.trim()
  if text is nonblank and not placeholder:
      upsertPending(...)
  else if pending exists for this field:
      commit(key)   // user cleared/sent; preserve last real text
```

#### B3. Fallback when `event.source` is null/non-editable

In `onTextChanged()`, if `event.source` is null or not editable:

```kotlin
val node = rootInActiveWindow?.findFocus(FOCUS_INPUT)
if (node != null && node.isEditable && !node.isPassword) {
    use node.text as the text
}
```

This is the specific fix for the observed gap where only `"Type a message…"` was captured.

#### B4. Conservative placeholder filter

Add a helper like the `android/` variant's `isPlaceholderOrHint()`, but **do not** copy its `length <= 1` rule.

Reject only when:

- text equals the node's `hintText` after normalization
- text is in the exact set: `message`, `type a message`, `write a message`, `send a message`, `ask google`
- text is the same as `contentDescription` and contains no user-typed content

Do not reject short real messages such as `"r"`, `"ok"`, `"ha"`, or `"1"` when they are not hints.

#### B5. Correct captured time

`TypedTextService.kt:219-230` currently sends `Instant.now()` at flush time. Change it to:

```kotlin
capturedAt = Instant.ofEpochMilli(item.firstSeenAt).toString()
```

This makes the dashboard timeline reflect the first typing snapshot, not the debounce flush.

#### B6. Progressive snapshots and duplicates

Keep the per-field key `package:viewIdResourceName` and overwrite pending text as the user types. On flush/clear:

- send only the latest text for that key
- remove the pending item
- suppress an identical normalized text for the same package within 30 seconds

This preserves the final message while avoiding 4–5 rows for one typing session.

#### B7. Target attribution for shade replies

Keep the existing `targetFromContext()` and server 90-minute inheritance.

For `com.android.systemui` remote input, look up the most recently captured notification for the same messaging package within 10 minutes and use its `senderName` as `targetName` if accessibility context has no `to/recipient` node.

This uses the notification data from change set A; no guessing.

#### B8. Accessibility heartbeat

Add to `ServiceHealth`:

- `typed_text_alive_at` in `notifyhub_state` prefs
- update it in `TypedTextService.onServiceConnected()` and on every captured event
- include it in `AppStatusReporter.kt` JSON as `typedTextAliveAt`

Future server change needed after this APK:

- `ALTER TABLE app_status ADD COLUMN typed_text_alive_at TIMESTAMPTZ`
- include it in `appStatus.js`, `devices.js`, and `appRoles.js`
- dashboard shows `Text service not alive` when the value is stale

Do not attempt to auto-toggle accessibility settings; MIUI will not reliably allow it. Surface a "Re-open app / re-grant text access" warning instead.

#### B9. Typed-text failsafes for the unconnected target

The 8.17.4 behavior already failed for `Good morning` at `11:07` local: no typed row appeared after `01:03`, even though the Settings toggle stayed enabled. Treat that as proof that "enabled" is not "alive".

Add:

- `typed_text_alive_at` updated on every received accessibility event and at least every 60 seconds while the service is connected.
- `typed_events_total`, `typed_text_sent_total`, `typed_placeholder_rejected_total` counters in `AppStatusReporter`.
- A self-test button/action on the setup screen: "Send test typed event". It writes one harmless local event that must appear in dashboard within 5 seconds if both service and server are healthy.
- On `onInterrupt()` / `onDestroy()`, mark the service not alive immediately; on reconnect, force an `AppStatusReporter.report(force = true)`.
- If `typed_text_v2` is off, keep only the stable 8.17.4 behavior: no new focused-node fallback, no blank-field flush.

### Acceptance test

1. Type in Messenger app → real progressive text captured, final text retained.
2. Reply from notification shade → real text captured, not just `"Type a message…"`.
3. Send quickly (<2.5 s) → final text is still sent before pending is cleared.
4. Placeholder rows no longer appear.
5. `captured_at` is close to the first keystroke, not 2.5+ seconds later.
6. Target shows the contact when available; blank target inherits within 90 minutes.
7. Send `Good morning` both inside Messenger and from the shade; both must appear as typed rows.
8. Self-test button → typed row appears within 5 seconds; if not, dashboard shows `Text service not alive` within 2 minutes.
9. Disable `typed_text_v2`; behavior matches 8.17.4 and no new false rows appear.

---

## 4. Change set C — safer throttling and system noise

### Goal

Keep spam quiet without dropping real messages.

### Client side

Port only these ideas from `android/NotificationForwarderService.kt`:

- `isNoisyProgressOrEphemeral()` — drop exact ephemeral/progress phrases unless terminal phrases like sent/delivered/complete appear
- `shouldSuppressState()` — suppress identical `sbn.key` + content within 1.5 s
- SHA-256 dedup cache within 15 s

Do **not** port:

- the full package blocklist that drops SystemUI, GMS, YouTube, Pinterest, etc.
- the typed-text `length <= 1` placeholder rule
- the typed-text SystemUI block

### Server side already done

Current deployed behavior:

- Android system "Choose input method" → exact phrase block
- Daraz/GMS → identical content 30-min cooldown
- MIUI/Xiaomi → identical content 30-min cooldown plus a 10-min marker cooldown only when title/text contains known system-spam markers
- typed placeholders → exact normalized set

This is intentionally conservative: a normal message from a MIUI/Xiaomi package passes unless it repeats identical content or matches a known system marker.

### Acceptance test

1. Ten different MIUI system notifications with different content must not all be dropped; only marker matches within 10 min are throttled.
2. A real message from a Xiaomi package with no spam marker must be stored.
3. Two identical Daraz/GMS notifications within 30 min → second throttled.
4. `"Type a message…"`, `"Message"`, `"Ask Google"` → ignored.
5. `"r"`, `"ok"`, `"Khao ni ekhono!"` → stored.

---

## 5. Change set D — media completeness

### Goal

Cover images/video frames from public app storage, camera, screenshots, downloads/saves, and local trash/disappearance.

### D1. Screenshot labeling

Files:

- `MC/MediaCaptureManager.kt` constants and `sourceInfoFor()`
- `MC/AllFilesCapture.kt` `sourceFor()`
- `MC/MediaUploader.kt` labels
- dashboard `mediaAction()` / gallery source label

Add path/name markers:

```text
/DCIM/Screenshots/
/Pictures/Screenshots/
Screenshot_
Screenshot-
Screenshot @
```

Set `source = "screenshot"` and label `Screenshot`.

True screenshot-event capture would require user-consented `MediaProjection`; path/name detection is the safe default and works for normal Android/MIUI screenshot locations.

### D2. True owner package

In `MediaCaptureManager.kt:224-236`, add to the MediaStore projection on API 29+:

```kotlin
MediaStore.MediaColumns.OWNER_PACKAGE_NAME
```

Use it before path heuristics:

```text
OWNER_PACKAGE_NAME → path heuristic → shared_app → media_store
```

This improves Messenger/Instagram/Snapchat attribution for public files. Private caches still require Share.

### D3. Downloads SAF periodic poll

Current SAF tree observer can miss events. Add:

- store last SAF poll time in `MediaSettings`
- in `MediaCaptureManager.poll()`, call `DownloadFolderCapture.pollTree(context, since)`
- cadence: every 5 minutes, same as MediaStore/all-files poll
- keep 500-document cap and 24-h manual scan

### D4. Local trash/disappearance

Current local `is_trashed = 1` scan works on API 30+. Add retention-based detection:

- store seen MediaStore IDs/hashes in Room
- on each 5-min scan, if a previously seen local media item disappeared and is not marked archived in app, emit a row with `source = "trash"` and action `photo deleted` / `video deleted`
- keep the existing `is_trashed = 1` path for rename/trash state

Cloud-only Google Photos trash remains impossible to observe locally. Do not claim otherwise.

### D5. Video frames vs original video

Current design uploads intervaled JPEG frames, not the video file. This matches "intervaled frames as earlier discussed".

If real video playback is later required:

- add `video/upload` path in `Cloudinary`
- store `video_url` in `media_captures`
- dashboard `<video>` control in gallery

Not required for the current wording.

### D6. Share-from-app attribution

`EXTRA_REFERRER` is often absent. Add fallback sources where available:

- `Intent.EXTRA_SOURCE_PACKAGE` if present
- `clipData` URI host/authority
- keep `shared_app` fallback

Do not guess a brand from filename alone unless path heuristics are strong.

### D7. Notification-photo media correlation

Implement the receiver side of A6 here:

- Add Room entity `notification_media_intent` and a small DAO method `findOpenIntent(packageName, capturedAt)`.
- Call it from `MediaCaptureManager.scanCollection()`, `AllFilesCapture.scanRecent()/poll()`, and `DownloadFolderCapture.scanRecent()/pollTree()` before uploading a new image.
- On match, pass `relatedNotificationHash` to the media payload and mark the intent `matched`.
- Keep the normal media row even if the notification row no longer exists; correlation is enrichment, not a dependency.
- If `media_owner_v2` is off, skip owner-based matching but keep path-based matching.

### Acceptance test

1. Screenshot → appears as `Screenshot`, not generic image.
2. Camera photo → `camera`.
3. Messenger/Insta/Snap saved image → package/owner label when exposed by MediaStore.
4. Download saved to chosen SAF tree → row appears even if observer event was missed.
5. Local file moved to trash → row appears as `photo trashed` / `photo deleted`.
6. Permanent delete with no local trace → honestly not detectable; document it.
7. Messenger photo notification with no readable notification bytes + locally saved photo → media row carries `relatedNotificationHash` and dashboard links it to the notification.
8. Disable `media_owner_v2`; path-based correlation still works.

---

## 6. Rollout checklist

### 6A. Offline work that can be completed here (Redmi not connected)

1. Create branch from current `main`.
2. Apply Room migration and bump version to `81705 / 8.17.5`.
3. Implement change sets A–D in small commits, one feature flag group per commit.
4. Add JVM/local unit tests with synthetic Bundles for:
   - `EXTRA_PICTURE` only
   - MessagingStyle `uri` + `type = image/jpeg`
   - MessagingStyle `uri` that throws `SecurityException`
   - giant bitmap requiring downscale
   - avatar-only large icon with `photoIntent=false`
   - no image at all with `photoIntent=true`
5. Add server contract checks with `curl`/Node for:
   - POST notification with synthetic `imageData` → 201 and non-null `image_url`
   - simulated Cloudinary failure → 503, not 201 with NULL image
   - media POST with `relatedNotificationHash` once section 8 lands
6. Run `./gradlew assembleDebug` and `./gradlew test` locally.
7. Build a signed debug APK with the current signing config but **do not install it anywhere yet**.

### 6B. On-device gates — only after the Redmi Note 10 Pro is connected

8. Back up the current Room DB/export history and keep the installed 8.17.4 APK available for rollback.
9. Install the new debug APK **over** 8.17.4 with the same signing key. Do not uninstall first.
10. Re-open the app once; re-grant only permissions Android resets; confirm all four dashboard health flags are green.
11. Run every acceptance test in sections 2, 3, 4, and 5. Capture dashboard row IDs and diagnostic counters.
12. Specifically confirm:
    - `📷 Sent a photo to you` produces an image, not just text
    - `Good morning` from in-app and shade produce typed rows
    - sender appears as `from <contact>`
    - no avatar is attached to ordinary text rows
    - offline photo queue retries with image
    - typed heartbeat is fresh
    - screenshot/download/trash still work
13. Monitor for 30 minutes: no crashes, no listener disconnect storm, no duplicate rows, no placeholder rows, no runaway SSE/event loop.
14. Rollback immediately if any of these happen:
    - wrong image attached to a message
    - app crash during notification posting
    - notification listener stays disconnected
    - typed heartbeat stale while typing works
    - image POST loops forever on 409/503
15. If rollback is needed, install 8.17.4 with the same key, disable the offending feature flag if re-testing, and restore DB backup only if rows were corrupted.
16. Only after all on-device gates pass for 24 hours: build release APK with same signing key, install once more, re-verify, then tag the release.

---

## 7. Honest limitations

- If a chat app does not put the sender/body into notification extras, no app can recover it from the notification. The fallback is today's title/text behavior.
- Notification photos are only recoverable when Android/Messenger exposes a real bitmap or a readable `content://` URI, or when the same photo is saved locally and the media-correlation fallback sees it. If none of those exist, no NotificationListenerService can invent the image.
- Large icons are often contact avatars. The plan deliberately refuses to use them as photos unless a `photoIntent` signal is present and the icon does not look like an avatar; this can leave some legitimate photos unattached when the app exposes nothing else.
- Notification-shade quick replies depend on MIUI exposing the remote input as an editable accessibility node. The plan reads that node on every content change, which fixes the observed placeholder-only case, but a fully hidden custom input cannot be read.
- Permanent deletion and cloud-only Google Photos trash leave no local evidence.
- Private app caches are not readable without Share or root; use the Share sheet.
- Accessibility-service death can be detected with a heartbeat, but MIUI does not provide a safe programmatic re-enable; the user must re-grant.
- The Redmi Note 10 Pro is not connected here. Until section 6B passes, the plan is a design, not a verified working update.

---

## 8. Deferred server work after this APK

These are small follow-ups, but the first four are **required for the photo/typed-text failsafes to fully work** and should ship before or with the APK:

1. `notifications`: add `image_source TEXT`, `photo_intent BOOLEAN NOT NULL DEFAULT false`, and `related_notification_hash TEXT`; accept them from the POST body and expose via GET/SSE.
2. `cloudinary.js` / `notifications.js`: if `uploadImage(imageData)` returns `null`, return `503` and do not insert `image_url = NULL`. Log the failure class/message without logging image bytes.
3. `media_captures`: add `related_notification_hash TEXT`; accept it from media POST and expose via GET/SSE. Dashboard renders linked media inline on the notification row when `image_url` is null.
4. `app_status`: add `typed_text_alive_at TIMESTAMPTZ`, `typed_events_total BIGINT`, `typed_text_sent_total BIGINT`, `typed_placeholder_rejected_total BIGINT`, `notification_images_total BIGINT`, and `notification_images_failed_total BIGINT`; include them in `devices.js`/`appRoles.js` and dashboard chips.
5. `typed_text_alive_at` stale → dashboard shows `Text service not alive` within 2 minutes.
6. SSE reconnect gap marker in the feed.
7. Optional: accept `chatBody` as a first-class notification field and render it distinctly.
8. Optional: add a private diagnostic endpoint that returns only counters/last error class for the 6B acceptance run; never expose message text or image URLs without the existing API key.
