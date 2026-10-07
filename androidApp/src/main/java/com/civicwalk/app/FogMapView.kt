package com.civicwalk.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fog-of-war map, drawn entirely on the device (no tiles, no network).
 *
 * Grey = not walked yet. A ~44 m cell you have been through is punched out of the fog, so the
 * map fills in as you walk. Reported issues are drawn on top as numbered dots.
 * Mirrors the canvas logic of the HTML prototype so the two stay comparable.
 */
class FogMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val cell = 0.0004                      // ~44 m
    private var cells: Set<String> = emptySet()
    private var route: List<DoubleArray> = emptyList()
    private var issues: List<Triple<Double, Double, Int>> = emptyList()   // lat, lon, colour

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 26f; isFakeBoldText = true
    }

    fun update(newCells: Set<String>, newRoute: List<DoubleArray>, newIssues: List<Triple<Double, Double, Int>>) {
        cells = newCells.toSet(); route = newRoute.toList(); issues = newIssues.toList()
        invalidate()
    }

    private fun cellKey(lat: Double, lon: Double) = "${Math.round(lat / cell)}:${Math.round(lon / cell)}"
    private fun cellLat(k: String) = k.substringBefore(":").toInt() * cell
    private fun cellLon(k: String) = k.substringAfter(":").toInt() * cell

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(Color.parseColor("#FBFCFB"))

        val pts = route + issues.map { doubleArrayOf(it.first, it.second) }
        if (pts.isEmpty() || w < 10 || h < 10) {
            paint.color = Color.parseColor("#98A29B"); paint.textAlign = Paint.Align.CENTER; paint.textSize = 30f
            canvas.drawText("No walk recorded yet", w / 2, h / 2, paint)
            paint.textAlign = Paint.Align.LEFT
            return
        }

        var lat0 = pts.minOf { it[0] }; var lat1 = pts.maxOf { it[0] }
        var lon0 = pts.minOf { it[1] }; var lon1 = pts.maxOf { it[1] }
        val padLat = max((lat1 - lat0) * 0.35, 8 * cell)
        val padLon = max((lon1 - lon0) * 0.35, 8 * cell)
        lat0 -= padLat; lat1 += padLat; lon0 -= padLon; lon1 += padLon
        val spanLat = max(lat1 - lat0, 1e-6); val spanLon = max(lon1 - lon0, 1e-6)

        val inset = 24f
        fun X(lon: Double) = inset + ((lon - lon0) / spanLon * (w - 2 * inset)).toFloat()
        fun Y(lat: Double) = h - inset - ((lat - lat0) / spanLat * (h - 2 * inset)).toFloat()

        // fog
        paint.color = Color.argb(158, 120, 128, 124)
        canvas.drawRect(0f, 0f, w, h, paint)

        // punched-out explored cells (lighter)
        paint.color = Color.argb(230, 236, 247, 240)
        val cw = abs(X(lon0 + cell) - X(lon0)) * 1.15f
        val ch = abs(Y(lat0) - Y(lat0 + cell)) * 1.15f
        cells.forEach { k ->
            val la = cellLat(k); val lo = cellLon(k)
            if (la < lat0 || la > lat1 || lo < lon0 || lo > lon1) return@forEach
            canvas.drawRect(X(lo - cell / 2), Y(la + cell / 2), X(lo - cell / 2) + cw, Y(la + cell / 2) + ch, paint)
        }

        // frontier: cells one step out that you could open next
        paint.color = Color.argb(90, 201, 162, 39)
        val fresh = HashSet<String>()
        cells.forEach { k ->
            val la = cellLat(k); val lo = cellLon(k)
            listOf(cell to 0.0, -cell to 0.0, 0.0 to cell, 0.0 to -cell).forEach { d ->
                val nk = cellKey(la + d.first, lo + d.second)
                if (!cells.contains(nk)) fresh.add(nk)
            }
        }
        fresh.forEach { k ->
            val la = cellLat(k); val lo = cellLon(k)
            if (la < lat0 || la > lat1 || lo < lon0 || lo > lon1) return@forEach
            canvas.drawRect(X(lo - cell / 2), Y(la + cell / 2), X(lo - cell / 2) + cw, Y(la + cell / 2) + ch, paint)
        }

        // route
        if (route.size > 1) {
            paint.color = Color.parseColor("#2F7D52"); paint.strokeWidth = 7f; paint.style = Paint.Style.STROKE
            var prev: DoubleArray? = null
            route.forEach { p ->
                prev?.let { canvas.drawLine(X(it[1]), Y(it[0]), X(p[1]), Y(p[0]), paint) }
                prev = p
            }
            paint.style = Paint.Style.FILL
        }

        // issues
        issues.forEachIndexed { i, it ->
            paint.color = it.third
            canvas.drawCircle(X(it.second), Y(it.first), 20f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(X(it.second), Y(it.first), 20f, paint)
            paint.color = it.third
            canvas.drawCircle(X(it.second), Y(it.first), 17f, paint)
            canvas.drawText("${i + 1}", X(it.second), Y(it.first) + 9f, text)
        }
    }

    /** Cells currently revealed — used by the activity for its counter. */
    fun cellCount(): Int = cells.size

    companion object {
        fun key(lat: Double, lon: Double) = "${Math.round(lat / 0.0004)}:${Math.round(lon / 0.0004)}"
    }
}
