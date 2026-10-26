package uz.antiradar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 10 km, 3 ta kamera (0, 5, 10 km), chegara 60 km/soat. */
class MultiCameraTest {
    private val metersPerDeg = 111_195.0 // 1 daraja kenglik ≈ 111.2 km
    private fun at(m: Double) = GeoPoint(41.0 + m / metersPerDeg, 69.0)
    private val zone = Zone("z3", "3 kamera", at(0.0), at(10_000.0), limitKmh = 60, via = listOf(at(5_000.0)))

    /** Tezlik masofaga bog'liq: speed(m) km/soat. Har soniyada GPS nuqta. */
    private fun drive(speed: (Double) -> Double): List<TrackerState> {
        val tracker = ZoneTracker(listOf(zone))
        val out = mutableListOf<TrackerState>()
        var m = -300.0
        var t = 0L
        while (m < 10_300.0) {
            val v = speed(m)
            out += tracker.update(Fix(at(m), t, v / 3.6))
            m += v / 3.6
            t += 1000
        }
        return out
    }

    @Test
    fun cumulativeDistances() {
        assertEquals(listOf(0.0, 5000.0, 10000.0), zone.cumulative.map { Math.round(it / 10) * 10.0 })
        assertEquals(600.0, zone.minTimeSeconds, 3.0)
    }

    @Test
    fun slowingOnlyNearCamerasIsCaught() {
        // Kameralar oldida 60 ga tushadi, oraliqda 120 yuradi
        val nearCamera = { m: Double -> listOf(0.0, 5000.0, 10000.0).any { kotlin.math.abs(m - it) < 300 } }
        val states = drive { m -> if (nearCamera(m)) 60.0 else 120.0 }
        val inZone = states.filterIsInstance<TrackerState.InZone>()
        // Oraliqda tezlashganda xavf ko'rsatiladi
        assertTrue(inZone.any { it.risk == Risk.DANGER })
        val fin = states.filterIsInstance<TrackerState.ZoneFinished>().single()
        assertTrue(fin.violated)
        assertTrue(fin.averageKmh > 100)
    }

    @Test
    fun fastFirstHalfViolatesSegmentEvenIfTotalAverageIsLegal() {
        // 1-yarmi 100, 2-yarmi 40: umumiy ~57, lekin A–B oralig'i 100
        val states = drive { m -> if (m < 5000) 100.0 else 40.0 }
        val fin = states.filterIsInstance<TrackerState.ZoneFinished>().single()
        assertTrue(fin.averageKmh < 60, "umumiy=${fin.averageKmh}")
        assertTrue(fin.violated)
        assertEquals(100.0, fin.worstPairAverageKmh, 3.0)
        // Oraliq kamera o'tilgandan keyin segment natijasi ko'rsatiladi
        val after = states.filterIsInstance<TrackerState.InZone>().first { it.nextCheckpoint == 2 }
        assertEquals(100.0, after.lastSegmentAverageKmh!!, 3.0)
    }

    @Test
    fun steady55IsSafeEverywhere() {
        val states = drive { 55.0 }
        assertTrue(states.filterIsInstance<TrackerState.InZone>().all { it.risk != Risk.DANGER })
        assertFalse(states.filterIsInstance<TrackerState.ZoneFinished>().single().violated)
    }

    @Test
    fun adviceTargetsNearestCameraAfterSpeeding() {
        // 2.5 km ni 100 bilan: B kameragacha qolgan 2.5 km uchun 5:00 - 1:30 = 3:30 -> ~43 km/soat
        val states = drive { 100.0 }
        val s = states.filterIsInstance<TrackerState.InZone>().first { it.travelledMeters >= 2500 }
        assertEquals(1, s.bindingCheckpoint)
        assertTrue(s.maxSpeedForRestKmh!! in 38.0..48.0, "max=${s.maxSpeedForRestKmh}")
    }
}
