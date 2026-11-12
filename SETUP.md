# NotifyHub — Setup Guide

## Overview

```
Android Device(s)
  └── NotificationForwarderService  ──HTTPS POST──▶  Express API (Render.com)
                                                          │
                                                     Neon PostgreSQL
                                                          │
                                               SSE stream ──▶  Web Dashboard
                                                          │
                                               Firebase Admin ──▶  FCM ──▶ Primary Phone
```

---

## Phase 1 — Android SDK (one-time machine setup)

No Android Studio needed — just the command-line tools.

### 1.1 Install SDK CLI tools

```bash
# Download command-line tools (macOS ARM)
curl -O https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
unzip commandlinetools-mac-11076708_latest.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest

# Accept licenses and install required packages
~/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
~/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0"
```

### 1.2 Create `android/local.properties`

Create this file (it is gitignored — never commit it):

```
sdk.dir=/Users/tilleruser/android-sdk
```

### 1.3 Bootstrap the Gradle wrapper

This is a one-time step because `gradle-wrapper.jar` is not committed to the repo.

```bash
cd "My Notification Command Center/android"

# Download Gradle temporarily to generate the wrapper
curl -L https://services.gradle.org/distributions/gradle-8.6-bin.zip -o /tmp/gradle-8.6-bin.zip
unzip /tmp/gradle-8.6-bin.zip -d /tmp/
/tmp/gradle-8.6/bin/gradle wrapper --gradle-version 8.6
chmod +x gradlew

# Clean up
rm /tmp/gradle-8.6-bin.zip
```

### 1.4 Set build secrets

Add these to `~/.gradle/gradle.properties` (create the file if it doesn't exist). These stay on your machine and are never committed.

```properties
API_KEY=your-secret-api-key-here
SERVER_URL=https://notifyhub.onrender.com
```

> You'll come back and fill in `SERVER_URL` after completing Phase 3.

### 1.5 Build the APK

```bash
cd android
./gradlew assembleDebug
```

The APK is output to:

```
app/build/outputs/apk/debug/app-debug.apk
```

### 1.6 Install on device

```bash
# Connect device via USB with USB debugging enabled
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> To verify USB debugging is enabled: Settings → Developer options → USB debugging → On.
> To enable Developer options: Settings → About phone → tap Build number 7 times.

---

## Phase 2 — Neon Database

### 2.1 Create a Neon project

1. Go to [console.neon.tech](https://console.neon.tech) → New Project → name it **notifyhub**
2. Copy the connection string from **Connection Details** — it looks like:
   ```
   postgresql://user:password@ep-xxx-yyy.us-east-2.aws.neon.tech/notifyhub?sslmode=require
   ```

### 2.2 Run the schema

**Option A — with psql:**

```bash
psql "postgresql://..." -f server/src/db/schema.sql
```

**Option B — Neon SQL console:**
Open the Neon project dashboard → SQL Editor, then paste the contents of `server/src/db/schema.sql` and run it.

---

## Phase 3 — Server (Render.com)

Render.com is free forever — no 30-day trial, no credit card required. The free tier gives you 750 hours/month (enough to keep one service always on). Your Android devices' constant POSTs keep the service warm, so cold starts won't be an issue in practice.

### 3.1 Copy and fill environment variables

```bash
cd server
cp .env.example .env
```

Edit `server/.env`:

| Variable | Value |
|---|---|
| `API_KEY` | Generate with: `openssl rand -hex 32` |
| `DATABASE_URL` | Your Neon connection string from Phase 2 |
| `FCM_SERVICE_ACCOUNT_JSON` | Fill in Phase 4 |
| `FCM_DEVICE_TOKEN` | Fill in Phase 4 |
| `PORT` | `3000` |

### 3.2 Test locally first

```bash
cd server
npm install
npm run dev
```

Send a test notification:

```bash
curl -s -X POST http://localhost:3000/api/notifications \
  -H "X-API-Key: your-key" \
  -H "Content-Type: application/json" \
  -d '{
    "deviceId": "test-device",
    "packageName": "com.test",
    "appLabel": "Test App",
    "title": "Hello",
    "textBody": "World",
    "postedAt": "2026-11-12T10:00:00Z",
    "dedupHash": "abc123"
  }'
