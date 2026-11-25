# ORBIT EQ v3.0 — Acoustic & Musical Pleasure Engine Design Specification

**Date:** 2026-11-25  
**Version:** `3.0.0` (`versionCode = 3`)  
**Platform:** Android (Native Kotlin + Jetpack Compose, `minSdk = 28`, `targetSdk = 35`, `compileSdk = 36`)  
**Repository:** `Emirhanbaba31/orbit-eq-android`  
**Delivery:** Upgraded source, expanded JVM unit tests + Compose UI smoke tests, signed release APK (`app-release.apk`)

---

## 1. Intent & Why

ORBIT EQ v2.0 delivered a clean, stable 31-band `PreEQ` adaptive equalizer, battery-smart service loop, Quick Settings Tile, home widget, backup/diagnostics, and Soundcore Life Q30 compensation that the user already enjoys across all music genres.

For **v3.0**, the user wants to go **all-in on pure musical enjoyment and sound quality** — specifically for all-genre listening on Soundcore Life Q30 (and any future headphone/IEM), and **explicitly authorized refining v2.0's default Q30 & `NOVA` voicing** wherever real acoustic precision makes it sound cleaner, richer, and less fatiguing. Every new capability must deliver real, audible acoustic improvements inside Android's native `DynamicsProcessing` DSP pipeline (zero root, zero artificial phase/spatial gimmicks):

1. **Higher-precision live psychoacoustic processing:** Mode-aware adaptive spectral references (so each Pleasure Mode works *with* the music rather than fighting its own tonal goal), ISO-226 perceptual equal-loudness compensation (rich bass and natural vocals even at low late-night volume), continuous 31-band **Analog Tone Tilt** (`-1.5..+1.5 dB/oct`), dynamic **Sibilance & Harshness Guard (De-Esser)** targeting `3.5–9.5 kHz` via 5-band MBC without dulling `> 10 kHz` air, and **Loudness-Matched A/B Bypass** so ears can judge real tonal quality without volume-jump bias.
2. **Studio-accurate headphone calibration & parametric AutoEQ:** True RBJ biquad **EqualizerAPO Parametric EQ** (`PK`, `LS`/`LSC`/`LSQ`, `HS`/`HSC`/`HSQ`) evaluation onto 31 ISO 1/3-octave bands, plus a searchable **36-model offline AutoEQ catalog** featuring dedicated **Soundcore Life Q30 ANC On (Oratory1990) / ANC Off / Transparency / Warm Vocal** profiles.
3. **Truthful 31-band acoustic visualization:** Upgrade Studio's `HeroSpectrumCanvas` live EQ curve from 10 coarse nodes to the true 31-band combined frequency response (`PreEQ31 + interpolated PostEQ`), plus a compact 31-band sparkline preview on custom headphone profiles in Activity.

---

## 2. Goals, Non-Goals, and Anti-Goals

### Goals
1. **Elevated Default Q30 & All-Genre Musical Voicing (User-Approved):**
   - Refine `Q30_CLEAN_BASE_31_CURVE` (31-band) to preserve deep sub-bass extension (`20–40 Hz`: `0.0 / -0.3 / -0.8 dB`), tighten the true mid-bass bloat (`80–250 Hz` peaking at `-5.2 dB` around `125–160 Hz`), lift the measured upper-mid vocal dip (`2.5–3.15 kHz`: `+3.2 / +3.5 dB`), smooth the metallic `6.3–8 kHz` glare (`-2.2 / -2.8 dB`), and open silky `12.5–16 kHz` air (`+0.8 / +1.0 dB`).
   - Refine `NOVA_TARGET` subtly (`[3.0, 3.2, 0.8, -0.8, -0.3, 0.8, 1.8, 2.2, 2.4, 2.8]`) to reduce 125 Hz mid-bass stack-up and 8 kHz glare while extending airy 16 kHz shimmer.
   - Default `deEsserMode = DeEsserMode.GENTLE` (transparent on smooth mixes; only catches sharp `3.5–9.5 kHz` sibilance/cymbal spikes) and `toneTiltDbPerOct = 0.0f` (`loudnessMatchedBypass = true`).
   - Existing persisted v2.0 custom profiles/trims/rules load with zero data loss (`de_esser_mode` defaults to `GENTLE` when absent so upgraded users immediately enjoy smooth, fatigue-free highs and can toggle `OFF`/`STRONG` anytime).
