package com.zclei.smartsensorball.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

private const val DEFAULT_RIPPLE_COLOR = "#33FFFFFF"

internal fun View.applyRippleOverlay(rippleColorHex: String = DEFAULT_RIPPLE_COLOR) {
    val tint = ColorStateList.valueOf(Color.parseColor(rippleColorHex))
    val current = background
    background =
        if (current != null) {
            RippleDrawable(tint, current, current)
        } else {
            RippleDrawable(tint, null, ColorDrawable(Color.WHITE))
        }
    isClickable = true
    isFocusable = true
}
