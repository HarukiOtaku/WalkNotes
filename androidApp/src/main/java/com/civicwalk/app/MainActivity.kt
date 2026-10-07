package com.civicwalk.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.exifinterface.media.ExifInterface
import com.civicwalk.core.TextTruncate
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
 * CivicWalk — on-device civic reporting.
 *
 * Four tabs, one job each: report · map · records · settings. Everything except the one-time
 * model download happens on the phone: the photo, the coordinates and the draft never leave it.
 */
class MainActivity : AppCompatActivity() {

    private val modelName = "gemma-4-E2B-it.litertlm"
    private val modelUrl =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    private val modelTotal = 2_588_147_712L

    // report tab
    private lateinit var imgPreview: ImageView
    private lateinit var tvExif: TextView
    private lateinit var tvPhotoStats: TextView
    private lateinit var btnBlur: MaterialButton
    private lateinit var tvPlace: TextView
    private lateinit var etAddr: EditText
    private lateinit var btnAnalyse: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var etDraft: EditText
    private lateinit var tvBytes: TextView
    private lateinit var etSupp: EditText

    // map tab
    private lateinit var fogMap: FogMapView
    private lateinit var kpiCells: TextView
    private lateinit var kpiKm: TextView
    private lateinit var tvRoute: TextView
    private lateinit var btnRoute: MaterialButton

    // records tab
    private lateinit var tvRecords: TextView

    // settings tab
    private lateinit var radioLang: RadioGroup
    private lateinit var tvModelState: TextView
    private lateinit var tvModelSize: TextView
    private lateinit var tvModelPath: TextView
    private lateinit var dlProgress: LinearProgressIndicator
    private lateinit var tvDlText: TextView
    private lateinit var btnModelDownload: MaterialButton
    private lateinit var btnModelPause: MaterialButton
    private lateinit var btnModelDelete: MaterialButton
    private lateinit var tvEngine: TextView
    private lateinit var tvVersion: TextView

    private var photoFile: File? = null
    private var archiveFile: File? = null
    private var displayBitmap: Bitmap? = null
    private var blurMode = false
    private val routePoints = ArrayList<DoubleArray>()
    private var exifTime: String? = null
    private var exifLoc: DoubleArray? = null
    private var gpsLoc: DoubleArray? = null

    private val cells = LinkedHashSet<String>()
    private var distanceKm = 0.0
    private var lastPoint: DoubleArray? = null
    private var recording = false
    private var routeListener: LocationListener? = null

