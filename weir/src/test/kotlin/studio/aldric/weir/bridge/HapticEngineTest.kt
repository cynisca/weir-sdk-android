package studio.aldric.weir.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the Phase-3 haptic style/intensity/API → effect mapping
 * ([HapticEngine.effectSpec]). No device/Robolectric needed — the mapping is a
 * plain function and the platform `EFFECT_*` ids are inlined constants.
 * Port-in-spirit of `sdk-ios/Tests/WeirTests/HapticEngineTests.swift`.
 */
class HapticEngineTest {

    @Test
    fun impactStylesAreAmplitudeControlledOneShots() {
        val light = HapticEngine.effectSpec(HapticStyle.LIGHT, null, 34)
        val heavy = HapticEngine.effectSpec(HapticStyle.HEAVY, null, 34)
        assertTrue(light is HapticEffectSpec.OneShot)
        assertTrue(heavy is HapticEffectSpec.OneShot)
        // Heavier style ⇒ higher amplitude.
        val lightAmp = (light as HapticEffectSpec.OneShot).amplitude
        val heavyAmp = (heavy as HapticEffectSpec.OneShot).amplitude
        assertTrue("heavy amplitude ($heavyAmp) > light amplitude ($lightAmp)", heavyAmp > lightAmp)
    }

    @Test
    fun intensityScalesImpactAmplitude() {
        val full = HapticEngine.effectSpec(HapticStyle.MEDIUM, 1.0, 34) as HapticEffectSpec.OneShot
        val half = HapticEngine.effectSpec(HapticStyle.MEDIUM, 0.5, 34) as HapticEffectSpec.OneShot
        assertTrue("half-intensity amplitude (${half.amplitude}) < full (${full.amplitude})", half.amplitude < full.amplitude)
    }

    @Test
    fun amplitudeIsClampedIntoValidRange() {
        // Even intensity 0 must yield the minimum valid amplitude (1), never 0
        // (createOneShot rejects 0), and never above 255.
        assertEquals(1, HapticEngine.amplitudeFor(0.35, 0.0))
        assertEquals(255, HapticEngine.amplitudeFor(1.0, 1.0))
        assertTrue(HapticEngine.amplitudeFor(1.0, 2.0) <= 255) // over-1 intensity clamped
    }

    @Test
    fun notificationStylesUsePredefinedEffectsOnApi29Plus() {
        val success = HapticEngine.effectSpec(HapticStyle.SUCCESS, null, 29)
        val warning = HapticEngine.effectSpec(HapticStyle.WARNING, null, 34)
        val error = HapticEngine.effectSpec(HapticStyle.ERROR, null, 34)
        assertEquals(HapticEffectSpec.Predefined(HapticEngine.EFFECT_DOUBLE_CLICK), success)
        assertEquals(HapticEffectSpec.Predefined(HapticEngine.EFFECT_HEAVY_CLICK), warning)
        assertEquals(HapticEffectSpec.Predefined(HapticEngine.EFFECT_DOUBLE_CLICK), error)
    }

    @Test
    fun notificationStylesFallBackToWaveformBelowApi29() {
        val success = HapticEngine.effectSpec(HapticStyle.SUCCESS, null, 28)
        val error = HapticEngine.effectSpec(HapticStyle.ERROR, null, 26)
        assertTrue("success on API 28 must be a waveform", success is HapticEffectSpec.Waveform)
        assertTrue("error on API 26 must be a waveform", error is HapticEffectSpec.Waveform)
    }

    @Test
    fun impactStylesStayOneShotsAcrossApiLevels() {
        // Intensity control matters more than a fixed predefined click for the
        // hold-to-seal ramp, so impacts are one-shots on 29+ too.
        val soft29 = HapticEngine.effectSpec(HapticStyle.SOFT, 0.7, 29)
        val rigid34 = HapticEngine.effectSpec(HapticStyle.RIGID, 0.7, 34)
        assertTrue(soft29 is HapticEffectSpec.OneShot)
        assertTrue(rigid34 is HapticEffectSpec.OneShot)
    }
}
