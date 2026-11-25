# ORBIT EQ v2.0 — Design Specification

**Date:** 2026-11-25  
**Version:** `2.0.0` (`versionCode = 2`)  
**Platform:** Android (Native Kotlin + Jetpack Compose, `minSdk = 28`, `targetSdk = 35`, `compileSdk = 36`)  
**Repository:** `Emirhanbaba31/orbit-eq-android`  
**Delivery:** Upgraded source, unit tests + Compose UI test suite, signed release APK (`app-release.apk`)

---

## 1. Intent & Why

ORBIT EQ v1.0 established a solid 10-band adaptive equalizer with Deep Space HUD UI, two-tier Android 14–16 foreground service safety, and Soundcore Life Q30 compatibility.

For **v2.0**, the user wants **all** planned upgrades delivered together as one cohesive release:
1. **Noticeably higher audio fidelity** by fixing real DSP limitations discovered in v1.0 (`sampleRateHz` mismatch, post-effect Visualizer feedback self-cancellation, 10-band PreEQ frequency smearing, subsonic driver load, abrupt bypass/mode switches, quiet-section noise-floor jitter) and adding precision controls (AutoEQ `GraphicEQ.txt` 31-band headphone profiles, L/R channel balance, 3-level MBC, True Headroom zero-clip mode, subsonic filter).
2. **Faster everyday Android integration** via a Quick Settings Tile, Deep Space HUD home screen widget, album art in Now-Playing, quick Next-Mode notification control, and JSON settings backup/restore.
3. **Lower battery drain** through smart DSP loop cadence throttling, UI foreground awareness, and dirty-checked native `DynamicsProcessing` writes.
4. **Better real-device reliability & maintainability** via a one-tap Diagnostics report, `AudioRouteResolver` deduplication, and expanded unit + Compose UI tests.

---

## 2. Goals, Non-Goals, and Anti-Goals

### Goals
1. **Studio-Grade Audio Pipeline:** Upgrade `PreEQ` inside `DynamicsProcessing` to **31 ISO 1/3-octave bands (`20 Hz – 20 kHz`)** (with automatic fallback to 10-band `DynamicsProcessing`, then `LEGACY_EQ`), fix FFT `sampleRateHz` alignment, add damped closed-loop EQ de-embedding, noise-floor spectral flatness guard, track-change fast adaptive decay, click-free gain ramping, optional Subsonic Filter (`20–25 Hz`), True Headroom (`0.0 dB` allowance), L/R Channel Balance, and 3-tier MBC (`OFF / SUBTLE / PUNCHY`).
2. **AutoEQ & Custom Headphone Profiles:** Parse standard Wavelet / AutoEQ / Peace `GraphicEQ:` curves (file picker or pasted text), resample onto 31 ISO 1/3-octave bands, persist per-device assignments, and bundle a compact offline catalog of popular headphones (including a 31-band Harman-calibrated Q30 profile).
3. **Zero-Friction Daily Controls:** Provide a Quick Settings Tile (`TileService`), native `RemoteViews` Home Screen Widget (`AppWidgetProvider`), album art thumbnail in Studio's Now-Playing pill, and a Next-Mode action on the foreground notification.
4. **Portability & Diagnostics:** Support versioned JSON backup export/import (via Android SAF) and a one-tap copy/share technical Diagnostics summary on the Activity tab.
5. **Battery Efficiency:** Eliminate unnecessary 18 Hz native JNI writes and UI `spectrumBins36` emissions when the screen/app is backgrounded, paused, or in static `PROFILE_FALLBACK`.
6. **Backward Compatibility:** Seamlessly migrate existing v1.0 DataStore preferences without losing saved rules, pinned tracks, or custom presets.

### Non-Goals
- Root / Magisk / system-level audio HAL patching.
- Android `Virtualizer` / artificial 3D surround effects (explicitly avoided due to phase artifacts and metallic coloration on headphones).
- Cloud sync, user accounts, or network fetching of AutoEQ databases (100% offline).
- Adding Jetpack Glance or third-party image-loading libraries (keep APK lean using native `RemoteViews` and standard Android `Bitmap` downscaling).