2. **Real Native Android DSP Upgrades:**
   - Upgrade `DynamicsProcessing` primary configuration to **31-band PreEQ + 5-band MBC + 10-band PostEQ + Limiter** (`31/5 -> 10/4 -> LEGACY_EQ -> session 0` leak-free fallback chain).
   - **5-Band MBC (`180 Hz`, `800 Hz`, `3500 Hz`, `9500 Hz`, `20000 Hz`):** Isolates the `3.5–9.5 kHz` sibilance/harshness band (band index 3) so `DeEsserMode` (`OFF / GENTLE / STRONG`) dynamically tames piercing "s/ş" vocals and cymbals while leaving `9.5–20 kHz` air/shimmer completely open. Works independently even when `MbcMode.OFF` (bands 0, 1, 2, 4 run transparent `ratio = 1.0f`).
   - **Mode-Aware Psychoacoustic Reference:** Subtle, bounded per-mode reference offsets around `PINK_REFERENCE_NORM` so `GRAVITY`, `HORIZON`, `ECLIPSE`, and `COMMS` stop fighting their own musical intent.
   - **ISO-226 Perceptual Equal-Loudness:** Nonlinear perceptual volume ramp (`pow(1.35f)` below `70%` volume) with an ISO-226 inspired contour applied smoothly in `PostEQ` (rich sub/bass & gentle upper-presence boost without shrillness or midrange bloat).
   - **31-Band Analog Tone Tilt (`-1.5..+1.5 dB/oct`):** Baxandall-style continuous spectral slope pivoted at `1000 Hz` (clamped at `±4.0` octaves, i.e. `62.5 Hz..16 kHz` shelves, max `±6.0 dB` at `±1.5 dB/oct`) applied in `preEq31Db` for stair-step-free warmth or crispness.
   - **Loudness-Matched A/B Bypass (`loudnessMatchedBypass = true` by default):** Computes an A-weighted perceptual loudness match gain (`[-9.0, 0.0] dB`, strictly attenuate-only so bypass can never clip) while active; when manually bypassed (`!autoBypassedByRule`), flattens PreEQ/PostEQ to `0 dB` and disables MBC while holding the matched input attenuation cleanly via `DspGainSmoother`. Auto-bypassed apps (calls, navigation, podcasts) always perform full true `0 dB` bypass + effect disable.
3. **Parametric Biquad AutoEQ & 36-Model Searchable Offline Catalog:**
   - Extend `AutoEqParser` to parse EqualizerAPO / Oratory1990 / squig.link parametric filters (`Filter [N]: ON|OFF PK|LS|LSC|LSQ|HS|HSC|HSQ Fc <Hz> Gain <dB> [Q <q>]`) alongside existing `GraphicEQ:`, CSV, and whitespace formats.
   - Evaluate exact RBJ biquad magnitude response `20 * log10(|H(e^(jω))|)` at `fs = 48000 Hz` onto all 31 ISO 1/3-octave centers.
   - Expand `AutoEqCatalog` to **36 curated offline models** across `Q30`, `OVER_EAR`, `TWS`, `IEM`, and `STUDIO` categories (including 4 dedicated Soundcore Life Q30 ANC/Transparency/Warm variants) with instant search and category filter chips in `AutoEqImportDialog`.
4. **31-Band Acoustic Visualization & Clean UX:**
   - Publish `liveEqCurve31Db` (31 bands) and `bypassMatchGainDb` in `LiveAudioTelemetry`; render 31-band smooth Bézier total EQ response in `HeroSpectrumCanvas`.
   - Add a clean **Tone & Comfort** card in `MixerScreen` (`Analog Tone Tilt`, `De-Esser`, `Loudness-Matched A/B`), a 31-band mini curve preview for custom profiles in `ActivityScreen`, and catalog search/category filters in `AutoEqImportDialog`.
5. **Backward-Compatible Persistence & Backup (`schemaVersion = 3`):**
   - Seamlessly load v1.0 and v2.0 DataStore preferences and v1/v2 JSON backup files while exporting v3 fields cleanly; preserve deterministic `catalogId` on catalog profile imports so cross-device backup merges never duplicate catalog profiles.
   - Include selected low-risk v2.0 polish items directly on touched modules (`AutoEqParser` post-dedup `MIN_POINTS` check, `OrbitBackupCodec` UTF-8 BOM trim, `DiagnosticsFormatter` `.roundToInt()` balance %, `MixerScreen` `SideEffect` hygiene, `ActivityScreen` paste-tab error placement above scroll box).

### Non-Goals
- Root / Magisk / HAL audio patching or custom native C++/JNI audio engines.
- Android `Virtualizer`, artificial 3D surround, reverb, or stereo-widening phase tricks (deliberately excluded to preserve pure stereo imaging and timbre).
- Online/cloud AutoEQ downloading, user accounts, or telemetry uploads (remains 100% offline).
- Manual 31-slider touch fader wall on a phone screen (31 sliders are unusable on 360dp mobile displays; users tune 31-band compensation via Catalog, GraphicEQ/Parametric import, Analog Tone Tilt, Subsonic, and the existing 10-band Mixer trims).
- Changing CI workflows or enabling R8 minification in this release (kept scoped to audio quality & verification).

