package uz.antiradar.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Avtomatik rejim: zona belgilanmagan, faqat 3 ta kamera (0, 5, 10 km), chegara 60. */
class AutoTrackerTest {
    private val metersPerDeg = 111_195.0
    private fun at(m: Double) = GeoPoint(41.0 + m / metersPerDeg, 69.0)
    private val cams = listOf(
        Camera("A", at(0.0), 60),
        Camera("B", at(5000.0), 60),
        Camera("C", at(10_000.0), 60),
    )

    private fun drive(speed: (Double) -> Double): List<AutoState> {
        val t = AutoTracker(cams)
        val out = mutableListOf<AutoState>()
        var m = -500.0
        var time = 0L
        while (m < 10_500.0) {
            val v = speed(m)
            out += t.update(Fix(at(m), time, v / 3.6))
            m += v / 3.6
            time += 1000
        }
        return out
    }

    @Test
    fun passesAllCamerasAndPairsThem() {
        val states = drive { 55.0 }
        val events = states.mapNotNull { it.passed }
        assertEquals(listOf("A", "B", "C"), events.map { it.camera.id })
        // C da: A–C va B–C natijalari
        assertEquals(2, events.last().results.size)
        assertFalse(events.any { it.violated })
        assertTrue(states.none { it.risk == Risk.DANGER })
    }

    @Test
    fun slowingOnlyNearCamerasIsCaught() {
        val near = { m: Double -> cams.any { abs(m - (it.point.lat - 41.0) * metersPerDeg) < 300 } }
        val states = drive { m -> if (near(m)) 60.0 else 120.0 }
        assertTrue(states.any { it.risk == Risk.DANGER })
        val atB = states.mapNotNull { it.passed }.first { it.camera.id == "B" }
        assertTrue(atB.violated)
        assertTrue(atB.worst!!.averageKmh > 100)
    }

    @Test
    fun adviceForNextCamera() {
        // 2.5 km ni 100 bilan: B gacha 2.5 km, vaqt 5:00 - 1:30 = 3:30 -> ~43 km/soat
        val states = drive { 100.0 }
        val s = states.first { st -> st.sections.any { it.from.camera.id == "A" && it.travelledM >= 2500 } }
        val b = assertNotNull(s.binding)
        assertEquals("B", b.to?.id)
        assertTrue(b.maxSpeedKmh!! in 38.0..48.0, "max=${b.maxSpeedKmh}")
        assertEquals(Risk.DANGER, s.risk)
    }

    @Test
    fun unknownLimitUsesDefault() {
        val t = AutoTracker(listOf(Camera("X", at(0.0)), Camera("Y", at(3000.0))), defaultLimitKmh = 70)
        var st: AutoState? = null
        var m = -200.0; var time = 0L
        while (m < 1000) { st = t.update(Fix(at(m), time, 20.0)); m += 20; time += 1000 }
        assertEquals(70, st!!.sections.single().limitKmh)
    }
}
