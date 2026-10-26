package uz.antiradar.app

import android.os.Handler
import android.os.Looper
import uz.antiradar.core.AutoState
import uz.antiradar.core.GeoPoint
import uz.antiradar.core.PassEvent

data class UiSnapshot(
    val running: Boolean = false,
    val state: AutoState? = null,
    val lastPoint: GeoPoint? = null,
    val gpsAccuracyM: Float? = null,
    val cameraCount: Int = 0,
    /** Oxirgi o'tilgan kamera natijasi (ekranda bir muddat ko'rsatish uchun). */
    val lastPass: PassEvent? = null,
    val lastPassTime: Long = 0L,
)

/** Xizmat va ekran o'rtasidagi oddiy "shina". Tinglovchilar asosiy oqimda chaqiriladi. */
object TrackingBus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(UiSnapshot) -> Unit>()

    @Volatile
    var snapshot = UiSnapshot()
        private set

    fun publish(s: UiSnapshot) {
        snapshot = s
        main.post { listeners.toList().forEach { it(s) } }
    }

    fun update(block: (UiSnapshot) -> UiSnapshot) = publish(block(snapshot))

    fun subscribe(l: (UiSnapshot) -> Unit) {
        listeners += l
        l(snapshot)
    }

    fun unsubscribe(l: (UiSnapshot) -> Unit) {
        listeners -= l
    }
}
