package uz.antiradar.core

/** OpenStreetMap ma'lumotlarining soddalashtirilgan ko'rinishi (Overpass API javobidan). */
data class OsmNode(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String> = emptyMap())

data class OsmMember(val type: String, val ref: Long, val role: String)

data class OsmRelation(val id: Long, val tags: Map<String, String>, val members: List<OsmMember>)

data class OsmResult(val zones: List<Zone>, val cameras: List<Camera>)

object OsmConverter {
    /** Overpass so'rovi: O'zbekiston bo'yicha kameralar va nazorat munosabatlari. */
    const val OVERPASS_QUERY = """[out:json][timeout:90];
area["ISO3166-1"="UZ"][admin_level=2]->.uz;
(
  node["highway"="speed_camera"](area.uz);
  node["enforcement"="maxspeed"](area.uz);
  relation["type"="enforcement"](area.uz);
);
out body;
>;
out skel qt;"""

    /** "60", "60 km/h", "60;80" -> 60. "mph" bo'lsa km/soatga o'giriladi. */
    fun parseSpeed(v: String?): Int? {
        if (v == null) return null
        val num = Regex("""\d+""").find(v)?.value?.toIntOrNull() ?: return null
        return if (v.contains("mph")) Math.round(num * 1.609).toInt() else num
    }

    fun convert(nodes: List<OsmNode>, relations: List<OsmRelation>): OsmResult {
        val byId = nodes.associateBy { it.id }
        val zones = mutableListOf<Zone>()
        val cameraLimits = mutableMapOf<Long, Int>()
        val cameraIds = linkedSetOf<Long>()

        for (r in relations) {
            if (r.tags["type"] != "enforcement") continue
            val kind = r.tags["enforcement"] ?: continue
            val limit = parseSpeed(r.tags["maxspeed"])
            val nodeMembers = r.members.filter { it.type == "node" }
            if (kind == "average_speed") {
                val from = nodeMembers.firstOrNull { it.role == "from" }?.let { byId[it.ref] }
                val to = nodeMembers.firstOrNull { it.role == "to" }?.let { byId[it.ref] }
                if (from != null && to != null && limit != null) {
                    val fromP = GeoPoint(from.lat, from.lon)
                    val toP = GeoPoint(to.lat, to.lon)
                    // Oraliq kameralar (device), boshidan uzoqligi bo'yicha tartiblanadi
                    val via = nodeMembers.filter { it.role == "device" }
                        .mapNotNull { byId[it.ref] }
                        .map { GeoPoint(it.lat, it.lon) }
                        .filter { Geo.distance(it, fromP) > 150 && Geo.distance(it, toP) > 150 }
                        .sortedBy { Geo.distance(fromP, it) }
                    zones += Zone(
                        via = via,
                        id = "osm_r${r.id}",
                        name = r.tags["name"] ?: r.tags["description"] ?: "O'rtacha tezlik zonasi",
                        start = GeoPoint(from.lat, from.lon),
                        end = GeoPoint(to.lat, to.lon),
                        limitKmh = limit,
                    )
                }
            } else if (kind == "maxspeed") {
                // device = kamera, from = nazorat nuqtasi. Kamera yo'q bo'lsa from ni olamiz.
                val point = nodeMembers.firstOrNull { it.role == "device" }
                    ?: nodeMembers.firstOrNull { it.role == "from" }
                if (point != null && byId.containsKey(point.ref)) {
                    cameraIds += point.ref
                    if (limit != null) cameraLimits[point.ref] = limit
                }
            }
        }

        for (n in nodes) {
            if (n.tags["highway"] == "speed_camera" || n.tags["enforcement"] == "maxspeed") cameraIds += n.id
        }

        val cameras = cameraIds.mapNotNull { id ->
            val n = byId[id] ?: return@mapNotNull null
            Camera(
                id = "osm_n$id",
                point = GeoPoint(n.lat, n.lon),
                limitKmh = cameraLimits[id] ?: parseSpeed(n.tags["maxspeed"]),
            )
        }
        return OsmResult(zones, cameras)
    }
}