# Expected: {"id":1,"status":"created"}
```

Open the dashboard: [http://localhost:3000](http://localhost:3000) — enter your API key and confirm the test notification appears.

### 3.3 Deploy to Render.com

**Option A — via GitHub (recommended, auto-deploys on push):**

1. Push the project to a GitHub repo
2. Go to [dashboard.render.com](https://dashboard.render.com) → **New** → **Web Service**
3. Connect your GitHub repo → select it
4. Render auto-detects `server/render.yaml` — confirm settings:
   - **Root directory:** `server`
   - **Build command:** `npm install`
   - **Start command:** `node src/index.js`
   - **Plan:** Free
5. Click **Create Web Service**
6. Add environment variables under **Environment** tab (copy from `server/.env`)

**Option B — via Render CLI:**

```bash
npm install -g @render-cli/render
cd server
render login
render deploy
```

Render gives you a public HTTPS URL like `https://notifyhub.onrender.com`. Copy it.

### 3.4 Update Android build secrets

Edit `~/.gradle/gradle.properties` and set:

```
SERVER_URL=https://notifyhub.onrender.com
```

Then rebuild and reinstall the APK (repeat Phase 1.5 and 1.6).

---

## Phase 4 — Firebase FCM (push alerts to primary phone)

### 4.1 Create a Firebase project

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Click **Add project** → name it **NotifyHub** → Continue
3. Skip Google Analytics → **Create project**

### 4.2 Get the service account key

1. Project settings (gear icon) → **Service accounts** tab
2. Click **Generate new private key** → **Generate key**
3. A JSON file downloads — keep it safe, it grants server access to FCM

Compact it to a single line (required for environment variables):

```bash
jq -c . ~/Downloads/notifyhub-firebase-adminsdk-*.json
```

Paste the entire output as the value of `FCM_SERVICE_ACCOUNT_JSON` in Render environment variables.

### 4.3 Get your primary phone's FCM token

The easiest approach is to temporarily log it from the NotifyHub APK itself:

1. Add Firebase to the Android app — download `google-services.json` from Firebase Console → Project settings → Your apps → Add app (Android) → package name `com.notifyhub`
2. Place `google-services.json` in `android/app/`
3. Add to `android/app/build.gradle` dependencies:

   ```groovy
   implementation 'com.google.firebase:firebase-messaging:24.0.0'
   ```

   And to the top-level plugins block:
   ```groovy
   id 'com.google.gms.google-services' version '4.4.2' apply false
   ```
4. In `MainActivity.kt`, temporarily add inside `onCreate`:

   ```kotlin
   com.google.firebase.messaging.FirebaseMessaging.getInstance().token
       .addOnSuccessListener { token -> android.util.Log.d("FCM_TOKEN", token) }
   ```
5. Rebuild and install, then:

   ```bash
   adb logcat | grep FCM_TOKEN
   ```
6. Copy the token → paste as `FCM_DEVICE_TOKEN` in Render environment variables
7. Remove the temporary logging line and rebuild for production

### 4.4 Verify FCM

After setting both Render environment variables, trigger a notification on a monitored device and confirm it arrives as a push notification on your primary phone.

---

## Phase 5 — Device Setup (per device)

### 5.1 Initial permissions

Open the NotifyHub app on the device:

1. **Post notifications prompt** (Android 13+) — tap **Allow**
2. **Battery exemption prompt** — tap **Allow** / **Unrestricted**
3. Tap **Grant Notification Access** → find **Data Sync** in the list → enable it

### 5.2 OEM-specific battery settings

Standard Android battery optimization bypass is not enough on most custom ROMs. Find your device below and apply the additional steps:

**Xiaomi / MIUI / HyperOS:**

- Settings → Apps → Manage apps → Data Sync → Battery saver → **No restrictions**
- Settings → Apps → Manage apps → Data Sync → **Autostart → Enable**
- Security app → Boost speed → Remove Data Sync from the kill list

