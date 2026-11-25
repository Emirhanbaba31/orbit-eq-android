# ORBIT EQ v2.0

Adaptive real-time equalizer and psychoacoustic DSP engine for Android (`minSdk 28` / Android 9.0 through `compileSdk 36` / Android 16, `targetSdk 35`, package `com.orbiteq.app`).

---

## What's New in v2.0

- **31-Band PreEQ & AutoEQ Import:** Attaches `DynamicsProcessing` with 31-band PreEQ on supported Android 9+ devices (`10-Band` and `Legacy Equalizer` fallbacks), plus full import for AutoEQ / Wavelet / Peace `GraphicEQ` `.txt` files, pasted curves, and a built-in 7-headphone Harman catalog (`Soundcore Life Q30`, `Space Q45`, `Sony WH-1000XM4`, `Sony WH-1000XM5`, `AirPods Pro 2`, `Galaxy Buds2 Pro`, `Sennheiser HD 600`).
- **Closed-Loop FFT De-Embedding & Noise Guard:** `0.70x` RMS-preserving de-embedding prevents adaptive self-cancellation across feedback loops while remaining contractive on pre-effect OEM pipelines, paired with spectral-flatness noise-floor guarding and smooth track-change decay.
- **Extended DSP Rack:** 3-tier Multi-Band Punch (`OFF` / `SUBTLE` / `PUNCHY`), **True Headroom (`0.0 dB` zero-clip ceiling)** toggle, **Subsonic Filter (`20 Hz`)** high-pass rumble protection, and **L/R Channel Balance** (attenuates the opposite channel without digital boost).
- **Battery-Smart Engine & System Surfaces:** UI-visibility-aware DSP loop cadence (`55ms` live visible -> `110ms` background -> `500ms` fallback -> `1000ms` idle), click-free dB slew/ramp, dirty-write skipping, album artwork thumbnails, **Quick Settings Tile**, and **4x1 Home Screen Widget**.
- **JSON Backup & Diagnostics:** Export and merge-import settings, custom presets, AutoEQ profiles, and app/track rules (`schemaVersion = 2`) via Android Storage Access Framework (SAF), plus 1-tap Copy & Share Diagnostics reports.

---

## Space HUD Features

ORBIT EQ pairs a 60 fps Jetpack Compose canvas interface with a two-tier Android audio pipeline:

- **Visual Themes:** Switch between **Deep Space HUD** (void black, starfield backdrop, and ion cyan telemetry) and **Lunar Monochrome** (OLED black/white with cobalt live curve).
- **Studio:**
  - **Now Playing Telemetry Pill:** Displays album artwork (when provided by the active media session), track title, artist/app, output route, mode resolution source (`Session Override`, `Pinned Track`, `App Rule`, `Device Default`, `Global Default`), `31-BAND` hi-res indicator when active, quick `Save for App` chip, `A/B` bypass toggle, and engine power toggle.
  - **Hero Spectrum Canvas:** Live 36-bin FFT spectrum alongside target vs. live EQ curves, preamp attenuation, loudness boost, and `-0.5 dB` limiter telemetry.
  - **5 Pleasure Modes:** `NOVA` (balanced wide stage), `GRAVITY` (deep sub-bass + tight punch), `HORIZON` (airy treble detail), `ECLIPSE` (warm night listening), and `COMMS` (vocal & podcast clarity).
  - **Orbital Dial & Macros:** `AUTO GAIN` adaptive intensity dial, **Sub Impact** and **Starlight Air** macro sliders, and **Dynamic Loudness** compensation.
- **10-Band Mixer & DSP Rack:**
  - **10-Band ISO Faders (`31 Hz` – `16 kHz`):** Hybrid live adaptive curve + manual per-band trim (drag to trim, double-tap to reset to `0.0 dB`).
  - **DSP Chain:** Android `DynamicsProcessing` (31/10-band PreEQ + 3-tier `MBC` + 10-band PostEQ + True-Peak Limiter at `-0.5 dB`, True Headroom `0.0 dB`, `20 Hz` Subsonic Filter, and L/R Channel Balance), with automatic `Legacy Equalizer + LoudnessEnhancer` fallback on unsupported devices.
  - **Response Speed & Presets:** Selectable `CHILL`, `BALANCED`, and `REACTIVE` adaptation speeds plus saved custom presets.
