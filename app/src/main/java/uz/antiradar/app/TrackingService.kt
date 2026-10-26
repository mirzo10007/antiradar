package uz.antiradar.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import uz.antiradar.core.AutoState
import uz.antiradar.core.AutoTracker
import uz.antiradar.core.Fix
import uz.antiradar.core.GeoPoint
import uz.antiradar.core.Risk
import kotlin.math.roundToInt

/** Fon xizmati: GPS ni o'qiydi, kameralar orasidagi o'rtacha tezlikni avtomatik hisoblaydi, ogohlantiradi. */
class TrackingService : Service(), LocationListener {

    private lateinit var repo: ZoneRepository
    private lateinit var tracker: AutoTracker
    private lateinit var alerts: Alerts
    private lateinit var lm: LocationManager

    private var lastLocation: Location? = null
    private var lastRisk = Risk.SAFE
    private val announced = mutableSetOf<String>() // e'lon qilingan oldindagi kameralar
    private var lastNotifText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = ZoneRepository(this)
        tracker = AutoTracker(repo.cameras(), defaultLimitKmh = repo.defaultLimitKmh)
        alerts = Alerts(this)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                tracker.setCameras(repo.cameras())
                tracker.defaultLimitKmh = repo.defaultLimitKmh
                TrackingBus.update { it.copy(cameraCount = tracker.cameras.size) }
                return START_STICKY
            }
        }
        startInForeground()
        startGps()
        TrackingBus.update { it.copy(running = true, cameraCount = tracker.cameras.size) }
        return START_STICKY
    }

    private fun startInForeground() {
        val n = buildNotification("GPS kuzatuvi yoqilgan")
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        runCatching {
            lm.removeUpdates(this)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        }
    }

    override fun onDestroy() {
        runCatching { lm.removeUpdates(this) }
        alerts.release()
        TrackingBus.update { it.copy(running = false, state = null) }
        super.onDestroy()
    }

    override fun onLocationChanged(loc: Location) {
        // Juda noaniq nuqtalarni tashlab yuboramiz
        if (loc.hasAccuracy() && loc.accuracy > 50f) {
            TrackingBus.update { it.copy(gpsAccuracyM = loc.accuracy) }
            return
        }
        lastLocation = loc
        val point = GeoPoint(loc.latitude, loc.longitude)
        val state = tracker.update(
            Fix(
                point = point,
                timeMillis = loc.time,
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 10.0,
            ),
        )
        handleAlerts(state)
        updateNotification(state)

        TrackingBus.update { s ->
            s.copy(
                state = state,
                lastPoint = point,
                gpsAccuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                lastPass = state.passed ?: s.lastPass,
                lastPassTime = if (state.passed != null) System.currentTimeMillis() else s.lastPassTime,
            )
        }
    }

    private fun handleAlerts(state: AutoState) {
        val ev = state.passed
        if (ev != null) {
            val worst = ev.worst
            if (worst != null) {
                val avg = worst.averageKmh.roundToInt()
                alerts.say(
                    if (ev.violated) "Kamera. Oraliq o'rtacha $avg. Chegaradan oshdi" else "Kamera. Oraliq o'rtacha $avg",
                    urgent = ev.violated,
                )
            }
            lastRisk = state.risk
            return
        }

        // Oldindagi kamera: 500 m qolganda bir marta
        val ahead = state.ahead
        if (ahead != null && ahead.distanceM <= 500 && announced.add(ahead.camera.id)) {
            val limit = ahead.camera.limitKmh
            val max = state.binding?.takeIf { it.to?.id == ahead.camera.id }?.maxSpeedKmh?.roundToInt()
            val text = buildString {
                append("Kamera, 500 metr")
                if (limit != null) append(". Chegara $limit")
                if (max != null && limit != null && max < limit) append(". O'rtacha $max dan oshmang")
            }
            val speed = state.currentSpeedKmh
            alerts.say(text, urgent = limit != null && speed != null && speed > limit)
        }
        if (announced.size > 200) announced.clear()

        if (state.risk == Risk.DANGER && lastRisk != Risk.DANGER) {
            val max = state.binding?.maxSpeedKmh?.roundToInt()
            alerts.say(
                if (max != null) "O'rtacha tezlik oshdi. $max dan oshmang" else "O'rtacha tezlik oshdi. Sekinlang",
                urgent = true,
            )
        } else if (state.risk == Risk.CAUTION && lastRisk == Risk.SAFE) {
            alerts.say("Ehtiyot bo'ling, o'rtacha tezlik chegaraga yaqin")
        }
        lastRisk = state.risk
    }

    private fun updateNotification(state: AutoState) {
        val b = state.binding
        val ahead = state.ahead
        val text = when {
            b?.to != null -> "Kameragacha ${Format.meters(b.remainingM)}: o'rtacha ≤ " +
                Format.kmh(b.maxSpeedKmh ?: b.limitKmh.toDouble())
            ahead != null -> "Oldinda kamera: ${Format.meters(ahead.distanceM)}"
            else -> "Tezlik: ${Format.kmh(state.currentSpeedKmh)}"
        }
        if (text == lastNotifText) return
        lastNotifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Kuzatuv", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    // Eski Android versiyalari uchun (API < 30 da abstract)
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    companion object {
        const val ACTION_STOP = "uz.antiradar.STOP"
        const val ACTION_RELOAD = "uz.antiradar.RELOAD"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIF_ID = 1

        fun send(context: Context, action: String) {
            context.startService(Intent(context, TrackingService::class.java).setAction(action))
        }
    }
}