**Samsung One UI:**

- Settings → Battery → Background usage limits → Never sleeping apps → Add Data Sync

**OnePlus / ColorOS / OxygenOS:**

- Settings → Battery → App battery management → Data Sync → **Allow auto-launch** + **Allow background activity** + **Allow foreground activity**

**Stock Android / Pixel:**
The in-app battery exemption prompt is sufficient.

### 5.3 Finish setup

Tap **Finish Setup** in the app. This hides the launcher icon from the app drawer. The service continues running in the background.

> To re-show the icon (e.g., to access settings again):
>
> ```bash
> adb shell pm enable com.notifyhub.debug/.MainActivity
> ```

### 5.4 Verify data is flowing

1. Send yourself a WhatsApp or SMS message
2. Open the dashboard at your Render URL
3. Enter your API key — you should see the notification within a few seconds

For ADB verification:

```bash
adb logcat | grep -i notifyhub
```

---

## Phase 6 — Reboot Test

Reboot the device and confirm the service auto-restarts:

```bash
adb reboot
# Wait for boot to complete
adb logcat | grep -i "SyncForegroundService\|BootReceiver"
```

Send a test notification — it should appear in the dashboard without opening the app.

---

## Verification Checklist

```
[ ] Android SDK installed, sdk.dir set in android/local.properties
[ ] ./gradlew assembleDebug succeeds
[ ] APK installed on device via adb
[ ] App opened, POST_NOTIFICATIONS allowed (Android 13+)
[ ] Battery exemption granted
[ ] Notification Listener access granted for Data Sync
[ ] Finish Setup tapped (icon hidden)

[ ] Neon DB created, schema.sql applied
[ ] server/.env filled (API_KEY, DATABASE_URL)
[ ] curl test POST returns {"id":1,"status":"created"}
[ ] Dashboard opens at localhost:3000, test notification visible

[ ] Render.com service deployed, public HTTPS URL obtained
[ ] SERVER_URL updated in ~/.gradle/gradle.properties
[ ] APK rebuilt and reinstalled with correct SERVER_URL
[ ] Live notification from device appears in dashboard

[ ] Firebase project created, service account key obtained
[ ] FCM_SERVICE_ACCOUNT_JSON set in Render environment variables
[ ] FCM_DEVICE_TOKEN obtained and set in Render environment variables
[ ] Push notification received on primary phone

[ ] Device rebooted — service restarts automatically
[ ] OEM battery whitelist applied for this device's ROM
```

---

## Troubleshooting

| Symptom                             | Fix                                                                                                     |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `./gradlew: command not found`    | Run the wrapper bootstrap in Phase 1.3                                                                  |
| `SDK location not found`          | Create `android/local.properties` with `sdk.dir=...`                                                |
| `BuildConfig.SERVER_URL is empty` | Set `SERVER_URL` in `~/.gradle/gradle.properties` and rebuild                                       |
| Notifications not reaching server   | Check `adb logcat                                                                                       |
| Dashboard shows "Load failed"       | Verify API key matches; check Render logs (dashboard.render.com → your service → Logs)                  |
| FCM not delivering                  | Verify `FCM_DEVICE_TOKEN` is current — tokens rotate; re-fetch if stale                              |
| App killed after a few minutes      | Apply OEM-specific battery whitelist in Phase 5.2                                                       |
| Duplicate notifications appearing   | Dedup logic operates on a 90-second window; rapid successive taps on the same message will pass through |

---

## ADB Reference

```bash
# Install APK
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# Battery whitelist (alternative to in-app prompt)
adb shell dumpsys deviceidle whitelist +com.notifyhub.debug

# Simulate Doze mode to test resilience
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce

# View service logs
adb logcat | grep -E "notifyhub|NotifyHub|SyncForeground|BootReceiver|NotificationForwarder"

# Re-enable launcher icon
adb shell pm enable com.notifyhub.debug/.MainActivity

# Uninstall
adb uninstall com.notifyhub.debug
```