### Anti-Goals (Failure even if technically working)
- **Muddying or fatiguing Q30's tonal balance:** Refined Q30 & `NOVA` voicing must stay tighter in mid-bass, clearer in vocals, and smoother in `6–8 kHz` treble than v2.0 — never boomy, nasal, or shrill.
- **Dulling airy sparkle when De-Esser is enabled:** De-Esser must target `3.5–9.5 kHz` without squashing `9.5–20 kHz` high-frequency air.
- **Clipping or boosting during Loudness-Matched Bypass:** Matched bypass gain must strictly be `<= 0.0 dB` (attenuate-only) and must NEVER attenuate auto-bypassed apps (calls/maps/podcasts).
- **Audio dropouts on OEMs rejecting 5-band MBC or 31-band PreEQ:** `DspEffectController` must probe and fall back cleanly (`31/5 -> 10/4 -> LEGACY_EQ -> session 0`) with zero handle leaks.
- **Cluttering Studio or breaking 360dp / `>= 48dp` / zero-emoji / bilingual UI rules.**

---

## 3. Approaches Considered

1. **Approach A — Live Psychoacoustic Engine Only (Rejected):**
   - Upgrades `AdaptiveEqEngine` and `DspEffectController` (mode-aware references, De-Esser, ISO-226 loudness, Tone Tilt, Loudness-Matched A/B) while leaving the 7-model catalog and GraphicEQ-only parser unchanged.
   - *Trade-off:* Leaves out Q30 ANC On/Off/Transparency profiles, Oratory1990 parametric imports, and 31-band response visualization.
2. **Approach B — Headphone Calibration & Parametric Lab Only (Rejected):**
   - Adds RBJ parametric EQ parsing, 36+ searchable catalog, and 31-band visualization while leaving live DSP unchanged from v2.0.
   - *Trade-off:* Misses real-time listening-pleasure upgrades (De-Esser, ISO-226 loudness, Tone Tilt, honest Loudness-Matched A/B).
3. **Approach C — ORBIT EQ v3.0 Full Musical Pleasure & Quality Package (Selected by User):**
   - Combines the high-impact core of A and B in one cohesive `3.0.0` release with a single `schemaVersion = 3` DataStore/Backup update and user-approved acoustic refinements to Q30 & `NOVA` voicing + `DeEsserMode.GENTLE` default.
   - *Trade-off:* Broadest scope (~5–6 modular tasks), managed cleanly with pure-Kotlin DSP math modules and comprehensive unit + Compose tests.

---

## 4. Technical Architecture & Detailed Design

### 4.1 Data Model, Persistence & Backup (`OrbitModels.kt`, `OrbitPreferencesStore.kt`, `OrbitBackupCodec.kt`, `DiagnosticsFormatter.kt`)
- **New Enum:**
  - `enum class DeEsserMode { OFF, GENTLE, STRONG }`
- **New `OrbitSettings` Fields (all with safe v3 musical defaults):**
  - `toneTiltDbPerOct: Float = 0f` (clamped `-1.5f..+1.5f` dB/oct; `0f` = neutral)
  - `deEsserMode: DeEsserMode = DeEsserMode.GENTLE`
  - `loudnessMatchedBypass: Boolean = true`
- **New `LiveAudioTelemetry` Fields:**
  - `liveEqCurve31Db: List<Float> = List(31) { 0f }` (combined `PreEQ31 + interpolated PostEQ10` clamped `[-15f, +15f]`, zeroed when idle/bypassed)
  - `bypassMatchGainDb: Float = 0f` (`[-9.0f, 0.0f]`, active attenuation applied during manual loudness-matched bypass, `0f` otherwise)
  - `mbcBandCount: Int = 5` (`5` on Hi-Res 31/5 `DynamicsProcessing`, `4` on 10/4 fallback, `0` on `LEGACY_EQ`/`NONE`)
- **DataStore Persistence (`OrbitPreferencesStore.kt`):**
  - New preference keys: `tone_tilt_db_per_oct` (`Float`, default `0f`), `de_esser_mode` (`String`, default `DeEsserMode.GENTLE`), `loudness_matched_bypass` (`Boolean`, default `true`).
  - Existing v1/v2 keys load untouched with zero data loss.
- **Backup Codec (`OrbitBackupCodec.kt`):**
  - Bump `CURRENT_SCHEMA_VERSION = 3`; accept `schemaVersion in 1..3` on import (v1/v2 backups default `toneTiltDbPerOct = 0f`, `deEsserMode = DeEsserMode.GENTLE`, `loudnessMatchedBypass = true`).
  - Strip leading UTF-8 BOM (`\uFEFF`) before JSON parsing.
  - Export and merge `toneTiltDbPerOct`, `deEsserMode`, and `loudnessMatchedBypass` alongside all v2 fields (continuing to exclude runtime `powerEnabled`, `bypassed`, `q30TipDismissed`, and `recentActivity`).
