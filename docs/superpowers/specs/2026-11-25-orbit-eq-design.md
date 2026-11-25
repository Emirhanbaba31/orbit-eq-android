# ORBIT EQ — Design Specification

**Date:** 2026-11-25  
**Platform:** Android (Native Kotlin + Jetpack Compose, API 28+ / Android 9.0–16)  
**Delivery:** Installable Android APK + Private GitHub Repository + GitHub Release (`v1.0.0`)

---

## 1. Intent & Why

The user wants an Android equalizer that runs in the background, tracks their live music activity, and dynamically shapes the sound in real time so that **listening enjoyment / musical pleasure is maximized** — rich, clean, energetic, immersive, and fatigue-free across tracks and apps, rather than a flat clinical calibration tool.

They specifically want:
- A premium, non-basic UI with a **Black / White / Blue Space aesthetic** (approved from the interactive live preview: **Deep Space HUD** primary theme, with optional **Lunar Monochrome** toggle).
- Full real-world compatibility with their **Anker Soundcore Life Q30** headphones and the **Anker Soundcore** companion app.
- The finished project published to a **private GitHub repository** with a ready-to-install **Android `.apk`**.

---

## 2. Goals, Non-Goals, and Anti-Goals

### Goals
1. **Real-Time Musical Auto-EQ:** Continuously analyze playing audio (`Visualizer` FFT) and smoothly adapt a 10-band EQ curve toward psychoacoustic "Pleasure Modes" (*NOVA, GRAVITY, HORIZON, ECLIPSE, COMMS*), taming muddiness/harshness and lifting thin recordings without jarring jumps.
2. **Music Activity Awareness:** Detect the active media app, track title, artist, playback state, and connected audio output device; apply per-app and per-device rules automatically and log recent activity.
3. **Zero-Distortion Protection:** Always keep headroom clean via automatic negative preamp calculation + `DynamicsProcessing` True-Peak Limiter (`-0.5 dB` ceiling) + optional subtle Multi-Band Punch (MBC).
4. **Volume-Adaptive Fullness:** Fletcher-Munson Dynamic Loudness compensation so music remains punchy and airy even at low night-listening volume.
5. **Soundcore Life Q30 Synergy:** Automatically recognize Soundcore Q30 over Bluetooth, avoid double-EQ muddiness via built-in Q30 acoustic compensation & Safe Stack headroom mode, and survive Bluetooth/ANC reconnects cleanly.
6. **Graceful DRM / OEM Resilience:** If an app or OEM zeroes out `Visualizer` FFT frames during playback, smoothly decay adaptive deltas to `0 dB` and continue applying Pleasure Mode + Macros + Dynamic Loudness + Headphone Compensation without interruption (`PROFILE FALLBACK` indicator).
7. **Studio-Grade Space UI:** 60fps Jetpack Compose `Canvas` (cosmic starfield + 3-layer FFT/target/live Bezier EQ), tactile orbital dial, 10-band hybrid live/manual faders, zero emojis (vector icons only), haptics, and WCAG-compliant OLED contrast.

### Non-Goals
- Root / Magisk / Viper4Android system-library modifications.
- Built-in music player or cloud account / login / backend server.
- Microphone-based room/speaker acoustic sweep measurement.
- Controlling Anker Soundcore Q30's proprietary BLE ANC/firmware settings (left to the official Soundcore app).

### Anti-Goals (Failure even if technically working)
- **Audible pumping or jittery EQ swings** mid-song (must use musical EMA smoothing and bounded adaptive deltas).
- **Digital clipping / harsh bass distortion** when stacking bass boosts on top of the Q30's naturally heavy bass response.
- **Silent failure on DRM tracks** or when Android restricts global session `0`.
- **Generic template UI** (plain Material sliders or cluttered settings wall).
- **Aggressive background kill** without guiding the user through Android's foreground service & battery exemption setup.

---

## 3. Approaches Considered

