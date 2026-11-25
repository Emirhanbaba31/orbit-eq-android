# ORBIT EQ

Adaptive real-time 10-band equalizer and psychoacoustic DSP engine for Android (`minSdk 28` / Android 9.0 through `compileSdk 36` / Android 16, `targetSdk 35`, package `com.orbiteq.app`).

---

## Space HUD Features

ORBIT EQ pairs a custom 60 fps Jetpack Compose canvas interface with a two-tier Android audio pipeline:

- **Visual Themes:** Switch between **Deep Space HUD** (void black, starfield backdrop, and ion cyan telemetry) and **Lunar Monochrome** (OLED black/white with cobalt live curve).
- **Studio:**
  - **Now Playing Telemetry Pill:** Shows active media app, track, output route, mode resolution source (`Session Override`, `Pinned Track`, `App Rule`, `Device Default`, `Global Default`), quick `Save for App` chip, `A/B` bypass toggle, and engine power toggle.
  - **Hero Spectrum Canvas:** Live 36-bin FFT spectrum alongside target vs. live EQ curves, preamp attenuation, loudness boost, and `-0.5 dB` limiter telemetry.
  - **5 Pleasure Modes:** `NOVA` (balanced wide stage), `GRAVITY` (deep sub-bass + tight punch), `HORIZON` (airy treble detail), `ECLIPSE` (warm night listening), and `COMMS` (vocal & podcast clarity).
  - **Orbital Dial & Macros:** `AUTO GAIN` adaptive intensity dial, **Sub Impact** and **Starlight Air** macro sliders, and **Dynamic Loudness** compensation.
- **10-Band Mixer & DSP Rack:**
  - **10-Band ISO Faders (`31 Hz` – `16 kHz`):** Hybrid live adaptive curve + manual per-band trim (drag to trim, double-tap to reset to `0.0 dB`).
  - **DSP Chain:** Android `DynamicsProcessing` (PreEQ + Multi-Band Punch `MBC` + PostEQ + True-Peak Limiter at `-0.5 dB`), with automatic `Legacy Equalizer + LoudnessEnhancer` fallback on unsupported devices.
  - **Response Speed & Presets:** Selectable `CHILL`, `BALANCED`, and `REACTIVE` adaptation speeds plus saved custom presets.
- **Activity & Rules:**
  - **Live Audio Engine Telemetry:** Active audio session ID (`Auto (Smart)` or `Global Mix (#0)`), DSP backend, sample rate, output route, and FFT/fallback status.
  - **Per-App & Device Rules:** Assign automatic `USE_MODE` or `BYPASS` rules per app, pin modes to specific tracks, and browse recent listening history with spectral tags.

---

## Soundcore Life Q30 Guide

ORBIT EQ automatically detects Soundcore Life Q30 Bluetooth connections and provides dedicated compensation profiles:

- **Why they work together cleanly:** The Soundcore companion app stores its EQ directly on the Q30 hardware over BLE, so it does not conflict with Android's on-device audio effects pipeline.
- **Q30 Clean Base:** Recommended when your Soundcore app EQ is set to **Flat** or **Acoustic**.
- **Q30 Safe Stack:** Recommended if you keep a Soundcore app bass preset active—uses a gentler compensation curve and adds **`-2.0 dB` extra headroom** to prevent clipping.
- **Additional Profiles:** `Flat / Generic` (neutral reference) and `Phone Speaker Safe` (sub-bass protection for built-in phone speakers).

---

## Android 14–16 Permissions & Green-Dot Explainer

ORBIT EQ surfaces an interactive **Engine Setup & Permissions** checklist in the **Activity** tab:

| Permission / Setting | Purpose | Behavior on Android 14–16 |
| --- | --- | --- |
| **Audio Spectrum Capture (`RECORD_AUDIO`)** | Required by Android's `Visualizer` API to read playback output spectrum. **ORBIT EQ never records voice or microphone input.** | Triggers Android's system **green status-bar dot** while live FFT (`TIER1_LIVE_FFT`) is running. |
| **Two-Tier Foreground Service (`mediaPlayback\|microphone`)** | Keeps DSP active reliably across foreground and background starts. | Starts in `AUTO FFT · LOCKED` when launched from the foreground with `RECORD_AUDIO`; falls back to `PROFILE FALLBACK` (`TIER2_PROFILE_ONLY`, no Visualizer) if started in the background, without `RECORD_AUDIO`, or on DRM streams. |
| **Music Activity Access (`BIND_NOTIFICATION_LISTENER_SERVICE`)** | Reads active media app, track title, and artist. | Powers automatic per-app rules (`USE_MODE` / `BYPASS`), pinned tracks, and listening history. |
| **Engine Status Notification (`POST_NOTIFICATIONS`)** | Persistent foreground service notification. | Exposes quick `A/B Bypass` and `Stop` controls from the shade. |
| **Unrestricted Battery (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)** | Battery optimization exemption. | Prevents Android from suspending the audio engine when the screen turns off. |

---

## Local Build & Signing

### Prerequisites

- **JDK 21**
- **Android SDK** with `compileSdk = 36` and `buildToolsVersion = "36.0.0"`

### Run Unit Tests & Build Debug APK

```powershell
# Windows PowerShell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

```bash
# Linux / macOS
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

### Build a Signed Release APK

Release signing reads `keystore.properties` at the repository root (or `ORBIT_KEYSTORE_*` environment variables). Both `*.jks` and `keystore.properties` are gitignored and must never be committed.

1. Generate a local release keystore (example):

   ```bash
   keytool -genkeypair -v -keystore orbit-release.jks -storetype PKCS12 \
     -alias orbit_release -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Create `keystore.properties` at the repository root:

   ```properties
   storeFile=orbit-release.jks
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=orbit_release
   keyPassword=YOUR_KEY_PASSWORD
   ```

   Alternatively, export `ORBIT_KEYSTORE_PATH`, `ORBIT_KEYSTORE_PASSWORD`, `ORBIT_KEYSTORE_ALIAS`, and `ORBIT_KEYSTORE_KEY_PASSWORD` in your environment (if neither is provided, Gradle falls back to debug signing so CI builds stay green).

3. Run tests and assemble the release APK:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --rerun-tasks --console=plain
   ```

   Output APK: `app/build/outputs/apk/release/app-release.apk`
