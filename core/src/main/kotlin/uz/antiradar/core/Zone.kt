package uz.antiradar.core

/**
 * O'rtacha tezlik nazorati zonasi: kameralar zanjiri start -> via... -> end.
 * O'rtacha tezlik har qanday ikki kamera orasida tekshiriladi (A–B, B–C va A–C).
 *
 * @param lengthMeters start dan end gacha yo'l uzunligi. 0 bo'lsa, to'g'ri chiziqlar yig'indisi olinadi.
 * @param limitKmh ruxsat etilgan tezlik.
 * @param via oraliq kameralar, yo'nalish tartibida.
 */
data class Zone(
    val id: String,
    val name: String,
    val start: GeoPoint,
    val end: GeoPoint,
    val limitKmh: Int,
    val lengthMeters: Double = 0.0,
    val via: List<GeoPoint> = emptyList(),
) {
    val checkpoints: List<GeoPoint> get() = listOf(start) + via + listOf(end)

    /** Har bir kameragacha yo'l bo'yicha masofa (birinchisi 0, oxirgisi — zona uzunligi). */
    val cumulative: List<Double> by lazy {
        val cps = checkpoints
        val raw = ArrayList<Double>(cps.size)
        raw += 0.0
        for (i in 1 until cps.size) raw += raw[i - 1] + Geo.distance(cps[i - 1], cps[i])
        val total = raw.last()
        if (lengthMeters > 0 && total > 0) raw.map { it * lengthMeters / total } else raw
    }

    val effectiveLength: Double get() = cumulative.last()

    /** Zonani ruxsat etilgan tezlikda bosib o'tish uchun eng kam vaqt (soniya). */
    val minTimeSeconds: Double
        get() = effectiveLength / (limitKmh / 3.6)
}
