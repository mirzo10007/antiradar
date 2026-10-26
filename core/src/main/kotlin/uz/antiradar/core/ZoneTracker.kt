package uz.antiradar.core

/** GPS dan kelgan bitta nuqta. timeMillis — vaqt (ms), speedMps — GPS tezligi (bo'lmasa null). */
data class Fix(
    val point: GeoPoint,
    val timeMillis: Long,
    val speedMps: Double? = null,
    val accuracyM: Double = 10.0,
)

enum class Risk { SAFE, CAUTION, DANGER }

sealed class TrackerState {
    /** Hech qanday zonada emas. */
    data class Idle(
        val currentSpeedKmh: Double?,
        val nearestZone: Zone?,
        val distanceToNearestM: Double?,
    ) : TrackerState()

    data class InZone(
        val zone: Zone,
        val elapsedSeconds: Double,
        val travelledMeters: Double,
        /** Zona oxirigacha qolgan masofa. */
        val remainingMeters: Double,
        /** Zonaga kirgandan beri o'rtacha tezlik. */
        val averageKmh: Double,
        val currentSpeedKmh: Double?,
        /** Keyingi o'tiladigan kamera tartib raqami (1 dan; start = 0). */
        val nextCheckpoint: Int,
        val checkpointCount: Int,
        val distanceToNextCheckpointM: Double,
        /** Eng qattiq cheklov qaysi kameragacha (tartib raqami). */
        val bindingCheckpoint: Int,
        /** O'sha kameragacha jarimasiz yetish uchun eng kam qolgan vaqt (soniya). */
        val minRemainingSeconds: Double,
        /** O'sha kameragacha ruxsat etilgan eng yuqori o'rtacha tezlik. null = cheklov yo'q (faqat oddiy limit). */
        val maxSpeedForRestKmh: Double?,
        /** Hozirgi tezlikda davom etsa, eng yomon juftlikdagi o'rtacha tezlik. */
        val projectedAverageKmh: Double,
        /** Oxirgi o'tilgan oraliq kamera bo'yicha natija (oldingi kameradan o'rtacha). */
        val lastSegmentAverageKmh: Double?,
        val risk: Risk,
    ) : TrackerState()

    data class ZoneFinished(
        val zone: Zone,
        val totalSeconds: Double,
        val averageKmh: Double,
        /** Har qanday ikki kamera orasidagi eng yuqori o'rtacha tezlik. */
        val worstPairAverageKmh: Double,
        val violated: Boolean,
    ) : TrackerState()
}

/**
 * GPS nuqtalarini qabul qilib, zonaga kirish/chiqishni aniqlaydi va o'rtacha tezlikni hisoblaydi.
 * Zona bir nechta kameradan iborat bo'lsa, har bir kamera jufti alohida tekshiriladi.
 * Android'ga bog'liq emas, shuning uchun oddiy JVM testlari bilan tekshiriladi.
 */
