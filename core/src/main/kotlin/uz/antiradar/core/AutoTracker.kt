package uz.antiradar.core

/** O'tilgan kamera: qachon va o'shanda "odometr" (bosib o'tilgan yo'l) qancha edi. */
data class PassedCamera(val camera: Camera, val timeMillis: Long, val odometerM: Double)

/** O'tilgan kameradan oldindagi kameragacha bo'lgan oraliq bo'yicha jonli hisob. */
data class Section(
    val from: PassedCamera,
    /** Oldindagi kamera. null — oldinda kamera topilmadi (faqat ma'lumot uchun). */
    val to: Camera?,
    val limitKmh: Int,
    val elapsedSeconds: Double,
    val travelledM: Double,
    /** O'tilgan kameradan beri o'rtacha tezlik. */
    val averageKmh: Double,
    val remainingM: Double,
    /** Oldindagi kameragacha jarimasiz yetish uchun eng kam qolgan vaqt. */
    val minRemainingSeconds: Double,
    /** Oldindagi kameragacha ruxsat etilgan o'rtacha tezlik. null = cheklov yo'q. */
    val maxSpeedKmh: Double?,
    /** Hozirgi tezlikda davom etsa, oraliq bo'yicha chiqadigan o'rtacha. */
    val projectedKmh: Double,
)

/** Kameradan o'tganda: oldingi har bir kameradan shu kameragacha bo'lgan natija. */
data class PairResult(val from: Camera, val to: Camera, val lengthM: Double, val seconds: Double, val averageKmh: Double, val limitKmh: Int) {
    val violated get() = averageKmh > limitKmh
}

data class PassEvent(val camera: Camera, val results: List<PairResult>) {
    val worst: PairResult? get() = results.maxByOrNull { it.averageKmh - it.limitKmh }
    val violated get() = results.any { it.violated }
}

data class AutoState(
    val currentSpeedKmh: Double?,
    val ahead: CameraAlert?,
    val sections: List<Section>,
    /** Eng qattiq cheklov (eng past ruxsat etilgan tezlik yoki eng yomon prognoz). */
    val binding: Section?,
    val risk: Risk,
    /** Faqat kameradan o'tilgan yangilanishda to'ldiriladi. */
    val passed: PassEvent?,
)

/**
 * To'liq avtomatik rejim: oldindan zona belgilash shart emas.
 * Bazadagi har bir kameradan o'tilganda vaqt va yo'l yoziladi; keyingi har qanday kameragacha
 * o'rtacha tezlik real vaqtda hisoblanadi (A–B, B–C, A–C ...).
 *
 * Kamera chegarasi noma'lum bo'lsa [defaultLimitKmh] olinadi.
 */
