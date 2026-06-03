package me.bechberger.jfrtofp.util

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/** Rounds [this] to [decimals] decimal places. Negative [decimals] is a no-op. */
fun Double.quantize(decimals: Int): Double {
    if (decimals < 0) return this
    val factor = 10.0.pow(decimals)
    return (this * factor).roundToLong().toDouble() / factor
}

/** based on the code from Firefox Profiler */
fun Long.formatBytes(): String {
    fun formatNumber(number: Double): String {
        return "%,.2f".format(Locale.US, number)
    }

    if (this < 10000) {
        return "${formatNumber(this * 1.0)}B"
    }
    if (this < 1024 * 1024) {
        return formatNumber(this / 1024.0) + "KB"
    }
    if (this < 1024 * 1024 * 1024) {
        return formatNumber(
            this / (1024 * 1024.0),
        ) + "MB"
    }
    return formatNumber(
        this / (1024 * 1024 * 1024.0),
    ) + "GB"
}