class ZoneTracker(
    zones: List<Zone>,
    /** Kamera nuqtasiga shu masofadan yaqin bo'lsa, "o'tdi" deb hisoblanadi. */
    private val triggerRadiusM: Double = 60.0,
    /** Zonadan chetga chiqib ketganini aniqlash uchun ortiqcha masofa. */
    private val overrunM: Double = 1500.0,
    /** CAUTION holati: o'rtacha tezlik limitning shu ulushidan oshsa. */
    private val cautionRatio: Double = 0.93,
) {
    var zones: List<Zone> = zones
        private set

    private var active: Zone? = null
    private var passTimes: Array<Long?> = emptyArray()
    private var nextIdx = 1
    private var travelled = 0.0
    private var last: Fix? = null
    private var minDistToNext = Double.MAX_VALUE
    private var lastFinishedId: String? = null
    private var worstPairAvg = 0.0
    private var lastSegmentAvg: Double? = null

    fun setZones(newZones: List<Zone>) {
        zones = newZones
        if (active != null && newZones.none { it.id == active!!.id }) reset()
    }

    fun reset() {
        active = null
        travelled = 0.0
        minDistToNext = Double.MAX_VALUE
        worstPairAvg = 0.0
        lastSegmentAvg = null
    }

    fun update(fix: Fix): TrackerState {
        val prev = last
        last = fix
        val speedKmh = fix.speedMps?.let { it * 3.6 }
            ?: prev?.let { p ->
                val dt = (fix.timeMillis - p.timeMillis) / 1000.0
                if (dt > 0) Geo.distance(p.point, fix.point) / dt * 3.6 else null
            }

        val zone = active
        if (zone == null) {
            // Tugagan zona qayta "kirish" bermasligi uchun: boshidan uzoqlashgandan keyingina tozalanadi
            lastFinishedId?.let { id ->
                val z = zones.firstOrNull { it.id == id }
                if (z == null || Geo.distance(fix.point, z.start) > triggerRadiusM * 3) lastFinishedId = null
            }
            val entered = zones.firstOrNull { z ->
                z.id != lastFinishedId && passed(prev, fix, z.start)
            }
            if (entered != null) {
                active = entered
                passTimes = arrayOfNulls(entered.checkpoints.size)
                passTimes[0] = fix.timeMillis
                nextIdx = 1
                travelled = 0.0
                worstPairAvg = 0.0
                lastSegmentAvg = null
                minDistToNext = Geo.distance(fix.point, entered.checkpoints[1])
                return inZoneState(entered, fix, speedKmh)
            }
            val nearest = zones.minByOrNull { Geo.distance(fix.point, it.start) }
            return TrackerState.Idle(
                currentSpeedKmh = speedKmh,
                nearestZone = nearest,
                distanceToNearestM = nearest?.let { Geo.distance(fix.point, it.start) },
            )
        }

        if (prev != null) {
            val step = Geo.distance(prev.point, fix.point)
            // GPS sakrashlarini e'tiborsiz qoldiramiz (soniyasiga 70 m dan ortiq ~ 250 km/soat)
            val dt = (fix.timeMillis - prev.timeMillis) / 1000.0
            if (dt > 0 && step / dt < 70.0) travelled += step
        }

        val cps = zone.checkpoints
        val cum = zone.cumulative
        val last = cps.size - 1

        // Keyingi kameradan (yoki GPS uzilishida undan keyingisidan) o'tdikmi
        val distNext = Geo.distance(fix.point, cps[nextIdx])
        if (distNext < minDistToNext) minDistToNext = distNext
        val passedByOvershoot = minDistToNext <= triggerRadiusM * 3 && distNext > minDistToNext + triggerRadiusM
        val passedIdx = (nextIdx..last).firstOrNull { k -> passed(prev, fix, cps[k]) }
            ?: if (passedByOvershoot) nextIdx else null

        if (passedIdx != null) {
            val t = fix.timeMillis
            passTimes[passedIdx] = t
            // Tugagan juftliklarni tekshiramiz
            for (i in 0 until passedIdx) {
                val ti = passTimes[i] ?: continue
                val sec = (t - ti) / 1000.0
                if (sec <= 0) continue
                val avg = (cum[passedIdx] - cum[i]) / sec * 3.6
                if (avg > worstPairAvg) worstPairAvg = avg
                if (i == passedIdx - 1) lastSegmentAvg = avg
            }
            travelled = cum[passedIdx]
            nextIdx = passedIdx + 1
            if (passedIdx == last) {
                val total = (t - passTimes[0]!!) / 1000.0
                val avg = if (total > 0) zone.effectiveLength / total * 3.6 else 0.0
                val result = TrackerState.ZoneFinished(
                    zone = zone,
                    totalSeconds = total,
                    averageKmh = avg,
                    worstPairAverageKmh = maxOf(worstPairAvg, avg),
                    violated = maxOf(worstPairAvg, avg) > zone.limitKmh,
                )
                lastFinishedId = zone.id
                reset()
                return result
            }
            minDistToNext = Geo.distance(fix.point, cps[nextIdx])
        } else {
            // Yo'ldan chiqib ketdi yoki teskari yo'nalishda ketyapti
            val expectedToNext = (cum[nextIdx] - travelled).coerceAtLeast(0.0)
            val lost = travelled > zone.effectiveLength + overrunM || distNext > expectedToNext + overrunM
            if (lost) {
                reset()
                return TrackerState.Idle(speedKmh, null, null)
            }
        }
        return inZoneState(zone, fix, speedKmh)
    }

    /** Oxirgi harakat kesmasi (prev -> fix) kamera nuqtasi yonidan o'tdimi. */
    private fun passed(prev: Fix?, fix: Fix, camera: GeoPoint): Boolean {
        if (Geo.distance(fix.point, camera) <= triggerRadiusM) return true
        if (prev == null) return false
        val dt = (fix.timeMillis - prev.timeMillis) / 1000.0
        if (dt <= 0 || dt > 30) return false
        return Geo.distanceToSegment(camera, prev.point, fix.point) <= triggerRadiusM
    }

    private fun inZoneState(zone: Zone, fix: Fix, speedKmh: Double?): TrackerState.InZone {
        val cum = zone.cumulative
        val n = cum.size
        val limit = zone.limitKmh.toDouble()
        val limitMps = limit / 3.6
        val now = fix.timeMillis
        val progress = travelled.coerceIn(0.0, zone.effectiveLength)
        val cur = speedKmh ?: 0.0

        var bindingIdx = n - 1
        var bindingMax: Double? = null
        var bindingMinRem = 0.0
        var worstProjected = 0.0

        // Har bir juftlik (o'tilgan kamera i -> hali o'tilmagan kamera j) cheklov hisoblanadi
        for (i in 0 until nextIdx) {
            val ti = passTimes[i] ?: continue
            val elapsed = (now - ti) / 1000.0
            for (j in nextIdx until n) {
                val len = cum[j] - cum[i]
                val rem = (cum[j] - progress).coerceAtLeast(0.0)
                val minRem = (len / limitMps - elapsed).coerceAtLeast(0.0)
                val maxSp = if (minRem <= 0.0) null else rem / minRem * 3.6
                if (maxSp != null && (bindingMax == null || maxSp < bindingMax)) {
                    bindingMax = maxSp
                    bindingIdx = j
                    bindingMinRem = minRem
                }
                val projected = if (cur > 0.5) len / (elapsed + rem / (cur / 3.6)) * 3.6
                else if (elapsed > 0) (len - rem) / elapsed * 3.6 else 0.0
                if (projected > worstProjected) worstProjected = projected
            }
        }

        val elapsedAll = (now - passTimes[0]!!) / 1000.0
        val avg = if (elapsedAll > 0) progress / elapsedAll * 3.6 else 0.0

        val risk = when {
            bindingMax != null && bindingMax < limit * 0.5 -> Risk.DANGER
            worstProjected > limit -> Risk.DANGER
            worstProjected > limit * cautionRatio -> Risk.CAUTION
            else -> Risk.SAFE
        }
        return TrackerState.InZone(
            zone = zone,
            elapsedSeconds = elapsedAll,
            travelledMeters = progress,
            remainingMeters = zone.effectiveLength - progress,
            averageKmh = avg,
            currentSpeedKmh = speedKmh,
            nextCheckpoint = nextIdx,
            checkpointCount = n,
            distanceToNextCheckpointM = (cum[nextIdx] - progress).coerceAtLeast(0.0),
            bindingCheckpoint = bindingIdx,
            minRemainingSeconds = bindingMinRem,
            maxSpeedForRestKmh = bindingMax?.coerceAtMost(limit),
            projectedAverageKmh = worstProjected,
            lastSegmentAverageKmh = lastSegmentAvg,
            risk = risk,
        )
    }
}