### Anti-Goals (Failure even if technically working)
- **EQ feedback oscillation / runaway** caused by over-aggressive FFT de-embedding on OEMs that tap `Visualizer` pre-effect.
- **Clicks, pops, or zipper noise** when toggling A/B Bypass or switching Pleasure Modes.
- **Audio dropouts or crashes on OEMs** that reject 31-band `DynamicsProcessing` (must silently fall back to 10-band `DynamicsProcessing`, then `LEGACY_EQ`).
- **Cluttering the Studio screen** with technical toggles (Studio stays clean and tactile; advanced DSP controls belong in Mixer, setup/profiles/backup/diagnostics in Activity).
- **Widget or Tile crashing on Android 14–16** due to background `microphone` FGS start restrictions (background triggers must always use `TIER2_PROFILE_ONLY` / `mediaPlayback`).

---

## 3. Approaches Considered

1. **Approach A — Unified `v2.0` Release in Layered Slices (Selected by User):**
   - Build the upgraded data model, DSP/battery engine, Android system surfaces (Tile, Widget, Artwork, Notification, Backup, Diagnostics), and tests in one coherent `2.0.0` release.
   - *Trade-off:* Wider change surface, mitigated by strict modular architecture, unit tests per component, and clean layered verification. Chosen so `OrbitSettings`, DataStore migration, and JSON backup schema are designed once with zero churn.
2. **Approach B — Two-Stage Split (`v1.1` Audio & Battery -> `v1.2` System Integration & Tools) (Rejected):**
   - Ship audio/battery first, pause for on-device listening, then ship Tile/Widget/Backup/Diagnostics in a second release.
   - *Trade-off:* Faster first listening test, but requires two schema revisions and two APK build/install cycles.

---

## 4. Technical Architecture & Detailed Design

### 4.1 Upgraded Data Model & Backward-Compatible Persistence (`OrbitModels.kt`, `OrbitPreferencesStore.kt`, `HistoryCodec.kt`)
- **New Enums & Models:**
  - `enum class MbcMode { OFF, SUBTLE, PUNCHY }`
  - `@Immutable data class CustomHeadphoneProfile(val id: String, val name: String, val gains31Db: List<Float>, val extraHeadroomDb: Float = 0f, val sourceLabel: String = "AutoEQ")`
    - `gains31Db` is always normalized to 31 finite floats clamped to `[-15f, +15f]`.
- **Additions to `OrbitSettings` (all with safe defaults):**
  - `mbcMode: MbcMode = MbcMode.SUBTLE` (retaining `val mbcEnabled: Boolean = true` kept synchronized as `mbcMode != MbcMode.OFF`; on DataStore read, if `mbc_mode` key is absent, migrate from legacy `mbc_enabled`: `true -> SUBTLE`, `false -> OFF`).
  - `subsonicFilterEnabled: Boolean = true`
  - `trueHeadroomMode: Boolean = false`
  - `channelBalance: Float = 0f` (clamped `-1.0f..+1.0f`; `0f` = centered)
  - `customHeadphoneProfiles: List<CustomHeadphoneProfile> = emptyList()`
  - `deviceCustomProfileIds: Map<String, String> = emptyMap()` (normalized `deviceKey -> customProfileId`)
- **Additions to `LiveAudioTelemetry`:**
  - `preEqBandCount: Int = 10` (`31` when hi-res PreEQ is active, `10` on standard DynamicsProcessing, `0` when `NONE`, or device band count on `LEGACY_EQ`).
  - `activeCompensationName: String = ""` (resolved display name for diagnostics & telemetry).
- **Additions to `OrbitUiState`:**
  - `activeCustomHeadphoneProfile: CustomHeadphoneProfile? = null` (resolved from `deviceCustomProfileIds[deviceKey]` matching an entry in `customHeadphoneProfiles`; selecting a built-in `HeadphoneProfile` clears `deviceCustomProfileIds[deviceKey]`, and selecting a custom profile sets it).
  - Deleting a `CustomHeadphoneProfile` automatically cleans up any references in `deviceCustomProfileIds`.
- **Album Artwork State:**
  - Stored in a dedicated `OrbitEngineRepository.nowPlayingArtwork: StateFlow<Bitmap?>` (downscaled to max `128x128 px`, recycled/replaced cleanly) rather than inside `OrbitUiState`/`NowPlayingInfo`, preventing unnecessary structural equality overhead and DataStore bloat.
- **UI Visibility Signal:**
  - `OrbitEngineRepository.setUiVisible(visible: Boolean)` updated from `MainActivity.onStart()` / `onStop()`, readable by `OrbitAudioService` to throttle spectrum UI telemetry when backgrounded.

