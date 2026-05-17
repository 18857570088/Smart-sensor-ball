package com.zclei.smartsensorball.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

internal object Haptics {
    private const val HIT_DURATION_MS = 12L

    fun tap(context: Context) {
        val vibrator = resolveVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createOneShot(HIT_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java) ?: return null
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
