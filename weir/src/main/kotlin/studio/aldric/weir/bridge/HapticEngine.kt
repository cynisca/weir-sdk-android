package studio.aldric.weir.bridge

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * `Vibrator`/`VibrationEffect` mapping for the bridge's `haptic` method. Fires
 * and returns immediately (BRIDGE.md: "ack immediately, don't block on it"); the
 * router acks the bridge call independently of this.
 *
 * Android analogue of `sdk-ios/Sources/Weir/Bridge/HapticEngine.swift`. The iOS
 * `UIImpactFeedbackGenerator`/`UINotificationFeedbackGenerator` styles map here
 * as follows (Phase 3 polish — plan §8 item 4):
 *
 *  - **Impact** styles (light/medium/heavy/soft/rigid): a short one-shot whose
 *    amplitude scales with the style and the optional 0..1 `intensity`
 *    (API 26+). Kept as one-shots (not predefined effects) precisely so
 *    `intensity` stays expressive — the hold-to-seal ramp (motion-tokens.md)
 *    needs continuous amplitude control a fixed predefined click can't give.
 *  - **Notification** styles (success/warning/error): **predefined
 *    `VibrationEffect` effects on API 29+** (`EFFECT_*` — the system's own
 *    tuned, device-consistent feedback), with a hand-built **waveform fallback
 *    on API 26–28** where predefined effects don't exist.
 *
 * The style/intensity → effect decision is the pure [effectSpec] function so it
 * can be unit-tested without a device; [fire] only turns a spec into the actual
 * platform `VibrationEffect` and plays it. Android has no Taptic "prepare"
 * concept, so iOS's `prepareAll()` pre-priming has no analogue and is dropped.
 */
class HapticEngine(context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context.applicationContext)

    fun fire(style: HapticStyle, intensity: Double? = null) {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return

        val effect = platformEffect(effectSpec(style, intensity, Build.VERSION.SDK_INT)) ?: return
        try {
            vibrator.vibrate(effect)
        } catch (_: Exception) {
            // A haptic must never take the flow down; swallow device quirks.
        }
    }

    /** Turns a pure [HapticEffectSpec] into a concrete [VibrationEffect]. */
    private fun platformEffect(spec: HapticEffectSpec): VibrationEffect? = when (spec) {
        is HapticEffectSpec.Predefined -> VibrationEffect.createPredefined(spec.effectId)
        is HapticEffectSpec.OneShot -> VibrationEffect.createOneShot(spec.durationMs, spec.amplitude)
        is HapticEffectSpec.Waveform -> VibrationEffect.createWaveform(spec.timings, -1)
    }

    companion object {
        const val LIGHT_MS = 10L
        const val MEDIUM_MS = 20L
        const val HEAVY_MS = 35L
        const val GAP_MS = 60L

        /**
         * Pure style/intensity/API → effect-spec mapping (Phase 3 polish). No
         * Android framework calls — the `EFFECT_*` ids are inlined as their
         * platform constant values so this stays a plain JVM function the unit
         * tests drive directly across simulated API levels.
         */
        fun effectSpec(
            style: HapticStyle,
            intensity: Double?,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ): HapticEffectSpec = when (style) {
            // Impact styles → amplitude-controlled one-shots (intensity matters).
            HapticStyle.LIGHT -> HapticEffectSpec.OneShot(LIGHT_MS, amplitudeFor(0.35, intensity))
            HapticStyle.MEDIUM -> HapticEffectSpec.OneShot(MEDIUM_MS, amplitudeFor(0.6, intensity))
            HapticStyle.HEAVY -> HapticEffectSpec.OneShot(HEAVY_MS, amplitudeFor(1.0, intensity))
            HapticStyle.SOFT -> HapticEffectSpec.OneShot(LIGHT_MS, amplitudeFor(0.4, intensity))
            HapticStyle.RIGID -> HapticEffectSpec.OneShot(MEDIUM_MS, amplitudeFor(0.9, intensity))

            // Notification styles → system predefined effects on 29+, waveform below.
            HapticStyle.SUCCESS ->
                if (sdkInt >= Build.VERSION_CODES.Q) HapticEffectSpec.Predefined(EFFECT_DOUBLE_CLICK)
                else HapticEffectSpec.Waveform(longArrayOf(0, MEDIUM_MS, GAP_MS, MEDIUM_MS))
            HapticStyle.WARNING ->
                if (sdkInt >= Build.VERSION_CODES.Q) HapticEffectSpec.Predefined(EFFECT_HEAVY_CLICK)
                else HapticEffectSpec.Waveform(longArrayOf(0, HEAVY_MS, GAP_MS, LIGHT_MS))
            HapticStyle.ERROR ->
                if (sdkInt >= Build.VERSION_CODES.Q) HapticEffectSpec.Predefined(EFFECT_DOUBLE_CLICK)
                else HapticEffectSpec.Waveform(longArrayOf(0, HEAVY_MS, GAP_MS, HEAVY_MS))
        }

        /** Maps a style's base amplitude, optionally scaled by the caller's
         *  0..1 intensity, into the 1..255 range `createOneShot` wants. */
        fun amplitudeFor(base: Double, intensity: Double?): Int {
            val scaled = base * (intensity?.coerceIn(0.0, 1.0) ?: 1.0)
            return (scaled * 255).toInt().coerceIn(1, 255)
        }

        // VibrationEffect.EFFECT_* platform constant values (API 29+). Inlined
        // so effectSpec stays framework-free and JVM-unit-testable.
        const val EFFECT_CLICK = 0
        const val EFFECT_DOUBLE_CLICK = 1
        const val EFFECT_HEAVY_CLICK = 5

        fun resolveVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
    }
}

/**
 * Pure description of a haptic effect — the output of [HapticEngine.effectSpec],
 * turned into a real `VibrationEffect` only inside [HapticEngine.fire]. Split
 * out so the mapping is testable without a device/Robolectric.
 */
sealed interface HapticEffectSpec {
    /** A system predefined effect (API 29+); [effectId] is a `VibrationEffect.EFFECT_*`. */
    data class Predefined(val effectId: Int) : HapticEffectSpec

    /** A single amplitude-controlled buzz (`createOneShot`), [amplitude] in 1..255. */
    data class OneShot(val durationMs: Long, val amplitude: Int) : HapticEffectSpec

    /** A hand-built timing pattern (`createWaveform`), the pre-29 fallback. */
    data class Waveform(val timings: LongArray) : HapticEffectSpec {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Waveform && timings.contentEquals(other.timings))

        override fun hashCode(): Int = timings.contentHashCode()
    }
}
