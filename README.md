# DashCam (Android)

Records the front and back cameras as a dash cam. Footage is split into clips
(10 minutes by default), and once saved clips reach your storage limit the oldest
ones are deleted automatically (loop recording).

## Build and run

1. Install Android Studio (current stable).
2. File > Open, and choose this `DashCam` folder. Let Gradle sync (it downloads
   Gradle 8.9 and the dependencies). If Android Studio offers to upgrade the
   Android Gradle Plugin or Kotlin, you can accept.
3. Plug in a phone (Android 11 or newer) with USB debugging on, and press Run.
4. Open the app, set a storage limit, press **Start recording**, and allow the
   camera, microphone and notification prompts.

Clips are saved to `Movies/DashCam` and appear in your Gallery or Files app.
Names look like `DashCam_20261111_164600_back.mp4` and
`DashCam_20261111_164600_front.mp4`.

## How it works

- `RecordingService.kt`: a foreground service (so recording continues with the
  screen off or the app in the background). It uses CameraX concurrent cameras to
  record front and back at once, and every N minutes it closes the current clips
  and starts new ones.
- `Storage.kt`: lists the app's clips through MediaStore and deletes the oldest
  until saved footage plus space for the clip in progress fits your limit. It
  re-checks every 30 seconds while recording, and also keeps about 1 GB free on
  the phone itself. If the phone is nearly full even after cleaning up, recording
  stops and says why.
- `MainActivity.kt`: start/stop button and settings (storage limit, cameras,
  quality, clip length, sound).
- `Prefs.kt`: saved settings.

## Background recording

- Press **Start recording** and the app drops back to your home screen (you can
  turn this off in settings). Then open music, an audiobook app, maps, or anything
  else. Recording carries on, including with the screen off or after you swipe the
  app away from recent apps.
- A "Dash cam is recording" notification stays up the whole time. It has a **Stop**
  button, or you can reopen the app and press Stop.
- Press Start while the app is open on screen. Android only lets a camera start
  from a visible app, so the app can't begin recording by itself from the background.
- Tap **Allow background running** in the app once. This exempts DashCam from
  battery optimisation so Android is less likely to stop it on long drives. Some
  brands (Samsung, Xiaomi, OnePlus, Huawei) have extra "app launch" or "sleeping
  apps" settings that should also be set to allow DashCam.
- If another app or a phone call takes a camera, DashCam waits a few seconds and
  retries, so footage resumes by itself.
- If a music or audiobook app behaves oddly while DashCam records sound, turn off
  **Record sound**.

## Choosing cameras and lenses

Under **Cameras** the app lists every camera your phone offers, for example
"Back - Main, ~26mm", "Back - Ultra-wide, ~14mm", "Front" and, if available,
"USB / external camera". Tap one or two. The first one you tap also records the
sound. Leaving everything unselected uses the default (back + front).

- Lens names are worked out from each camera's focal length, so treat them as a guide.
- Many phones hide their extra lenses inside the main back camera and only show
  one "Back" entry to apps. In that case the app can't pick the ultra-wide or
  telephoto lens separately. This is a phone limit, not something the app can get around.
- Two cameras at once only works for pairs the phone allows. If it refuses, the app
  records the first camera only and shows a message.
- Clip names include the camera, e.g. `DashCam_20261112_081500_back-0.mp4`.

## USB cameras

A USB webcam (UVC) shows up in the list only if your phone exposes it through
Android's standard camera system. Support is patchy and depends on the phone and
Android version.

- You need a USB-C (OTG / host) cable or adapter. Plug the camera in before you
  open DashCam. If you plug it in later, close and reopen the app.
- If a USB camera is detected but your phone doesn't offer it to apps, the app says so
  and can't use it. Supporting those phones needs a different approach (a bundled
  UVC driver library and our own video encoding), which is a larger change.
- Pairing a USB camera with a phone camera at the same time may be refused by the
  phone. The app then records just the first camera and tells you.
- A USB camera's own microphone isn't used. Sound comes from the phone's microphone.
- USB cameras draw power from the phone, so keep it on charge.

## Things to know

- **Not every phone can run both cameras at once.** It needs Android 11+ and
  hardware support. If yours can't, the app says so and records the back camera only.
- With both cameras running, most phones cap each at about 720p. 1080p applies
  when recording a single camera.
- There is a brief gap (a fraction of a second to a second or so) between clips
  while one finishes saving and the next starts.
- Sound is recorded on the back camera's clip only, since two recordings can't
  share the microphone reliably.
- Android can still stop background apps aggressively. For long drives, turn off
  battery optimisation for DashCam and keep the phone on charge.
- The storage limit counts the space reserved for the clips being recorded, so
  saved footage on disk sits a little under the limit while recording.
- Clips from a previous install can't be deleted by a new install; remove those
  by hand from `Movies/DashCam`.
- This project was written without being compiled, so expect to fix a small
  build error or two on first sync. Send me the error text and I'll sort it out.

## Ideas for next steps

- Live preview of the cameras
- Start automatically when the phone connects to power or Bluetooth
- Lock/protect a clip from deletion (a "save this" button after an incident)
- Speed and GPS overlay on the video