1. **Approach 1 — Hybrid Dynamic Auto-EQ (Selected):** Combines live `Visualizer` FFT spectral analysis + `MediaSession` / `NotificationListenerService` music activity tracking + `DynamicsProcessing` 10-band PreEQ/MBC/PostEQ/Limiter + automatic DRM/OEM profile fallback.  
   - *Trade-off:* Requests two one-time Android permissions (`RECORD_AUDIO` for system output FFT capture, Notification Access for track/app metadata) plus `POST_NOTIFICATIONS` on Android 13+. Chosen because it delivers true real-time music-reactive pleasure while staying rock-solid on DRM streams and across apps.
2. **Approach 2 — Spectrum-Only Auto-EQ (Rejected):** Live FFT analysis without media/app tracking. Simpler permissions, but cannot tell Spotify music apart from YouTube speech/podcasts or log music activity.
3. **Approach 3 — Metadata / Profile-Only Engine (Rejected):** Switches static EQ presets per app/song with no live FFT analysis. Zero audio-capture permission needed, but does not adapt dynamically in real time inside a song.

---

## 4. Technical Architecture & Constraints

### Platform & Stack (Builder's Call, Confirmed)
- **Language:** Kotlin (JVM 21 toolchain, Kotlin 2.x, Coroutines + Flow)
- **UI:** Jetpack Compose + Material 3 Dark (`compileSdk = 36`, `targetSdk = 35`, `minSdk = 28`)
- **Architecture:** Single-Activity MVVM (`MainActivity` + `OrbitViewModel`) bound to a persistent Foreground Service (`OrbitAudioService`) sharing reactive immutable state via a process-scoped `OrbitEngineRepository` (`StateFlow<OrbitUiState>`).
- **Persistence:** Jetpack DataStore Preferences (settings, active mode, intensity, macros, trims, custom presets, theme, Q30 compensation mode, per-app rules, per-device memory, and compact JSON-encoded recent listening history up to 50 items). No SQLite/Room bloat needed.
- **Fonts:** Bundled local `.ttf` font assets in `res/font/` (`Space Grotesk`, `DM Sans`, `Space Mono`) so typography renders identically offline on any Android device without Play Services font provider failures.

