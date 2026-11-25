package com.orbiteq.app.model

import androidx.compose.runtime.Immutable
import com.orbiteq.app.dsp.PleasureProfiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.math.log2
import kotlin.math.sqrt

class OrbitModelsTest {

    @Test
    fun enumMembersMatchSpecificationExactly() {
        assertEquals(
            listOf("NOVA", "GRAVITY", "HORIZON", "ECLIPSE", "COMMS"),
            PleasureMode.entries.map { it.name },
        )
        assertEquals(
            listOf("CHILL", "BALANCED", "REACTIVE"),
            ResponseSpeed.entries.map { it.name },
        )
        assertEquals(
            listOf("Q30_CLEAN_BASE", "Q30_SAFE_STACK", "FLAT_GENERIC", "PHONE_SPEAKER_SAFE"),
            HeadphoneProfile.entries.map { it.name },
        )
        assertEquals(
            listOf("OFF", "SUBTLE", "PUNCHY"),
            MbcMode.entries.map { it.name },
        )
        assertEquals(
            listOf("OFF", "GENTLE", "STRONG"),
            DeEsserMode.entries.map { it.name },
        )
        assertEquals(
            listOf("DEEP_SPACE_HUD", "LUNAR_MONOCHROME"),
            OrbitThemeMode.entries.map { it.name },
        )
        assertEquals(
            listOf("IDLE", "AUTO_FFT_LOCKED", "PROFILE_FALLBACK", "BYPASSED"),
            EngineStatus.entries.map { it.name },
        )
        assertEquals(
            listOf("NONE", "DYNAMICS_PROCESSING", "LEGACY_EQ"),
            DspBackend.entries.map { it.name },
        )
        assertEquals(
            listOf("AUTO", "GLOBAL_ONLY"),
            SessionAttachmentMode.entries.map { it.name },
        )
        assertEquals(
            listOf("USE_MODE", "BYPASS"),
            AppRuleAction.entries.map { it.name },
        )
        assertEquals(
            listOf("MANUAL_OVERRIDE", "PINNED_TRACK", "APP_RULE", "DEVICE_DEFAULT", "GLOBAL_DEFAULT"),
            ModeResolutionSource.entries.map { it.name },
        )
    }

    @Test
    fun defaultOrbitSettingsMatchExpectedInvariants() {
        val settings = OrbitSettings()

        assertFalse(settings.powerEnabled)
        assertFalse(settings.bypassed)
        assertEquals(PleasureMode.NOVA, settings.selectedMode)
        assertEquals(78, settings.intensityPercent)
        assertEquals(2.0f, settings.bassMacroDb, 0.0001f)
        assertEquals(1.5f, settings.airMacroDb, 0.0001f)
        assertTrue(settings.dynamicLoudness)
        assertTrue(settings.mbcEnabled)
        assertEquals(MbcMode.SUBTLE, settings.mbcMode)
        assertTrue(settings.subsonicFilterEnabled)
        assertFalse(settings.trueHeadroomMode)
        assertEquals(0f, settings.channelBalance, 0.0001f)
        assertEquals(ResponseSpeed.BALANCED, settings.responseSpeed)
        assertEquals(10, settings.manualTrimsDb.size)
        assertTrue(settings.manualTrimsDb.all { it == 0f })
        assertEquals(OrbitThemeMode.DEEP_SPACE_HUD, settings.themeMode)
        assertEquals(SessionAttachmentMode.AUTO, settings.sessionMode)
        assertFalse(settings.q30TipDismissed)
        assertTrue(settings.appRules.isEmpty())
        assertTrue(settings.pinnedTracks.isEmpty())
        assertTrue(settings.deviceProfiles.isEmpty())
        assertTrue(settings.deviceDefaultModes.isEmpty())
        assertTrue(settings.customPresets.isEmpty())
        assertTrue(settings.customHeadphoneProfiles.isEmpty())
        assertTrue(settings.deviceCustomProfileIds.isEmpty())
        assertEquals(0f, settings.toneTiltDbPerOct, 0.0001f)
        assertEquals(DeEsserMode.GENTLE, settings.deEsserMode)
        assertTrue(settings.loudnessMatchedBypass)
    }