### 4.2 High-Resolution 31-Band `PreEQ`, AutoEQ Parser & Offline Catalog
- **31 ISO 1/3-Octave Frequencies (`PleasureProfiles.ISO_31_FREQUENCIES_HZ`):**
  `20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000 Hz`.
- **Built-in 31-Band Compensation Curves:**
  - `Q30_CLEAN_BASE`, `Q30_SAFE_STACK`, `FLAT_GENERIC`, and `PHONE_SPEAKER_SAFE` expose both 10-band and calibrated 31-band curves (`compensationCurve31For(profile)`). The Q30 31-band curves accurately sculpt the Q30's 80–250 Hz mid-bass hump, 2.5–4 kHz vocal recess, and 6–8 kHz treble resonance while maintaining identical overall tonal character with higher resolution.
- **AutoEQ Parser (`AutoEqParser.kt` — Pure Kotlin, Unit-Tested):**
  - Parses standard AutoEQ / Wavelet / Peace formats:
    - `GraphicEQ: 20 -1.2; 21 -1.2; 25 -1.8; ...` (single-line or multi-line, case-insensitive `GraphicEQ:` prefix optional if `freq gain; freq gain` pairs are present).
    - Also accepts newline-separated `freq gain` or CSV `freq,gain` lines (ignoring comment lines starting with `#` or `Preamp:`).
  - Validates at least 3 valid `(freqHz in 10..24000, gainDb finite)` points, sorts by frequency, deduplicates by averaging same-frequency entries, and resamples onto all 31 ISO 1/3-octave centers using log2-frequency linear interpolation (flat edge extension outside measured bounds), clamping each band to `[-15f, +15f]`.
  - Returns a typed result (`AutoEqParseResult.Success(gains31Db, pointCount)` or `AutoEqParseResult.Error(reason)`).
- **Curated Offline Catalog (`AutoEqCatalog.kt`):**
  - Lightweight built-in list of 7 popular models users can import with 1 tap:
    1. `Soundcore Life Q30 (Harman 31-Band)`
    2. `Soundcore Space Q45 (Harman)`
    3. `Sony WH-1000XM4 (Harman)`
    4. `Sony WH-1000XM5 (Harman)`
    5. `Apple AirPods Pro 2 (Harman)`
    6. `Samsung Galaxy Buds2 Pro (Harman)`
    7. `Sennheiser HD 600 (Harman)`
- **PreEQ Assembly (`AdaptiveEqEngine`):**
  - Computes 31-band `preEq31Db`:
    - Base compensation: `activeCustomHeadphoneProfile.gains31Db` if assigned to active device, else `PleasureProfiles.compensationCurve31For(headphoneProfile)`.
    - Manual 10-band trims (`manualTrimsDb`): log2-interpolated across the 31 bands and added to base compensation.
    - Optional **Subsonic Filter** (`subsonicFilterEnabled`): adds `-6.0 dB` at `20 Hz` (band 0) and `-2.5 dB` at `25 Hz` (band 1).
  - Also computes 10-band `preEqDb` (sampled from `preEq31Db` at the 10 ISO frequencies) for 10-band `DynamicsProcessing` fallback, `LEGACY_EQ` interpolation, and HUD telemetry.

### 4.3 Five Audio-Quality DSP Upgrades (`AdaptiveEqEngine.kt`, `FftAnalyzer.kt`, `DspEffectController.kt`)
1. **Real `sampleRateHz` Alignment:**
   - `AdaptiveEqEngine.processFrame(...)` accepts `sampleRateHz: Int = 48000` (passed from `visualizerController.sampleRateHz`) and forwards it to `FftAnalyzer.analyze(fftBytes, sampleRateHz)`.
2. **Damped Closed-Loop FFT EQ De-Embedding:**
   - `AdaptiveEqEngine` tracks `lastAppliedTotalEqDb` (10 bands) from the previous active frame.
   - Before comparing `bandEnergiesNorm` against `PINK_REFERENCE_NORM`, it computes damped inverse gain compensation per band:
     - `deembedGainLinear[b] = 10f.pow(-clamp(lastAppliedTotalEqDb[b], -9f, 9f) * DEEMBED_DAMPING / 20f)` with `DEEMBED_DAMPING = 0.70f`.
     - Scales `bandEnergiesNorm[b]` by `deembedGainLinear[b]` and re-normalizes across the 10 bands to preserve mean broadband energy scale (clamped to `[0f, 1f]`).
   - Damping at `0.70f` plus bounded `[-5.5, +5.5] dB` adaptive clamp prevents self-cancellation on post-effect Android AudioFlinger while guaranteeing monotonic convergence with zero oscillation even on OEMs that tap `Visualizer` pre-effect.