### Audio Engine Pipeline (`OrbitAudioService`)
- **Two-Tier Foreground Service (Android 14–16 / `targetSdk = 35` Safe — User Decision: A):**
  - Manifest declares `android:foregroundServiceType="mediaPlayback|microphone"` with `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `FOREGROUND_SERVICE_MICROPHONE` permissions.
  - **Tier 1 — Full Live Auto-EQ (`mediaPlayback|microphone`):** When the user enables Master Power from the foreground Activity (and `RECORD_AUDIO` is granted), the service runs with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK | FOREGROUND_SERVICE_TYPE_MICROPHONE`, keeping continuous real-time `Visualizer` FFT analysis active in the background across Spotify, YouTube Music, etc. Onboarding copy clearly explains Android's system green status-bar dot (playback output FFT analysis only, never voice/mic recording).
  - **Tier 2 — Safe Background Auto-Start (`mediaPlayback` only):** When triggered from the background (e.g., Soundcore Life Q30 Bluetooth connect or active media session while app is closed), Android 14+ forbids starting a `microphone` FGS from background (`SecurityException`). Therefore the service starts safely with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` in `PROFILE_FALLBACK` state — immediately applying Q30 Compensation + Pleasure Mode + Macros + Dynamic Loudness + True-Peak Limiter — and promotes seamlessly to `mediaPlayback|microphone` live FFT the next time ORBIT EQ opens in foreground.
  - Displays an ongoing compact notification showing active track, active mode, engine state (`AUTO FFT` / `PROFILE FALLBACK`), and quick notification actions (**A/B Bypass** and **Pause/Resume Engine**).
- **Audio Session Attachment:**
  1. Listens to `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` and `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION` broadcasts to discover active music app session IDs (Spotify, YouTube Music, Poweramp, etc.) and also tracks active audio playback.
  2. Default **Session Mode = Auto (Smart)**: prefers attaching `DynamicsProcessing` to the active music app's `audioSessionId` when advertised (highest compatibility across Samsung/Xiaomi/Pixel OEMs), and attaches to Global Output Mix (`sessionId = 0`) when no dedicated session is open or when user selects Global Mode. Re-attaches automatically on session change or Bluetooth route reconnect.
  3. **Fallback DSP:** If `DynamicsProcessing` fails to initialize on a device, automatically falls back to `android.media.audiofx.Equalizer` (mapping the 10-band curve onto device bands) + `LoudnessEnhancer`, reporting `LEGACY_EQ` in telemetry.
- **DynamicsProcessing Configuration (Stereo, `VARIANT_FAVOR_FREQUENCY_RESOLUTION`):**
  - **Input Gain (Dynamic Preamp) & Exact Headroom Telemetry (Builder's Call):** Automatically computed as `-max(0f, peakPositiveTotalGainDb - 1.5f) - extraHeadroomDb` (where `extraHeadroomDb = 2.0f` in Q30 Safe Stack mode, else `0f`) so internal DSP headroom never clips. Because Android's `DynamicsProcessing.Limiter` exposes no real-time gain-reduction getter API, the HUD and DSP Rack display this **exact calculated Dynamic Preamp attenuation (`PREAMP -X.X dB`)** with a live headroom bar alongside an honest static **True-Peak Limiter active ceiling badge (`LIM -0.5 dB`)** — zero fake GR readings, works identically in `PROFILE_FALLBACK`.
  - **Pre-EQ (10 bands: `31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz`):** Applies **Headphone Acoustic Compensation** (e.g., Soundcore Q30 profile) + **User Manual Band Trims** (`-8.0` to `+8.0 dB`).
  - **MBC (4 bands: `180, 800, 4000, 20000 Hz`):** Optional **Multi-Band Punch** (enabled by default; mild musical glue: ratio `1.8:1` on bass/highs, `1.5:1` on mids, attack `12–25 ms`, release `90–140 ms`, soft knee `6 dB`). Bypassed cleanly when toggled off.
  - **Post-EQ (10 bands, same ISO centers):** Applies **Pleasure Mode Target Curve** + **Real-Time Adaptive Auto-EQ Delta** + **Sub Impact & Starlight Air Macros** + **Dynamic Loudness Compensation**.
  - **Limiter (Always enabled when engine active):** True-Peak safety ceiling `-0.5 dB`, ratio `10.0f`, attack `1.0 ms`, release `60.0 ms`, post-gain `0.0 dB`.
- **Bypass / A-B Toggle:** Smoothly toggles `DynamicsProcessing` bypass for instant before/after comparison while keeping the service & visual telemetry alive.

### Real-Time Adaptive DSP Engine (`AdaptiveEqEngine` — Pure Kotlin, Unit-Tested)
- **Input:** Raw FFT byte array from `Visualizer` (`captureSize = 1024`, ~16–20 Hz capture rate), current volume ratio (`0f..1f`), active Pleasure Mode, Intensity (`0f..1f`), Speed (`CHILL / BALANCED / REACTIVE`), Bass/Air macros, Loudness toggle, Headphone Profile, Manual Trims.
- **Spectral Extraction:**
  - Converts FFT `(re, im)` pairs into magnitude spectrum, bins into 10 logarithmic ISO bands (`31 Hz .. 16 kHz`) plus 36 smooth UI display bins.
  - Normalizes each band relative to the frame's broadband RMS energy so the algorithm responds to **tonal balance** (muddy vs thin vs harsh vs dull), not just raw track loudness!
- **Adaptive Psychoacoustic Shaping:**
  - Compares normalized spectral tilt against an ideal psychoacoustic pink-tilt reference curve.
  - Applies bounded adaptive corrections (`[-5.5 dB, +5.5 dB]`, scaled by `intensity`):
    - Sub/Bass (`31–125 Hz`): Gently lifts thin tracks; trims excessive boom (`> threshold`) so sub-bass stays tight.
    - Mud Region (`125–250 Hz`): Actively cleans muddy build-up while preserving warmth.
    - Presence / Fatigue (`2k–4k Hz`): Asymmetric fast-attack tame of harsh vocal/guitar spikes to prevent ear fatigue.
    - Air / Brilliance (`8k–16k Hz`): Restores sparkle on dull/compressed streams without causing sibilance.
  - **Smoothing:** Asymmetric exponential moving average (EMA) controlled by Response Speed:
    - *Chill:* attack `0.06`, release `0.03`
    - *Balanced (default):* attack `0.12`, release `0.05`
    - *Reactive:* attack `0.22`, release `0.09`
- **Dynamic Loudness (Fletcher-Munson):** When enabled and system music volume is below `65%`, smoothly adds up to `+4.0 dB` at `31–62 Hz` and `+2.8 dB` at `8k–16k Hz` inversely proportional to volume level.
- **DRM / Silent FFT Watchdog:** Tracks consecutive near-zero FFT frames (`rms < 1e-4`). After `1500 ms` of silent FFT while music is actively playing, marks engine state as `PROFILE_FALLBACK` and smoothly decays adaptive deltas toward `0 dB` so the Pleasure Mode + Loudness + Headphone Compensation remain active cleanly.

### Music Activity Tracking, Rule Precedence & Pinning Semantics (Builder's Call)
- **`OrbitNotificationListenerService`:** Reads active `MediaController` sessions via `MediaSessionManager.getActiveSessions(componentName)`. Extracts package name, app display name, track title, artist, and `PlaybackState`.
- **Strict Separation & Precedence (Zero Rule Collisions):**
  - **Headphone Compensation** (`Q30 Clean Base`, `Q30 Safe Stack`, `Flat`, `Phone Speaker Safe`) is owned **solely by the connected output device** — switching apps or songs never alters your Q30 compensation layer.
  - **Pleasure Mode** resolves deterministically in strict priority order (`AppRuleResolver`):
    1. **Manual Session Override:** Tapping a mode on Studio applies immediately for the current track/app session without overwriting saved rules; clears cleanly when switching to a different app or a pinned track, and shows a subtle 1-tap **"Uygulamaya Kaydet"** action if the user wants to make it permanent.
    2. **Pinned Track Rule:** Tapping the pin button on a song in Activity persists `packageName + normalizedTrackTitle -> mode` in a dedicated DataStore map (separate from the rolling 50-item history so pinned tracks are never evicted). Tapping again unpins.
    3. **Per-App Smart Rule:** Editable per-app mapping. Defaults: `Spotify` → *NOVA*, `YouTube Music` → *NOVA*, `YouTube` → *COMMS* (user can switch to any mode), `Podcasts/Audible` → *COMMS*, `Phone / Google Maps / Navigation` → *Bypass (Excluded)*.
    4. **Per-Device Default Pleasure Mode:** Remembered fallback Pleasure Mode for the active output device when no manual override, track pin, or app rule matches.
- **Activity Log:** Records up to 50 recent unique tracks in DataStore with timestamp, app name, active mode, pinned state, and dominant spectral character tag (*Sub-Heavy*, *Wide Stage*, *Vocal*, *Warm*, *Balanced*).

### Language & Localization (User Decision: A)
- **Bilingual Android String Resources:** `values-tr/strings.xml` (primary Turkish UI matching the approved preview: *Studio / 10-Bant / Aktivite*, Turkish onboarding, Q30 synergy tips, and rule explanations while preserving crisp English studio/space terms like *NOVA, GRAVITY, HORIZON, ECLIPSE, COMMS, Sub Impact, Starlight Air, True-Peak Limiter*) + `values/strings.xml` (full English strings for non-Turkish system locales). All compact-screen (`360dp`) chips, tabs, and headers are sized to prevent wrapping or truncation in both languages.

### Anker Soundcore Life Q30 Compatibility Layer
- **Why No Conflict:** The Anker Soundcore app stores EQ directly inside the Q30's onboard Bluetooth DSP chip over BLE/RFCOMM and does not attach an Android `AudioEffect`. Thus ORBIT EQ and the Soundcore app coexist cleanly without crashing or fighting over Android audio sessions.
- **Preventing Double-EQ & Mud:**
  - `AudioManager.registerAudioDeviceCallback` monitors active audio output routes (`AudioDeviceInfo.productName`, `AudioDeviceInfo.type`) without requiring extra Bluetooth scan permissions.
  - Automatically detects `Q30` / `Soundcore Life Q30` in the connected Bluetooth output name (and also allows manual headphone profile selection).
  - **Built-in Headphone Compensation Profiles:**
    1. `Soundcore Life Q30 — Clean Base` (recommended when Soundcore app EQ is set to *Flat / Acoustic*): `[-1.5, -3.5, -4.5, -3.0, -0.5, +0.5, +2.0, +2.5, -1.5, +0.5] dB` — removes Q30's well-known mid-bass bloat and opens veiled vocals so Auto-EQ shines.
    2. `Soundcore Life Q30 — Safe Stack` (when user keeps a custom preset active inside the Soundcore app): `[-1.0, -2.0, -2.5, -1.5, 0.0, +0.5, +1.0, +1.0, -1.0, 0.0] dB` + extra `-2.0 dB` preamp headroom to guarantee zero clipping.
    3. `Flat / Generic Headphones`: all `0.0 dB`.
    4. `Phone Speaker Safe`: `[-6.0, -4.0, -1.5, 0.0, +0.5, +1.0, +1.0, +0.5, 0.0, -1.0] dB` (protects phone speakers from sub-bass distortion when headphones disconnect).
  - **Per-Device Memory:** Automatically restores the user's preferred Compensation + Pleasure Mode whenever Q30 connects, and switches to `Phone Speaker Safe` (or pauses auto-boost) on disconnect.
  - **Q30 Synergy Tip Banner:** Appears once (dismissible) when Q30 is detected, explaining optimal Soundcore app settings (*Acoustic/Flat* or *Safe Stack* mode).

---

## 5. UI / UX Design System (`ui-ux-pro-max` — Deep Space HUD)

### Visual Direction
- **Primary Theme (`Deep Space HUD`):** Void Black `#03050A`, Deep Space Surface `#0B0F1A`, Glass Panel `#0D1322` (78% alpha + `1dp` luminous hairline border `#1E293B` / `#38BDF8` active), Star White `#F8FAFC`, Lunar Silver `#94A3B8`, Launch Blue `#3B82F6`, Ion Cyan `#38BDF8`, Deep Orbit `#1D4ED8`.
- **Optional Secondary Theme (`Lunar Monochrome`):** Switchable in the Activity tab — Pure OLED Black `#000000`, Pure White `#FFFFFF`, Cobalt Blue `#2563EB` reserved exclusively for live active audio curves and telemetry.
- **Typography:**
  - Display / Headers: `Space Grotesk` (SemiBold / Bold)
  - Body / Labels: `DM Sans` (Regular / Medium / SemiBold)
  - Numeric Telemetry (`31Hz..16kHz`, `+2.4 dB`, `PREAMP -2.8 dB`, `78%`): `Space Mono` with tabular numerals (`tnum`) so live readouts never jitter.
