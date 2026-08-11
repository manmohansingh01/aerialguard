# AerialGuard

A basic, lightweight Android app that overlays real-time detection boxes
(person / vehicle / aircraft / possible quadcopter) on top of **whatever is
currently on your phone's screen** — so it works with your existing drone
controller app (DJI Fly, etc.) without needing that app to expose its video
feed to anyone else.

## How it works

1. You tap **Start**, grant two permissions, then switch to your drone app.
2. AerialGuard keeps running as a background foreground service. It uses
   Android's `MediaProjection` API to capture the screen a few times a
   second (not the camera — the phone's *screen*, whatever app is showing).
3. Each captured frame is run through:
   - a small pretrained on-device TensorFlow Lite model for **person**,
     **vehicle** (car/truck/bus/motorcycle/bicycle), and **aircraft**
     (airplane), and
   - a lightweight motion-based heuristic that flags small, fast-moving
     blobs in the upper part of the frame as **possible quadcopter** (see
     the "About quadcopter detection" section below — this is the honest
     limitation of the app).
4. Results are drawn as colored boxes in a transparent system overlay on
   top of your drone app, in real time.

Nothing leaves your phone — all inference runs on-device.

## Fastest way to get an installable .apk (no software to install)

This project includes `.github/workflows/build-apk.yml`, which tells
**GitHub's own servers** (which have a full Android SDK and internet
access) to compile the app for you. You don't need Android Studio for
this path — just a free GitHub account and a web browser:

1. Go to [github.com](https://github.com) and sign in (or create a free
   account).
2. Click **+** (top right) → **New repository**. Give it any name (e.g.
   `aerialguard`), leave it Public or Private — either works — and click
   **Create repository**.
3. On the new empty repo's page, click **uploading an existing file**.
4. Unzip the AerialGuard project on your computer, then drag the
   **contents** of that folder (not the outer `AerialGuard` folder itself)
   into the GitHub upload box — this includes the hidden `.github` folder,
   which most browsers will pick up if you drag the whole selection at
   once; if `.github` doesn't show up, use "choose your files" and
   navigate into it manually, or see the note below.
5. Scroll down and click **Commit changes**.
6. Click the **Actions** tab at the top of the repo. A build should start
   automatically within a few seconds (if not, click **Build APK** on the
   left, then **Run workflow**).
7. Wait 3–5 minutes for it to finish (green checkmark).
8. Click into the finished run, scroll to **Artifacts**, and download
   **AerialGuard-debug-apk** — it's a small zip containing `app-debug.apk`.
9. Transfer that `.apk` to your Android phone (email it to yourself,
   Google Drive, USB — anything works), tap it, and allow "install from
   this source" when prompted. That's it — no Play Store needed.

> Note on the hidden `.github` folder: GitHub's drag-and-drop uploader
> sometimes skips folders starting with a dot. If after committing you
> don't see a `.github` folder in your repo, use **Add file → Create new
> file**, type `.github/workflows/build-apk.yml` as the filename (GitHub
> auto-creates the folders), and paste in the contents of that file from
> the zip. Then redo step 6 onward.

## Requirements

- **Android Studio** (Hedgehog/2023.1 or newer recommended) — this is the
  easiest way to build it. Command-line Gradle also works if you have a
  JDK 17 and Android SDK set up already.
- An Android phone running **Android 8.0 (API 26) or newer**.
- Internet access **the first time you build** — Gradle needs to download
  standard Android/Kotlin dependencies, plus one extra step: a small
  (~4MB) pretrained object-detection model is downloaded automatically
  from Google's official TensorFlow model repository the first time you
  build (see `app/download_model.gradle`). After that first build it's
  cached locally.

## Build & install

1. Open Android Studio → **Open** → select the `AerialGuard` folder.
2. Let Gradle sync finish (first sync downloads dependencies + the
   detection model — can take a minute or two).