- **Repository (`OrbitEngineRepository.kt`):**
  - New mutators: `setToneTiltDbPerOct(tiltDbPerOct: Float)`, `setDeEsserMode(mode: DeEsserMode)`, `setLoudnessMatchedBypass(enabled: Boolean)`.
  - Extend `importCustomHeadphoneProfile(..., preferredId: String? = null)` so catalog imports preserve deterministic `entry.catalogId` (preventing cross-device backup duplicates while still generating random UUIDs for user-imported `.txt`/pasted profiles).
- **Diagnostics (`DiagnosticsFormatter.kt`):**
  - Include `Tone Tilt`, `De-Esser`, `Loudness-Matched A/B`, and `MBC Bands` in formatted diagnostics output; fix balance percentage rounding with `.roundToInt()`.

### 4.2 Live Psychoacoustic Engine (`AdaptiveEqEngine.kt`, `PleasureProfiles.kt`)
1. **Mode-Aware Psychoacoustic Spectral Reference & Refined Q30 / `NOVA` Voicing:**
   - Refined `Q30_CLEAN_BASE_31_CURVE` (`PleasureProfiles.kt`):
     `[0.0f, -0.3f, -0.8f, -1.6f, -2.5f, -3.5f, -4.4f, -5.0f, -5.2f, -4.7f, -3.9f, -3.0f, -1.8f, -0.9f, -0.3f, 0.1f, 0.4f, 0.6f, 0.9f, 1.5f, 2.4f, 3.2f, 3.5f, 2.6f, 0.6f, -2.2f, -2.8f, -0.8f, 0.8f, 1.0f, 0.4f]` (preserves sub-bass extension, cleans mid-bass bloom, restores Q30 vocal presence, tames `6.3–8 kHz` glare, opens `12.5–16 kHz` air).
   - Refined `NOVA_TARGET`: `[3.0f, 3.2f, 0.8f, -0.8f, -0.3f, 0.8f, 1.8f, 2.2f, 2.4f, 2.8f]` (tighter mid-bass transition, smoother 8 kHz, airier 16 kHz).
   - `PleasureProfiles.adaptiveReferenceShiftFor(mode: PleasureMode): FloatArray` (10 bands):
     - `NOVA`: `[0.02f, 0.02f, -0.01f, -0.01f, 0f, 0f, 0.01f, 0f, -0.01f, 0.02f]` (preserves deep sub-bass & upper air while gently curbing mid-bass bloat and 8 kHz glare)
     - `GRAVITY`: `[0.05f, 0.04f, 0.01f, -0.01f, 0f, 0f, 0f, 0f, 0f, 0f]`
     - `HORIZON`: `[0f, 0f, 0f, 0f, 0f, 0f, 0.01f, 0.02f, 0.04f, 0.04f]`
     - `ECLIPSE`: `[0.02f, 0.02f, 0.01f, 0f, 0f, 0f, -0.02f, -0.03f, -0.02f, -0.02f]`
     - `COMMS`: `[-0.03f, -0.02f, 0f, 0.02f, 0.03f, 0.04f, 0.02f, 0f, -0.02f, -0.02f]`
   - In `computePsychoacousticTargetDelta`: `effectiveRef = (PINK_REFERENCE_NORM[bandIndex] + shift[bandIndex] * intensityScale).coerceIn(0.20f, 0.65f)`.
2. **ISO-226 Perceptual Equal-Loudness:**
   - Replace linear ramp (`< 0.65f`) with perceptual curve: when `dynamicLoudness && safeVolume < 0.70f`, `loudnessScale = ((0.70f - safeVolume) / 0.70f).pow(1.35f).coerceIn(0f, 1f)`.
   - Refined ISO-226 10-band loudness weights (`dB` at full `loudnessScale = 1f`):
     `[4.8f, 4.4f, 2.0f, 0.6f, 0.0f, 0.0f, 0.2f, 1.0f, 2.4f, 2.2f]` (deeper natural sub-bass warmth, completely flat `500–1000 Hz` midrange, restrained non-shrill treble compensation).
3. **31-Band Analog Tone Tilt (`PreEQ`):**
   - Pure helper `PleasureProfiles.analogTiltCurve31(tiltDbPerOct: Float): FloatArray`:
     - For each ISO 31-band frequency `f`: `octaves = log2(f / 1000f).coerceIn(-4.0f, 4.0f)`, `gain = clamp(tiltDbPerOct, -1.5f, 1.5f) * octaves`.
     - Added directly into `preEq31Db` alongside headphone compensation, interpolated manual trims, and subsonic filter (clamped `[-15f, +15f]`).
     - Zero tilt (`0.0f`) yields exact zeros.
4. **Adaptive Harshness Assist (`DeEsserMode`):**
   - Primary dynamic de-essing runs in hardware `DynamicsProcessing` MBC Band 3 (`3.5–9.5 kHz`, §4.3).
   - As an acoustic complement (including on `LEGACY_EQ` fallback), when `deEsserMode != DeEsserMode.OFF`, `computePsychoacousticTargetDelta` applies an extra harshness-cut sensitivity multiplier on excess energy (`excess > 0f`) at bands `6 (2 kHz)` and `7 (4 kHz)` and `8 (8 kHz)` (`GENTLE`: `1.15x` at 4/8 kHz; `STRONG`: `1.35x` at 4/8 kHz, `1.10x` at 2 kHz) without increasing cut sensitivity at `16 kHz` (band 9), preserving high air.
