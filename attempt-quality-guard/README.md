# Attempt Quality Guard

A hackathon MVP that proves whether a delivery call attempt is stronger than a
Field Executive (FE) just tapping a "Call Customer" CTA that opens the phone
dialer. Today that click is the only proxy for "a call was attempted" — there
is no visibility into whether the FE actually dialed and stayed on the line.

This app closes that gap for calls it initiates itself: it places the call
directly, watches the device's call-state transitions for that one attempt,
and writes everything to a shared Google Sheet so it can be reviewed
centrally — automatically, with no extra taps.

## 1. Product overview

1. The FE opens the app, types the customer's number, and taps **Call
   Customer**.
2. The app fires `Intent.ACTION_CALL` directly (no dialer hand-off) and
   starts watching call state for that one attempt.
3. When the call ends (or a fallback path is taken), the app automatically
   evaluates four signals and shows a single **VERIFIED / NOT_VERIFIED**
   decision — no separate validation step needed.
4. Every raw event and every validated summary is appended to a shared
   Google Sheet via a Google Apps Script Web App, so every device installing
   this APK writes to the same place. Anything that fails to send is queued
   locally and retried automatically the next time the app is opened.

## 2. What signal this strengthens

Today, "quality attempt" = "FE clicked the call CTA in the delivery app."
That signal is trivially gameable: a click doesn't mean a call happened.
This app adds a second, harder-to-fake signal: the *device itself* entering
a live call state for a meaningful duration, immediately after an
app-initiated call intent, and it attributes each attempt to the FE's own
device number (`fe_phone_number`).

## 3. What it proves

- The call was initiated **from this app** (`ACTION_CALL`, not just opening
  the dialer).
- The device's telephony stack entered a call state (`OFFHOOK`) and stayed
  there for at least 15 seconds before returning to `IDLE`.
- This happened within the last 10 minutes of validation.
- Which FE device placed it (`fe_phone_number`, when the carrier/SIM exposes
  it — see the permissions table below).

## 4. What it does NOT prove

- **It does not prove the customer answered.** `OFFHOOK` means the device
  entered a mobile call flow (dialing/ringing/connected) — that is a much
  stronger signal than opening a dialer, but it is not proof of an answered,
  two-way conversation.
- It does not read or expose the FE's general call history — only the one
  attempt this app placed.
- It does not record call audio or content.
- It is not tamper-proof against a determined attacker with a rooted device;
  it is a meaningfully stronger proxy than a CTA click, not a cryptographic
  guarantee.

**Dual-SIM devices**: the app never picks which SIM places the call - that's
entirely Android's decision (its own SIM-picker dialog, or the FE's configured
default). On Android 11+ (API 30+), `CallStateTracker` registers on every
active SIM subscription so whichever one the system actually uses still gets
observed. On older API levels there's no per-subscription telephony API to do
that with, so it only watches the default SIM - if a call goes out on the
non-default SIM on one of those older devices, it may not register `OFFHOOK`
at all. For those devices, set a single default SIM for calls in the phone's
own Settings (Network → SIM cards → Calls) to remove the ambiguity.

## 5. Permissions and what each one unlocks

| Permission | Why | If denied |
|---|---|---|
| `CALL_PHONE` | Places the call directly via `ACTION_CALL`. | Falls back to `ACTION_DIAL` (opens the dialer pre-filled). The attempt is marked `fallback_mode` and can **never** be `VERIFIED`. |
| `READ_PHONE_STATE` | Lets the app observe call-state transitions (`OFFHOOK`/`IDLE`) **only** for the attempt it just placed. The listener is registered right before the call intent fires and unregistered the moment the call ends (or after a 5-minute timeout). The app never reads call history and never requests `READ_CALL_LOG`. | "Phone entered call state" and "duration" cannot be measured. |
| `READ_PHONE_NUMBERS` | Reads this device's own SIM number once per attempt, to attribute it to the FE (`fe_phone_number`). | `fe_phone_number` is left blank for that attempt. |
| `INTERNET` | Sends attempt events to the Google Apps Script Web App. | Events queue locally and are retried automatically the next time the app is opened. |

The app never requests `READ_CALL_LOG`, `READ_CONTACTS`, `RECORD_AUDIO`, or
any SMS permission, and never uploads a usable phone number — only a masked
value (e.g. `XXXXXX3210`) and a one-way SHA-256 hash.

## 6. Google Sheet setup

