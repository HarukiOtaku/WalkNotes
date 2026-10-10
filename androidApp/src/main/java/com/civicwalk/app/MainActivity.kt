package com.civicwalk.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.os.SystemClock
import android.widget.Chronometer
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.exifinterface.media.ExifInterface
import com.civicwalk.core.TextTruncate
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WalkNotes — a walking notebook that runs entirely on the phone.
 *
 * Photograph something you pass and an open-weight Gemma model, on the device, writes a
 * description, an impression or a field note about it. The photo, the coordinates, the words and
 * the map of where you have walked never leave the phone.
 *
 * Four tabs, map first, bottom bar.
 */
class MainActivity : AppCompatActivity() {

    // note tab
    private lateinit var imgPreview: ImageView
    private lateinit var tvExif: TextView
    private lateinit var tvPhotoStats: TextView
    private lateinit var btnBlur: MaterialButton
    private lateinit var tvPlace: TextView
    private lateinit var etAddr: EditText
    private lateinit var radioMode: RadioGroup
    private lateinit var tvStatus: TextView
    private lateinit var etNote: EditText
    private lateinit var tvBytes: TextView
    private lateinit var etSupp: EditText

    // map tab
    private lateinit var fogMap: FogMapView
    private lateinit var mapContainer: FrameLayout
    private lateinit var btnMapReal: MaterialButton
    private lateinit var btnMapGrid: MaterialButton
    private lateinit var kpiCells: TextView
    private lateinit var kpiKm: TextView
    private lateinit var tvRoute: TextView
    private lateinit var btnRoute: MaterialButton
    private lateinit var recLive: View
    private lateinit var recClock: Chronometer
    private lateinit var recPulse: TextView

    // records tab
    private lateinit var tvRecords: TextView
    private lateinit var recordsList: LinearLayout
    private var recordsFilter = "all"
    private var recordsDate = "all"
    private var recordsQuery = ""
    private var tipShownThisSession = false

    private val REQ_EXPORT = 1005
    private val REQ_IMPORT = 1006

    // settings tab
    private lateinit var radioLang: RadioGroup
    private lateinit var radioBackend: RadioGroup
    private lateinit var modelList: LinearLayout
    private lateinit var tvStorage: TextView
    private lateinit var tvEngine: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvCrash: TextView
    private lateinit var loadProgress: LinearProgressIndicator
    private lateinit var btnLoadCancel: MaterialButton
    private lateinit var tvLoadInfo: TextView

    private class ModelRow(
        val entry: ModelEntry,
        val status: TextView,
        val bar: LinearProgressIndicator,
        val primary: MaterialButton,
        val load: MaterialButton,
        val delete: MaterialButton
    )

    private val rows = LinkedHashMap<String, ModelRow>()

    // state
    private var photoFile: File? = null
    private var archiveFile: File? = null
    private var displayBitmap: Bitmap? = null
    private var blurMode = false
    private val routePoints = ArrayList<DoubleArray>()
    private var showLiveLine = false
    private var replayAll: List<DoubleArray>? = null
    private var replayIdx = 0
    private var sessionStart = 0L
    private var exifTime: String? = null
    private var exifLoc: DoubleArray? = null
    private var gpsLoc: DoubleArray? = null

    private val cells = LinkedHashSet<String>()
    private var distanceKm = 0.0
    private var lastPoint: DoubleArray? = null
    private var recording = false
    private var lastFixAt = 0L
    private var routeListener: LocationListener? = null

    @Volatile private var dlStop = false
    private var dlThread: Thread? = null

    @Volatile private var loadCancelled = false
    @Volatile private var loadPhase = ""
    private var loadThread: Thread? = null

    @Volatile private var busy = false
    private var busyStart = 0L

    private var mapReady = false
    private var mapCentered = false
    private var currentTab = 0
    private var mapMode = "real"
    private var mapView: MapView? = null
    private var mapCreated = false
    private var mapRef: MapLibreMap? = null

    private val main = Handler(Looper.getMainLooper())
    private val replayTick = Runnable { advanceReplay() }
    private val prefs by lazy { getSharedPreferences("walknotes", MODE_PRIVATE) }

    private val REQ_CAMERA = 1001
    private val REQ_PICK = 1002
    private val REQ_PERM = 1003

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is light by design; never let a dark system theme repaint it.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WalkRecordService.sink = { loc -> applyWalkFix(loc) }

        bindViews()
        loadState()
        wireTabs()
        wireNoteTab()
        wireSettings()

        mapMode = prefs.getString("mapmode", "real") ?: "real"
        setMapMode(mapMode)

