package uz.antiradar.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import uz.antiradar.core.OsmConverter
import uz.antiradar.core.OsmMember
import uz.antiradar.core.OsmNode
import uz.antiradar.core.OsmRelation
import uz.antiradar.core.OsmResult
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** OpenStreetMap (Overpass API) dan O'zbekiston kameralarini yuklab oladi. */
object OsmUpdater {
    private val servers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    const val UPDATE_EVERY_MS = 24L * 60 * 60 * 1000

    fun update(repo: ZoneRepository, done: (Result<OsmResult>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = runCatching {
                val json = download()
                val parsed = parse(json)
                repo.saveOsm(parsed.zones, parsed.cameras)
                parsed
            }
            main.post { done(result) }
        }.start()
    }

    private fun download(): String {
        var lastError: Exception? = null
        val body = "data=" + URLEncoder.encode(OsmConverter.OVERPASS_QUERY, "UTF-8")
        for (server in servers) {
            try {
                val c = URL(server).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = 120_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.setRequestProperty("User-Agent", "AntiradarUz/0.2")
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode == 200) return c.inputStream.bufferedReader().use { it.readText() }
                lastError = Exception("$server: HTTP ${c.responseCode}")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("Server javob bermadi")
    }

    private fun parse(text: String): OsmResult {
        val elements = JSONObject(text).getJSONArray("elements")
        val nodes = mutableListOf<OsmNode>()
        val rels = mutableListOf<OsmRelation>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = mutableMapOf<String, String>()
            e.optJSONObject("tags")?.let { t -> t.keys().forEach { k -> tags[k] = t.getString(k) } }
            when (e.getString("type")) {
                "node" -> nodes += OsmNode(e.getLong("id"), e.getDouble("lat"), e.getDouble("lon"), tags)
                "relation" -> {
                    val m = e.optJSONArray("members")
                    val members = if (m == null) emptyList() else (0 until m.length()).map { j ->
                        val o = m.getJSONObject(j)
                        OsmMember(o.getString("type"), o.getLong("ref"), o.optString("role"))
                    }
                    rels += OsmRelation(e.getLong("id"), tags, members)
                }
            }
        }
        // "out skel" bilan kelgan takroriy nodelarda teglar bo'lmaydi — teglisini saqlaymiz
        val merged = nodes.groupBy { it.id }.map { (_, list) -> list.maxByOrNull { it.tags.size }!! }
        return OsmConverter.convert(merged, rels)
    }
}