    @Test
    fun defaultTelemetryAndUiStateMatchExpectedInvariants() {
        val telemetry = LiveAudioTelemetry()
        assertEquals(EngineStatus.IDLE, telemetry.engineStatus)
        assertEquals(DspBackend.NONE, telemetry.dspBackend)
        assertEquals(0, telemetry.activeSessionId)
        assertEquals(48000, telemetry.sampleRateHz)
        assertEquals(36, telemetry.spectrumBins36.size)
        assertTrue(telemetry.spectrumBins36.all { it == 0f })
        assertEquals(10, telemetry.liveEqCurveDb.size)
        assertTrue(telemetry.liveEqCurveDb.all { it == 0f })
        assertEquals(31, telemetry.liveEqCurve31Db.size)
        assertTrue(telemetry.liveEqCurve31Db.all { it == 0f })
        assertEquals(10, telemetry.adaptiveDeltaDb.size)
        assertTrue(telemetry.adaptiveDeltaDb.all { it == 0f })
        assertEquals(0f, telemetry.preampAttenuationDb, 0.0001f)
        assertEquals(0f, telemetry.loudnessBoostDb, 0.0001f)
        assertEquals(0f, telemetry.bypassMatchGainDb, 0.0001f)
        assertEquals(-0.5f, telemetry.limiterCeilingDb, 0.0001f)
        assertEquals("Balanced", telemetry.dominantSpectralTag)
        assertEquals(10, telemetry.preEqBandCount)
        assertEquals(5, telemetry.mbcBandCount)
        assertEquals("", telemetry.activeCompensationName)

        val uiState = OrbitUiState()
        assertEquals(OrbitSettings(), uiState.settings)
        assertEquals(PleasureMode.NOVA, uiState.activeMode)
        assertEquals(ModeResolutionSource.GLOBAL_DEFAULT, uiState.modeSource)
        assertNull(uiState.manualOverrideMode)
        assertFalse(uiState.autoBypassedByRule)
        assertEquals(HeadphoneProfile.FLAT_GENERIC, uiState.activeHeadphoneProfile)
        assertNull(uiState.activeCustomHeadphoneProfile)
        assertEquals(NowPlayingInfo(), uiState.nowPlaying)
        assertFalse(uiState.nowPlaying.isPlaying)
        assertEquals(ConnectedOutputDevice(), uiState.connectedDevice)
        assertTrue(uiState.connectedDevice.isSpeaker)
        assertFalse(uiState.connectedDevice.isBluetooth)
        assertFalse(uiState.connectedDevice.isSoundcoreQ30)
        assertEquals(LiveAudioTelemetry(), uiState.telemetry)
        assertTrue(uiState.recentActivity.isEmpty())

        val customProfile = CustomHeadphoneProfile(
            id = "custom-1",
            name = "Test AutoEQ",
            gains31Db = List(31) { 0f },
        )
        assertEquals(0f, customProfile.extraHeadroomDb, 0.0001f)
        assertEquals("AutoEQ", customProfile.sourceLabel)
    }

