package com.candy

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext

/**
 * Small taps in the app. They go straight to the phone's vibration motor, so they work even when the phone's own
 * "touch vibration" switch is off (the standard Compose haptics depend on that switch, and on many phones they feel like nothing).
 * They follow the app's own switch in the message settings.
 */
object Haptics {
    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun play(ctx: Context, predefined: Int, millis: Long, amplitude: Int) {
        if (!ChatPrefs.haptics(ctx)) return
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        try {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(predefined)
            } else {
                VibrationEffect.createOneShot(millis, amplitude)
            }
            v.vibrate(effect)
        } catch (e: Exception) {
            // no vibration on this phone right now
        }
    }

    /** A light tap: a tab, a like, a send. */
    fun tap(ctx: Context) = play(ctx, VibrationEffect.EFFECT_CLICK, 22L, 140)

    /** A firmer one: a long press, starting to record. */
    fun press(ctx: Context) = play(ctx, VibrationEffect.EFFECT_HEAVY_CLICK, 40L, 220)
}

/** Same call shape as the standard haptic feedback, so every place that used it keeps working. */
class AppHaptic(private val ctx: Context) {
    fun performHapticFeedback(type: HapticFeedbackType) {
        if (type == HapticFeedbackType.LongPress) Haptics.press(ctx) else Haptics.tap(ctx)
    }
}

@Composable
fun rememberAppHaptic(): AppHaptic {
    val ctx = LocalContext.current
    return remember { AppHaptic(ctx.applicationContext) }
}