3. **Noise-Floor Spectral-Flatness Guard & Track-Change Fast Decay (Engineering Call):**
   - `FftAnalyzer` computes true **Wiener entropy (spectral flatness)** over the **raw linear FFT bin powers** `P[k] = mags[k]^2` for `k = 1..halfN`: `exp(mean(ln(P[k] + 1e-12))) / (mean(P[k]) + 1e-12)`, yielding `0f..1f` on `SpectralFrame`. On linear FFT bins, broadband white/quantization noise scores `~0.75–1.00`, while tonal music (even pink/balanced music) scores `~0.05–0.45`.
   - When `spectralFlatness >= 0.72f` (quantization noise floor during quiet fade-ins/outs under `SCALING_MODE_NORMALIZED`), `AdaptiveEqEngine` treats the frame as non-tonal noise: it gently decays `smoothedDelta` toward `0 dB` while **preserving `AUTO_FFT_LOCKED`** (it does **not** trip the 1.5 s `PROFILE_FALLBACK` DRM watchdog, since live non-zero FFT is still flowing).
   - `processFrame(...)` accepts `trackKey: String?`; when `trackKey` changes to a different non-null track, `smoothedDelta` is immediately multiplied by `0.35f` (fast musical decay without an audible cliff) so the new track locks in rapidly.
4. **True Headroom, L/R Channel Balance & 3-Tier MBC:**
   - **Headroom calculation:** Computes worst-case positive cascade peak across all 31 bands (`preEq31Db[i] + interpolatedPostEq31Db[i]`). `allowance = if (trueHeadroomMode) 0.0f else 1.5f`. `inputGainDb = -max(0f, peakCascadeDb - allowance) - extraHeadroomDb`.
   - **L/R Channel Balance (`channelBalance in -1f..+1f`):**
     - `leftBalanceTrimDb = if (channelBalance > 0f) -12f * channelBalance else 0f`
     - `rightBalanceTrimDb = if (channelBalance < 0f) 12f * channelBalance else 0f`
     - Applied in `DynamicsProcessing` via `setInputGainbyChannelIndex(0, inputGainDb + leftBalanceTrimDb)` and `setInputGainbyChannelIndex(1, inputGainDb + rightBalanceTrimDb)`. Attenuating the opposite channel guarantees zero clipping.
   - **MBC Modes (`MbcMode`):**
     - `OFF`: MBC disabled.
     - `SUBTLE`: ratios `[1.45, 1.30, 1.30, 1.45]`, thresholds `[-18, -16, -16, -17] dB`, knee `8 dB`, attacks `[25, 18, 14, 12] ms`, releases `[140, 120, 100, 90] ms`.
     - `PUNCHY`: ratios `[2.0, 1.65, 1.65, 2.0]`, thresholds `[-15, -13, -13, -14] dB`, knee `5 dB`, attacks `[18, 14, 10, 9] ms`, releases `[110, 95, 85, 75] ms`.
5. **Click-Free Gain Ramp & Slew-Rate Limiting (`DspEffectController.kt`):**
   - `DspEffectController` maintains a smooth `bypassRamp` (`0f..1f`, stepping `±0.34f` per tick -> ~3 ticks / ~165 ms full fade) and slew-limits sudden jumps in `preEq`, `postEq`, and `inputGain` to `±3.0 dB` per tick while engaged.
   - When transitioning to bypassed (`active = false`), `DynamicsProcessing` ramps all EQ/input gains smoothly toward `0 dB` and disables the native effect once `bypassRamp <= 0.01f` (instant disable still happens on `release()`).
   - **31-Band -> 10-Band -> Legacy Fallback Chain:**
     - `DspBackendFactory.createDynamicsProcessing(sessionId, preEqBandCount = 31)` -> if exception or enable probe fails, retry `createDynamicsProcessing(sessionId, preEqBandCount = 10)` -> if that fails, `createLegacyEqualizer(sessionId)` -> if non-zero session failed, retry session `0`.