5. **31-Band Combined Curve & Loudness-Matched A/B Gain Calculation:**
   - `AdaptiveEqEngine.processFrame(...)` accepts new optional parameters (defaulting to v2-compatible values):
     - `toneTiltDbPerOct: Float = 0f`
     - `deEsserMode: DeEsserMode = DeEsserMode.OFF`
     - `loudnessMatchedBypass: Boolean = true`
     - `isAutoBypassed: Boolean = false`
   - Computes `totalEq31Db[i] = clamp(preEq31Db[i] + postEq31Db[i], -15f, +15f)` for all 31 bands.
   - Computes A-weighted perceptual loudness delta while active (`computePerceivedNetGainDb(totalEq31Db, inputGainDb)`):
     - Uses normalized perceptual midband weights across the 31 ISO bands peaking around `500 Hz – 4 kHz` (approximating ITU-R 468 / A-weighting loudness sensitivity) so sub-bass or 20 kHz extremes don't skew perceived loudness match.
     - `activePerceivedGainDb = inputGainDb + weightedMeanDb(totalEq31Db)`.
     - Stores `lastActiveBypassMatchGainDb = activePerceivedGainDb.coerceIn(-9.0f, 0.0f)` (strictly attenuate-only: if active EQ is already perceptually quieter than 0 dB, match attenuation reduces bypass down to match it; if active EQ is louder or equal, match gain is `0.0f` — never boosts bypass!).
   - When `bypassed == true`:
     - If `loudnessMatchedBypass && !isAutoBypassed && lastActiveBypassMatchGainDb < -0.05f`:
       - Returns `preEq31Db = zeros`, `preEqDb = zeros`, `postEqDb = zeros`, `totalEqDb = zeros`, `totalEq31Db = zeros`, `inputGainDb = lastActiveBypassMatchGainDb`, `bypassMatchGainDb = lastActiveBypassMatchGainDb`, `engineStatus = EngineStatus.BYPASSED`.
     - Else (raw bypass or auto-bypassed app):
       - Returns `inputGainDb = 0f`, `bypassMatchGainDb = 0f`, `engineStatus = EngineStatus.BYPASSED` (identical to v2.0).
   - `EqEngineFrame` adds default-valued `totalEq31Db: FloatArray = FloatArray(31)` and `bypassMatchGainDb: Float = 0f` (with updated `equals`/`hashCode`).

### 4.3 5-Band MBC De-Esser & Loudness-Matched Bypass in `DspEffectController.kt`
- **Fallback Chain Upgrade:**
  - Candidate configs in `DspBackendFactory.createDynamicsProcessing(sessionId, preEqBandCount, mbcBandCount)`:
    1. `(preEqBandCount = 31, mbcBandCount = 5)` — Hi-Res 31-band PreEQ + 5-band MBC (dedicated `3.5–9.5 kHz` De-Esser band + preserved `9.5–20 kHz` air band)
    2. `(preEqBandCount = 10, mbcBandCount = 4)` — Standard 10-band PreEQ + 4-band MBC (v2 fallback)
    3. `createLegacyEqualizer(sessionId)`
    4. Retry on session `0` if `sessionId > 0`.
  - Expose `@Volatile var currentMbcBandCount: Int = 0` alongside `currentPreEqBandCount`.
- **5-Band MBC Frequency Layout (`MBC_5_CUTOFFS_HZ`):**
  - Band 0: `<= 180 Hz` (Sub / Bass)
  - Band 1: `180 – 800 Hz` (Low-Mid / Body)
  - Band 2: `800 – 3500 Hz` (Vocal Midrange)
  - Band 3: `3500 – 9500 Hz` (**Sibilance / Harshness / De-Esser Band**)
  - Band 4: `9500 – 20000 Hz` (**Air / Sparkle Band — protected from De-Esser!**)
- **Combined `(MbcMode, DeEsserMode)` Resolution (`mbcConfigFor(mbcMode, deEsserMode, mbcBandCount)`):**
  - If `mbcMode == OFF && deEsserMode == OFF`: MBC stage disabled (`enabled = false`).
  - If `mbcMode == OFF && deEsserMode != OFF`: MBC stage enabled (`enabled = true`); non-de-esser bands run transparent (`ratio = 1.0f`, `threshold = 0f dB`, `knee = 0f`, `attack = 20 ms`, `release = 100 ms`), while the De-Esser band (index `3` on 5-band MBC, or index `3` on 4-band fallback) runs De-Esser parameters:
    - `GENTLE`: `ratio = 2.4f`, `threshold = -20f dB`, `knee = 6f dB`, `attack = 4f ms`, `release = 55f ms`
    - `STRONG`: `ratio = 3.6f`, `threshold = -24f dB`, `knee = 4f dB`, `attack = 2.5f ms`, `release = 45f ms`
  - If `mbcMode != OFF`: bands run `SUBTLE` or `PUNCHY` values (on 5-band MBC, bands 0..4 use `[1.45, 1.30, 1.30, 1.40, 1.35]` for `SUBTLE` and `[2.0, 1.65, 1.65, 1.85, 1.70]` for `PUNCHY`); if `deEsserMode != OFF`, Band 3 is overridden with the `GENTLE`/`STRONG` De-Esser parameters while Band 4 (`9.5–20 kHz`) retains normal MBC (never over-compressed).
