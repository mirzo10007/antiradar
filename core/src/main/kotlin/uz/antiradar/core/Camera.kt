package uz.antiradar.core

/** Oddiy (bir nuqtali) tezlik kamerasi. limitKmh noma'lum bo'lsa null. */
data class Camera(
    val id: String,
    val point: GeoPoint,
    val limitKmh: Int? = null,
)

data class CameraAlert(val camera: Camera, val distanceM: Double)

/**
 * Oldinda (harakat yo'nalishida) turgan eng yaqin kamerani topadi.
 * Yo'nalish oxirgi ikki GPS nuqtadan aniqlanadi.
 */
class CameraWarner(
    cameras: List<Camera>,
    private val rangeM: Double = 1000.0,
    private val maxAngle: Double = 40.0,
) {
    var cameras: List<Camera> = cameras
        private set

    private var prev: GeoPoint? = null
    private var heading: Double? = null

    fun setCameras(list: List<Camera>) {
        cameras = list
    }

    fun update(p: GeoPoint): CameraAlert? {
        val pr = prev
        if (pr == null || Geo.distance(pr, p) >= 8.0) {
            if (pr != null) heading = Geo.bearing(pr, p)
            prev = p
        }
        val h = heading ?: return null
        return cameras.asSequence()
            .map { it to Geo.distance(p, it.point) }
            .filter { (c, d) -> d <= rangeM && Geo.angleDiff(Geo.bearing(p, c.point), h) <= maxAngle }
            .minByOrNull { it.second }
            ?.let { (c, d) -> CameraAlert(c, d) }
    }
}

/** Bir joyda (radius ichida) takrorlangan kameralarni bittaga birlashtiradi; chegarasi ma'lumi ustun. */
fun dedupeCameras(list: List<Camera>, radiusM: Double = 50.0): List<Camera> {
    val out = mutableListOf<Camera>()
    for (c in list.sortedBy { if (it.limitKmh != null) 0 else 1 }) {
        if (out.none { Geo.distance(it.point, c.point) <= radiusM }) out += c
    }
    return out
}