### 4.4 Battery & Performance Optimization (`DspLoopCadencePlanner.kt`, `OrbitAudioService.kt`, `DspEffectController.kt`)
- **Dirty-Checked Native DSP Writes:**
  - `AndroidDynamicsProcessingHandle` tracks the last written `preEq` array, `postEq` array, left/right input gains, `mbcMode`, and `enabled` flag.
  - Native `dp.setPreEqAllChannelsTo`, `dp.setPostEqAllChannelsTo`, `dp.setInputGainbyChannelIndex`, and `dp.setMbcAllChannelsTo` are skipped unless values changed by `>= 0.05 dB` (or mode/enabled changed).
  - Same `>= 5 mB` dirty check applied to `AndroidLegacyEqualizerHandle`.
- **Smart Loop Cadence (`DspLoopCadencePlanner.kt` — Pure Kotlin, Unit-Tested):**
  - Computes next sleep interval based on `(isMediaPlaying, isLiveFftActive, isUiVisible, isRampSettled, isAdaptiveDeltaZero)`:
    - Ramp active (bypass/engage fade in progress): `35 ms` (snappy click-free fade).
    - Playing + live FFT + UI visible: `55 ms` (~18 Hz).
    - Playing + live FFT + UI backgrounded: `110 ms` (~9 Hz) and suppresses `spectrumBins36` UI list churn (keeps last bins static while backgrounded).
    - Playing + `PROFILE_FALLBACK` (no live FFT) + settled: `500 ms`.
    - Paused / idle + settled: `1000 ms`.
- **Instant Wake on Control Changes:**
  - `OrbitAudioService` launches a lightweight collector on `OrbitEngineRepository.uiState` (distinct on settings/activeMode/activeHeadphoneProfile/activeCustomHeadphoneProfile/nowPlaying.isPlaying/nowPlaying.trackTitle/autoBypassedByRule) that pings `wakeSignal.trySend(Unit)` immediately, so user adjustments, mode switches, and playback resumes take effect in `< 10 ms` regardless of sleep cadence.

### 4.5 Android System Integration & Everyday UX
1. **Quick Settings Tile (`OrbitQuickSettingsTileService.kt`) (Engineering Call):**
   - Extends `TileService`, registered in `AndroidManifest.xml` (`android:exported="true"`, `BIND_QUICK_SETTINGS_TILE`, `ic_stat_orbit`, label `ORBIT EQ`, `META_DATA_TOGGLEABLE_TILE = true`).
   - `onStartListening()`: initializes `OrbitEngineRepository`, syncs tile state (`STATE_ACTIVE` when `powerEnabled && !effectiveBypass`, `STATE_INACTIVE` otherwise) and subtitle (`NOVA · AKTİF`, `BYPASS`, `AUTO BYPASS`, `KAPALI`).
   - `onClick()` state machine (matches visual affordance):
     - If `!powerEnabled`: sets `powerEnabled = true` and attempts `OrbitAudioService.startBackgroundSafe(applicationContext)` (which returns `Boolean`, catching `ForegroundServiceStartNotAllowedException` / `SecurityException`). If direct background FGS start is blocked by Android 14–16 (e.g. battery optimization not yet exempted), falls back to launching `MainActivity` with `EXTRA_START_ENGINE = true` via `startActivityAndCollapse(PendingIntent)` (wrapped in `unlockAndRun` if `isLocked`), ensuring the tile reliably starts the engine without crashing.
     - If `powerEnabled && settings.bypassed` (manually bypassed, tile shows `STATE_INACTIVE` / `BYPASS`): clears manual bypass (`setBypassed(false)`) so tapping an inactive-looking tile activates EQ immediately (`STATE_ACTIVE`).
     - If `powerEnabled && !settings.bypassed` (active, or auto-bypassed by an excluded app rule): calls `OrbitAudioService.stopEngine(applicationContext)` (`STATE_INACTIVE`, `KAPALI`).
   - Also refreshed via `TileService.requestListeningState(...)` whenever power/mode/bypass changes in `OrbitAudioService` or `OrbitViewModel`.