    @Test
    fun allModelDataClassesHaveFinalFieldsAndCopyImmutability() {
        val modelClasses = listOf(
            AppRule::class.java,
            TrackActivityEntry::class.java,
            CustomPreset::class.java,
            CustomHeadphoneProfile::class.java,
            NowPlayingInfo::class.java,
            ConnectedOutputDevice::class.java,
            OrbitSettings::class.java,
            LiveAudioTelemetry::class.java,
            OrbitUiState::class.java,
        )

        modelClasses.forEach { clazz ->
            val instanceFields = clazz.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            assertTrue("${clazz.simpleName} should have instance fields", instanceFields.isNotEmpty())
            instanceFields.forEach { field ->
                assertTrue(
                    "${clazz.simpleName}.${field.name} must be final (val)",
                    Modifier.isFinal(field.modifiers),
                )
            }
            // Verify @Immutable annotation binary descriptor exists in compiled class metadata.
            val classBytes = clazz.getResourceAsStream("${clazz.simpleName}.class")?.use { it.readBytes() }
            assertTrue(
                "${clazz.simpleName} bytecode must carry @Immutable annotation",
                classBytes != null &&
                    String(classBytes, Charsets.ISO_8859_1)
                        .contains("Landroidx/compose/runtime/Immutable;"),
            )
        }

        // Verify copy creates independent snapshot without altering original.
        val original = OrbitSettings()
        val updated = original.copy(
            powerEnabled = true,
            selectedMode = PleasureMode.GRAVITY,
            manualTrimsDb = List(10) { i -> if (i == 0) 3.0f else 0f },
        )
        assertFalse(original.powerEnabled)
        assertEquals(PleasureMode.NOVA, original.selectedMode)
        assertEquals(0f, original.manualTrimsDb[0], 0.0001f)
        assertTrue(updated.powerEnabled)
        assertEquals(PleasureMode.GRAVITY, updated.selectedMode)
        assertEquals(3.0f, updated.manualTrimsDb[0], 0.0001f)
        // Reference Compose @Immutable explicitly to pin its import in tests without kotlin-reflect.
        assertEquals(
            java.lang.annotation.RetentionPolicy.CLASS,
            Immutable::class.java.getAnnotation(java.lang.annotation.Retention::class.java)?.value,
        )
    }

    @Test
    fun pleasureProfiles31BandCurvesAndHelpersMatchSpecification() {
        val expectedFreqs31 = floatArrayOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
            200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
            2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f,
        )
        assertArrayEquals(expectedFreqs31, PleasureProfiles.ISO_31_FREQUENCIES_HZ, 0.0001f)

        val cutoffs = PleasureProfiles.ISO_31_UPPER_CUTOFFS_HZ
        assertEquals(31, cutoffs.size)
        assertEquals(sqrt(20f * 25f), cutoffs[0], 0.01f)
        assertEquals(sqrt(1000f * 1250f), cutoffs[17], 0.01f)
        assertEquals(sqrt(16000f * 20000f), cutoffs[29], 0.01f)
        assertEquals(20000f, cutoffs[30], 0.0001f)
        for (i in 0 until cutoffs.lastIndex) {
            assertTrue("Cutoffs must strictly increase", cutoffs[i] < cutoffs[i + 1])
        }

        HeadphoneProfile.entries.forEach { profile ->
            val curve = PleasureProfiles.compensationCurve31For(profile)
            assertEquals(31, curve.size)
            assertTrue(curve.all { it.isFinite() && it in -15f..15f })
            val before = curve[0]
            curve[0] = 99f
            assertEquals(before, PleasureProfiles.compensationCurve31For(profile)[0], 0.0001f)
        }
        assertArrayEquals(
            FloatArray(31),
            PleasureProfiles.compensationCurve31For(HeadphoneProfile.FLAT_GENERIC),
            0.0001f,
        )

        val q30Curve31 = PleasureProfiles.compensationCurve31For(HeadphoneProfile.Q30_CLEAN_BASE)
        // Sculpt Q30 mid-bass bloom (80-250 Hz), boost recessed 2.5-4 kHz upper mids, tame 6.3-8 kHz peak
        assertTrue(q30Curve31[8] < -3.0f) // 125 Hz mid-bass cut
        assertTrue(q30Curve31[22] > 2.0f) // 3150 Hz upper-mid lift
        assertTrue(q30Curve31[26] < 0f) // 8000 Hz resonance tame

        // interpolate10To31 preserves flat & log2-tilted curves and sanitizes non-finite entries
        val flat10 = FloatArray(10) { -2.5f }
        assertArrayEquals(FloatArray(31) { -2.5f }, PleasureProfiles.interpolate10To31(flat10), 0.0001f)

