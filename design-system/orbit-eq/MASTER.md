# Design System Master File

> **LOGIC:** When building a specific page or component in ORBIT EQ, strictly follow these verified Deep Space HUD & Lunar Monochrome design tokens and Android Jetpack Compose rules.

---

**Project:** ORBIT EQ
**Pattern:** Immersive / Interactive Audio Console HUD
**Style:** HUD / Sci-Fi Aerospace FUI (Space Black / Star White / Launch & Ion Blue)
**Platform:** Android (Jetpack Compose, OLED-first, minSdk 28, targetSdk 35)

---

## Global Rules

### 1. Primary Theme — Deep Space HUD (`OrbitThemeMode.DEEP_SPACE_HUD`)

| Role | Hex / Alpha | Token Name | Contrast vs Card (`#0D1322`) |
|------|-------------|------------|------------------------------|
| Background (OLED Void Black) | `#03050A` | `background` | — |
| Surface (Deep Space) | `#0B0F1A` | `surface` | — |
| Elevated Glass Card | `#0D1322` (`0.78f` alpha) | `surfaceElevated` | — |
| Hairline Border (Default) | `#1E293B` (`1.dp`) | `borderHairline` | Non-text divider |
| Hairline Border (Active) | `#38BDF8` (`0.55f` alpha, `1.dp`) | `borderActive` | Active focus glow |
| Primary Text (Star White) | `#F8FAFC` | `textPrimary` | ~17.7:1 (>= 4.5:1) |
| Secondary Text (Lunar Silver) | `#94A3B8` | `textSecondary` | ~7.2:1 (>= 4.5:1) |
| Muted Telemetry Label | `#8093AC` | `textMuted` | ~5.9:1 (>= 4.5:1) |
| Primary Accent (Launch Blue) | `#3B82F6` | `primaryAccent` | ~5.0:1 vs card |
| On Primary Accent | `#03050A` | `onPrimaryAccent` | ~5.5:1 on `#3B82F6` |
| Active Energy (Ion Cyan) | `#38BDF8` | `ionAccent` | ~8.6:1 vs card |
| Deep Orbit Blue | `#1D4ED8` | `deepAccent` | Atmospheric gradient |
| Destructive / Bypassed | `#EF4444` | `danger` | ~4.9:1 vs card |

### 2. Secondary Theme — Lunar Monochrome (`OrbitThemeMode.LUNAR_MONOCHROME`)

| Role | Hex / Alpha | Token Name | Rule |
|------|-------------|------------|------|
| Background (Pure OLED Black) | `#000000` | `background` | True OLED black |
| Surface | `#09090B` | `surface` | Dark obsidian |
| Elevated Glass Card | `#121216` (`0.90f` alpha) | `surfaceElevated` | High separation |
| Hairline Border (Default) | `rgba(255,255,255,0.18)` | `borderHairline` | `1.dp` crisp edge |
| Hairline Border (Active) | `#FFFFFF` | `borderActive` | Pure white active frame |
| Primary Text & Active Chips | `#FFFFFF` | `textPrimary` / `primaryAccent` | On-white text `#000000` (21:1) |
| Secondary Text | `#A1A1AA` | `textSecondary` | ~7.1:1 vs card |
| Muted Telemetry Label | `#8E8E98` | `textMuted` | ~5.7:1 (>= 4.5:1) |
| Live Audio Accent ONLY | `#60A5FA` / `#2563EB` | `liveAudioPrimary` / `liveAudioSecondary` | Cobalt blue used strictly for live FFT, live EQ curve & live fader offset |

### 3. Bundled Typography (`res/font/`)

- **Headings / Mode Titles:** `SpaceGroteskFamily` (`space_grotesk_semibold.ttf` 600, `space_grotesk_bold.ttf` 700), tight tracking `-0.5.sp`
- **Body / Controls:** `DmSansFamily` (`dm_sans_regular.ttf` 400, `dm_sans_medium.ttf` 500, `dm_sans_semibold.ttf` 600)
- **Live Telemetry & Numeric Readouts:** `SpaceMonoFamily` (`space_mono_regular.ttf` 400, `space_mono_bold.ttf` 700) with mandatory `fontFeatureSettings = "tnum"` (`SpaceMonoTabularStyle`) on **all** dB, Hz, %, Preamp, Loudness, and Limiter readouts so live updates never jitter horizontally.