- **Loudness-Matched Bypass Handling in `DspGainSmoother` & `DspEffectController.apply(...)`:**
  - `DspEffectController.apply(frame: EqEngineFrame, mbcMode: MbcMode, deEsserMode: DeEsserMode = DeEsserMode.OFF, active: Boolean)`:
  - When `!active && frame.bypassMatchGainDb < -0.05f` (manual loudness-matched bypass):
    - `DspGainSmoother` smoothly ramps all EQ bands (`preEq31`, `preEq10`, `postEq10`, `totalEq10`) to `0.0f` and smoothly ramps `inputGainDb` toward `frame.bypassMatchGainDb` (with `leftTrim = 0f`, `rightTrim = 0f`), keeping `effectiveActive = true` with `mbcMode = MbcMode.OFF` and `deEsserMode = DeEsserMode.OFF` so `DynamicsProcessing` applies ONLY flat-EQ loudness-matched attenuation with zero clicks!
  - When `!active && frame.bypassMatchGainDb >= -0.05f` (raw bypass or auto-bypassed app):
    - Exact v2.0 behavior: ramps all EQ & input gains proportionally to `0 dB` and sets `effectiveActive = false` once `bypassRamp <= 0.01f`.
  - `DspDirtyTracker` tracks `deEsserMode` alongside `mbcMode` so toggling De-Esser immediately updates native MBC bands while identical frames still emit zero native writes.

### 4.4 RBJ Biquad Parametric EQ Parser & 36-Model Searchable Catalog (`ParametricBiquadEvaluator.kt`, `AutoEqParser.kt`, `AutoEqCatalog.kt`)
- **`ParametricBiquadEvaluator.kt` (Pure Kotlin, Unit-Tested):**
  - Supports RBJ Audio EQ Cookbook filter types at `sampleRateHz = 48000.0`:
    - `PK` (Peaking EQ: `A = 10^(gainDb/40)`, `w0 = 2π·fc/fs`, `alpha = sin(w0)/(2·Q)`)
    - `LS` / `LSC` / `LSQ` (Low-Shelf EQ: standard RBJ shelf with `Q`, default `Q = 0.7071` if omitted)
    - `HS` / `HSC` / `HSQ` (High-Shelf EQ: standard RBJ shelf with `Q`, default `Q = 0.7071` if omitted)
  - Computes exact digital transfer function magnitude at frequency `f`:
    `H(e^(jω)) = (b0 + b1·e^(-jω) + b2·e^(-2jω)) / (a0 + a1·e^(-jω) + a2·e^(-2jω))`
    returning `20 * log10(|H|)` in dB (double precision throughout to avoid numerical noise at `20 Hz`).
  - Evaluates the summed response of a list of parametric filters at all 31 ISO 1/3-octave frequencies (`PleasureProfiles.ISO_31_FREQUENCIES_HZ`), clamping final values to `[-15f, +15f]`.
- **`AutoEqParser.kt` Upgrades:**
  - Auto-detects format from input text:
    - If any non-comment line matches EqualizerAPO parametric syntax (`Filter ... : ON|OFF ...`), parses as **Parametric EQ**:
      - Accepts `Filter 1: ON PK Fc 105 Hz Gain -5.2 dB Q 0.71`, `Filter: ON LSC Fc 105 Hz Gain 3.5 dB Q 0.70`, `Filter 2: ON LS Fc 105 Hz Gain 3.0 dB` (optional `Q` defaults to `0.7071` for shelves; `Q` required and `> 0` for `PK`), `Filter 3: OFF PK ...` (ignored safely), and `Preamp: ...` (ignored safely, as ORBIT EQ computes dynamic headroom automatically).
      - Rejects unsupported/malformed filter lines, `Fc !in 10..24000`, non-finite gains, `Q !in 0.05..30.0`, or zero active `ON` filters with `AutoEqParseResult.Error(...)`.
      - Returns `AutoEqParseResult.Success(gains31Db, pointCount = activeFilters.size, sourceLabel = "Parametric EQ")`.
    - Otherwise parses as GraphicEQ / CSV / whitespace (existing behavior), fixing the v2 minor issue by enforcing `deduplicated.size >= MIN_POINTS` **after** deduplication! Returns `sourceLabel = "AutoEQ"`.