        val tilted10 = FloatArray(10) { idx ->
            log2(PleasureProfiles.ISO_FREQUENCIES_HZ[idx].toFloat() / 1000f)
        }
        val tilted31 = PleasureProfiles.interpolate10To31(tilted10)
        assertEquals(31, tilted31.size)
        // Flat edge extension below 31 Hz and above 16000 Hz
        assertEquals(tilted10[0], tilted31[0], 0.0001f) // 20 Hz
        assertEquals(tilted10[0], tilted31[1], 0.0001f) // 25 Hz
        assertEquals(tilted10[9], tilted31[30], 0.0001f) // 20000 Hz
        // Exact at matching 10-band frequencies (125..16000 Hz)
        assertEquals(0.0f, tilted31[17], 0.0001f) // 1000 Hz
        assertEquals(1.0f, tilted31[20], 0.0001f) // 2000 Hz
        assertEquals(log2(315f / 1000f), tilted31[12], 0.001f)

        val dirty10 = FloatArray(10) { i ->
            when (i) {
                0 -> Float.NaN
                5 -> Float.POSITIVE_INFINITY
                9 -> Float.NEGATIVE_INFINITY
                else -> 2.0f
            }
        }
        val clean31 = PleasureProfiles.interpolate10To31(dirty10)
        assertTrue(clean31.all { it.isFinite() })
        assertEquals(0.0f, clean31[0], 0.0001f)
        assertEquals(0.0f, clean31[17], 0.0001f)
        assertEquals(0.0f, clean31[30], 0.0001f)

        // sample31To10 preserves flat curve, exact anchors, and sanitizes non-finite entries
        assertArrayEquals(FloatArray(10) { 3.5f }, PleasureProfiles.sample31To10(FloatArray(31) { 3.5f }), 0.0001f)
        val sampledTilted = PleasureProfiles.sample31To10(tilted31)
        assertEquals(0.0f, sampledTilted[5], 0.0001f) // 1000 Hz
        assertEquals(1.0f, sampledTilted[6], 0.0001f) // 2000 Hz
        val dirty31 = FloatArray(31) { Float.NaN }
        assertArrayEquals(FloatArray(10), PleasureProfiles.sample31To10(dirty31), 0.0001f)

