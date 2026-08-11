# AerialGuard

A basic, lightweight Android app that overlays real-time detection boxes
(person / vehicle) on top of **whatever is currently on your phone's
screen** — so it works with your existing drone controller app (DJI Fly,
etc.) without needing that app to expose its video feed to anyone else.

## How it works

1. You tap **Start**, grant two permissions, then switch to your drone app.
2. 2. AerialGuard keeps running as a background foreground service. It uses
   3.    Android's `MediaProjection` API to capture the screen a few times a
   4.   second (not the camera — the phone's *screen*, whatever app is showing).
   5.   3. Each captured frame is run through a small pretrained on-device
        4.    TensorFlow Lite model that reports **person** and **vehicle**
        5.   (car/truck/bus/motorcycle/bicycle) — every other class it can detect is
        6.      ignored, to keep this basic and light.
        7.  4. Results are drawn as colored boxes in a transparent system overlay on
            5.    top of your drone app, in real time.
          
            6.Nothing leaves your phone — all inference runs on-device.

            ## Fastest way to get an installable .apk (no software to install)

            This project includes `.github/workflows/build-apk.yml`, which tells
            **GitHub's own servers** (which have a full Android SDK and internet
            access) to compile the app for you. You don't need Android Studio for
            this path — just a free GitHub account and a web browser:

            1. Go to [github.com](https://github.com) and sign in (or create a free
            2.    account).
            3.2. Click **+** (top right) → **New repository**. Give it any name (e.g.
                 `aerialguard`), leave it Public or Private — either works — and click
                 **Create repository**.
              3. On the new empty repo's page, click **uploading an existing file**.
              4. 4. Unzip the AerialGuard project on your computer, then drag the
                 5.    **contents** of that folder (not the outer `AerialGuard` folder itself)
                 6.   into the GitHub upload box — this includes the hidden `.github` folder,
                 7.      which most browsers will pick up if you drag the whole selection at
                 8.     once; if `.github` doesn't show up, use "choose your files" and
                 9.    navigate into it manually, or see the note below.
                 10.5. Scroll down and click **Commit changes**.
                   6. Click the **Actions** tab at the top of the repo. A build should start
                   7.    automatically within a few seconds (if not, click **Build APK** on the
                   8.   left, then **Run workflow**).
                   9.   7. Wait 3–5 minutes for it to finish (green checkmark).
                        8. 8. Click into the finished run, scroll to **Artifacts**, and download
                           9.    **AerialGuard-debug-apk** — it's a small zip containing `app-debug.apk`.
                           10.9. Transfer that `.apk` to your Android phone (email it to yourself,
                                Google Drive, USB — anything works), tap it, and allow "install from
                                this source" when prompted. That's it — no Play Store needed.

                             > Note on the hidden `.github` folder: GitHub's drag-and-drop uploader
                             > > sometimes skips folders starting with a dot. If after committing you
                             > > > don't see a `.github` folder in your repo, use **Add file → Create new
                             > > > > file**, type `.github/workflows/build-apk.yml` as the filename (GitHub
                             > > > > > auto-creates the folders), and paste in the contents of that file from
                             > > > > > > the zip. Then redo step 6 onward.
                             > > > > > >
                             > > > > > > ## Requirements
                             > > > > > >
                             > > > > > > - **Android Studio** (Hedgehog/2023.1 or newer recommended) — this is the
                             > > > > > > -   easiest way to build it. Command-line Gradle also works if you have a
                             > > > > > > -     JDK 17 and Android SDK set up already.
                             > > > > > > - - An Android phone running **Android 8.0 (API 26) or newer**.
                             > > > > > >   - - Internet access **the first time you build** — Gradle needs to download
                             > > > > > >     -   standard Android/Kotlin dependencies, plus one extra step: a small
                             > > > > > >     -     (~4MB) pretrained object-detection model is downloaded automatically
                             > > > > > >     -   from Google's official TensorFlow model repository the first time you
                             > > > > > >     -     build (see `app/download_model.gradle`). After that first build it's
                             > > > > > >     -   cached locally.
                             > > > > > >    
                             > > > > > >     -   ## Build & install
                             > > > > > >    
                             > > > > > >     -   1. Open Android Studio → **Open** → select the `AerialGuard` folder.
                             > > > > > > 2. Let Gradle sync finish (first sync downloads dependencies + the
                             > > > > > > 3.    detection model — can take a minute or two).
                             > > > > > > 4.3. Plug in your Android phone (with USB debugging enabled) or use a
                             > > > > > >      wireless ADB connection.
                             > > > > > >   4. Click **Run ▶** and select your device.
                             > > > > > >
                             > > > > > >   5. If you'd rather sideload an APK instead of running from Android Studio:
                             > > > > > >   6. **Build → Build Bundle(s) / APK(s) → Build APK(s)**, then copy the
                             > > > > > >   7. resulting `app-debug.apk` to your phone and install it (you'll need to
                             > > > > > >   8. allow "install unknown apps" for whichever app you use to transfer it).
                             > > > > > >
                             > > > > > >   9. ## Using it
                             > > > > > >
                             > > > > > >   10. 1. Open **AerialGuard** and tap **Start Detection**.
                             > > > > > >       2. 2. First permission: **"Display over other apps"** — Android will send
                             > > > > > >          3.    you to a settings screen; turn the toggle on for AerialGuard, then
                             > > > > > >          4.   press back and tap **Start Detection** again.
                             > > > > > >          5.   3. Second permission: Android's built-in **screen capture confirmation**
                             > > > > > >               4.    dialog — tap **Start now**.
                             > > > > > >               5.4. A small permanent notification ("AerialGuard is watching") appears —
                             > > > > > >                    this is required by Android for any app doing background screen
                             > > > > > >                    capture, and also gives you a **Stop** button.
                             > > > > > >                 5. Switch to your drone controller app (or play back any video — this
                             > > > > > >                 6.    works on any on-screen video, not just live drone feeds). Detection
                             > > > > > >                 7.   boxes appear on top of it in real time:
                             > > > > > >                 8.      - 🔴 red = person
                             > > > > > >                 9.     - 🟡 yellow = vehicle
                             > > > > > >                 10. 6. Tap **Stop Detection** in the app, or **Stop** in the notification, to
                             > > > > > >                     7.    end it.
                             > > > > > >                    
                             > > > > > >                     8.## Adding more categories later
                             > > > > > >
                             > > > > > > The underlying model (COCO SSD-MobileNet) can recognize 90 general object
                             > > > > > > classes, not just person/vehicle — things like "airplane", "backpack",
                             > > > > > > "dog", etc. This app only surfaces person and vehicle on purpose, to keep
                             > > > > > > it basic. To bring back a class, edit `ObjectDetector.kt`: add it to (or
                             > > > > > > create a new) label set, and add a matching branch in the `category = when { ... }` block. `OverlayBoxView.kt` and `ThreatCategory` (in
                             > > > > > > `Detection.kt`) will need a matching case added too if you introduce a new
                             > > > > > > category rather than reusing HUMAN/VEHICLE.
                             > > > > > >
                             > > > > > > There is no free pretrained model that recognizes "quadcopter" specifically
                             > > > > > > the way there is for person/car/airplane — consumer drones aren't in the
                             > > > > > > standard COCO dataset. A previous version of this app included a
                             > > > > > > motion-based heuristic as a rough stand-in, but it produced too many false
                             > > > > > > positives (birds, grain/noise, camera shake) to be useful, so it's been
                             > > > > > > removed. If you want real drone detection later, a good free starting
                             > > > > > > point is searching "drone detection" on Roboflow Universe for a
                             > > > > > > community-trained model you can export as TensorFlow Lite, then wire it in
                             > > > > > > the same way `ObjectDetector.kt` is wired in `ScreenCaptureService.kt`.
                             > > > > > >
                             > > > > > > ## Performance tuning
                             > > > > > >
                             > > > > > > Everything is tuned for "basic and light" out of the box. Two knobs in
                             > > > > > > `ScreenCaptureService.kt` if you want to trade battery for smoothness:
                             > > > > > >
                             > > > > > > - `PROCESS_INTERVAL_MS` (default 150ms ≈ 6-7 fps) — lower it for a
                             > > > > > > -   smoother-looking overlay, raise it to save battery/heat.
                             > > > > > > -   - `CAPTURE_SCALE` (default 0.5) — the screen is captured at half
                             > > > > > >     -   resolution before detection; raise it toward 1.0 for small/far objects
                             > > > > > >     -     to be detected more reliably, at a CPU cost.
                             > > > > > >  
                             > > > > > >     - ## Known limitations
                             > > > > > >  
                             > > > > > >     - - This detects objects **visible on your phone's screen**, not from a raw
                             > > > > > >       -   drone video/telemetry stream — it works with any app or video, but
                             > > > > > >       -     it's only as good as what's rendered on screen (compression, drone app
                             > > > > > >       -   overlays/HUD text, etc. can affect accuracy).
                             > > > > > >       -   - Only person and vehicle are detected — no aircraft/drone class (see
                             > > > > > >           -   "Adding more categories later" above).
                             > > > > > >           -   - No persistence/alerting/logging is built in — it's intentionally a
                             > > > > > >               -   basic real-time overlay, not a full surveillance system. Extend
                             > > > > > >               -     `ScreenCaptureService.processImage()` if you want to add alerts, a
                             > > > > > >               -   detection log, etc.
                             > > > > > >               -   