- **Activity, AutoEQ & Backup:**
  - **Live Audio Engine Telemetry:** Active audio session ID (`Auto (Smart)` or `Global Mix (#0)`), `31-Band PreEQ` / `10-Band` / `Legacy` backend status, sample rate, output route, and FFT/fallback state.
  - **Headphone Compensation & AutoEQ:** Built-in profiles plus imported custom 31-band AutoEQ curves bound per output device.
  - **Per-App & Device Rules:** Assign automatic `USE_MODE` or `BYPASS` rules per app, pin modes to specific tracks, and browse recent listening history with spectral tags.
  - **Backup & Diagnostics:** SAF JSON export/import and Copy/Share device diagnostics report.

---

## Soundcore Life Q30 Guide

ORBIT EQ automatically detects Soundcore Life Q30 Bluetooth connections and provides dedicated compensation profiles:

- **Why they work together cleanly:** The Soundcore companion app stores its EQ directly on the Q30 hardware over BLE, so it does not conflict with Android's on-device audio effects pipeline.
- **Q30 Clean Base:** Recommended when your Soundcore app EQ is set to **Flat** or **Acoustic**.
- **Q30 Safe Stack:** Recommended if you keep a Soundcore app bass preset active—uses a gentler compensation curve and adds **`-2.0 dB` extra headroom** to prevent clipping.
- **31-Band Harman AutoEQ:** One-tap `Soundcore Life Q30 (Harman 31-Band)` profile is also included in the built-in AutoEQ catalog.
- **Additional Profiles:** `Flat / Generic` (neutral reference) and `Phone Speaker Safe` (sub-bass protection for built-in phone speakers).

---

## Android 14–16 Permissions & Green-Dot Explainer

ORBIT EQ surfaces an interactive **Engine Setup & Permissions** checklist in the **Activity** tab:

| Permission / Setting | Purpose | Behavior on Android 14–16 |
| --- | --- | --- |
| **Audio Spectrum Capture (`RECORD_AUDIO`)** | Required by Android's `Visualizer` API to read playback output spectrum. **ORBIT EQ never records voice or microphone input.** | Triggers Android's system **green status-bar dot** while live FFT (`TIER1_LIVE_FFT`) is running. |
| **Two-Tier Foreground Service (`mediaPlayback\|microphone`)** | Keeps DSP active reliably across foreground and background starts. | Starts in `AUTO FFT · LOCKED` when launched from the foreground with `RECORD_AUDIO`; falls back to `PROFILE FALLBACK` (`TIER2_PROFILE_ONLY`, no Visualizer) if started in the background, without `RECORD_AUDIO`, or on DRM streams. |
| **Music Activity Access (`BIND_NOTIFICATION_LISTENER_SERVICE`)** | Reads active media app, track title, artist, and album artwork. | Powers automatic per-app rules (`USE_MODE` / `BYPASS`), pinned tracks, artwork thumbnails, and listening history. |
| **Engine Status Notification (`POST_NOTIFICATIONS`)** | Persistent foreground service notification. | Exposes quick `A/B Bypass`, `Next Mode`, and `Stop` controls from the shade. |
| **Unrestricted Battery (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)** | Battery optimization exemption. | Prevents Android from suspending the audio engine when the screen turns off. |

---

## Local Build & Signing

### Prerequisites

- **JDK 21**
- **Android SDK** with `compileSdk = 36` and `buildToolsVersion = "36.0.0"`

### Run Unit Tests, Compile Compose UI Tests & Build Debug APK

```powershell
# Windows PowerShell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug
```

```bash
# Linux / macOS
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug
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

3. Run tests and assemble the signed release APK:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleRelease --console=plain
   ```

   Output APK: `app/build/outputs/apk/release/app-release.apk`