        // nextMode cycles NOVA -> GRAVITY -> HORIZON -> ECLIPSE -> COMMS -> NOVA
        var mode = PleasureMode.NOVA
        val visited = mutableListOf<PleasureMode>()
        repeat(5) {
            mode = PleasureProfiles.nextMode(mode)
            visited.add(mode)
        }
        assertEquals(
            listOf(
                PleasureMode.GRAVITY,
                PleasureMode.HORIZON,
                PleasureMode.ECLIPSE,
                PleasureMode.COMMS,
                PleasureMode.NOVA,
            ),
            visited,
        )
    }

    @Test
    fun pleasureProfilesV3VoicingTiltAndAdaptiveShiftsMatchSpecification() {
        val expectedQ30_31 = floatArrayOf(
            0.0f, -0.3f, -0.8f, -1.6f, -2.5f, -3.5f, -4.4f, -5.0f, -5.2f, -4.7f,
            -3.9f, -3.0f, -1.8f, -0.9f, -0.3f, 0.1f, 0.4f, 0.6f, 0.9f, 1.5f,
            2.4f, 3.2f, 3.5f, 2.6f, 0.6f, -2.2f, -2.8f, -0.8f, 0.8f, 1.0f, 0.4f,
        )
        assertArrayEquals(
            expectedQ30_31,
            PleasureProfiles.compensationCurve31For(HeadphoneProfile.Q30_CLEAN_BASE),
            0.0001f,
        )

        val expectedNova = floatArrayOf(3.0f, 3.2f, 0.8f, -0.8f, -0.3f, 0.8f, 1.8f, 2.2f, 2.4f, 2.8f)
        assertArrayEquals(expectedNova, PleasureProfiles.targetCurveFor(PleasureMode.NOVA), 0.0001f)

        PleasureMode.entries.forEach { pleasureMode ->
            val shift = PleasureProfiles.adaptiveReferenceShiftFor(pleasureMode)
            assertEquals(10, shift.size)
            assertTrue(shift.all { it.isFinite() })
            val before = shift[0]
            shift[0] = 99f
            assertEquals(before, PleasureProfiles.adaptiveReferenceShiftFor(pleasureMode)[0], 0.0001f)
        }
        assertArrayEquals(
            floatArrayOf(0.02f, 0.02f, -0.01f, -0.01f, 0f, 0f, 0.01f, 0f, -0.01f, 0.02f),
            PleasureProfiles.adaptiveReferenceShiftFor(PleasureMode.NOVA),
            0.0001f,
        )
        assertArrayEquals(
            floatArrayOf(0.05f, 0.04f, 0.01f, -0.01f, 0f, 0f, 0f, 0f, 0f, 0f),
            PleasureProfiles.adaptiveReferenceShiftFor(PleasureMode.GRAVITY),
            0.0001f,
        )
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.01f, 0.02f, 0.04f, 0.04f),
            PleasureProfiles.adaptiveReferenceShiftFor(PleasureMode.HORIZON),
            0.0001f,
        )
        assertArrayEquals(
            floatArrayOf(0.02f, 0.02f, 0.01f, 0f, 0f, 0f, -0.02f, -0.03f, -0.02f, -0.02f),
            PleasureProfiles.adaptiveReferenceShiftFor(PleasureMode.ECLIPSE),
            0.0001f,
        )
        assertArrayEquals(
            floatArrayOf(-0.03f, -0.02f, 0f, 0.02f, 0.03f, 0.04f, 0.02f, 0f, -0.02f, -0.02f),
            PleasureProfiles.adaptiveReferenceShiftFor(PleasureMode.COMMS),
            0.0001f,
        )

        val zeroTilt = PleasureProfiles.analogTiltCurve31(0f)
        assertEquals(31, zeroTilt.size)
        assertTrue(zeroTilt.all { it == 0f && it.toRawBits() == 0 })
        assertArrayEquals(FloatArray(31), PleasureProfiles.analogTiltCurve31(Float.NaN), 0.0001f)
        assertArrayEquals(FloatArray(31), PleasureProfiles.analogTiltCurve31(Float.POSITIVE_INFINITY), 0.0001f)

        val brightTilt = PleasureProfiles.analogTiltCurve31(1.0f)
        val warmTilt = PleasureProfiles.analogTiltCurve31(-1.0f)
        assertEquals(0f, brightTilt[17], 0.0001f) // 1000 Hz
        assertEquals(0f, warmTilt[17], 0.0001f)
        assertEquals(0, warmTilt[17].toRawBits()) // normalized -0f -> +0f
        assertEquals(-brightTilt[14], brightTilt[20], 0.0001f) // 500 Hz vs 2000 Hz
        assertEquals(-brightTilt[11], brightTilt[23], 0.0001f) // 250 Hz vs 4000 Hz
        assertEquals(-brightTilt[8], brightTilt[26], 0.0001f) // 125 Hz vs 8000 Hz
        for (i in 0 until 31) {
            assertEquals(-brightTilt[i], warmTilt[i], 0.0001f)
        }
        // Shelf clamp at +-4.0 octaves (<= 50 Hz and >= 16 kHz)
        assertEquals(-4.0f, brightTilt[0], 0.0001f)
        assertEquals(-4.0f, brightTilt[4], 0.0001f)
        assertEquals(4.0f, brightTilt[29], 0.0001f)
        assertEquals(4.0f, brightTilt[30], 0.0001f)

        // Clamp tilt to [-1.5, +1.5] dB/oct
        val overClamped = PleasureProfiles.analogTiltCurve31(4.0f)
        assertEquals(-6.0f, overClamped[0], 0.0001f)
        assertEquals(6.0f, overClamped[30], 0.0001f)
    }
}
