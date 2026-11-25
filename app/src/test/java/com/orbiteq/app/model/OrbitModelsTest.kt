package com.orbiteq.app.model

import androidx.compose.runtime.Immutable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

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
        assertEquals(10, telemetry.adaptiveDeltaDb.size)
        assertTrue(telemetry.adaptiveDeltaDb.all { it == 0f })
        assertEquals(0f, telemetry.preampAttenuationDb, 0.0001f)
        assertEquals(0f, telemetry.loudnessBoostDb, 0.0001f)
        assertEquals(-0.5f, telemetry.limiterCeilingDb, 0.0001f)
        assertEquals("Balanced", telemetry.dominantSpectralTag)

        val uiState = OrbitUiState()
        assertEquals(OrbitSettings(), uiState.settings)
        assertEquals(PleasureMode.NOVA, uiState.activeMode)
        assertEquals(ModeResolutionSource.GLOBAL_DEFAULT, uiState.modeSource)
        assertNull(uiState.manualOverrideMode)
        assertFalse(uiState.autoBypassedByRule)
        assertEquals(HeadphoneProfile.FLAT_GENERIC, uiState.activeHeadphoneProfile)
        assertEquals(NowPlayingInfo(), uiState.nowPlaying)
        assertFalse(uiState.nowPlaying.isPlaying)
        assertEquals(ConnectedOutputDevice(), uiState.connectedDevice)
        assertTrue(uiState.connectedDevice.isSpeaker)
        assertFalse(uiState.connectedDevice.isBluetooth)
        assertFalse(uiState.connectedDevice.isSoundcoreQ30)
        assertEquals(LiveAudioTelemetry(), uiState.telemetry)
        assertTrue(uiState.recentActivity.isEmpty())
    }

    @Test
    fun allModelDataClassesHaveFinalFieldsAndCopyImmutability() {
        val modelClasses = listOf(
            AppRule::class.java,
            TrackActivityEntry::class.java,
            CustomPreset::class.java,
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
}
