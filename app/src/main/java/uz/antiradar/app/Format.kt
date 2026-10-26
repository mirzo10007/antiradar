package uz.antiradar.app

import kotlin.math.roundToInt

object Format {
    fun kmh(v: Double?): String = v?.let { "${it.roundToInt()} km/soat" } ?: "—"

    fun meters(m: Double): String =
        if (m >= 1000) String.format("%.1f km", m / 1000) else "${m.roundToInt()} m"

    fun duration(sec: Double): String {
        val s = sec.roundToInt().coerceAtLeast(0)
        return String.format("%d:%02d", s / 60, s % 60)
    }
}