### 4. Spacing Rhythm & Corner Radii

- **4dp / 8dp Spacing System:** `4.dp` (xs), `8.dp` (sm), `12.dp` (md-compact), `16.dp` (md), `20.dp` (lg), `24.dp` (xl)
- **Surface Radii:** Hero HUD card `20.dp`, standard glass card `16.dp`, pill badges/buttons `999.dp` or `12.dp`
- **Touch Target Minimum:** All interactive controls `>= 48.dp x 48.dp` (`Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`), minimum `8.dp` gap between adjacent targets.

### 5. Iconography Discipline (`OrbitIcons`)

- **ZERO EMOJIS:** 100% custom Compose `ImageVector` vectors in `com.orbiteq.app.ui.icons.OrbitIcons`.
- **Uniform Stroke:** `1.8f` stroke width on a `24f x 24f` viewport with `StrokeCap.Round` and `StrokeJoin.Round`.
- **Contextual Semantics:** Decorative icons alongside visible text pass `contentDescription = null`; standalone icon actions supply clear localized `contentDescription` and toggle/selected state semantics.

### 6. Motion, Reduced Motion & Haptics

- **60fps Canvas Loop:** `CosmicBackdrop` starfield drift and `HeroSpectrumCanvas` FFT/EQ interpolation run smoothly in Compose Draw/Frame phases without per-frame allocations or layout thrashing.
- **Reduced Motion Compliance:** Observe `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`. When enabled, pause continuous star drift and render a crisp static starfield + calm instantaneous curve updates.
- **Haptic Tactile Feedback:** Use `LocalHapticFeedback` (`HapticFeedbackType.TextHandleMove`) on `OrbitalDial` 5% detent steps and `HybridBandFader` `0.0 dB` magnetic center snap.

### 7. Honest Audio Engine Telemetry

- **Hero Top HUD:** Status badge (`AUTO FFT · LOCKED` / `PROFILE FALLBACK` / `A/B BYPASSED` / `ENGINE · IDLE`), `LOUD +X.X dB` / `LOUD OFF`, and active `MODE`.
- **Hero Bottom Telemetry:** Exact `PREAMP -X.X dB` headroom attenuation bar + static `LIM -0.5 dB` True-Peak Limiter active ceiling badge. Never fabricate or estimate compressor gain reduction (GR).

---

## Component Specs

- **`CosmicBackdrop`:** Void/OLED backdrop, mode-tinted atmospheric radial nebula glow, 3-depth parallax starfield + faint orbital arcs, reduced-motion static fallback.
- **`HeroSpectrumCanvas`:** Glass HUD card (`1.dp` hairline border), 3-layer ISO log-frequency & dB reference grid, 36-bin FFT spectrum bars, dashed target Bezier spline, glowing live EQ Bezier spline with 10 orbital band nodes + manual trim stems, honest `PREAMP` bar & `LIM -0.5 dB` badge.
- **`OrbitalDial`:** 270° cockpit arc gauge with 21 tick marks, glowing progress arc, circular drag (dead-zone wrap-safe) + tap-arc stepping + `48.dp` accessible `-` / `+` step buttons + TalkBack `ProgressBarRangeInfo` & `setProgress`.
- **`HybridBandFader`:** `>= 48.dp` wide fader column with `0 dB` datum crosshair, live blue animated auto-offset bar, draggable white manual trim thumb (`-8.0..+8.0 dB`) with `0.0 dB` magnetic snap + double-tap-to-zero, vertical-slop isolation for horizontal scroll compatibility, and `setProgress` semantics.

---

## Avoid (Anti-Patterns)

- Using Unicode emojis anywhere in UI labels, buttons, or headers.
- Low-contrast gray telemetry labels below `4.5:1` on OLED dark cards.
- Non-tabular numbers causing layout jitter in live dB/Hz/% readouts.
- Ignoring `ANIMATOR_DURATION_SCALE == 0f` on continuous Canvas loops.
- Allowing horizontal/vertical drag controls to fight parent scroll containers.
- Reporting synthetic gain-reduction numbers when Android `DynamicsProcessing` exposes no GR readback.
