package uz.antiradar.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import uz.antiradar.core.Camera
import uz.antiradar.core.Risk

class MainActivity : AppCompatActivity() {

    private lateinit var repo: ZoneRepository
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var currentSpeed: TextView
    private lateinit var details: TextView
    private lateinit var toggleButton: Button
    private lateinit var baseInfo: TextView
    private lateinit var limitButton: Button

    private val listener: (UiSnapshot) -> Unit = { render(it) }
    private var osmLoading = false
    private var cameraCount = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) startTracking()
            else toast("GPS ruxsati kerak")
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                repo.importJson(text)
            }.onSuccess { n ->
                toast("$n ta kamera qo'shildi")
                baseChanged()
            }.onFailure { toast("Faylni o'qib bo'lmadi: ${it.message}") }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(repo.exportJson()) }
            }.onSuccess { toast("Saqlandi") }.onFailure { toast("Xato: ${it.message}") }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = ZoneRepository(this)
        cameraCount = repo.cameras().size

        statusCard = findViewById(R.id.statusCard)
        statusTitle = findViewById(R.id.statusTitle)
        currentSpeed = findViewById(R.id.currentSpeed)
        details = findViewById(R.id.details)
        toggleButton = findViewById(R.id.toggleButton)
        baseInfo = findViewById(R.id.baseInfo)
        limitButton = findViewById(R.id.limitButton)

        toggleButton.setOnClickListener {
            if (TrackingBus.snapshot.running) TrackingService.send(this, TrackingService.ACTION_STOP)
            else requestAndStart()
        }
        findViewById<Button>(R.id.addCameraButton).setOnClickListener { addCameraHere() }
        findViewById<Button>(R.id.osmButton).setOnClickListener { updateFromOsm(silent = false) }
        limitButton.setOnClickListener { askDefaultLimit() }
        findViewById<Button>(R.id.importButton).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        findViewById<Button>(R.id.exportButton).setOnClickListener { exportLauncher.launch("kameralar.json") }

        // Hammasi avtomatik: kuzatuv darhol yoqiladi, baza kuniga bir marta yangilanadi
        if (savedInstanceState == null && !TrackingBus.snapshot.running) requestAndStart()
        if (System.currentTimeMillis() - repo.osmUpdatedAt() > OsmUpdater.UPDATE_EVERY_MS) updateFromOsm(silent = true)
    }

    override fun onStart() {
        super.onStart()
        TrackingBus.subscribe(listener)
    }

    override fun onStop() {
        TrackingBus.unsubscribe(listener)
        super.onStop()
    }

    private fun requestAndStart() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            startTracking()
            return
        }
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startTracking() {
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
    }

    private fun baseChanged() {
        cameraCount = repo.cameras().size
        if (TrackingBus.snapshot.running) TrackingService.send(this, TrackingService.ACTION_RELOAD)
        render(TrackingBus.snapshot)
    }

    private fun updateFromOsm(silent: Boolean) {
        if (osmLoading) return
        osmLoading = true
        if (!silent) toast("Yuklanmoqda… (1-2 daqiqa)")
        OsmUpdater.update(repo) { res ->
            osmLoading = false
            res.onSuccess { r ->
                toast("Baza yangilandi: ${r.cameras.size} ta kamera, ${r.zones.size} ta zona")
                baseChanged()
            }.onFailure { if (!silent) toast("Yuklab bo'lmadi: ${it.message}") }
        }
    }

    private fun askNumber(title: String, initial: Int, onOk: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial.toString())
        }
        val box = LinearLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v == null || v !in 10..150) toast("Tezlik noto'g'ri") else onOk(v)
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun addCameraHere() {
        val p = TrackingBus.snapshot.lastPoint
        if (p == null) {
            toast("GPS signalini kuting")
            return
        }
        askNumber("Kamera chegarasi, km/soat", repo.defaultLimitKmh) { limit ->
            repo.addUserCamera(Camera("u_" + System.currentTimeMillis(), p, limit))
            toast("Kamera qo'shildi")
            baseChanged()
        }
    }

    private fun askDefaultLimit() {
        askNumber("Chegara noma'lum kameralar uchun, km/soat", repo.defaultLimitKmh) { v ->
            repo.defaultLimitKmh = v
            baseChanged()
        }
    }

    private fun render(s: UiSnapshot) {
        toggleButton.text = if (s.running) "To'xtatish" else "Boshlash"
        limitButton.text = "Standart: ${repo.defaultLimitKmh}"
        val updated = repo.osmUpdatedAt()
        baseInfo.text = "Bazada: $cameraCount ta kamera" +
            (if (updated > 0) " · yangilangan " + android.text.format.DateFormat.format("dd.MM.yyyy", updated) else " · baza hali yuklanmagan") +
            (s.gpsAccuracyM?.let { "\nGPS aniqligi: ±${it.toInt()} m" } ?: "")

        if (!s.running) {
            setCard("#455A64", "Kuzatuv o'chiq", "—", "")
            return
        }
        val st = s.state
        if (st == null) {
            setCard("#455A64", "GPS signal kutilmoqda…", "—", "")
            return
        }
        val color = when (st.risk) {
            Risk.SAFE -> "#1E3A5F"
            Risk.CAUTION -> "#EF8F00"
            Risk.DANGER -> "#C62828"
        }
        val lines = mutableListOf<String>()
        val b = st.binding
        val target = b?.to
        if (b != null && target != null) {
            val max = b.maxSpeedKmh
            lines += if (max != null) "Keyingi kameragacha o'rtacha ≤ ${Format.kmh(max)}"
            else "Vaqt yetarli — oddiy chegara ${b.limitKmh}"
            if (max != null) lines += "Kamida ${Format.duration(b.minRemainingSeconds)} yurish kerak"
            lines += "Prognoz: ${Format.kmh(b.projectedKmh)} (chegara ${b.limitKmh})"
        }
        val ahead = st.ahead
        if (ahead != null) {
            lines += "Oldinda kamera: ${Format.meters(ahead.distanceM)}" +
                (ahead.camera.limitKmh?.let { " · chegara $it" } ?: "")
        }
        st.sections.lastOrNull()?.let { sec ->
            lines += "Oxirgi kameradan: ${Format.meters(sec.travelledM)}, o'rtacha ${Format.kmh(sec.averageKmh)}"
        }
        val pass = s.lastPass
        if (pass != null && System.currentTimeMillis() - s.lastPassTime < 60_000) {
            pass.worst?.let { w ->
                lines += (if (pass.violated) "⚠ " else "✓ ") +
                    "O'tilgan oraliq: ${Format.kmh(w.averageKmh)} (chegara ${w.limitKmh})"
            }
        }
        if (lines.isEmpty()) lines += "Yaqinda kamera yo'q"
        val title = when (st.risk) {
            Risk.SAFE -> if (st.sections.isNotEmpty() && target != null) "Nazorat oralig'idasiz" else "Yo'lda"
            Risk.CAUTION -> "Ehtiyot bo'ling"
            Risk.DANGER -> "Sekinlang!"
        }
        setCard(color, title, Format.kmh(st.currentSpeedKmh), lines.joinToString("\n"))
    }

    private fun setCard(color: String, title: String, speed: String, body: String) {
        statusCard.setBackgroundColor(Color.parseColor(color))
        statusTitle.text = title
        currentSpeed.text = speed
        details.text = body
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
