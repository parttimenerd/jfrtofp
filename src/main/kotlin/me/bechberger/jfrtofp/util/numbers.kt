package me.bechberger.jfrtofp.util

/** based on the code from Firefox Profiler */
fun Long.formatBytes(): String {
    fun formatNumber(number: Double): String {
        return "%,.2f".format(number)
    }

    if (this < 10000) {
        // Use singles up to 10,000.  I think 9,360B looks nicer than 9.36KB.
        // We use "0" for significantDigits because bytes will always be integers.
        return "${formatNumber(this * 1.0)}B"
    }
    if (this < 1024 * 1024) {
        return formatNumber(this / 1024.0) + "KB"
    }
    if (this < 1024 * 1024 * 1024) {
        return formatNumber(
            this / (1024 * 1024.0)
        ) + "MB"
    }
    return formatNumber(
        this / (1024 * 1024 * 1024.0),
    ) + "GB"
}
