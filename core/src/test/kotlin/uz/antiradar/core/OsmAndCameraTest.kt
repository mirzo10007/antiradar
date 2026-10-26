package uz.antiradar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OsmAndCameraTest {
    @Test
    fun convertsAverageSpeedRelationAndCameras() {
        val nodes = listOf(
            OsmNode(1, 41.0, 69.0, mapOf("highway" to "speed_camera", "maxspeed" to "70")),
            OsmNode(2, 41.09, 69.0, mapOf("highway" to "speed_camera")),
            OsmNode(3, 41.2, 69.1, mapOf("highway" to "speed_camera")),
            OsmNode(4, 41.21, 69.1),
        )
        val rels = listOf(
            OsmRelation(
                10, mapOf("type" to "enforcement", "enforcement" to "average_speed", "maxspeed" to "60"),
                listOf(OsmMember("node", 1, "from"), OsmMember("node", 2, "to")),
            ),
            OsmRelation(
                11, mapOf("type" to "enforcement", "enforcement" to "maxspeed", "maxspeed" to "80 km/h"),
                listOf(OsmMember("node", 3, "device"), OsmMember("node", 4, "from")),
            ),
        )
        val r = OsmConverter.convert(nodes, rels)
        assertEquals(1, r.zones.size)
        assertEquals(60, r.zones[0].limitKmh)
        assertEquals(10_007.0, r.zones[0].effectiveLength, 30.0)
        assertEquals(3, r.cameras.size)
        assertEquals(80, r.cameras.first { it.id == "osm_n3" }.limitKmh)
        assertEquals(70, r.cameras.first { it.id == "osm_n1" }.limitKmh)
    }

    @Test
    fun parseSpeedVariants() {
        assertEquals(60, OsmConverter.parseSpeed("60"))
        assertEquals(80, OsmConverter.parseSpeed("80 km/h"))
        assertEquals(48, OsmConverter.parseSpeed("30 mph"))
        assertNull(OsmConverter.parseSpeed("none"))
    }

    @Test
    fun warnsOnlyAboutCameraAhead() {
        val ahead = Camera("a", GeoPoint(41.005, 69.0))   // ~555 m shimolda
        val behind = Camera("b", GeoPoint(40.997, 69.0))  // ~333 m janubda
        val w = CameraWarner(listOf(ahead, behind))
        assertNull(w.update(GeoPoint(40.999, 69.0)))       // yo'nalish hali noma'lum
        val alert = assertNotNull(w.update(GeoPoint(41.0, 69.0))) // shimolga harakat
        assertEquals("a", alert.camera.id)
        assertEquals(555.0, alert.distanceM, 10.0)
    }
}
