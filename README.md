# ORBIT EQ v3.0

Adaptive real-time equalizer and psychoacoustic DSP engine for Android (`minSdk 28` / Android 9.0 through `compileSdk 36` / Android 16, `targetSdk 35`, package `com.orbiteq.app`).

---

## What's New in v3.0

- **Refined Q30 & `NOVA` Musical Voicing:** Updated 31-band `Q30 Clean Base` curve (deeper `20–40 Hz` sub-bass extension, cleaner `80–250 Hz` mid-bass bloom control, restored `2.5–3.15 kHz` vocal presence, smoothed `6.3–8 kHz` glare, and open `12.5–16 kHz` air) paired with a tighter, more holographic `NOVA` reference curve.
- **5-Band MBC + Dedicated De-Esser (`3.5–9.5 kHz`):** Upgrades `DynamicsProcessing` to **31-Band PreEQ + 5-Band MBC + 10-Band PostEQ + True-Peak Limiter** (`31/5 -> 10/4 -> Legacy EQ` leak-free fallback chain). Isolates `3.5–9.5 kHz` sibilance/cymbal harshness (`OFF` / `GENTLE` / `STRONG`) while keeping `9.5–20 kHz` air band unattenuated—even when `Multi-Band Punch` is `OFF`.
- **Mode-Aware Adaptive References & ISO-226 Loudness:** Per-mode psychoacoustic spectral targets (`NOVA`, `GRAVITY`, `HORIZON`, `ECLIPSE`, `COMMS`) stop adaptive tracking from fighting your chosen Pleasure Mode, complemented by nonlinear `pow(1.35)` ISO-226 equal-loudness compensation below 70% system volume.
- **31-Band Analog Tone Tilt (`-1.5..+1.5 dB/oct`):** Continuous Baxandall-style spectral slope pivoted at `1 kHz` inside 31-band PreEQ for warm or bright tonal balance with zero staircase artifacts.
- **Loudness-Matched A/B Bypass:** Attenuate-only A-weighted perceptual loudness matching (`[-9.0, 0.0] dB`) during manual A/B comparison so you judge true tonal improvement without volume-jump bias (auto-bypassed apps always perform full `0 dB` bypass).
- **RBJ Biquad Parametric EQ Parser & 36-Model Searchable Catalog:** Auto-detects EqualizerAPO / Oratory1990 / squig.link parametric filter files (`PK`, `LS`/`LSC`/`LSQ`, `HS`/`HSC`/`HSQ`) alongside `GraphicEQ:` and CSV curves, plus a searchable, category-filtered 36-model offline AutoEQ catalog featuring **4 Soundcore Life Q30 modes** (`ANC On · Oratory1990`, `ANC Off`, `Transparency`, `Warm Vocal`).
- **31-Band Live Curve Canvas & Sparklines:** `HeroSpectrumCanvas` renders the smooth 31-band log-spaced live EQ curve in real time with zero per-frame heap allocations, and `Activity` displays a 31-band compensation sparkline on active custom profiles alongside `schemaVersion = 3` backward-compatible JSON backup/import (`v1`–`v3`).

---

## Space HUD Features

ORBIT EQ pairs a 60 fps Jetpack Compose canvas interface with a two-tier Android audio pipeline:

- **Visual Themes:** Switch between **Deep Space HUD** (void black, starfield backdrop, and ion cyan telemetry) and **Lunar Monochrome** (OLED black/white with cobalt live curve).
- **Studio:**
  - **Now Playing Telemetry Pill:** Displays album artwork (when provided by the active media session), track title, artist/app, output route, mode resolution source (`Session Override`, `Pinned Track`, `App Rule`, `Device Default`, `Global Default`), `31-BAND` hi-res indicator, loudness-matched `A/B` attenuation chip when manually bypassed, quick `Save for App` chip, `A/B` bypass toggle, and engine power toggle.
  - **Hero Spectrum Canvas:** Live 36-bin FFT spectrum alongside target vs. 31-band live EQ curves, preamp attenuation, loudness boost, and `-0.5 dB` limiter telemetry.
  - **5 Pleasure Modes:** `NOVA` (balanced wide stage), `GRAVITY` (deep sub-bass + tight punch), `HORIZON` (airy stage & treble detail), `ECLIPSE` (warm night listening), and `COMMS` (vocal & podcast clarity).
  - **Orbital Dial & Macros:** `AUTO GAIN` adaptive intensity dial, **Sub Impact** and **Starlight Air** macro sliders, and **Dynamic Loudness** ISO-226 compensation.
- **10-Band Mixer & DSP Rack:**
  - **10-Band ISO Faders (`31 Hz` – `16 kHz`):** Hybrid live adaptive curve + manual per-band trim (drag to trim, double-tap to reset to `0.0 dB`).
  - **DSP Chain & Tone & Comfort:** Android `DynamicsProcessing` (31/10-band PreEQ + 5/4-band `MBC` + 10-band PostEQ + True-Peak Limiter at `-0.5 dB`, True Headroom `0.0 dB`, `20 Hz` Subsonic Filter, **Analog Tone Tilt**, **De-Esser**, **Loudness-Matched A/B**, and L/R Channel Balance), with automatic `Legacy Equalizer + LoudnessEnhancer` fallback on unsupported devices.
  - **Response Speed & Presets:** Selectable `CHILL`, `BALANCED`, and `REACTIVE` adaptation speeds plus saved custom presets.
- **Activity, AutoEQ & Backup:**
  - **Live Audio Engine Telemetry:** Active audio session ID (`Auto (Smart)` or `Global Mix (#0)`), `31-Band PreEQ · 5-Band MBC` / `10-Band PreEQ · 4-Band MBC` / `Legacy` backend status, sample rate, output route, and FFT/fallback state.
  - **Headphone Compensation, Sparkline & Searchable Catalog:** Built-in profiles plus 36 offline Harman/Oratory1990 catalog models, `.txt` / pasted GraphicEQ & Parametric EQ import, deterministic catalog IDs, and active 31-band sparkline preview bound per output device.
  - **Per-App & Device Rules:** Assign automatic `USE_MODE` or `BYPASS` rules per app, pin modes to specific tracks, and browse recent listening history with spectral tags.
  - **Backup & Diagnostics:** SAF JSON export/import (`schemaVersion = 3`, supporting `1..3`) and Copy/Share device diagnostics report.

---

## Soundcore Life Q30 Guide

ORBIT EQ automatically detects Soundcore Life Q30 Bluetooth connections and provides dedicated compensation profiles:

- **Why they work together cleanly:** The Soundcore companion app stores its EQ directly on the Q30 hardware over BLE, so it does not conflict with Android's on-device audio effects pipeline.
- **Q30 Clean Base:** Recommended when your Soundcore app EQ is set to **Flat** or **Acoustic**.
- **Q30 Safe Stack:** Recommended if you keep a Soundcore app bass preset active—uses a gentler compensation curve and adds **`-2.0 dB` extra headroom** to prevent clipping.
- **4 Dedicated 31-Band Q30 Catalog Profiles:** One-tap `ANC On (Oratory1990)`, `ANC Off (Harman)`, `Transparency (Harman)`, and `Warm Vocal (Zero-Fatigue)` profiles in the searchable AutoEQ catalog.
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