1. Go to [sheets.google.com](https://sheets.google.com) and create a new
   blank spreadsheet.
2. Rename it **"Attempt Quality Guard Logs"**.
3. Leave it otherwise empty — the Apps Script below creates the
   `Raw_Events` and `Attempt_Summary` tabs (with headers) automatically on
   the first write.

## 7. Apps Script deployment

1. In that Sheet, open **Extensions > Apps Script**.
2. Delete the default `myFunction() {}` boilerplate and paste in the
   contents of [`apps-script/Code.gs`](apps-script/Code.gs).
3. Near the top of the file, change:
   ```js
   var SCRIPT_SHARED_SECRET = 'CHANGE_ME_FOR_MVP';
   ```
   to a secret only your team knows. You'll paste the same value into the
   Android app in the next section.
4. Click **Deploy > New deployment**.
5. Click the gear icon next to "Select type" and choose **Web app**.
6. Set:
   - **Execute as:** Me
   - **Who has access:** Anyone with the link
7. Click **Deploy**, authorize the script when prompted, and copy the
   **Web app URL** (looks like
   `https://script.google.com/macros/s/XXXXXXXX/exec`).
8. Keep this tab open — you'll need this same URL for every device that
   installs the APK.

If you later edit `Code.gs`, use **Deploy > Manage deployments > Edit >
New version** so the same Web App URL picks up the change.

## 8. Put the Web App URL into the Android app

Open
[`android/app/src/main/java/com/example/attemptqualityguard/AppConfig.kt`](android/app/src/main/java/com/example/attemptqualityguard/AppConfig.kt)
and set:

```kotlin
const val APPS_SCRIPT_WEB_APP_URL: String = "https://script.google.com/macros/s/XXXXXXXX/exec"
const val SHARED_SECRET: String = "the-same-secret-you-set-in-Code.gs"
```

**Use this exact same URL in every APK install** so all devices append to
the same Google Sheet. If you leave the placeholder URL in place, the app
still runs and queues events locally, but nothing reaches the Sheet until
you configure it and reinstall.

## 9. Android build steps

Prerequisites: [Android Studio](https://developer.android.com/studio)
(bundles a matching JDK and lets you install SDK platforms/build-tools
through the SDK Manager), or a standalone Android SDK + JDK 17+ if you
prefer the command line.

### Option A — Android Studio

1. Open the `android/` folder as a project in Android Studio.
2. Let it sync Gradle and download the Android SDK platform (34) and build
   tools if prompted.
3. **Build > Build Bundle(s) / APK(s) > Build APK(s)**, or run the `app`
   configuration on a connected device/emulator.

### Option B — command line

```bash
cd attempt-quality-guard/android
export ANDROID_HOME=/path/to/your/Android/sdk   # must contain platforms/android-34, build-tools
./gradlew assembleDebug
```

The debug APK is written to:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

## 10. Install the APK on a device

With a device connected over USB (Developer Options > USB debugging
enabled):

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the device and open it (allow "install from unknown
sources" if prompted).

## 11. Test the flow

See the demo script below, or just:

1. Open the app and tap **Request Permissions**; grant all three.
2. Enter a real phone number you can call (e.g. your own second phone).
3. Tap **Call Customer** — the phone app should open and start dialing
   immediately (not just pre-fill a number).
4. Stay on the call for 15+ seconds, then hang up and return to the app.
5. The app validates automatically as soon as the call ends — check the
   "Final decision" signal card for **VERIFIED**.
6. Open the Google Sheet and confirm new rows in `Raw_Events` and
   `Attempt_Summary`.

## Demo script

1. Open app.
2. Grant permissions.
3. Enter phone number.
4. Tap Call Customer.
5. End the call after more than 15 seconds.
6. Return to the app — the VERIFIED result appears automatically.
7. Open the Google Sheet and show the new rows in `Raw_Events` and
   `Attempt_Summary`.

## Troubleshooting

**"Setup needed" banner won't go away.** `AppConfig.APPS_SCRIPT_WEB_APP_URL`
is still the placeholder. Paste your deployed Web App URL (step 8) and
rebuild.

**Rows never show up in the Sheet, but the app says "sent."** The Apps
Script returned a non-`success` response — usually a secret mismatch.
Confirm `SCRIPT_SHARED_SECRET` in `Code.gs` matches `SHARED_SECRET` in
`AppConfig.kt` exactly, and that you deployed a **new version** after
editing `Code.gs`.

**Rows show up in the wrong sheet / spreadsheet.** The script must be
bound to the target Sheet (opened via that Sheet's **Extensions > Apps
Script**), not a standalone script project.

**"Could not start the call" / it only opens the dialer.** `CALL_PHONE`
was denied. The app intentionally falls back to `ACTION_DIAL` in that case
and marks the attempt as `fallback_mode` — it can never be `VERIFIED`.
Grant the permission and try again.

**"Phone entered call state" never turns on.** `READ_PHONE_STATE` is
denied, or you're on an emulator that doesn't simulate real call-state
transitions — test on a physical device with a real SIM/eSIM or an active
calling app.

**Attempt is `NOT_VERIFIED` even though the call clearly happened.** Check
the missing-signals list on the "Final decision" card — most commonly it's
"call_state_lasted_15_sec" (hung up too soon) or "phone_entered_call_state"
(the device never registered an `OFFHOOK` transition — common on emulators).

**Gradle build fails with "SDK location not found."** Set `ANDROID_HOME`
(or create `android/local.properties` with `sdk.dir=/path/to/Android/sdk`)
to point at an Android SDK that has platform 34 and build-tools installed.

**Rows aren't showing up in the Sheet.** Events that fail to send are
queued locally and retried automatically the next time the app is opened
or resumed — no button to tap. If it's still not catching up, check
`adb logcat` filtered to tag `AttemptQualityGuard` for the exact failure
reason (HTTP status, network exception, or the server's own error message).

**`fe_phone_number` is blank.** This is expected on many devices/carriers —
`READ_PHONE_NUMBERS` grants access to the *attempt* of reading the SIM's own
number, but plenty of SIMs simply don't have it provisioned. There's no
reliable universal fallback for this on Android.
