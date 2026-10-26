package uz.antiradar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ZoneTrackerTest {
    // Shimolga qarab ~10 km: 0.09 daraja kenglik ≈ 10 007 m
    private val start = GeoPoint(41.0, 69.0)
    private val end = GeoPoint(41.09, 69.0)
    private val zone = Zone("z1", "Test", start, end, limitKmh = 60)

    /** start dan end ga qarab berilgan tezlikda haydash, har 5 soniyada GPS nuqta. */
    private fun drive(tracker: ZoneTracker, kmh: Double, fromMeters: Double = -200.0, toMeters: Double = 10_300.0): List<TrackerState> {
        val states = mutableListOf<TrackerState>()
        val mps = kmh / 3.6
        val metersPerDeg = zone.effectiveLength / 0.09
        var t = 0L
        var m = fromMeters
        while (m <= toMeters) {
            val p = GeoPoint(41.0 + m / metersPerDeg, 69.0)
            states += tracker.update(Fix(p, t, mps))
            m += mps * 5
            t += 5000
        }
        return states
    }

    @Test
    fun minTimeMatchesExample() {
        // 10 km, 60 km/soat -> 10 daqiqa
        val z = Zone("x", "x", start, end, 60, lengthMeters = 10_000.0)
        assertEquals(600.0, z.minTimeSeconds, 0.001)
    }

    @Test
    fun slowDriveIsSafeAndFinishedWithoutViolation() {
        val states = drive(ZoneTracker(listOf(zone)), 55.0)
        assertTrue(states.any { it is TrackerState.InZone })
        val fin = states.filterIsInstance<TrackerState.ZoneFinished>().single()
        assertFalse(fin.violated)
        assertEquals(55.0, fin.averageKmh, 2.0)
        assertTrue(states.filterIsInstance<TrackerState.InZone>().all { it.risk != Risk.DANGER })
    }

    @Test
    fun fastDriveIsDangerAndViolation() {
        // Misoldagidek 10 km ni ~5 daqiqada: 120 km/soat
        val states = drive(ZoneTracker(listOf(zone)), 120.0)
        val inZone = states.filterIsInstance<TrackerState.InZone>()
        assertTrue(inZone.drop(2).all { it.risk == Risk.DANGER })
        val fin = states.filterIsInstance<TrackerState.ZoneFinished>().single()
        assertTrue(fin.violated)
        assertEquals(120.0, fin.averageKmh, 5.0)
    }

    @Test
    fun maxSpeedForRestDropsAfterSpeeding() {
        val tracker = ZoneTracker(listOf(zone))
        // Birinchi yarmini 100 km/soatda
        val states = drive(tracker, 100.0, toMeters = 5000.0)
        val s = assertIs<TrackerState.InZone>(states.last())
        // Qolgan ~5 km uchun ~10 daqiqa - 3 daqiqa = ~7 daqiqa qoladi -> ~43 km/soat
        val max = s.maxSpeedForRestKmh!!
        assertTrue(max in 38.0..48.0, "max=$max")
    }

    @Test
    fun noEntryWhenFarFromZone() {
        val tracker = ZoneTracker(listOf(zone))
        val st = tracker.update(Fix(GeoPoint(40.5, 69.0), 0, 10.0))
        val idle = assertIs<TrackerState.Idle>(st)
        assertTrue(idle.distanceToNearestM!! > 50_000)
    }

    @Test
    fun reverseDirectionEventuallyDropsZone() {
        val tracker = ZoneTracker(listOf(zone))
        val metersPerDeg = zone.effectiveLength / 0.09
        var t = 0L
        var last: TrackerState? = null
        var m = 200.0
        while (m > -3000.0) {
            last = tracker.update(Fix(GeoPoint(41.0 + m / metersPerDeg, 69.0), t, 16.0))
            m -= 80; t += 5000
        }
        assertIs<TrackerState.Idle>(last)
    }
}