class AutoTracker(
    cameras: List<Camera>,
    var defaultLimitKmh: Int = 60,
    private val passRadiusM: Double = 40.0,
    /** Shu masofadan uzoqdagi o'tilgan kameralar hisobdan chiqariladi. */
    private val maxSectionM: Double = 25_000.0,
    private val maxSectionMs: Long = 45 * 60 * 1000L,
    /** Oldindagi kamerani qidirish masofasi (to'g'ri chiziq). */
    private val lookAheadM: Double = 20_000.0,
    private val cautionRatio: Double = 0.93,
) {
    var cameras: List<Camera> = cameras
        private set

    private val history = ArrayDeque<PassedCamera>()
    private var odometer = 0.0
    private var prev: Fix? = null
    private var heading: Double? = null
    private var headingFrom: GeoPoint? = null

    fun setCameras(list: List<Camera>) {
        cameras = list
    }

    val passedCameras: List<PassedCamera> get() = history.toList()

    fun update(fix: Fix): AutoState {
        val p = prev
        prev = fix
        val now = fix.timeMillis

        if (p != null) {
            val step = Geo.distance(p.point, fix.point)
            val dt = (now - p.timeMillis) / 1000.0
            if (dt > 0 && step / dt < 70.0) odometer += step
        }
        val hf = headingFrom
        if (hf == null) headingFrom = fix.point
        else if (Geo.distance(hf, fix.point) >= 15.0) {
            heading = Geo.bearing(hf, fix.point)
            headingFrom = fix.point
        }

        val speedKmh = fix.speedMps?.let { it * 3.6 }
            ?: p?.let {
                val dt = (now - it.timeMillis) / 1000.0
                if (dt > 0) Geo.distance(it.point, fix.point) / dt * 3.6 else null
            }

        // Eski kameralarni tozalash
        while (history.isNotEmpty() &&
            (odometer - history.first().odometerM > maxSectionM || now - history.first().timeMillis > maxSectionMs)
        ) history.removeFirst()

        // Kameradan o'tdikmi
        var event: PassEvent? = null
        val passedCam = cameras.firstOrNull { c ->
            passedNear(p, fix, c.point) && history.none { it.camera.id == c.id && now - it.timeMillis < 3 * 60_000 }
        }
        if (passedCam != null) {
            val results = history.mapNotNull { h ->
                val sec = (now - h.timeMillis) / 1000.0
                val len = odometer - h.odometerM
                if (sec <= 0 || len < 300) null
                else PairResult(h.camera, passedCam, len, sec, len / sec * 3.6, limitFor(h.camera, passedCam))
            }
            event = PassEvent(passedCam, results)
            history.addLast(PassedCamera(passedCam, now, odometer))
        }

        val ahead = findAhead(fix.point)
        val cur = speedKmh ?: 0.0
        val sections = history.map { h -> section(h, ahead, fix, cur) }

        val binding = sections.filter { it.maxSpeedKmh != null }.minByOrNull { it.maxSpeedKmh!! }
            ?: sections.filter { it.to != null }.maxByOrNull { it.projectedKmh }
        val risk = sections.filter { it.to != null }.map { s ->
            val limit = s.limitKmh.toDouble()
            when {
                s.maxSpeedKmh != null && s.maxSpeedKmh < limit * 0.5 -> Risk.DANGER
                s.projectedKmh > limit -> Risk.DANGER
                s.projectedKmh > limit * cautionRatio -> Risk.CAUTION
                else -> Risk.SAFE
            }
        }.maxByOrNull { it.ordinal } ?: Risk.SAFE

        return AutoState(speedKmh, ahead, sections, binding, risk, event)
    }

    private fun limitFor(a: Camera, b: Camera?): Int {
        val known = listOfNotNull(a.limitKmh, b?.limitKmh)
        return known.minOrNull() ?: defaultLimitKmh
    }

    private fun section(h: PassedCamera, ahead: CameraAlert?, fix: Fix, curKmh: Double): Section {
        val limit = limitFor(h.camera, ahead?.camera)
        val elapsed = (fix.timeMillis - h.timeMillis) / 1000.0
        val travelled = odometer - h.odometerM
        val avg = if (elapsed > 0) travelled / elapsed * 3.6 else 0.0
        if (ahead == null) {
            return Section(h, null, limit, elapsed, travelled, avg, 0.0, 0.0, null, avg)
        }
        val rem = ahead.distanceM
        val len = travelled + rem
        val minRem = (len / (limit / 3.6) - elapsed).coerceAtLeast(0.0)
        val maxSp = if (minRem <= 0.0) null else (rem / minRem * 3.6).coerceAtMost(limit.toDouble())
        val projected = if (curKmh > 0.5) len / (elapsed + rem / (curKmh / 3.6)) * 3.6 else avg
        return Section(h, ahead.camera, limit, elapsed, travelled, avg, rem, minRem, maxSp, projected)
    }

    /** Harakat yo'nalishidagi eng yaqin kamera (o'tilganlarni hisobga olmay). */
    private fun findAhead(p: GeoPoint): CameraAlert? {
        val h = heading ?: return null
        val recent = history.lastOrNull()?.camera?.id
        return cameras.asSequence()
            .filter { it.id != recent }
            .map { it to Geo.distance(p, it.point) }
            .filter { (c, d) ->
                d in passRadiusM..lookAheadM &&
                    Geo.angleDiff(Geo.bearing(p, c.point), h) <= if (d < 2000) 40.0 else 60.0
            }
            .minByOrNull { it.second }
            ?.let { (c, d) -> CameraAlert(c, d) }
    }

    private fun passedNear(prev: Fix?, fix: Fix, camera: GeoPoint): Boolean {
        if (Geo.distance(fix.point, camera) <= passRadiusM) return true
        if (prev == null) return false
        val dt = (fix.timeMillis - prev.timeMillis) / 1000.0
        if (dt <= 0 || dt > 30) return false
        return Geo.distanceToSegment(camera, prev.point, fix.point) <= passRadiusM
    }
}

/** Zonalarni kamera nuqtalariga aylantirish (start, oraliq, end — zona chegarasi bilan). */
fun Zone.toCameras(): List<Camera> = checkpoints.mapIndexed { i, p -> Camera("${id}_$i", p, limitKmh) }