- **`AutoEqCatalog.kt` Expansion (36 Curated Offline Models):**
  - Add `enum class CatalogCategory { Q30, OVER_EAR, TWS, IEM, STUDIO }` and `val brand: String`, `val category: CatalogCategory` on `CatalogHeadphoneEntry`.
  - Include **36 curated Harman-calibrated 31-band models** (all gains strictly finite in `[-12f, +12f]`), starting with 4 Soundcore Life Q30 profiles:
    1. `Soundcore Life Q30 (ANC On · Oratory1990)` (`Q30`)
    2. `Soundcore Life Q30 (ANC Off · Harman)` (`Q30`)
    3. `Soundcore Life Q30 (Transparency · Harman)` (`Q30`)
    4. `Soundcore Life Q30 (Warm Vocal · Zero-Fatigue)` (`Q30`)
    - Plus 32 widely-used models across Soundcore (`Space Q45`, `Space One`, `Life Q35`, `Life Q20`, `Liberty 4 NC`), Sony (`WH-1000XM3`, `WH-1000XM4`, `WH-1000XM5`, `WF-1000XM4`, `WF-1000XM5`, `ULT Wear`), Apple (`AirPods Pro 2`, `AirPods Max`, `AirPods 3`), Samsung (`Galaxy Buds2 Pro`, `Galaxy Buds3 Pro`, `Galaxy Buds FE`, `Galaxy Buds+`), Sennheiser (`HD 600`, `HD 650`, `HD 560S`, `Momentum 4`), Beyerdynamic (`DT 770 Pro 80 Ohm`, `DT 990 Pro`), Bose (`QuietComfort 45`, `QuietComfort Ultra`), JBL (`Tune 720BT`, `Live 660NC`), Audio-Technica (`ATH-M50x`), Moondrop (`Chu II`, `Aria 2`), Truthear (`Zero:Red`, `Hexa`).
  - Add pure helper `AutoEqCatalog.filter(query: String, category: CatalogCategory? = null): List<CatalogHeadphoneEntry>` (case-insensitive substring match across `name` and `brand`, unit-tested).

### 4.5 UI Upgrades & Bilingual Localization (`StudioScreen.kt`, `HeroSpectrumCanvas.kt`, `MixerScreen.kt`, `ActivityScreen.kt`, `strings.xml`, `values-tr/strings.xml`)
- **Studio Tab (`HeroSpectrumCanvas.kt`, `StudioScreen.kt`):**
  - Keep Studio clean and uncluttered.
  - Upgrade `HeroSpectrumCanvas` to accept `liveEqCurve31Db: List<Float>` (31 bands) and render the live combined neon EQ curve through all 31 log-spaced ISO positions when available (falling back cleanly to 10-band if unavailable).
  - When manually bypassed with `loudnessMatchedBypass == true && telemetry.bypassMatchGainDb < -0.05f`, display a subtle compact chip/readout indicating loudness-matched bypass attenuation (e.g., `A/B · -3.1 dB`).
- **10-Bant (Mixer) Tab (`MixerScreen.kt`):**
  - Add a compact **"Tonal Karakter & Konfor" (`Tone & Comfort`)** card between `MbcPunchCard` and `ChannelBalanceCard`:
    - **Analog Ton Eğimi (`Tone Tilt`)**: slider `-1.5f..+1.5f` dB/oct quantized to `0.1f` steps (`steps`/local drag state, snapping `<= 0.08f` to `0f` with haptic tick), tabular label (`Sıcak -0.6 dB/okt` / `Düz 0.0` / `Berrak +0.6 dB/okt`), and `48dp` Center Reset button.
    - **Sertlik & Sibilans Koruması (`De-Esser`)**: 3-way segmented selector (`KAPALI / YUMUŞAK / GÜÇLÜ` | `OFF / GENTLE / STRONG`, `heightIn(min = 48.dp)`).
    - **Ses Eşitlenmiş A/B (`Loudness-Matched A/B`)**: `>= 48dp` switch row explaining honest volume-matched bypass comparison.
  - Clean up `ChannelBalanceCard` to update `lastEmittedBalance` inside `SideEffect` rather than directly during composition.
- **Aktivite Tab (`ActivityScreen.kt`):**
  - **`AutoEqImportDialog` (Catalog Tab):** Add an instant search field (`OutlinedTextField`, `>= 48dp`) and horizontally scrollable category filter chips (`Tümü`, `Q30`, `Kafa Üstü`, `TWS`, `IEM`, `Stüdyo`) powered by `AutoEqCatalog.filter(...)`, showing model count & brand cleanly.
  - **`AutoEqImportDialog` (Paste Tab):** Update hint & helper copy to document both `GraphicEQ:` and EqualizerAPO Parametric (`Filter 1: ON PK Fc ... Gain ... Q ...`) formats; place the inline validation error above the scrollable area so it remains visible even with the IME open.
  - **`HeadphoneCompensationCard`:** Render a sleek, compact **31-band mini curve sparkline** (`Canvas`, `28dp` height, zero extra deps) for the active custom 31-band profile so users can visually see its bass/mid/treble correction curve at a glance.
  - **`LiveEngineTelemetryCard`:** Show `31-Bant PreEQ · 5-Bant MBC` (or actual `preEqBandCount` / `mbcBandCount` fallback).