        requestPermissions(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) + if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
            REQ_PERM
        )

        buildModelRows()
        refreshModelRows()
        renderRecords()
        renderRoute()
        renderEngine()
        renderCrash()
    }

    private fun bindViews() {
        imgPreview = findViewById(R.id.imgPreview)
        tvExif = findViewById(R.id.tvExif)
        tvPhotoStats = findViewById(R.id.tvPhotoStats)
        btnBlur = findViewById(R.id.btnBlur)
        tvPlace = findViewById(R.id.tvPlace)
        etAddr = findViewById(R.id.etAddr)
        radioMode = findViewById(R.id.radioMode)
        tvStatus = findViewById(R.id.tvStatus)
        etNote = findViewById(R.id.etDraft)
        tvBytes = findViewById(R.id.tvBytes)
        etSupp = findViewById(R.id.etSupp)

        fogMap = findViewById(R.id.fogMap)
        mapContainer = findViewById(R.id.mapContainer)
        btnMapReal = findViewById(R.id.btnMapReal)
        btnMapGrid = findViewById(R.id.btnMapGrid)
        kpiCells = findViewById(R.id.kpiCells)
        kpiKm = findViewById(R.id.kpiKm)
        tvRoute = findViewById(R.id.tvRoute)
        btnRoute = findViewById(R.id.btnRoute)
        recLive = findViewById(R.id.recLive)
        recClock = findViewById(R.id.recClock)
        recPulse = findViewById(R.id.recPulse)
        recClock.setOnChronometerTickListener { updateRecPulse() }
        findViewById<MaterialButton>(R.id.btnReplay).setOnClickListener { showReplayDialog() }
        findViewById<MaterialButton>(R.id.btnHideLine).setOnClickListener { hideRouteLine() }

        tvRecords = findViewById(R.id.tvRecords)
        recordsList = findViewById(R.id.recordsList)

        radioLang = findViewById(R.id.radioLang)
        radioBackend = findViewById(R.id.radioBackend)
        modelList = findViewById(R.id.modelList)
        tvStorage = findViewById(R.id.tvStorage)
        tvEngine = findViewById(R.id.tvEngine)
        tvVersion = findViewById(R.id.tvVersion)
        tvCrash = findViewById(R.id.tvCrash)
        loadProgress = findViewById(R.id.loadProgress)
        btnLoadCancel = findViewById(R.id.btnLoadCancel)
        tvLoadInfo = findViewById(R.id.tvLoadInfo)
    }

    private fun wireTabs() {
        val bar = findViewById<TabLayout>(R.id.tabBar)
        // The map is the first page: walking is what you actually do with this app.
        bar.addTab(bar.newTab().setText(R.string.tab_map))
        bar.addTab(bar.newTab().setText(R.string.tab_report))
        bar.addTab(bar.newTab().setText(R.string.tab_records))
        bar.addTab(bar.newTab().setText(R.string.tab_settings))
        bar.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        showPanel(0)
    }

    private fun showPanel(index: Int) {
        currentTab = index
        val panels = intArrayOf(R.id.panelMap, R.id.panelReport, R.id.panelRecords, R.id.panelSettings)
        panels.forEachIndexed { i, id ->
            findViewById<View>(id).visibility = if (i == index) View.VISIBLE else View.GONE
        }
        if (index == 0) {
            if (mapMode == "real") initMap()
            renderFog()
            pushMapData()
            maybeShowTip()
        }
    }

    private fun wireNoteTab() {
        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener { takePhoto() }
        findViewById<MaterialButton>(R.id.btnUpload).setOnClickListener { pickPhoto() }
        findViewById<MaterialButton>(R.id.btnGps).setOnClickListener { useGps() }
        findViewById<MaterialButton>(R.id.btnExifLoc).setOnClickListener { useExifLocation() }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener { copyNote() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveNote() }
        findViewById<MaterialButton>(R.id.btnClearRecords).setOnClickListener { confirmClearRecords() }
        findViewById<MaterialButton>(R.id.btnAnalyse).setOnClickListener { generateNote() }
        val filters = findViewById<MaterialButtonToggleGroup>(R.id.filterRecords)
        filters.check(R.id.filterAll)
        filters.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            recordsFilter = when (id) {
                R.id.filterPhoto -> "photo"
                R.id.filterNoPhoto -> "nophoto"
                else -> "all"
            }
            renderRecords()
        }
        fogMap.onNoteTap = { i -> showNotesFor(i) }
        val dates = findViewById<MaterialButtonToggleGroup>(R.id.filterDate)
        dates.check(R.id.dateAll)
        dates.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            recordsDate = when (id) {
                R.id.dateToday -> "today"
                R.id.dateWeek -> "week"
                R.id.dateMonth -> "month"
                else -> "all"
            }
            renderRecords()
        }
        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                recordsQuery = s?.toString()?.trim().orEmpty()
                renderRecords()
            }
        })
        btnRoute.setOnClickListener { toggleRoute() }
        findViewById<MaterialButton>(R.id.btnRecenter).setOnClickListener { recenter() }
        findViewById<MaterialButton>(R.id.btnTipClose).setOnClickListener { dismissTip() }
        btnMapReal.setOnClickListener { setMapMode("real") }
        btnMapGrid.setOnClickListener { setMapMode("grid") }

        btnBlur.setOnClickListener {
            blurMode = !blurMode
            btnBlur.text = getString(if (blurMode) R.string.btn_blur_on else R.string.btn_blur)
        }
        imgPreview.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN && blurMode) {
                blurAt(v as ImageView, ev); true
            } else false
        }
        etNote.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateBytes()
        })
    }

    private fun wireSettings() {
        val current = AppCompatDelegate.getApplicationLocales()
        when {
            current.isEmpty -> radioLang.check(R.id.rbSystem)
            current.toLanguageTags().startsWith("zh-Hans") -> radioLang.check(R.id.rbHans)
            current.toLanguageTags().startsWith("zh") -> radioLang.check(R.id.rbHant)
            else -> radioLang.check(R.id.rbEn)
        }
        radioLang.setOnCheckedChangeListener { _, checkedId ->
            val tag = when (checkedId) {
                R.id.rbHant -> "zh-Hant-HK"
                R.id.rbHans -> "zh-Hans-CN"
                R.id.rbEn -> "en"
                else -> ""
            }
            // AppCompat persists this per app and recreates the activity for us.
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag)
            )
        }

        radioBackend.check(
            when (backendChoice()) {
                "gpu" -> R.id.rbGpuOnly
                "auto" -> R.id.rbAuto
                else -> R.id.rbCpu
            }
        )
        radioBackend.setOnCheckedChangeListener { _, id ->
            val v = when (id) {
                R.id.rbGpuOnly -> "gpu"
                R.id.rbAuto -> "auto"
                else -> "cpu"
            }
            prefs.edit().putString("backend", v).apply()
        }

        btnLoadCancel.setOnClickListener {
            loadCancelled = true
            toast(getString(R.string.st_cancelled))
        }
        findViewById<MaterialButton>(R.id.btnWipe).setOnClickListener { confirmWipe() }
        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener { startExport() }
        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener { startImport() }
        tvVersion.text = getString(R.string.app_name) + " " + versionName()

        val sw = findViewById<MaterialSwitch>(R.id.switchWalkNotif)
        sw.isChecked = prefs.getBoolean("walk_notif", true)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("walk_notif", on).apply()
            if (recording) {
                if (on) startWalkForeground() else {
                    WalkRecordService.stop(this)
                    startWalkInApp()
                }
            }
        }
        val tipSw = findViewById<MaterialSwitch>(R.id.switchMapTips)
        tipSw.isChecked = prefs.getBoolean("map_tips", true)
        tipSw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("map_tips", on).apply()
            if (!on) hideTip()
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (_: Throwable) { "-" }

    // ----------------------------------------------------------------- model library

    private fun modelDir(): File = getExternalFilesDir(null) ?: filesDir

    private fun modelTarget(e: ModelEntry): File = File(modelDir(), e.fileName)

    private fun modelPartial(e: ModelEntry): File = File(modelDir(), e.fileName + ".part")

    private fun modelFiles(): List<File> =
        (modelDir().listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".litertlm") || it.name.endsWith(".part") }

    /** A model counts as installed only when its exact byte count is present. */
    private fun isInstalled(e: ModelEntry): Boolean {
        val f = modelTarget(e)
        return f.isFile && f.length() == e.sizeBytes
    }

    private fun buildModelRows() {
        modelList.removeAllViews()
        rows.clear()
        ModelCatalog.entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
            }
            val title = TextView(this).apply {
                text = "${entry.label}  ·  ${entry.sizeLabel}  ·  " +
                    getString(if (entry.vision) R.string.model_vision_tag else R.string.model_text_tag)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val desc = TextView(this).apply {
                text = if (isChineseUi()) entry.noteZh else entry.noteEn
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#6B7A70"))
            }
            val status = TextView(this).apply {
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#6B7A70"))
            }
            val bar = LinearProgressIndicator(this).apply {
                max = 100
                isIndeterminate = false
                visibility = View.GONE
                trackColor = android.graphics.Color.parseColor("#DFE6E1")
                setIndicatorColor(android.graphics.Color.parseColor("#2F7D52"))
            }
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val primary = MaterialButton(this).apply { text = getString(R.string.model_download_now) }
            val load = MaterialButton(this).apply {
                text = getString(R.string.btn_load)
                visibility = View.GONE
            }
            val delete = MaterialButton(this).apply {
                text = getString(R.string.btn_delete_short)
                visibility = View.GONE
            }
            btns.addView(primary, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            btns.addView(load, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            btns.addView(delete, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(title)
            row.addView(desc)
            row.addView(status)
            row.addView(bar)
            row.addView(btns)
            modelList.addView(row)

            rows[entry.id] = ModelRow(entry, status, bar, primary, load, delete)

            primary.setOnClickListener { startDownload(entry) }
            load.setOnClickListener { loadModel(entry) }
            delete.setOnClickListener { confirmDeleteModel(entry) }
        }
    }

    private fun isChineseUi(): Boolean {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val sys = resources.configuration.locales[0].toLanguageTag()
        return (if (tag.isEmpty()) sys else tag).startsWith("zh")
    }

    private fun refreshModelRows() {
        var onDisk = 0L
        ModelCatalog.entries.forEach { e ->
            val row = rows[e.id] ?: return@forEach
            val installed = isInstalled(e)
            val partial = modelPartial(e)
            when {
                installed -> {
                    onDisk += modelTarget(e).length()
                    row.status.text = getString(R.string.model_downloaded) + " · " + e.sizeLabel
                    row.primary.visibility = View.GONE
                    row.load.visibility = View.VISIBLE
                    row.delete.visibility = View.VISIBLE
                }
                partial.isFile && partial.length() > 0 -> {
                    onDisk += partial.length()
                    val pct = ((partial.length() * 100) / e.sizeBytes).toInt().coerceIn(0, 100)
                    row.status.text = getString(R.string.dl_progress, mb(partial.length()), e.sizeLabel, pct)
                    row.primary.text = getString(R.string.btn_resume)
                    row.primary.visibility = View.VISIBLE
                    row.load.visibility = View.GONE
                    row.delete.visibility = View.VISIBLE
                }
                else -> {
                    onDisk += legacyBytes(e)
                    row.status.text = getString(R.string.model_absent)
                    row.primary.text = getString(R.string.model_download_now)
                    row.primary.visibility = View.VISIBLE
                    row.load.visibility = View.GONE
                    row.delete.visibility = View.GONE
                }
            }
        }
        tvStorage.text = getString(R.string.storage_fmt, mb(onDisk), mb(modelDir().usableSpace))
    }

    /** A file left under the final name by an older build that did not use a .part file. */
    private fun legacyBytes(e: ModelEntry): Long =
        modelTarget(e).takeIf { it.isFile && it.length() < e.sizeBytes }?.length() ?: 0L

    private fun startDownload(entry: ModelEntry) {
        if (dlThread?.isAlive == true) return
        if (isInstalled(entry)) { refreshModelRows(); return }

        val partial = modelPartial(entry)
        var already = if (partial.isFile) partial.length() else 0L
        if (already == 0L) {
            val legacy = modelTarget(entry)
            if (legacy.isFile && legacy.length() < entry.sizeBytes) {
                // Left over from a build that downloaded straight to the final name: resume it.
                legacy.renameTo(partial)
                already = partial.length()
            }
        }

        dlStop = false
        val row = rows[entry.id]
        row?.bar?.visibility = View.VISIBLE
        row?.status?.text = getString(R.string.dl_progress, mb(already), entry.sizeLabel, 0)

        dlThread = Thread {
            try {
                val conn = (URL(entry.url).openConnection() as HttpURLConnection)
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (already > 0) conn.setRequestProperty("Range", "bytes=$already-")
                val code = conn.responseCode
                val resuming = already > 0 && code == HttpURLConnection.HTTP_PARTIAL
                if (already > 0 && !resuming) partial.delete()      // server ignored the range

                var total = if (resuming) already else 0L
                conn.inputStream.use { input ->
                    FileOutputStream(partial, resuming).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var n: Int
                        var lastPost = 0L
                        while (input.read(buf).also { n = it } > 0) {
                            if (dlStop) break
                            out.write(buf, 0, n)
                            total += n
                            val now = System.currentTimeMillis()
                            if (now - lastPost > 400) {
                                lastPost = now
                                val seen = total
                                main.post { renderRowProgress(entry, seen) }
                            }
                        }
                    }
                }
                val complete = partial.length() == entry.sizeBytes
                if (complete && !dlStop) {
                    if (modelTarget(entry).exists()) modelTarget(entry).delete()
                    partial.renameTo(modelTarget(entry))
                }
                main.post {
                    row?.bar?.visibility = View.GONE
                    refreshModelRows()
                }
            } catch (t: Throwable) {
                main.post {
                    row?.bar?.visibility = View.GONE
                    row?.status?.text = "Download failed: ${t.message}"
                    refreshModelRows()
                }
            }
        }.also { it.start() }
    }

    private fun renderRowProgress(e: ModelEntry, done: Long) {
        val row = rows[e.id] ?: return
        val pct = ((done * 100) / e.sizeBytes).toInt().coerceIn(0, 100)
        row.bar.setProgress(pct, true)
        row.status.text = getString(R.string.dl_progress, mb(done), e.sizeLabel, pct)
    }

    /** Backend preference: "cpu" (default, always works), "auto" (GPU then CPU), "gpu". */
    private fun backendChoice(): String = prefs.getString("backend", "cpu") ?: "cpu"

    private fun loadModel(entry: ModelEntry) {
        if (sharedEngine != null) {
            toast(getString(R.string.st_backend_ok, sharedModelLabel.ifEmpty { "?" }))
            return
        }
        if (!isInstalled(entry)) { toast(getString(R.string.model_absent)); return }
        if (loadThread?.isAlive == true) return

        val f = modelTarget(entry)
        loadCancelled = false
        // CPU first is deliberate: on most Android devices the GPU backend initialises happily and
        // then dies at inference time with "Can not find OpenCL library on this device", which is
        // exactly what the smoke test exists to catch.
        val order: List<Boolean> = when (backendChoice()) {
            "gpu" -> listOf(true)
            "auto" -> listOf(true, false)
            else -> listOf(false)
        }

        val started = System.currentTimeMillis()
        loadProgress.visibility = View.VISIBLE
        btnLoadCancel.visibility = View.VISIBLE
        tvLoadInfo.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.st_loading)

        val tick = object : Runnable {
            override fun run() {
                val s = ((System.currentTimeMillis() - started) / 1000).toInt()
                tvLoadInfo.text = loadPhase + " · " + getString(R.string.st_load_elapsed, s)
                if (loadThread?.isAlive == true) main.postDelayed(this, 1000)
            }
        }
        main.post(tick)

        loadThread = Thread {
            var ok = false
            var how = ""
            var lastError = ""
            for (useGpu in order) {
                if (loadCancelled) break
                loadPhase = getString(if (useGpu) R.string.st_load_gpu else R.string.st_load_cpu)
                val backend = if (useGpu) Backend.GPU() else Backend.CPU()
                var e: Engine? = null
                try {
                    val cfg = EngineConfig(
                        modelPath = f.absolutePath,
                        backend = backend,
                        visionBackend = backend,
                        audioBackend = backend,
                        cacheDir = cacheDir.path
                    )
                    e = Engine(cfg)
                    e.initialize()
                    if (loadCancelled) { try { e.close() } catch (_: Throwable) {}; break }
                    loadPhase = getString(if (entry.vision) R.string.st_vision_check else R.string.st_verify)
                    if (smokeTest(e, entry)) {
                        sharedEngine = e
                        sharedBackend = if (useGpu) "GPU" else "CPU"
                        sharedModelLabel = entry.label
                        ok = true
                        how = sharedBackend
                        e = null                       // keep it open
                        break
                    }
                    lastError = "the model initialised but could not generate a reply"
                } catch (t: Throwable) {
                    lastError = t.message ?: "unknown"
                    if (lastError.contains("OpenCL", ignoreCase = true)) {
                        lastError = "Can not find OpenCL library on this device"
                    }
                } finally {
                    e?.let { try { it.close() } catch (_: Throwable) {} }
                }
            }

            main.post {
                loadProgress.visibility = View.GONE
                btnLoadCancel.visibility = View.GONE
                tvLoadInfo.visibility = View.GONE
                when {
                    loadCancelled -> tvStatus.text = getString(R.string.st_cancelled)
                    ok -> {
                        tvStatus.text = getString(
                            if (how == "GPU") R.string.st_loaded_gpu else R.string.st_loaded_cpu
                        )
                        renderEngine()
                    }
                    else -> tvStatus.text =
                        getString(R.string.st_load_failed, lastError.ifEmpty { "unknown" })
                }
            }
        }.also { it.start() }
    }

    /**
     * One tiny synchronous generation. A backend that initialises but cannot actually infer must
     * fail here rather than at the user's first real photo. For a vision build the check also
     * pushes a generated PNG through the image path, because the vision encoder is a separate
     * graph and can fail on its own.
     */
    private fun smokeTest(e: Engine, entry: ModelEntry): Boolean = try {
        e.createSession().use { s ->
            val inputs = ArrayList<InputData>()
            inputs.add(InputData.Text("Reply with one short word."))
            if (entry.vision) inputs.add(InputData.Image(tinyPng()))
            s.generateContent(inputs)
            true
        }
    } catch (t: Throwable) { false }

    private fun tinyPng(): ByteArray {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.rgb(47, 125, 82))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun confirmDeleteModel(entry: ModelEntry) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (sharedModelLabel == entry.label) {
                    try { sharedEngine?.close() } catch (_: Throwable) {}
                    sharedEngine = null
                    sharedModelLabel = ""
                }
                modelTarget(entry).delete()
                modelPartial(entry).delete()
                toast(getString(R.string.delete_done))
                refreshModelRows()
                renderEngine()
            }
            .show()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setMessage(R.string.wipe_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> wipeAll() }
            .show()
    }

    /** Uninstalling the app does all of this too — nothing is written outside the app's own storage. */
    private fun wipeAll() {
        try { sharedEngine?.close() } catch (_: Throwable) {}
        sharedEngine = null
        sharedModelLabel = ""
        modelFiles().forEach { it.delete() }
        File(filesDir, "records.json").delete()
        File(filesDir, "cells.json").delete()
        File(filesDir, "photos").deleteRecursively()
        File(cacheDir, "photos").deleteRecursively()
        File(filesDir, "tracks.json").delete()
        cells.clear()
        routePoints.clear()
        showLiveLine = false
        stopReplay()
        distanceKm = 0.0
        lastPoint = null
        photoFile = null
        archiveFile = null
        displayBitmap = null
        etNote.setText("")
        renderRecords()
        renderRoute()
        renderEngine()
        refreshModelRows()
        toast(getString(R.string.wipe_done))
    }

    private fun renderEngine() {
        val e = sharedEngine
        tvEngine.text = getString(
            R.string.engine_state,
            if (e == null) getString(R.string.engine_off)
            else getString(R.string.engine_on, "$sharedModelLabel / $sharedBackend")
        )
    }

    // ----------------------------------------------------------------- photo

    private fun takePhoto() {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        photoFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_CAMERA)
    }

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        startActivityForResult(Intent.createChooser(i, getString(R.string.btn_upload)), REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_EXPORT) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { Backup.exportZip(filesDir, it) }
                toast(getString(R.string.export_ok))
            } catch (t: Throwable) { toast(getString(R.string.t_export_fail, t.message ?: "")) }
            return
        }
        if (requestCode == REQ_IMPORT) {
            val uri = data?.data ?: return
            try {
                val n = contentResolver.openInputStream(uri)?.use { Backup.importZip(filesDir, it) } ?: 0
                cells.clear(); loadState()
                renderRecords(); renderFog(); renderRoute()
                toast(getString(R.string.import_ok, n))
            } catch (t: Throwable) { toast(getString(R.string.import_fail, t.message ?: "")) }
            return
        }
        val f: File? = when (requestCode) {
            REQ_CAMERA -> photoFile
            REQ_PICK -> data?.data?.let { copyToCache(it) }
            else -> null
        }
        if (f == null || !f.isFile) { tvStatus.text = getString(R.string.st_photo_none); return }
        photoFile = f
        imgPreview.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
        readExif(f)
        processPhoto(f)
        tvStatus.text = getString(R.string.st_photo_ok)
    }

    private fun copyToCache(uri: Uri): File? = try {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "picked-${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri).use { input -> FileOutputStream(f).use { input!!.copyTo(it) } }
        f
    } catch (t: Throwable) { null }

    private fun readExif(f: File) {
        try {
            val ex = ExifInterface(f.absolutePath)
            val latLon = ex.latLong
            exifLoc = latLon
            val d = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
            exifTime = d?.replaceFirst(":", "-")?.replaceFirst(":", "-")
            val gps = if (latLon != null) " · GPS %.5f, %.5f".format(latLon[0], latLon[1]) else ""
            tvExif.text = (exifTime ?: getString(R.string.exif_none)) + gps
        } catch (t: Throwable) {
            exifLoc = null; exifTime = null
            tvExif.text = getString(R.string.exif_none)
        }
    }

    private fun processPhoto(src: File) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.absolutePath, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
            var bmp = BitmapFactory.decodeFile(
                src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: run { tvPhotoStats.text = ""; return }
            val long = max(bmp.width, bmp.height)
            if (long > 1600) {
                val s = 1600f / long
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            }
            val marked = watermarked(bmp)
            val dir = File(filesDir, "photos").apply { mkdirs() }
            val out = File(dir, "archive-${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { marked.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            archiveFile = out
            displayBitmap = marked
            imgPreview.setImageBitmap(marked)
            tvPhotoStats.text = getString(
                R.string.photo_stats, kb(src.length()), kb(out.length()), marked.width, marked.height, "✓"
            )
        } catch (t: Throwable) {
            tvPhotoStats.text = "Photo processing failed: ${t.message}"
        }
    }

    private fun watermarked(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val label = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) +
            (currentPoint()?.let { "  %.4f, %.4f".format(it[0], it[1]) } ?: "") + "  WalkNotes"
        val size = max(22f, out.width / 30f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = android.graphics.Color.WHITE
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
        c.drawText(label, size, out.height - size, p)
        return out
    }

    private fun blurAt(v: ImageView, ev: MotionEvent) {
        val bmp = displayBitmap ?: return
        val scale = min(v.width.toFloat() / bmp.width, v.height.toFloat() / bmp.height)
        val dx = (v.width - bmp.width * scale) / 2f
        val dy = (v.height - bmp.height * scale) / 2f
        val bx = ((ev.x - dx) / scale).toInt()
        val by = ((ev.y - dy) / scale).toInt()
        val side = max(48, bmp.width / 9)
        val left = (bx - side / 2).coerceIn(0, max(0, bmp.width - side))
        val top = (by - side / 2).coerceIn(0, max(0, bmp.height - side))
        val w = min(side, bmp.width - left); val h = min(side, bmp.height - top)
        if (w < 8 || h < 8) return
        try {
            val region = Bitmap.createBitmap(bmp, left, top, w, h)
            val tiny = Bitmap.createScaledBitmap(region, 8, 8, false)
            val up = Bitmap.createScaledBitmap(tiny, w, h, false)
            Canvas(bmp).drawBitmap(up, left.toFloat(), top.toFloat(), Paint().apply { isFilterBitmap = false })
            archiveFile?.let { f -> FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
            v.setImageBitmap(bmp)
            toast("Blurred")
        } catch (t: Throwable) { toast("Blur failed: ${t.message}") }
    }

    // ----------------------------------------------------------------- location

    private fun requireLocationPerm(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun useGps() {
        if (!requireLocationPerm()) { toast(getString(R.string.t_perm)); return }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        tvStatus.text = getString(R.string.st_gps_wait)
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                gpsLoc = doubleArrayOf(loc.latitude, loc.longitude)
                lm.removeUpdates(this)
                val lat = loc.latitude; val lon = loc.longitude; val acc = loc.accuracy
                main.post {
                    tvPlace.text = "GPS %.5f, %.5f  ±%.0f m".format(lat, lon, acc)
                    tvStatus.text = getString(R.string.st_gps_ok)
                }
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null && gpsLoc == null) {
                gpsLoc = doubleArrayOf(last.latitude, last.longitude)
                tvPlace.text = "GPS %.5f, %.5f".format(last.latitude, last.longitude)
            }
            main.postDelayed({ lm.removeUpdates(listener) }, 20_000)
        } catch (t: Throwable) { tvStatus.text = "GPS unavailable: ${t.message}" }
    }

    private fun useExifLocation() {
        val p = exifLoc
        if (p == null) { toast(getString(R.string.t_exif_none)); return }
        tvPlace.text = "EXIF %.5f, %.5f%s".format(p[0], p[1], exifTime?.let { "  $it" } ?: "")
    }

    private fun currentPoint(): DoubleArray? = gpsLoc ?: exifLoc

    private fun placeLabel(): String {
        val addr = etAddr.text.toString().trim()
        val p = currentPoint()
        return when {
            p != null && addr.isNotEmpty() -> "$addr · %.5f, %.5f".format(p[0], p[1])
            p != null -> "%.5f, %.5f".format(p[0], p[1])
            addr.isNotEmpty() -> addr
            else -> "-"
        }
    }

    // ----------------------------------------------------------------- the note

    private fun noteMode(): String = when (radioMode.checkedRadioButtonId) {
        R.id.rbFeel -> "feel"
        R.id.rbNoteMode -> "note"
        else -> "desc"
    }

    private fun modeName(): String = when (noteMode()) {
        "feel" -> getString(R.string.mode_feel)
        "note" -> getString(R.string.mode_note)
        else -> getString(R.string.mode_desc)
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "feel" -> getString(R.string.mode_feel)
        "note" -> getString(R.string.mode_note)
        else -> getString(R.string.mode_desc)
    }

    /** The note language follows the app language unless the user is on "follow the device". */
    private fun noteLanguage(): String {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val sys = resources.configuration.locales[0].toLanguageTag()
        val effective = if (tag.isEmpty()) sys else tag
        return when {
            effective.startsWith("zh-Hans") -> "Simplified Chinese"
            effective.startsWith("zh") -> "Traditional Chinese"
            else -> "English"
        }
    }

    private fun buildPrompt(entry: ModelEntry): String {
        val stamps = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val userWords = etSupp.text.toString().trim()
        val instruction = when (noteMode()) {
            "desc" -> "Describe only what is actually in the photo: objects, materials, colours, " +
                "numbers, state of repair. No feelings, no opinions."
            "feel" -> "Write a short first-person impression of this place: what it recalls, what it " +
                "made you feel. Stay specific to what is in the photo."
            else -> "Write a compact field note: the time, the place, what can be seen, and one thing " +
                "worth looking up or coming back to later."
        }
        val grounding = if (entry.vision)
            "A photo taken by the user is attached; the note must be grounded in what is visible in it."
        else
            "You cannot see any image. Base the note strictly on the user's own words below; if they " +
                "are empty, keep it to the time and place only."
        return """
            You are the on-device writer of a Hong Kong walking notebook. The user is out walking and
            has photographed something they passed.

            Note type: ${modeName()}. $instruction
            $grounding
            Place: ${placeLabel()}
            Time: $stamps
            The user's own words: ${userWords.ifEmpty { "(none)" }}

            Rules: write in ${noteLanguage()}; 40-90 words (in Chinese 60-140 characters); do not
            invent street names, districts, brand names or numbers that are not visible or supplied;
            no greeting, no heading, no list, no emoji; output only the note itself.
        """.trimIndent()
    }

    private fun generateNote() {
        val e = sharedEngine ?: run { toast(getString(R.string.t_need_model)); return }
        val entry = ModelCatalog.entries.firstOrNull { it.label == sharedModelLabel } ?: ModelCatalog.default()
        val photo = archiveFile
        if (entry.vision && (photo == null || !photo.isFile)) {
            toast(getString(R.string.note_need_photo)); return
        }
        if (busy) return

        val prompt = buildPrompt(entry)
        val statusText = getString(if (entry.vision) R.string.st_note_analysing else R.string.st_analysing)
        busy = true
        busyStart = System.currentTimeMillis()
        tvStatus.text = statusText
        etNote.setText("")

        val tick = object : Runnable {
            override fun run() {
                if (!busy) return
                val s = ((System.currentTimeMillis() - busyStart) / 1000).toInt()
                tvStatus.text = statusText + " · " + getString(R.string.st_load_elapsed, s)
                main.postDelayed(this, 1000)
            }
        }
        main.post(tick)

        Thread {
            var text = ""
            var error: String? = null
            try {
                e.createSession().use { s ->
                    val inputs = ArrayList<InputData>()
                    inputs.add(InputData.Text(prompt))
                    if (entry.vision && photo != null) inputs.add(InputData.Image(photo.readBytes()))
                    text = s.generateContent(inputs)
                }
            } catch (t: Throwable) {
                error = t.message ?: "unknown"
            }
            val trimmed = text.trim()
            val shown = TextTruncate.truncateUtf8(trimmed, 600)
            main.post {
                busy = false
                if (error != null) {
                    tvStatus.text = getString(R.string.st_failed, error)
                } else {
                    etNote.setText(shown)
                    updateBytes()
                    tvStatus.text = getString(R.string.st_done)
                }
            }
        }.also { it.start() }
    }

    private fun updateBytes() {
        val n = etNote.text.toString().toByteArray(Charsets.UTF_8).size
        tvBytes.text = getString(R.string.bytes_fmt, n)
    }

    private fun copyNote() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("note", etNote.text.toString()))
        toast(getString(R.string.t_copied))
    }

    // ----------------------------------------------------------------- records

    private fun recordsFile() = File(filesDir, "records.json")

    private fun saveNote() {
        try {
            val arr = if (recordsFile().isFile) JSONArray(recordsFile().readText()) else JSONArray()
            val p = currentPoint()
            val o = JSONObject().apply {
                put("time_local", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
                put("mode", noteMode())
                put("place_label", placeLabel())
                put("lat", p?.get(0) ?: JSONObject.NULL)
                put("lon", p?.get(1) ?: JSONObject.NULL)
                put("photo", if (findViewById<MaterialCheckBox>(R.id.cbSavePhoto).isChecked)
                    (archiveFile?.absolutePath ?: "") else "")
                put("note", etNote.text.toString())
                put("note_language", noteLanguage())
                put("model", sharedModelLabel)
                put("user_words", etSupp.text.toString())
            }
            arr.put(o)
            recordsFile().writeText(arr.toString())
            renderRecords()
            renderFog()
            toast(getString(R.string.t_saved))
        } catch (t: Throwable) { toast("Save failed: ${t.message}") }
    }

    private fun renderRecords() {
        recordsList.removeAllViews()
        try {
            if (!recordsFile().isFile) {
                tvRecords.visibility = View.VISIBLE
                tvRecords.text = getString(R.string.empty)
                return
            }
            val arr = JSONArray(recordsFile().readText())
            val shown = ArrayList<Int>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val has = o.optString("photo").isNotEmpty() && File(o.optString("photo")).isFile
                val photoOk = when (recordsFilter) {
                    "photo" -> has
                    "nophoto" -> !has
                    else -> true
                }
                val dateOk = dateMatches(o.optString("time_local"))
                val q = recordsQuery.lowercase(Locale.US)
                val searchOk = q.isEmpty() || listOf(
                    o.optString("note"), o.optString("place_label"),
                    o.optString("time_local"), o.optString("user_words")
                ).any { it.lowercase(Locale.US).contains(q) }
                if (photoOk && dateOk && searchOk) shown.add(i)
            }
            if (shown.isEmpty()) {
                tvRecords.visibility = View.VISIBLE
                tvRecords.text = getString(R.string.empty)
                return
            }
            tvRecords.visibility = View.GONE
            val inf = layoutInflater
            shown.reversed().forEach { i ->
                val o = arr.getJSONObject(i)
                val row = inf.inflate(R.layout.item_record, recordsList, false)
                val img = row.findViewById<ImageView>(R.id.rowPhoto)
                val path = o.optString("photo")
                if (path.isNotEmpty() && File(path).isFile) {
                    img.setImageBitmap(BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                        inSampleSize = 8
                    }))
                } else {
                    img.setImageDrawable(null)
                    img.setBackgroundColor(ContextCompat.getColor(this, R.color.line))
                }
                row.findViewById<TextView>(R.id.rowTitle).text =
                    "[${modeLabel(o.optString("mode"))}]  ${o.optString("time_local")}"
                row.findViewById<TextView>(R.id.rowPlace).text = o.optString("place_label")
                row.findViewById<TextView>(R.id.rowSnippet).text =
                    o.optString("note").lineSequence().firstOrNull()?.take(80)
                        ?: getString(R.string.record_no_photo)
                row.setOnClickListener { showNotesFor(i) }
                recordsList.addView(row)
            }
        } catch (_: Throwable) {
            tvRecords.visibility = View.VISIBLE
            tvRecords.text = getString(R.string.empty)
        }
    }

    private fun confirmClearRecords() {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try { recordsFile().delete() } catch (_: Throwable) {}
                renderRecords()
                renderFog()
                toast(getString(R.string.t_cleared))
            }
            .show()
    }

    // ----------------------------------------------------------------- route / fog

    private fun cellsFile() = File(filesDir, "cells.json")

    private fun loadState() {
        try {
            if (cellsFile().isFile) {
                val a = JSONArray(cellsFile().readText())
                for (i in 0 until a.length()) cells.add(a.getString(i))
            }
        } catch (_: Throwable) {}
    }

    private fun saveCells() {
        try { cellsFile().writeText(JSONArray(cells.toList()).toString()) } catch (_: Throwable) {}
    }

    private fun toggleRoute() {
        if (!requireLocationPerm()) { toast(getString(R.string.t_perm)); return }
        recording = !recording

        if (!recording) {
            saveCurrentTrack()
            stopWalkTracking()
            paintWalkButton()
            tvStatus.text = getString(R.string.st_route_stop)
            findViewById<MaterialButton>(R.id.btnHideLine).visibility = if (showLiveLine) View.VISIBLE else View.GONE
            return
        }

        stopReplay()
        if (routePoints.size > 1) saveCurrentTrack()
        routePoints.clear()
        sessionStart = System.currentTimeMillis()
        showLiveLine = true
        findViewById<MaterialButton>(R.id.btnHideLine).visibility = View.VISIBLE
        paintWalkButton()
        if (prefs.getBoolean("walk_notif", true)) startWalkForeground() else startWalkInApp()
        renderRoute()
    }

    private fun paintWalkButton() {
        if (recording) {
            btnRoute.text = getString(R.string.btn_route_stop)
            btnRoute.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_dark))
            btnRoute.setTextColor(ContextCompat.getColor(this, R.color.white))
            lastFixAt = 0L
            recLive.visibility = View.VISIBLE
            recPulse.text = getString(R.string.rec_wait_gps)
            recClock.base = SystemClock.elapsedRealtime()
            recClock.start()
        } else {
            btnRoute.text = getString(R.string.btn_route_start)
            btnRoute.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
            btnRoute.setTextColor(ContextCompat.getColor(this, R.color.white))
            recClock.stop()
            recLive.visibility = View.GONE
        }
    }

    private fun updateRecPulse() {
        if (!recording) return
        recPulse.text = if (lastFixAt == 0L) {
            getString(R.string.rec_wait_gps)
        } else {
            val ago = ((System.currentTimeMillis() - lastFixAt) / 1000L).toInt()
            if (ago <= 8) getString(R.string.rec_gps_ok, ago)
            else getString(R.string.rec_gps_stale, ago)
        }
    }

    private fun startWalkForeground() {
        stopInAppUpdates()
        try {
            WalkRecordService.start(this)
        } catch (t: Throwable) {
            startWalkInApp()
        }
    }

    private fun startWalkInApp() {
        stopInAppUpdates()
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) { applyWalkFix(loc) }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        routeListener = listener
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) { }
    }

    private fun stopWalkTracking() {
        WalkRecordService.stop(this)
        stopInAppUpdates()
    }

    private fun stopInAppUpdates() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        routeListener?.let { try { lm.removeUpdates(it) } catch (_: Throwable) {} }
        routeListener = null
    }

    private fun applyWalkFix(loc: Location) {
        val k = cellKey(loc.latitude, loc.longitude)
        cells.add(k)
        saveCells()
        routePoints.add(doubleArrayOf(loc.latitude, loc.longitude, System.currentTimeMillis().toDouble()))
        lastPoint?.let { p ->
            distanceKm += haversine(p, doubleArrayOf(loc.latitude, loc.longitude)) / 1000.0
        }
        lastPoint = doubleArrayOf(loc.latitude, loc.longitude)
        gpsLoc = doubleArrayOf(loc.latitude, loc.longitude)
        lastFixAt = System.currentTimeMillis()
        updateRecPulse()
        renderRoute()
    }

    private fun renderRoute() {
        kpiCells.text = cells.size.toString()
        kpiKm.text = "%.2f".format(distanceKm)
        tvRoute.text = if (cells.isEmpty()) getString(R.string.map_empty)
        else getString(R.string.cells_fmt, cells.size, distanceKm)
        renderFog()
    }

    private fun issueMarks(): List<NotePin> = try {
        val groups = LinkedHashMap<String, ArrayList<Int>>()
        if (recordsFile().isFile) {
            val arr = JSONArray(recordsFile().readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.isNull("lat") || o.isNull("lon")) continue
                val k = cellKey(o.getDouble("lat"), o.getDouble("lon"))
                groups.getOrPut(k) { ArrayList() }.add(i)
            }
        }
        groups.map { (k, idxs) ->
            val newest = idxs.maxOrNull() ?: 0
            NotePin(cellKeyLat(k), cellKeyLon(k), 0xFF2F7D52.toInt(), newest, idxs.size)
        }
    } catch (_: Throwable) { emptyList() }

    private fun renderFog() {
        fogMap.update(cells, visibleRoute(), issueMarks())
        pushMapData()
    }

    private fun visibleRoute(): List<DoubleArray> {
        val replay = replayAll
        if (replay != null) return replay.take(replayIdx.coerceAtLeast(0).coerceAtMost(replay.size))
        return if (showLiveLine) routePoints else emptyList()
    }

    private fun cellKey(lat: Double, lon: Double): String =
        "${Math.round(lat / 0.0004)}:${Math.round(lon / 0.0004)}"

    private fun cellKeyLat(k: String) = k.substringBefore(":").toInt() * 0.0004
    private fun cellKeyLon(k: String) = k.substringAfter(":").toInt() * 0.0004

    private fun haversine(a: DoubleArray, b: DoubleArray): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b[0] - a[0]); val dLon = Math.toRadians(b[1] - a[1])
        val l1 = Math.toRadians(a[0]); val l2 = Math.toRadians(b[0])
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(l1) * cos(l2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    // ----------------------------------------------------------------- street map

    /**
     * Real street map from the free OpenFreeMap vector tiles (no API key). The walked cells, the
     * route and the note positions are our own GeoJSON layers on top. Tiles are the only thing that
     * needs the network; the offline grid shows the same data with no network at all.
     */
    private fun initMap() {
        if (mapCreated) return
        try {
            val mv = MapView(this)
            mapView = mv
            mapCreated = true
            mapContainer.addView(
                mv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            // One-finger drags still scroll the page; a pinch belongs to the map.
            mv.setOnTouchListener { v, e ->
                if (e.pointerCount > 1) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            mv.onCreate(null)
            mv.onStart()
            mv.onResume()
            mv.getMapAsync { map ->
                mapRef = map
                try {
                    map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { _ ->
                        mapReady = true
                        buildMapLayers()
                        pushMapData()
                        centerOnLastFix()
                        map.addOnMapClickListener { latLng ->
                            val pt = map.projection.toScreenLocation(latLng)
                            val box = android.graphics.RectF(pt.x - 28, pt.y - 28, pt.x + 28, pt.y + 28)
                            val hits = map.queryRenderedFeatures(box, "notes-dot")
                            if (hits.isNotEmpty()) {
                                val i = hits[0].getNumberProperty("i")?.toInt()
                                if (i != null) showNotesFor(i)
                                true
                            } else false
                        }
                    }
                } catch (t: Throwable) { fallbackToGrid() }
            }
            main.postDelayed({
                if (!mapReady && mapMode == "real") fallbackToGrid()
            }, 15_000)
        } catch (t: Throwable) {
            // MapLibre refused to start: the app keeps working on the offline grid.
            mapView = null
            mapCreated = false
            toast(getString(R.string.map_unavailable))
            setMapMode("grid")
        }
    }

    private fun fallbackToGrid() {
        if (mapMode != "real") return
        toast(getString(R.string.map_style_failed))
        setMapMode("grid")
    }

    private fun buildMapLayers() {
        val style = mapRef?.style ?: return
        try {
            style.addSource(GeoJsonSource("fog"))
            style.addLayer(
                FillLayer("fog-fill", "fog").withProperties(
                    PropertyFactory.fillColor(android.graphics.Color.parseColor("#5C6561")),
                    PropertyFactory.fillOpacity(0.78f),
                    PropertyFactory.fillAntialias(true)
                )
            )
            style.addSource(GeoJsonSource("cells"))
            style.addLayer(
                FillLayer("cells-fill", "cells").withProperties(
                    PropertyFactory.fillColor(android.graphics.Color.parseColor("#2F7D52")),
                    PropertyFactory.fillOpacity(0.18f)
                )
            )
            style.addSource(GeoJsonSource("route"))
            style.addLayer(
                LineLayer("route-line", "route").withProperties(
                    PropertyFactory.lineColor(android.graphics.Color.parseColor("#2F7D52")),
                    PropertyFactory.lineWidth(4f)
                )
            )
            style.addSource(GeoJsonSource("notes"))
            style.addLayer(
                CircleLayer("notes-dot", "notes").withProperties(
                    PropertyFactory.circleColor(android.graphics.Color.parseColor("#2F7D52")),
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2f)
                )
            )
        } catch (t: Throwable) { /* style not ready; pushMapData will retry */ }
    }

    private fun pushMapData() {
        if (!mapReady || mapMode != "real") return
        val map = mapRef ?: return
        val style = map.style ?: return
        try {
            val half = 0.0004 / 2
            val outer = listOf(
                Point.fromLngLat(-180.0, -85.0),
                Point.fromLngLat(180.0, -85.0),
                Point.fromLngLat(180.0, 85.0),
                Point.fromLngLat(-180.0, 85.0),
                Point.fromLngLat(-180.0, -85.0)
            )
            val holes = cells.take(1500).map { k ->
                val la = cellKeyLat(k); val lo = cellKeyLon(k)
                listOf(
                    Point.fromLngLat(lo - half, la - half),
                    Point.fromLngLat(lo - half, la + half),
                    Point.fromLngLat(lo + half, la + half),
                    Point.fromLngLat(lo + half, la - half),
                    Point.fromLngLat(lo - half, la - half)
                )
            }
            val fogFeature = Feature.fromGeometry(Polygon.fromLngLats(listOf(outer) + holes))
            val cellFeatures = cells.mapNotNull { k ->
                val la = cellKeyLat(k); val lo = cellKeyLon(k)
                Feature.fromGeometry(
                    Polygon.fromLngLats(
                        listOf(
                            listOf(
                                Point.fromLngLat(lo - half, la - half),
                                Point.fromLngLat(lo + half, la - half),
                                Point.fromLngLat(lo + half, la + half),
                                Point.fromLngLat(lo - half, la + half),
                                Point.fromLngLat(lo - half, la - half)
                            )
                        )
                    )
                )
            }.toTypedArray()
            val shown = visibleRoute()
            val routeFeature = if (shown.size > 1) arrayOf(
                Feature.fromGeometry(
                    LineString.fromLngLats(shown.map { Point.fromLngLat(it[1], it[0]) })
                )
            ) else emptyArray()
            val noteFeatures = issueMarks().map { m ->
                Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).also { f ->
                    f.addNumberProperty("i", m.index)
                }
            }.toTypedArray()

            style.getSourceAs<GeoJsonSource>("fog")?.setGeoJson(fogFeature)
            style.getSourceAs<GeoJsonSource>("cells")?.setGeoJson(FeatureCollection.fromFeatures(cellFeatures))
            style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(FeatureCollection.fromFeatures(routeFeature))
            style.getSourceAs<GeoJsonSource>("notes")?.setGeoJson(FeatureCollection.fromFeatures(noteFeatures))

            if (!mapCentered) {
                val p = currentPoint()
                val first = cells.firstOrNull()
                val target = when {
                    p != null -> LatLng(p[0], p[1])
                    first != null -> LatLng(cellKeyLat(first), cellKeyLon(first))
                    else -> null
                }
                if (target != null) {
                    map.cameraPosition = CameraPosition.Builder().target(target).zoom(16.0).build()
                    mapCentered = true
                }
            }
        } catch (t: Throwable) { /* layer missing on the very first call */ }
    }

    /** Jump the camera to the last known fix, or to the first walked cell. */
    private fun recenter() {
        val map = mapRef
        val p = currentPosOrLast()
        if (map != null && p != null) {
            map.cameraPosition = CameraPosition.Builder().target(LatLng(p[0], p[1])).zoom(16.5).build()
            return
        }
        val first = cells.firstOrNull()
        if (map != null && first != null) {
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(cellKeyLat(first), cellKeyLon(first))).zoom(15.5).build()
        } else {
            toast(getString(R.string.toast_no_fix))
        }
    }

    /** Centre the map on the last known fix when the map first appears, if we are allowed to. */
    private fun centerOnLastFix() {
        if (mapCentered) return
        val map = mapRef ?: return
        val p = currentPosOrLast() ?: return
        map.cameraPosition = CameraPosition.Builder().target(LatLng(p[0], p[1])).zoom(16.0).build()
        mapCentered = true
    }

    /** Either a fix we already have, or the OS's last known one (no new fix requested). */
    private fun currentPosOrLast(): DoubleArray? {
        currentPoint()?.let { return it }
        if (!requireLocationPerm()) return null
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                gpsLoc = doubleArrayOf(it.latitude, it.longitude)
                tvPlace.text = "GPS %.5f, %.5f".format(it.latitude, it.longitude)
                doubleArrayOf(it.latitude, it.longitude)
            }
        } catch (t: Throwable) { null }
    }

    private fun paintMapChips() {
        val real = mapMode == "real"
        val accent = ContextCompat.getColor(this, R.color.accent)
        val card = ContextCompat.getColor(this, R.color.card)
        val ink = ContextCompat.getColor(this, R.color.ink)
        val on = ContextCompat.getColor(this, R.color.white)
        btnMapReal.backgroundTintList = ColorStateList.valueOf(if (real) accent else card)
        btnMapReal.setTextColor(if (real) on else ink)
        btnMapGrid.backgroundTintList = ColorStateList.valueOf(if (!real) accent else card)
        btnMapGrid.setTextColor(if (!real) on else ink)
    }

    private fun setMapMode(mode: String) {
        mapMode = mode
        prefs.edit().putString("mapmode", mode).apply()
        paintMapChips()
        val real = mode == "real"
        mapView?.visibility = if (real) View.VISIBLE else View.GONE
        fogMap.visibility = if (real) View.GONE else View.VISIBLE
        if (real) {
            // Create the map only while its panel is on screen: MapLibre must not build a GL
            // surface inside a GONE panel with zero size.
            if (currentTab == 0) initMap()
            pushMapData()
        } else {
            renderFog()
        }
    }

    // ----------------------------------------------------------------- misc

    private fun mb(bytes: Long): String =
        if (bytes >= 1_000_000_000L) "%.2f GB".format(bytes / 1_000_000_000.0)
        else "%.0f MB".format(bytes / 1_000_000.0)

    private fun kb(bytes: Long): String =
        if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${bytes / 1024} KB"

    private fun maybeShowTip() {
        val bar = findViewById<View>(R.id.tipBar)
        if (!prefs.getBoolean("map_tips", true)) {
            bar.visibility = View.GONE
            return
        }
        if (tipShownThisSession) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("map_tip_last", 0L)
        if (last != 0L && now - last < 2 * 60 * 60 * 1000L) return
        val pool = resources.getStringArray(R.array.map_tips_pool)
        if (pool.isEmpty()) return
        val i = (prefs.getInt("map_tip_i", -1) + 1) % pool.size
        findViewById<TextView>(R.id.tvTip).text = pool[i]
        bar.visibility = View.VISIBLE
        tipShownThisSession = true
        prefs.edit().putInt("map_tip_i", i).putLong("map_tip_last", now).apply()
        bar.removeCallbacks(hideTipRunnable)
        bar.postDelayed(hideTipRunnable, 12_000L)
    }

    private val hideTipRunnable = Runnable { hideTip() }

    private fun hideTip() {
        val bar = findViewById<View>(R.id.tipBar)
        bar.removeCallbacks(hideTipRunnable)
        bar.visibility = View.GONE
    }

    private fun dismissTip() {
        hideTip()
        prefs.edit().putLong("map_tip_last", System.currentTimeMillis()).apply()
    }

    private fun saveCurrentTrack() {
        if (routePoints.size < 2) return
        val end = System.currentTimeMillis()
        val start = if (sessionStart == 0L) end else sessionStart
        Tracks.add(filesDir, Track("t-$end", start, end, routePoints.map { it.copyOf() }))
    }

    private fun hideRouteLine() {
        showLiveLine = false
        stopReplay()
        findViewById<MaterialButton>(R.id.btnHideLine).visibility = View.GONE
        renderFog()
    }

    private fun stopReplay() {
        main.removeCallbacks(replayTick)
        replayAll = null
        replayIdx = 0
    }

    private fun startReplay(label: String, pts: List<DoubleArray>) {
        if (pts.size < 2) { toast(getString(R.string.replay_empty)); return }
        showLiveLine = false
        replayAll = pts
        replayIdx = 2
        findViewById<MaterialButton>(R.id.btnHideLine).visibility = View.VISIBLE
        tvRoute.text = getString(R.string.replay_playing, label)
        renderFog()
        main.removeCallbacks(replayTick)
        main.post(replayTick)
    }

    private fun advanceReplay() {
        val all = replayAll ?: return
        val step = (all.size / 80).coerceAtLeast(1)
        replayIdx = (replayIdx + step).coerceAtMost(all.size)
        renderFog()
        if (replayIdx < all.size) main.postDelayed(replayTick, 40)
    }

    private fun showReplayDialog() {
        var unit = "day"
        val view = layoutInflater.inflate(R.layout.dialog_replay, null)
        val list = view.findViewById<LinearLayout>(R.id.replayList)
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.replayUnit)
        group.check(R.id.unitDay)
        fun fill() {
            list.removeAllViews()
            val rows = Tracks.grouped(filesDir, unit)
            if (rows.isEmpty()) {
                val t = TextView(this)
                t.setText(R.string.replay_empty)
                list.addView(t)
                return
            }
            rows.forEach { (key, tracks) ->
                val km = tracks.sumOf { it.km() }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                    minimumHeight = 48
                }
                val label = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = getString(R.string.replay_row, if (key == "all") getString(R.string.unit_all) else key, km, tracks.size)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                }
                val vid = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.btn_export_video)
                    minHeight = 48
                    setOnClickListener { exportTrackVideo(Tracks.mergePoints(tracks)) }
                }
                row.addView(label)
                row.addView(vid)
                row.setOnClickListener { startReplay(if (key == "all") getString(R.string.unit_all) else key, Tracks.mergePoints(tracks)) }
                list.addView(row)
            }
        }
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            unit = when (id) {
                R.id.unitMonth -> "month"
                R.id.unitYear -> "year"
                R.id.unitAll -> "all"
                else -> "day"
            }
            fill()
        }
        fill()
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_replay)
            .setView(view)
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun exportTrackVideo(pts: List<DoubleArray>) {
        if (pts.size < 2) { toast(getString(R.string.replay_empty)); return }
        toast(getString(R.string.t_video_wait))
        val out = File(cacheDir, "walknotes-path.mp4")
        Thread {
            val err = try { TrackVideo.render(this, cells.toSet(), pts, out) } catch (t: Throwable) { t.message }
            main.post {
                if (err != null) {
                    toast(getString(R.string.t_video_fail, err))
                    return@post
                }
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_video))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.share_video)))
                } catch (t: Throwable) {
                    toast(getString(R.string.t_video_fail, t.message ?: "share"))
                }
            }
        }.start()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun startExport() {
        val name = "WalkNotes-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun startImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun showNotesFor(index: Int) {
        val ids = notesInCell(index)
        val start = ids.indexOf(index).coerceAtLeast(0)
        showNotes(ids, start)
    }

    private fun notesInCell(index: Int): List<Int> {
        return try {
            val arr = JSONArray(recordsFile().readText())
            if (index < 0 || index >= arr.length()) return listOf(index)
            val o = arr.getJSONObject(index)
            if (o.isNull("lat") || o.isNull("lon")) return listOf(index)
            val k = cellKey(o.getDouble("lat"), o.getDouble("lon"))
            (0 until arr.length()).filter { i ->
                val x = arr.getJSONObject(i)
                !x.isNull("lat") && !x.isNull("lon") &&
                    cellKey(x.getDouble("lat"), x.getDouble("lon")) == k
            }.sortedByDescending { it }
        } catch (_: Throwable) { listOf(index) }
    }

    private fun dateMatches(stamp: String): Boolean {
        if (recordsDate == "all") return true
        if (stamp.isEmpty()) return true
        val t = try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(stamp)?.time ?: return true } catch (_: Throwable) { return true }
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        return when (recordsDate) {
            "today" -> {
                val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                fmt.format(Date(t)) == fmt.format(Date(now))
            }
            "week" -> now - t <= 7 * day
            "month" -> now - t <= 30 * day
            else -> true
        }
    }

    private fun showNotes(ids: List<Int>, start: Int) {
        if (ids.isEmpty()) return
        var pos = start.coerceIn(0, ids.lastIndex)
        try {
            val view = layoutInflater.inflate(R.layout.dialog_note, null)
            val img = view.findViewById<ImageView>(R.id.dlgPhoto)
            val meta = view.findViewById<TextView>(R.id.dlgMeta)
            val note = view.findViewById<EditText>(R.id.dlgNote)
            val pager = view.findViewById<View>(R.id.dlgPager)
            val posTv = view.findViewById<TextView>(R.id.dlgPos)
            fun bind() {
                val arr = JSONArray(recordsFile().readText())
                val o = arr.getJSONObject(ids[pos])
                val path = o.optString("photo")
                if (path.isNotEmpty() && File(path).isFile) {
                    img.visibility = View.VISIBLE
                    img.setImageBitmap(BitmapFactory.decodeFile(path))
                } else img.visibility = View.GONE
                meta.text = "[${modeLabel(o.optString("mode"))}]  ${o.optString("time_local")}\n${o.optString("place_label")}"
                note.setText(o.optString("note"))
                pager.visibility = if (ids.size > 1) View.VISIBLE else View.GONE
                posTv.text = getString(R.string.pager_pos, pos + 1, ids.size)
            }
            view.findViewById<MaterialButton>(R.id.dlgPrev).setOnClickListener {
                pos = (pos - 1 + ids.size) % ids.size
                bind()
            }
            view.findViewById<MaterialButton>(R.id.dlgNext).setOnClickListener {
                pos = (pos + 1) % ids.size
                bind()
            }
            img.setOnClickListener {
                img.scaleType = if (img.scaleType == ImageView.ScaleType.CENTER_CROP)
                    ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            }
            bind()
            AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(R.string.btn_save_edit) { _, _ ->
                    try {
                        val arr = JSONArray(recordsFile().readText())
                        arr.getJSONObject(ids[pos]).put("note", note.text.toString())
                        recordsFile().writeText(arr.toString())
                        renderRecords()
                        renderFog()
                        toast(getString(R.string.t_edited))
                    } catch (t: Throwable) { toast(t.message ?: "save") }
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        } catch (_: Throwable) { }
    }

    private fun renderCrash() {
        val c = CivicWalkApp.lastCrash(this)
        tvCrash.text = if (c.isNullOrBlank()) getString(R.string.no_error) else c
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView?.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        // The engine is intentionally process-scoped (see companion): a language change recreates
        // the activity, and re-loading a multi-gigabyte model every time would be unacceptable.
        dlStop = true
        busy = false
        mapView?.onDestroy()
    }

    companion object {
        /**
         * Process-scoped so that an activity recreation (orientation, language switch) does not
         * throw away a loaded model. The OS reclaims it with the process, and the user can drop it
         * explicitly from Settings.
         */
        @Volatile private var sharedEngine: Engine? = null
        @Volatile private var sharedBackend: String = ""
        @Volatile private var sharedModelLabel: String = ""

        /** Free vector tiles, no API key. */
        private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    }
}
