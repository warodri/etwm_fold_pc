package com.etmw.foldpc.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

fun Context.dp(value: Number): Int {
    return (value.toFloat() * resources.displayMetrics.density).roundToInt()
}

fun Context.sp(value: Number): Float {
    return value.toFloat() * resources.displayMetrics.scaledDensity
}

fun Context.textView(
    text: String,
    sizeSp: Float = 14f,
    color: Int = Color.WHITE,
    medium: Boolean = false,
    gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL
): TextView {
    return TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        this.gravity = gravity
        includeFontPadding = false
    }
}

fun Context.iconView(resId: Int, tint: Int, sizeDp: Int = 22, paddingDp: Int = 0): ImageView {
    return ImageView(this).apply {
        setImageResource(resId)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        if (paddingDp > 0) setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