2. **Home Screen Widget (`OrbitWidgetProvider.kt` + XML layout) (Engineering Call):**
   - Native `AppWidgetProvider` + `RemoteViews` (zero extra dependencies) styled in Deep Space HUD (dark rounded glass surface `#0D1322`, hairline border `#1E293B`, ion cyan accents `#38BDF8`, Space-clean typography).
   - Displays: Active Mode badge (`NOVA`), engine status text (`AUTO FFT` / `FALLBACK` / `BYPASS` / `BEKLEMEDE`), current track or connected device name, and 3 tactile action buttons (`Power`, `A/B`, `Next Mode`).
   - Handles broadcast intents: `ACTION_WIDGET_TOGGLE_POWER`, `ACTION_WIDGET_TOGGLE_BYPASS`, `ACTION_WIDGET_NEXT_MODE` (cycling `NOVA -> GRAVITY -> HORIZON -> ECLIPSE -> COMMS`). When turning power ON, calls `OrbitAudioService.startBackgroundSafe(context)`; if blocked by Android 14–16 background FGS restriction, falls back to launching `MainActivity` with `EXTRA_START_ENGINE = true` (`FLAG_ACTIVITY_NEW_TASK`) so the widget power button always succeeds cleanly.
   - `OrbitWidgetUpdater.requestUpdate(context)` called from `OrbitAudioService` & `OrbitViewModel` only when non-FFT state changes (power, bypass, mode, status, track, device) — never on 18 Hz FFT ticks.
3. **Notification Enhancements & Album Artwork:**
   - `OrbitNotificationListenerService` extracts artwork bitmap from `MediaMetadata` (`METADATA_KEY_ALBUM_ART`, `METADATA_KEY_ART`, `METADATA_KEY_DISPLAY_ICON`), downscales to max `128x128` off the main thread, and publishes to `OrbitEngineRepository.updateNowPlayingArtwork(bitmap)`.
   - `StudioScreen`'s `NowPlayingTelemetryPill` renders the album artwork inside the existing `40dp` rounded box when available (clipped `RoundedCornerShape(12.dp)`), falling back to `OrbitIcons.MusicNote`.
   - `OrbitAudioService` adds a 3rd notification action: `ACTION_NEXT_MODE` (`Sonraki Mod` / `Next Mode`) alongside `A/B Bypass` and `Durdur`.
4. **JSON Backup & Restore (`OrbitBackupCodec.kt`) (Engineering Call):**
   - Pure-Kotlin encoder/decoder using `org.json.JSONObject` (`schemaVersion = 2`, `exportedAtMs`, `settings`).
   - **Included fields:** All user tuning & configuration fields — `selectedMode`, `intensityPercent`, `bassMacroDb`, `airMacroDb`, `dynamicLoudness`, `mbcMode`, `subsonicFilterEnabled`, `trueHeadroomMode`, `channelBalance`, `responseSpeed`, `manualTrimsDb`, `themeMode`, `sessionMode`, `customPresets`, `customHeadphoneProfiles`, `deviceProfiles`, `deviceCustomProfileIds`, `deviceDefaultModes`, `appRules`, `pinnedTracks`.
   - **Explicitly excluded:** Runtime service state (`powerEnabled`, `bypassed`) and listening history (`recentActivity`) — restoring a backup never unexpectedly starts/stops `OrbitAudioService` or leaks playback history.
   - **Import semantics (Safe Merge):** Scalar tuning settings are updated from the backup; collections (`customPresets`, `customHeadphoneProfiles`, `appRules`, `pinnedTracks`, `deviceProfiles`, `deviceCustomProfileIds`, `deviceDefaultModes`) **merge** (upserting imported entries by ID/normalized key while preserving existing local entries not present in the backup) to prevent accidental data loss.
   - Uses Android SAF (`CreateDocument("application/json")` for export, `OpenDocument` for import) in `MainActivity`, reporting concise localized status feedback on `ActivityScreen`.
5. **Diagnostics Report (`DiagnosticsFormatter.kt`):**
   - Pure-Kotlin formatter generating a readable multi-line plain-text summary (version `2.0.0`, Android API + device model, DSP backend + PreEQ band count, session ID/mode, sample rate, FFT status, active device + compensation profile, active mode + source, preamp/headroom/subsonic/MBC/balance values, permission states).
   - Surfaced on `ActivityScreen` with 1-tap **Copy** (`ClipboardManager`) and **Share** (`Intent.ACTION_SEND`) buttons.
6. **Code Deduplication (`AudioRouteResolver.kt`):**
   - Pure helper unifying the identical Bluetooth / Wired / Speaker route detection logic currently duplicated between `OrbitAudioService.selectConnectedOutputDevice()` and `OrbitViewModel.resolveOutputDevice()`.