3. Plug in your Android phone (with USB debugging enabled) or use a
   wireless ADB connection.
4. Click **Run ▶** and select your device.

If you'd rather sideload an APK instead of running from Android Studio:
**Build → Build Bundle(s) / APK(s) → Build APK(s)**, then copy the
resulting `app-debug.apk` to your phone and install it (you'll need to
allow "install unknown apps" for whichever app you use to transfer it).

## Using it

1. Open **AerialGuard** and tap **Start Detection**.
2. First permission: **"Display over other apps"** — Android will send
   you to a settings screen; turn the toggle on for AerialGuard, then
   press back and tap **Start Detection** again.
3. Second permission: Android's built-in **screen capture confirmation**
   dialog — tap **Start now**.
4. A small permanent notification ("AerialGuard is watching") appears —
   this is required by Android for any app doing background screen
   capture, and also gives you a **Stop** button.
5. Switch to your drone controller app (or play back any video — this
   works on any on-screen video, not just live drone feeds). Detection
   boxes appear on top of it in real time:
   - 🔴 red = person
   - 🟡 yellow = vehicle
   - 🔵 cyan = aircraft (fixed-wing / plane-shaped)
   - 🟣 magenta = possible quadcopter (heuristic, see below)
6. Tap **Stop Detection** in the app, or **Stop** in the notification, to
   end it.

## About quadcopter detection (please read)

There is no free, off-the-shelf, lightweight pretrained AI model that
specifically recognizes "quadcopter" the way there is for person, car, or
airplane — those come from the standard COCO dataset that most small
mobile detection models are trained on; consumer drones aren't in it.

So instead of pretending otherwise, this app ships with a **motion-based
heuristic** (`QuadcopterHeuristicDetector.kt`): it compares consecutive
frames on a small downsampled grid and flags small, roughly blob-shaped
regions of motion in the upper part of the screen. It's fast and needs no
training data, but it is **not a trained classifier** — it will also
trigger on birds, insects near the lens, or any small fast-moving object
against the sky. Treat a magenta box as "worth a look," not a confirmed
identification.

**To upgrade to a real trained quadcopter/drone model later:**

1. Find or train a small object-detection model for drones — a good free
   starting point is searching "drone detection" on Roboflow Universe,
   where several community-trained models can be exported directly as
   TensorFlow Lite with no training required on your end.
2. Drop the exported `drone.tflite` + its label file into
   `app/src/main/assets/`.
3. Write a class with the same shape as `ObjectDetector.kt` (a `detect(bitmap): List<Detection>` method) pointed at that model, and swap
   `QuadcopterHeuristicDetector` for it in `ScreenCaptureService.kt`
   (`frameAnalyzer = FrameAnalyzer(objectDetector, yourNewDroneDetector)`).

## Performance tuning

Everything is tuned for "basic and light" out of the box. Two knobs in
`ScreenCaptureService.kt` if you want to trade battery for smoothness:

- `PROCESS_INTERVAL_MS` (default 150ms ≈ 6-7 fps) — lower it for a
  smoother-looking overlay, raise it to save battery/heat.
- `CAPTURE_SCALE` (default 0.5) — the screen is captured at half
  resolution before detection; raise it toward 1.0 for small/far objects
  to be detected more reliably, at a CPU cost.

## Known limitations

- This detects objects **visible on your phone's screen**, not from a raw
  drone video/telemetry stream — it works with any app or video, but
  it's only as good as what's rendered on screen (compression, drone app
  overlays/HUD text, etc. can affect accuracy).
- "Aircraft" detection is the general COCO "airplane" class — it's tuned
  for plane-shaped aircraft, not tiny/distant objects.
- Quadcopter flagging is a heuristic, not a trained classifier (see above).
- No persistence/alerting/logging is built in — it's intentionally a
  basic real-time overlay, not a full surveillance system. Extend
  `ScreenCaptureService.processImage()` if you want to add alerts, a
  detection log, etc.
