package uz.antiradar.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Ikki nuqta orasidagi masofa (metr), haversine formulasi. */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Nuqtadan [a, b] kesmagacha eng qisqa masofa (metr). Qisqa masofalar uchun tekis proyeksiya.
     * Tez harakatda GPS nuqtalari kamera radiusidan "sakrab" o'tib ketmasligi uchun kerak.
     */
    fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_320.0 * cos(Math.toRadians(p.lat))
        val ky = 110_540.0
        val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    /** a dan b ga yo'nalish, gradusda (0 = shimol, 90 = sharq). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Ikki yo'nalish orasidagi farq, 0..180 gradus. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}