- **Accessibility & Craft Rules:**
  - Zero emojis anywhere in UI — 100% custom/Material vector icons with consistent stroke width.
  - Every interactive touch target `>= 48dp × 48dp`; edge-to-edge `WindowInsets.systemBars` safe area handling.
  - Text contrast `>= 4.5:1` on all OLED surfaces.
  - Android haptic feedback (`HapticFeedbackType.TextHandleMove` / `LongPress`) on A/B bypass, mode switches, orbital dial steps, and `0.0 dB` fader center snap.
  - Respects Android reduced-motion (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f`) by switching starfield drift to static subtle stars and lowering canvas redraw frequency smoothly.

### Screen Map (3 Bottom-Navigation Tabs)
1. **Studio (Live Engine):**
   - Subtle animated deep-space starfield backdrop + mode-tinted radial nebula glow.
   - **Floating Now-Playing Telemetry Pill:** Track title, artist, active app, connected output device (e.g., `Soundcore Life Q30`), instant **A/B Bypass** button, and **Master Power** glow toggle.
   - **60fps 3-Layer Hero Canvas:** Incoming 36-bin FFT spectrum (star-white to cobalt gradient), dashed Pleasure Mode target curve, glowing ion-blue live Bezier Auto-EQ curve with orbital band nodes, plus top/bottom HUD telemetry (`AUTO FFT · LOCKED` or `PROFILE FALLBACK`, `LOUD +2.4dB`, exact Dynamic Preamp Headroom bar `PREAMP -2.8 dB`, and static `LIM -0.5 dB` ceiling badge).
   - **5 Space Pleasure Mode Cards (horizontal row with custom vector mini curves):**
     - `NOVA` (Balanced signature euphoria)
     - `GRAVITY` (Deep tight sub-bass punch)
     - `HORIZON` (Wide stage & crystalline treble air)
     - `ECLIPSE` (Warm, velvety low-fatigue night sound)
     - `COMMS` (Crisp vocal & podcast clarity)
   - **Tactile Telemetry Deck:** Interactive circular **Orbital Auto-Gain Dial** (`0%–100%`, drag or tap) beside **Sub Impact** (`-6..+8 dB`), **Starlight Air** (`-6..+8 dB`), and **Dynamic Loudness** switch.
2. **10-Bant (Pro Mixer & DSP Rack):**
   - **10 Vertical Hybrid Faders (`31Hz–16kHz`):** Each band displays live animated blue auto-EQ offset bar + draggable white manual trim handle with `0.0 dB` magnetic snap and live tabular dB readout + Reset button.
   - **DSP Rack Cards:** True-Peak Limiter headroom status (`-0.5 dB` ceiling), Multi-Band Punch (MBC) toggle, Response Speed segmented selector (`CHILL / BALANCED / REACTIVE`), and Custom Preset Save/Load chips.
3. **Aktivite (Music Tracking, Q30 & Rules):**
   - **Live Engine & Output Telemetry Card:** Active Audio Session ID, DSP mode (`DynamicsProcessing` vs `Legacy`), FFT status, detected Bluetooth/output device, and **Headphone Compensation Selector** (with Soundcore Life Q30 *Clean Base* & *Safe Stack* + dismissible Q30 synergy tip).
   - **Guided Setup Cards:** One-tap permission status & grant actions for **Audio Analysis (`RECORD_AUDIO`)**, **Music Tracking (Notification Access)**, **Notifications (Android 13+)**, and **Battery Optimization Exemption** so Android does not kill the service.
   - **Per-App Smart Rules:** View/edit auto-mode mapping per detected music app (and exclude apps like Maps/Phone).
   - **Recent Music Activity Timeline:** Scrollable list of tracked songs with spectral character chips and 1-tap mode pinning.
   - **Appearance Switch:** Toggle between `Deep Space HUD` and `Lunar Monochrome`.

---

## 6. Build, Verification & Private GitHub Publishing

- **Build System:** Gradle Wrapper (`gradlew`) with Kotlin DSL (`build.gradle.kts`), JDK 21, Android SDK `android-36` / `build-tools 35.0.0–36.0.0`.
- **Verification:**
  - JUnit unit tests covering `AdaptiveEqEngine`, `HeadphoneProfiles` (including Q30 Clean Base & Safe Stack), `DynamicLoudnessCalculator`, `PreampHeadroomCalculator`, `DrmFallbackDetector`, and `AppRuleResolver`.
  - Clean local Gradle `assembleRelease` (signed with a local release keystore excluded from git via `.gitignore`) + `assembleDebug`, verifying APK integrity (`aapt2 dump badging` / APK size & manifest check).
- **Private GitHub Repository:**
  - Create private repo (e.g., `orbit-eq-android`) via `gh repo create --private`.
  - Clean `.gitignore` excluding `.superpowers/`, `build/`, `.gradle/`, `local.properties`, and `*.jks`/`*.keystore`.
  - Push full source code + GitHub Actions CI workflow (`.github/workflows/android-apk.yml`) that runs unit tests and builds APK artifacts.
  - Create GitHub Release `v1.0.0` attaching the signed installable `OrbitEQ-v1.0.0.apk` so the user can download and install directly onto their Android phone from their private GitHub repo.

---

## 7. Left to the Builder

- Exact Compose Canvas geometry, star particle counts, animation easing tokens, and layout padding tuned cleanly across compact (`360dp`) and tall phones.
- Exact Gradle / Compose BOM / AGP library versions compatible with JDK 21 and Android SDK 36.
- Notification channel styling and vector drawable XML paths for app launcher icon (cosmic orbital ring in black/white/blue) and status icons.
- Exact MediaSession artwork thumbnail extraction (optional nice-to-have if available without memory overhead).
