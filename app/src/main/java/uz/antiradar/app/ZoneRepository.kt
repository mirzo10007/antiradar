package uz.antiradar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uz.antiradar.core.Camera
import uz.antiradar.core.GeoPoint
import uz.antiradar.core.Zone
import uz.antiradar.core.dedupeCameras
import uz.antiradar.core.toCameras
import java.io.File

/**
 * Kamera bazasi uch manbadan: ilova ichidagi (assets/zones.json),
 * OpenStreetMap'dan yuklangan (files/osm_data.json) va foydalanuvchi qo'shgan (files/user_data.json).
 * Zonalar ham kamera nuqtalariga aylantiriladi — hisob avtomatik, har qanday ikki kamera orasida.
 */
class ZoneRepository(private val context: Context) {

    private val userFile get() = File(context.filesDir, "user_data.json")
    private val osmFile get() = File(context.filesDir, "osm_data.json")
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var defaultLimitKmh: Int
        get() = prefs.getInt("default_limit", 60)
        set(v) = prefs.edit().putInt("default_limit", v).apply()

    private fun readAsset(): String? = runCatching {
        context.assets.open("zones.json").bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun readFile(f: File): String? = if (f.exists()) runCatching { f.readText() }.getOrNull() else null

    private fun camerasFrom(text: String?): List<Camera> {
        if (text == null) return emptyList()
        val cams = runCatching { parseCameras(text) }.getOrDefault(emptyList())
        val zones = runCatching { parseZones(text) }.getOrDefault(emptyList())
        return cams + zones.flatMap { it.toCameras() }
    }

    fun userCameras(): List<Camera> = runCatching { parseCameras(readFile(userFile) ?: "{}") }.getOrDefault(emptyList())

    /** Butun baza (takrorlar birlashtirilgan). */
    fun cameras(): List<Camera> = dedupeCameras(
        userCameras() + camerasFrom(readAsset()) + camerasFrom(readFile(osmFile)),
    )

    /** OSM ma'lumoti oxirgi marta qachon yuklangan (ms), yo'q bo'lsa 0. */
    fun osmUpdatedAt(): Long = if (osmFile.exists()) osmFile.lastModified() else 0L

    fun saveOsm(zones: List<Zone>, cameras: List<Camera>) {
        osmFile.writeText(toJson(cameras, zones))
    }

    fun addUserCamera(c: Camera) = saveUser(userCameras().filter { it.id != c.id } + c)

    fun deleteUserCamera(id: String) = saveUser(userCameras().filter { it.id != id })

    /** JSON fayldan kameralar (va zonalar) qo'shadi. Qo'shilgan kameralar sonini qaytaradi. */
    fun importJson(text: String): Int {
        val incoming = camerasFrom(text)
        val byId = userCameras().associateBy { it.id }.toMutableMap()
        incoming.forEach { byId[it.id] = it }
        saveUser(byId.values.toList())
        return incoming.size
    }

    fun exportJson(): String = toJson(cameras())

    private fun saveUser(cams: List<Camera>) {
        userFile.writeText(toJson(cams))
    }

    companion object {
        fun parseZones(text: String): List<Zone> {
            val arr = JSONObject(text).optJSONArray("zones") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val s = o.getJSONArray("start")
                val e = o.getJSONArray("end")
                Zone(
                    id = o.optString("id").ifBlank { "zone_$i" },
                    name = o.optString("name", "Zona"),
                    start = GeoPoint(s.getDouble(0), s.getDouble(1)),
                    end = GeoPoint(e.getDouble(0), e.getDouble(1)),
                    limitKmh = o.getInt("limit"),
                    lengthMeters = o.optDouble("length", 0.0),
                    via = o.optJSONArray("via")?.let { v ->
                        (0 until v.length()).map { k ->
                            val p = v.getJSONArray(k)
                            GeoPoint(p.getDouble(0), p.getDouble(1))
                        }
                    } ?: emptyList(),
                )
            }
        }

        fun parseCameras(text: String): List<Camera> {
            val arr = JSONObject(text).optJSONArray("cameras") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Camera(
                    id = o.optString("id").ifBlank { "cam_$i" },
                    point = GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
                    limitKmh = if (o.has("limit")) o.getInt("limit") else null,
                )
            }
        }

        fun toJson(cameras: List<Camera>, zones: List<Zone> = emptyList()): String {
            val zs = JSONArray()
            zones.forEach { z ->
                zs.put(
                    JSONObject()
                        .put("id", z.id)
                        .put("name", z.name)
                        .put("start", JSONArray().put(z.start.lat).put(z.start.lon))
                        .put("end", JSONArray().put(z.end.lat).put(z.end.lon))
                        .put("limit", z.limitKmh)
                        .put("length", Math.round(z.lengthMeters).toDouble())
                        .put("via", JSONArray().apply { z.via.forEach { put(JSONArray().put(it.lat).put(it.lon)) } })
                )
            }
            val cams = JSONArray()
            cameras.forEach { c ->
                val o = JSONObject().put("id", c.id).put("lat", c.point.lat).put("lon", c.point.lon)
                c.limitKmh?.let { o.put("limit", it) }
                cams.put(o)
            }
            return JSONObject().put("zones", zs).put("cameras", cams).toString(2)
        }
    }
}