    @Volatile private var dlStop = false
    private var dlThread: Thread? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)

    private val REQ_CAMERA = 1001
    private val REQ_PICK = 1002
    private val REQ_PERM = 1003

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is light by design; never let a dark system theme repaint it.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadState()
        wireTabs()
        wireReport()
        wireSettings()

        requestPermissions(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ), REQ_PERM
        )

        refreshModelManager()
        renderRecords()
        renderRoute()
        renderEngine()
    }

    // ------------------------------------------------------------------ wiring

    private fun bindViews() {
        imgPreview = findViewById(R.id.imgPreview)
        tvExif = findViewById(R.id.tvExif)
        tvPhotoStats = findViewById(R.id.tvPhotoStats)
        btnBlur = findViewById(R.id.btnBlur)
        tvPlace = findViewById(R.id.tvPlace)
        etAddr = findViewById(R.id.etAddr)
        btnAnalyse = findViewById(R.id.btnAnalyse)
        tvStatus = findViewById(R.id.tvStatus)
        etDraft = findViewById(R.id.etDraft)
        tvBytes = findViewById(R.id.tvBytes)
        etSupp = findViewById(R.id.etSupp)

        fogMap = findViewById(R.id.fogMap)
        kpiCells = findViewById(R.id.kpiCells)
        kpiKm = findViewById(R.id.kpiKm)
        tvRoute = findViewById(R.id.tvRoute)
        btnRoute = findViewById(R.id.btnRoute)

        tvRecords = findViewById(R.id.tvRecords)

        radioLang = findViewById(R.id.radioLang)
        tvModelState = findViewById(R.id.tvModelState)
        tvModelSize = findViewById(R.id.tvModelSize)
        tvModelPath = findViewById(R.id.tvModelPath)
        dlProgress = findViewById(R.id.dlProgress)
        tvDlText = findViewById(R.id.tvDlText)
        btnModelDownload = findViewById(R.id.btnModelDownload)
        btnModelPause = findViewById(R.id.btnModelPause)
        btnModelDelete = findViewById(R.id.btnModelDelete)
        tvEngine = findViewById(R.id.tvEngine)
        tvVersion = findViewById(R.id.tvVersion)
    }

    private fun wireTabs() {
        val bar = findViewById<TabLayout>(R.id.tabBar)
        bar.addTab(bar.newTab().setText(R.string.tab_report))
        bar.addTab(bar.newTab().setText(R.string.tab_map))
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
        val panels = intArrayOf(R.id.panelReport, R.id.panelMap, R.id.panelRecords, R.id.panelSettings)
        panels.forEachIndexed { i, id ->
            findViewById<View>(id).visibility = if (i == index) View.VISIBLE else View.GONE
        }
        if (index == 1) renderFog()
    }

    private fun wireReport() {
        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener { takePhoto() }
        findViewById<MaterialButton>(R.id.btnUpload).setOnClickListener { pickPhoto() }
        findViewById<MaterialButton>(R.id.btnGps).setOnClickListener { useGps() }
        findViewById<MaterialButton>(R.id.btnExifLoc).setOnClickListener { useExifLocation() }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener { copyDraft() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveRecord() }
        findViewById<MaterialButton>(R.id.btnClearRecords).setOnClickListener { confirmClearRecords() }
        btnRoute.setOnClickListener { toggleRoute() }

        btnAnalyse.setOnClickListener { analyse() }

        btnBlur.setOnClickListener {
            blurMode = !blurMode
            btnBlur.text = getString(if (blurMode) R.string.btn_blur_on else R.string.btn_blur)
        }
        imgPreview.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN && blurMode) {
                blurAt(v as ImageView, ev); true
            } else false
        }

        etDraft.addTextChangedListener(object : android.text.TextWatcher {
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

        btnModelDownload.setOnClickListener { startDownload() }
        btnModelPause.setOnClickListener { dlStop = true; btnModelPause.visibility = View.GONE }
        tvEngine.setOnClickListener { renderEngine() }
        findViewById<MaterialButton>(R.id.btnModelLoad).setOnClickListener { loadModel() }
        btnModelDelete.setOnClickListener { confirmDeleteModel() }

        tvVersion.text = getString(R.string.app_name) + " " + versionName()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (_: Throwable) { "-" }

    // ------------------------------------------------------------------ model manager

    private fun modelTarget(): File = File(getExternalFilesDir(null) ?: filesDir, modelName)

    private fun modelPartial(): File = File(getExternalFilesDir(null) ?: filesDir, "$modelName.part")

    private fun findModel(): File? =
        listOf(modelTarget(), File(filesDir, modelName)).firstOrNull { it.isFile && it.length() > 1_000_000_000L }

    private fun refreshModelManager() {
        val f = findModel()
        val partial = modelPartial()
        if (f != null) {
            btnModelDownload.text = getString(R.string.btn_download)
            tvModelState.text = getString(R.string.model_ready)
            tvModelSize.text = getString(R.string.model_size, mb(f.length()))
        } else if (partial.isFile && partial.length() > 0) {
            btnModelDownload.text = getString(R.string.btn_resume)
            tvModelState.text = getString(R.string.model_missing)
            tvModelSize.text = getString(R.string.model_size, mb(partial.length()))
        } else {
            btnModelDownload.text = getString(R.string.btn_download)
            tvModelState.text = getString(R.string.model_missing)
            tvModelSize.text = ""
        }
        tvModelPath.text = getString(R.string.model_path, (getExternalFilesDir(null) ?: filesDir).absolutePath)
        renderEngine()
    }

    private fun renderEngine() {
        val e = sharedEngine
        tvEngine.text = getString(
            R.string.engine_state,
            if (e == null) getString(R.string.engine_off) else getString(R.string.engine_on, "LiteRT-LM")
        )
    }

    private fun startDownload() {
        if (dlThread?.isAlive == true) return
        val target = modelTarget()
        if (target.isFile && target.length() > 1_000_000_000L) { refreshModelManager(); return }

        val partial = modelPartial()
        val already = if (partial.isFile) partial.length() else 0L
        dlStop = false
        dlProgress.visibility = View.VISIBLE
        btnModelPause.visibility = View.VISIBLE
        renderDownload(already)

        dlThread = Thread {
            try {
                val conn = (URL(modelUrl).openConnection() as HttpURLConnection)
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
                                main.post { renderDownload(seen) }
                            }
                        }
                    }
                }
                val finished = partial.length() >= modelTotal - 1_000_000L
                if (finished && !dlStop) {
                    if (target.exists()) target.delete()
                    val ok = partial.renameTo(target)
                    main.post {
                        tvDlText.text = if (ok) getString(R.string.dl_done) else ""
                        dlProgress.visibility = View.GONE
                        btnModelPause.visibility = View.GONE
                        refreshModelManager()
                    }
                } else {
                    main.post {
                        renderDownload(partial.length())
                        dlProgress.visibility = View.GONE
                        btnModelPause.visibility = View.GONE
                        refreshModelManager()
                    }
                }
            } catch (t: Throwable) {
                main.post {
                    tvDlText.text = "Download failed: ${t.message}"
                    dlProgress.visibility = View.GONE
                    btnModelPause.visibility = View.GONE
                }
            }
        }.also { it.start() }
    }

    private fun renderDownload(done: Long) {
        val pct = ((done * 100) / modelTotal).toInt().coerceIn(0, 100)
        dlProgress.setProgress(pct, true)
        tvDlText.text = getString(R.string.dl_progress, mb(done), mb(modelTotal), pct)
    }

    private fun loadModel() {
        val f = findModel() ?: run { toast(getString(R.string.model_missing)); return }
        if (sharedEngine != null) { toast(getString(R.string.st_loaded_gpu)); return }
        tvStatus.text = getString(R.string.st_loading)
        Thread {
            var loaded: Engine? = null
            var how = "CPU"
            try {
                val cfg = EngineConfig(modelPath = f.absolutePath, backend = Backend.GPU(), cacheDir = cacheDir.path)
                loaded = Engine(cfg).also { it.initialize() }
                how = "GPU"
            } catch (t: Throwable) {
                try {
                    val cfg = EngineConfig(modelPath = f.absolutePath, backend = Backend.CPU(), cacheDir = cacheDir.path)
                    loaded = Engine(cfg).also { it.initialize() }
                    how = "CPU"
                } catch (t2: Throwable) {
                    val msg = t2.message ?: "unknown"
                    main.post { tvStatus.text = getString(R.string.st_load_failed, msg) }
                }
            }
            val e = loaded
            if (e != null) {
                sharedEngine = e
                val backend = how
                main.post {
                    tvStatus.text = getString(
                        if (backend == "GPU") R.string.st_loaded_gpu else R.string.st_loaded_cpu
                    )
                    renderEngine()
                }
            }
        }.start()
    }

    private fun confirmDeleteModel() {
        val f = findModel() ?: modelPartial().takeIf { it.isFile }
        if (f == null) { toast(getString(R.string.model_missing)); return }
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    sharedEngine?.close()
                    sharedEngine = null
                    f.delete()
                    modelPartial().delete()
                    toast(getString(R.string.delete_done))
                } catch (t: Throwable) { toast("Delete failed: ${t.message}") }
                dlProgress.visibility = View.GONE
                tvDlText.text = ""
                refreshModelManager()
            }
            .show()
    }

    // ------------------------------------------------------------------ photo

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
            tvExif.text = (exifTime ?: "") + gps
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
            (currentPoint()?.let { "  %.4f, %.4f".format(it[0], it[1]) } ?: "") + "  CivicWalk"
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

    // ------------------------------------------------------------------ location

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

    // ------------------------------------------------------------------ analyse

    /** The report language follows the app language unless the user is on "follow the device". */
    private fun reportLanguageName(): String {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val sys = resources.configuration.locales[0].toLanguageTag()
        val effective = if (tag.isEmpty()) sys else tag
        return when {
            effective.startsWith("zh-Hans") -> "Simplified Chinese (简体中文)"
            effective.startsWith("zh") -> "Traditional Chinese (繁體中文)"
            else -> "English"
        }
    }

    private fun analyse() {
        val e = sharedEngine ?: run { toast(getString(R.string.t_need_model)); return }
        val stamps = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date())
        val prompt = """
            You are the on-device assistant of a Hong Kong civic-reporting app.
            A photo of a street or pavement has been taken by the user.
            Location: ${placeLabel()}
            Local time: $stamps (UTC: $utc)

            Write ONLY the body of a case report to paste into the HKSAR 1823 online form.
            Language: ${reportLanguageName()}.
            Rules: describe only what is visible in a photo of this kind;
            do not invent a district or street name; state facts, no accusations, no personal names;
            120-200 Chinese characters (or 60-100 words in English); end with the action requested.
        """.trimIndent()

        tvStatus.text = getString(R.string.st_analysing)
        etDraft.setText("")
        scope.launch {
            val sb = StringBuilder()
            try {
                e.createConversation().use { conv ->
                    conv.sendMessageAsync(prompt).collect { chunk ->
                        sb.append(chunk)
                        etDraft.setText(sb.toString())
                    }
                }
                val shown = TextTruncate.truncateUtf8(sb.toString(), 1500)
                if (shown.length != sb.length) etDraft.setText(shown)
                updateBytes()
                tvStatus.text = getString(R.string.st_done)
            } catch (t: Throwable) {
                tvStatus.text = getString(R.string.st_failed, t.message ?: "?")
            }
        }
    }

    private fun updateBytes() {
        val n = etDraft.text.toString().toByteArray(Charsets.UTF_8).size
        tvBytes.text = getString(R.string.bytes_fmt, n)
    }

    private fun copyDraft() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("1823 draft", etDraft.text.toString()))
        toast(getString(R.string.t_copied))
    }

    // ------------------------------------------------------------------ records

    private fun recordsFile() = File(filesDir, "records.json")

    private fun saveRecord() {
        try {
            val arr = if (recordsFile().isFile) JSONArray(recordsFile().readText()) else JSONArray()
            val p = currentPoint()
            val o = JSONObject().apply {
                put("time_local", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
                put("place_label", placeLabel())
                put("lat", p?.get(0) ?: JSONObject.NULL)
                put("lon", p?.get(1) ?: JSONObject.NULL)
                put("photo", archiveFile?.absolutePath ?: "")
                put("draft", etDraft.text.toString())
                put("report_language", reportLanguageName())
                put("supplement", etSupp.text.toString())
            }
            arr.put(o)
            recordsFile().writeText(arr.toString())
            renderRecords()
            renderFog()
            toast(getString(R.string.t_saved))
        } catch (t: Throwable) { toast("Save failed: ${t.message}") }
    }

    private fun renderRecords() {
        tvRecords.text = try {
            if (!recordsFile().isFile) getString(R.string.empty) else {
                val arr = JSONArray(recordsFile().readText())
                if (arr.length() == 0) getString(R.string.empty)
                else {
                    val head = getString(R.string.records_n, arr.length())
                    val body = (0 until arr.length()).joinToString("\n") { i ->
                        val o = arr.getJSONObject(i)
                        "${i + 1}.  ${o.optString("time_local")}\n     ${o.optString("place_label")}"
                    }
                    "$head\n\n$body"
                }
            }
        } catch (t: Throwable) { getString(R.string.empty) }
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

    // ------------------------------------------------------------------ route / fog

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
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        recording = !recording

        if (!recording) {
            routeListener?.let { lm.removeUpdates(it) }
            routeListener = null
            btnRoute.text = getString(R.string.btn_route_start)
            tvStatus.text = getString(R.string.st_route_stop)
            return
        }

        btnRoute.text = getString(R.string.btn_route_stop)
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val k = cellKey(loc.latitude, loc.longitude)
                cells.add(k)
                saveCells()
                routePoints.add(doubleArrayOf(loc.latitude, loc.longitude))
                lastPoint?.let { p ->
                    distanceKm += haversine(p, doubleArrayOf(loc.latitude, loc.longitude)) / 1000.0
                }
                lastPoint = doubleArrayOf(loc.latitude, loc.longitude)
                renderRoute()
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        routeListener = listener
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, listener, Looper.getMainLooper())
        renderRoute()
    }

    private fun renderRoute() {
        kpiCells.text = cells.size.toString()
        kpiKm.text = "%.2f".format(distanceKm)
        tvRoute.text = if (cells.isEmpty()) getString(R.string.map_empty)
        else getString(R.string.cells_fmt, cells.size, distanceKm)
        renderFog()
    }

    private fun issueMarks(): List<Triple<Double, Double, Int>> = try {
        val out = ArrayList<Triple<Double, Double, Int>>()
        if (recordsFile().isFile) {
            val arr = JSONArray(recordsFile().readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.isNull("lat") && !o.isNull("lon")) {
                    out.add(Triple(o.getDouble("lat"), o.getDouble("lon"), 0xFFD9534F.toInt()))
                }
            }
        }
        out
    } catch (_: Throwable) { emptyList() }

    private fun renderFog() {
        fogMap.update(cells, routePoints, issueMarks())
    }

    private fun cellKey(lat: Double, lon: Double): String =
        "${Math.round(lat / 0.0004)}:${Math.round(lon / 0.0004)}"

    private fun haversine(a: DoubleArray, b: DoubleArray): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b[0] - a[0]); val dLon = Math.toRadians(b[1] - a[1])
        val l1 = Math.toRadians(a[0]); val l2 = Math.toRadians(b[0])
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(l1) * cos(l2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    // ------------------------------------------------------------------ misc

    private fun mb(bytes: Long): String = "%.0f MB".format(bytes / 1_000_000.0)

    private fun kb(bytes: Long): String =
        if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${bytes / 1024} KB"

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        // The engine is intentionally process-scoped (see companion): a language change recreates
        // the activity, and re-loading a 2.59 GB model every time would be unacceptable.
        dlStop = true
    }

    companion object {
        /**
         * Process-scoped so that an activity recreation (orientation, language switch) does not
         * throw away a loaded 2.59 GB model. The OS reclaims it with the process.
         */
        @Volatile private var sharedEngine: Engine? = null
    }
}