- **Bilingual Strings (`values/strings.xml`, `values-tr/strings.xml`):**
  - 100% key parity, zero emojis, natural Turkish + English terminology.

---

## 5. Verification Strategy

1. **Unit Tests (`:app:testDebugUnitTest`):**
   - **New tests:**
     - `ParametricBiquadEvaluatorTest`: verifies RBJ `PK` center-frequency gain accuracy (`±0.05 dB`), `Q` bandwidth selectivity, `LS`/`LSC` low-shelf DC & transition behavior, `HS`/`HSC` high-shelf Nyquist & transition behavior, multi-filter summation, and non-finite/edge-case guards.
   - **Extended tests:**
     - `AutoEqParserTest`: parses full Oratory1990 EqualizerAPO parametric files (`Preamp`, `ON`/`OFF` filters, `PK`/`LSC`/`HSC`, optional `Hz`/`dB`, optional shelf `Q`), rejects invalid `Q <= 0` / malformed lines / zero active filters, and rejects post-dedup `< 3` points.
     - `AutoEqCatalogTest`: verifies all 36 entries have unique IDs/names, 31 finite gains in `[-12f, +12f]`, all 4 Soundcore Q30 variants present, and `AutoEqCatalog.filter(query, category)` accuracy.
     - `AdaptiveEqEngineTest`: verifies mode-aware reference shifts (`NOVA` refined sub-bass/mid-bass/air balance, `GRAVITY` bass tolerance, `HORIZON` air tolerance), ISO-226 nonlinear loudness curve (`vol = 0.15, 0.45, 0.75`), 31-band Analog Tone Tilt symmetry around `1 kHz`, De-Esser harshness assist without dulling 16 kHz, 31-band `totalEq31Db` output, and Loudness-Matched A/B Bypass gain calculation (attenuate-only `<= 0 dB`, `0 dB` when `isAutoBypassed = true` or `loudnessMatchedBypass = false`).
     - `DspEffectControllerTest`: verifies `31/5 -> 10/4 -> LEGACY_EQ -> session 0` fallback chain, 5-band MBC De-Esser band-3 isolation (both with `MbcMode.OFF` and `SUBTLE`/`PUNCHY`), dirty tracking on `DeEsserMode` changes, and Loudness-Matched Bypass keeping flat EQ + matched input attenuation with smooth click-free ramping.
     - `OrbitBackupCodecTest` & `OrbitEngineRepositoryTest` & `DiagnosticsFormatterTest`: verifies `schemaVersion = 3` backup round-trip, v1/v2 backward-compatible import, UTF-8 BOM handling, deterministic catalog profile IDs preventing cross-device duplicates, v3 repository mutators, and `.roundToInt()` diagnostics formatting.
2. **Compose UI Tests (`app/src/androidTest/java/com/orbiteq/app/OrbitUiSmokeTest.kt`):**
   - Covers `StudioScreen` (31-band curve telemetry + matched bypass chip), `MixerScreen` (Tone Tilt, De-Esser 3-way selector, Loudness-Matched A/B switch), and `ActivityScreen` (Catalog search & category filtering + Parametric paste hint); verified via `:app:compileDebugAndroidTestKotlin`.
3. **Release Build & Integrity Verification:**
   - `.\gradlew.bat :app:testDebugUnitTest --rerun :app:compileDebugAndroidTestKotlin :app:assembleRelease --console=plain` confirming all tests pass and signed `v3.0.0` (`versionCode = 3`) APK builds cleanly.

---

## 6. Left to the Builder

- Exact 31-band Harman compensation arrays for the 32 newly added catalog headphones (derived from well-established public AutoEQ / Harman Over-Ear & In-Ear target tendencies, strictly finite and clamped within `[-12f, +12f] dB`), plus exact curve shapes for the 4 Soundcore Life Q30 variants (respecting Q30's documented ANC On mid-bass hump, ANC Off leaner low end, Transparency brighter upper-mids, and Warm Vocal treble softening).
- Exact visual styling, stroke widths, and spacing for the 31-band mini sparkline preview in `HeadphoneCompensationCard` and the Tone & Comfort card in `MixerScreen`, while maintaining `>= 48dp` touch targets, `360dp` compact width safety, zero emojis, and Deep Space HUD / Lunar Monochrome theme tokens.
- Exact A-weighting / perceptual loudness weight vector across the 31 ISO bands (provided it peaks in `500 Hz – 4 kHz`, sums to `1.0`, and is strictly deterministic and unit-tested).
