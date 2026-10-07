package com.civicwalk.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.civicwalk.core.TextTruncate
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CivicWalk — on-device civic reporting (prototype).
 * Everything except the one-time model download happens on the phone: the photo, the
 * coordinates and the draft never leave the device.
 */
class MainActivity : Activity() {

    private val modelName = "gemma-4-E2B-it.litertlm"
    private val modelUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    private lateinit var tvModel: TextView
    private lateinit var tvExif: TextView
    private lateinit var tvPlace: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBytes: TextView
    private lateinit var tvRoute: TextView
    private lateinit var tvRecords: TextView
    private lateinit var imgPreview: ImageView
    private lateinit var etDraft: EditText
    private lateinit var etAddr: EditText

    private var engine: Engine? = null
    private var photoFile: File? = null
    private var exifTime: String? = null
    private var exifLoc: DoubleArray? = null
    private var gpsLoc: DoubleArray? = null
    private var gpsAcc: Float = -1f

    private val cells = LinkedHashSet<String>()
    private var distanceKm = 0.0
    private var lastPoint: DoubleArray? = null
    private var recording = false

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)

    private val REQ_CAMERA = 1001
    private val REQ_PICK = 1002
    private val REQ_PERM = 1003

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvModel = findViewById(R.id.tvModel)
        tvExif = findViewById(R.id.tvExif)
        tvPlace = findViewById(R.id.tvPlace)
        tvStatus = findViewById(R.id.tvStatus)
        tvBytes = findViewById(R.id.tvBytes)
        tvRoute = findViewById(R.id.tvRoute)
        tvRecords = findViewById(R.id.tvRecords)
        imgPreview = findViewById(R.id.imgPreview)
        etDraft = findViewById(R.id.etDraft)
        etAddr = findViewById(R.id.etAddr)

        loadState()

        findViewById<Button>(R.id.btnLoad).setOnClickListener { loadModel() }
        findViewById<Button>(R.id.btnDownload).setOnClickListener { downloadModel() }
        findViewById<Button>(R.id.btnCamera).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.btnUpload).setOnClickListener { pickPhoto() }
        findViewById<Button>(R.id.btnGps).setOnClickListener { useGps() }
        findViewById<Button>(R.id.btnExifLoc).setOnClickListener { useExifLocation() }
        findViewById<Button>(R.id.btnAnalyse).setOnClickListener { analyse() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyDraft() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveRecord() }
        findViewById<Button>(R.id.btnRoute).setOnClickListener { toggleRoute() }

        etDraft.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateBytes() }
        })

        requestPermissions(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ), REQ_PERM)

        refreshModelState()
        renderRecords()
        renderRoute()
    }

    // ---------------------------------------------------------------- model

    private fun modelCandidates(): List<File> {
        val ext = getExternalFilesDir(null)
        // Android 10+ scoped storage: the app can always read its own external files dir
        // (/sdcard/Android/data/<pkg>/files) with no permission; /sdcard/Download would
        // need MANAGE_EXTERNAL_STORAGE, so it is deliberately not a candidate.
        return listOfNotNull(
            ext?.let { File(it, modelName) },
            File(filesDir, modelName)
        )
    }

    private fun findModel(): File? = modelCandidates().firstOrNull { it.isFile && it.length() > 1_000_000_000L }

    private fun refreshModelState() {
        val f = findModel()
        tvModel.text = if (f != null) getString(R.string.model_ready, f.absolutePath)
                       else getString(R.string.model_missing)
    }

    private fun loadModel() {
        val f = findModel() ?: run { toast(getString(R.string.model_missing)); return }
        if (engine != null) { toast("Model already loaded"); return }
        tvStatus.text = "Loading ${f.name} (${f.length() / 1_000_000} MB) …"
        Thread {
            try {
                val cfg = EngineConfig(modelPath = f.absolutePath, backend = Backend.GPU(), cacheDir = cacheDir.path)
                val e = Engine(cfg)
                e.initialize()
                engine = e
                main.post { tvStatus.text = "Model loaded (GPU). Ready to analyse." }
            } catch (t: Throwable) {
                main.post { tvStatus.text = "Load failed: ${t.message}" }
                try {
                    val cfg = EngineConfig(modelPath = f.absolutePath, backend = Backend.CPU(), cacheDir = cacheDir.path)
                    val e = Engine(cfg); e.initialize(); engine = e
                    main.post { tvStatus.text = "Model loaded (CPU fallback). Ready." }
                } catch (t2: Throwable) {
                    main.post { tvStatus.text = "Load failed on GPU and CPU: ${t2.message}" }
                }
            }
        }.start()
    }

    private fun downloadModel() {
        val target = File(getExternalFilesDir(null) ?: filesDir, modelName)
        if (target.isFile && target.length() > 1_000_000_000L) { refreshModelState(); return }
        tvStatus.text = "Downloading model (2.59 GB) …"
        Thread {
            try {
                val conn = (URL(modelUrl).openConnection() as HttpURLConnection)
                conn.connectTimeout = 15000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                conn.inputStream.use { input ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(1 shl 16); var total = 0L; var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n); total += n
                            if (total % (50L * 1_000_000) < buf.size) {
                                val mb = total / 1_000_000
                                main.post { tvStatus.text = "Downloading … $mb MB" }
                            }
                        }
                    }
                }
                main.post { tvStatus.text = "Model downloaded to ${target.absolutePath}"; refreshModelState() }
            } catch (t: Throwable) {
                main.post { tvStatus.text = "Download failed: ${t.message}" }
            }
        }.start()
    }

    // ---------------------------------------------------------------- photo

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
        startActivityForResult(Intent.createChooser(i, "Pick a photo"), REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val f: File? = when (requestCode) {
            REQ_CAMERA -> photoFile
            REQ_PICK -> data?.data?.let { copyToCache(it) }
            else -> null
        }
        if (f == null || !f.isFile) { tvStatus.text = "No photo"; return }
        photoFile = f
        imgPreview.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
        readExif(f)
        tvStatus.text = "Photo loaded (${f.length() / 1024} KB). Still on your device."
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
            val d = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
            exifTime = d?.replaceFirst(":", "-")?.replaceFirst(":", "-")
            val gps = if (latLon != null) " · GPS %.5f, %.5f".format(latLon[0], latLon[1]) else " · no GPS"
            tvExif.text = "EXIF: ${exifTime ?: "no time"}$gps"
        } catch (t: Throwable) {
            exifLoc = null; exifTime = null
            tvExif.text = "EXIF: unreadable (${t.message})"
        }
    }

    // ---------------------------------------------------------------- location

    private fun requireLocationPerm(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun useGps() {
        if (!requireLocationPerm()) { toast("Location permission needed"); return }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        tvStatus.text = "Getting a GPS fix …"
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                gpsLoc = doubleArrayOf(loc.latitude, loc.longitude); gpsAcc = loc.accuracy
                lm.removeUpdates(this)
                main.post {
                    tvPlace.text = "Place: GPS %.5f, %.5f (±%.0f m)".format(loc.latitude, loc.longitude, loc.accuracy)
                    tvStatus.text = "GPS fix acquired"
                }
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null && gpsLoc == null) {
                gpsLoc = doubleArrayOf(last.latitude, last.longitude); gpsAcc = last.accuracy
                tvPlace.text = "Place: GPS(cached) %.5f, %.5f".format(last.latitude, last.longitude)
            }
            main.postDelayed({ lm.removeUpdates(listener) }, 20000)
        } catch (t: Throwable) { tvStatus.text = "GPS unavailable: ${t.message}" }
    }

    private fun useExifLocation() {
        val p = exifLoc
        if (p == null) { toast("This photo has no GPS in EXIF"); return }
        tvPlace.text = "Place: EXIF %.5f, %.5f%s".format(p[0], p[1], exifTime?.let { " ($it)" } ?: "")
    }

    private fun currentPoint(): DoubleArray? = when {
        gpsLoc != null -> gpsLoc
        exifLoc != null -> exifLoc
        else -> null
    }

    private fun placeLabel(): String {
        val addr = etAddr.text.toString().trim()
        val p = currentPoint()
        return when {
            p != null && addr.isNotEmpty() -> "typed address: $addr; %s".format("%.5f, %.5f".format(p[0], p[1]))
            p != null -> "on-device coordinates %.5f, %.5f".format(p[0], p[1])
            addr.isNotEmpty() -> "typed address: $addr"
            else -> "not recorded (user chose not to)"
        }
    }

    // ---------------------------------------------------------------- analyse

    private fun analyse() {
        val e = engine ?: run { toast("Load the model first"); return }
        val stamps = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date())
        val prompt = """
            You are the on-device assistant of a Hong Kong civic-reporting app.
            A photo of a street or pavement has been taken by the user.
            Location: ${placeLabel()}
            Local time: $stamps (UTC: $utc)

            Write ONLY the body of a case report to paste into the HKSAR 1823 online form.
            Rules: Traditional Chinese; describe only what is visible in a photo of this kind;
            do not invent a district name; state facts, no accusations, no personal names;
            120-200 Chinese characters; end with the action requested.
        """.trimIndent()

        tvStatus.text = "Gemma is analysing on device …"
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
                tvStatus.text = "Done on device — no server was contacted."
            } catch (t: Throwable) {
                tvStatus.text = "Analysis failed: ${t.message}"
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
        toast("Copied — paste into 1823")
    }

    // ---------------------------------------------------------------- records

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
                put("photo", photoFile?.absolutePath ?: "")
                put("draft", etDraft.text.toString())
                put("report_language", "zh-HK")
            }
            arr.put(o)
            recordsFile().writeText(arr.toString())
            renderRecords()
            toast("Saved on this device")
        } catch (t: Throwable) { toast("Save failed: ${t.message}") }
    }

    private fun renderRecords() {
        tvRecords.text = try {
            if (!recordsFile().isFile) getString(R.string.empty) else {
                val arr = JSONArray(recordsFile().readText())
                if (arr.length() == 0) getString(R.string.empty)
                else (0 until arr.length()).joinToString("\n") { i ->
                    val o = arr.getJSONObject(i)
                    "${i + 1}. ${o.optString("time_local")} · ${o.optString("place_label")}"
                }
            }
        } catch (t: Throwable) { getString(R.string.empty) }
    }

    // ---------------------------------------------------------------- route / cells

    private fun cellsFile() = File(filesDir, "cells.json")

    private fun cellKey(lat: Double, lon: Double): String =
        "${Math.round(lat / 0.0004)}:${Math.round(lon / 0.0004)}"

    private fun loadState() {
        try {
            if (cellsFile().isFile) JSONArray(cellsFile().readText()).let { a -> for (i in 0 until a.length()) cells.add(a.getString(i)) }
        } catch (_: Throwable) {}
    }

    private fun saveCells() {
        try { cellsFile().writeText(JSONArray(cells.toList()).toString()) } catch (_: Throwable) {}
    }

    private fun toggleRoute() {
        if (!requireLocationPerm()) { toast("Location permission needed"); return }
        recording = !recording
        findViewById<Button>(R.id.btnRoute).text = getString(if (recording) R.string.btn_route_stop else R.string.btn_route_start)
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!recording) { tvStatus.text = "Route recording stopped"; return }
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val k = cellKey(loc.latitude, loc.longitude)
                cells.add(k); saveCells()
                lastPoint?.let { p -> distanceKm += haversine(p, doubleArrayOf(loc.latitude, loc.longitude)) / 1000.0 }
                lastPoint = doubleArrayOf(loc.latitude, loc.longitude)
                renderRoute()
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, listener, Looper.getMainLooper())
        renderRoute()
    }

    private fun renderRoute() {
        tvRoute.text = getString(R.string.cells_fmt, cells.size, distanceKm)
    }

    private fun haversine(a: DoubleArray, b: DoubleArray): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b[0] - a[0]); val dLon = Math.toRadians(b[1] - a[1])
        val l1 = Math.toRadians(a[0]); val l2 = Math.toRadians(b[0])
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(l1) * cos(l2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        try { engine?.close() } catch (_: Throwable) {}
    }
}