### 4.6 UI Placement & Bilingual Localization (`values-tr/strings.xml`, `values/strings.xml`)
- **Studio tab:** Uncluttered — adds album artwork thumbnail in the existing `40dp` slot and subtle `31-BANT` / `31-BAND` indicator when hi-res PreEQ is active.
- **10-Bant (Mixer) tab:**
  - Existing 10-band faders + Reset.
  - `LimiterPreampCard`: adds **True Headroom (`0.0 dB` Zero-Clip)** switch and **Subsonic Filter (`20 Hz`)** switch inside the same card.
  - `MbcPunchCard`: upgraded to 3-way segmented selector (`KAPALI / DOĞAL / PUNCH` | `OFF / SUBTLE / PUNCHY`).
  - New **L/R Kanal Dengesi** (`Channel Balance`) card with center-snapping slider (`-100% L .. 0 .. +100% R`) and reset-to-center button.
  - Existing `ResponseSpeedCard` and `CustomPresetsCard`.
- **Aktivite tab:**
  - `LiveEngineTelemetryCard`: shows `DynamicsProcessing · 31-Bant PreEQ` (or `10-Bant`) accurately.
  - `HeadphoneCompensationCard`: existing 4 built-in profiles + imported custom 31-band profiles list (selectable per device, deletable) + **"AutoEQ İçe Aktar"** button opening a modal dialog with 3 clean tabs/options: *Popüler Kulaklıklar (Katalog)*, *.txt Dosyası Seç*, *Metin Yapıştır*.
  - Existing `GuidedSetupCard`, `AppSmartRulesCard`, `PinnedTracksCard`, `RecentActivity`, `AppearanceThemeCard`.
  - New **Yedekleme & Tanılama** (`Backup & Diagnostics`) card at the bottom: Export JSON, Import JSON, Copy Diagnostics, Share Diagnostics, plus status banner for import/export/copy feedback.
- All new strings added in both Turkish (`values-tr/strings.xml`) and English (`values/strings.xml`), maintaining `>= 48dp` touch targets, `360dp` compact width safety, zero emojis, and Deep Space HUD / Lunar Monochrome theme consistency.

---

## 5. Verification Strategy

1. **Unit Tests (`:app:testDebugUnitTest`):**
   - New tests: `AutoEqParserTest`, `AutoEqCatalogTest`, `DspLoopCadencePlannerTest`, `OrbitBackupCodecTest`, `DiagnosticsFormatterTest`, `AudioRouteResolverTest`.
   - Extended tests: `AdaptiveEqEngineTest` (sampleRate propagation, damped FFT de-embedding stability over 200 frames, noise-floor spectral flatness guard, track-change fast decay, 31-band PreEQ + subsonic filter + True Headroom), `FftAnalyzerTest` (spectral flatness + non-48kHz sample rates), `DspEffectControllerTest` (31->10->Legacy fallback chain, dirty-write skipping, click-free bypass ramp, L/R channel balance gains, 3-level MBC), `OrbitEngineRepositoryTest` (custom AutoEQ profile CRUD/binding, v1->v2 MBC migration, backup restore, next-mode cycle).
2. **Compose UI Tests (`app/src/androidTest/`):**
   - `OrbitUiSmokeTest` verifying Studio/Mixer/Activity rendering, MBC 3-way selector, L/R balance card, AutoEQ import dialog, and Backup/Diagnostics card; verified compilation via `:app:compileDebugAndroidTestKotlin`.
3. **Release Build & Integrity Verification:**
   - `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleRelease` verifying signed `v2.0.0` APK builds cleanly with zero errors.

---

## 6. Left to the Builder

- Exact XML drawable/layout padding and font sizes for the `RemoteViews` home screen widget across `3x1`–`5x2` launcher grids.
- Exact 31-band Harman curve numbers for the 7 offline catalog headphones (derived from standard public AutoEQ/Harman target tendencies, clamped safely to `[-12, +12] dB`).
- Exact visual spacing and dialog layout details for the AutoEQ Import dialog and Backup/Diagnostics card while preserving `48dp` touch targets and `360dp` compact-screen legibility.
- Whether `OrbitAudioService` updates the home screen widget directly via `AppWidgetManager` or via a small `OrbitWidgetUpdater` helper called from both the service and ViewModel.
