package com.civicwalk.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Track(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val points: List<DoubleArray>
) {
    fun km(): Double {
        if (points.size < 2) return 0.0
        var s = 0.0
        for (i in 1 until points.size) s += haversine(points[i - 1], points[i])
        return s / 1000.0
    }

    fun bucket(unit: String): String {
        val d = Date(startMs)
        return when (unit) {
            "day" -> DAY.format(d)
            "month" -> MONTH.format(d)
            "year" -> YEAR.format(d)
            else -> "all"
        }
    }

    companion object {
        private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val MONTH = SimpleDateFormat("yyyy-MM", Locale.US)
        private val YEAR = SimpleDateFormat("yyyy", Locale.US)
    }
}

object Tracks {

    fun file(dir: File) = File(dir, "tracks.json")

    fun load(dir: File): MutableList<Track> {
        val f = file(dir)
        if (!f.isFile) return ArrayList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pts = o.optJSONArray("pts") ?: JSONArray()
                val list = ArrayList<DoubleArray>(pts.length())
                for (j in 0 until pts.length()) {
                    val p = pts.getJSONArray(j)
                    if (p.length() >= 2) {
                        list.add(doubleArrayOf(p.getDouble(0), p.getDouble(1), if (p.length() > 2) p.optDouble(2) else 0.0))
                    }
                }
                out.add(Track(o.optString("id"), o.optLong("start_ms"), o.optLong("end_ms"), list))
            }
            out
        } catch (_: Throwable) { ArrayList() }
    }

    fun save(dir: File, list: List<Track>) {
        val arr = JSONArray()
        list.forEach { t ->
            val pts = JSONArray()
            t.points.forEach { p ->
                pts.put(JSONArray().put(p[0]).put(p[1]).put(if (p.size > 2) p[2] else 0.0))
            }
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("start_ms", t.startMs)
                    .put("end_ms", t.endMs)
                    .put("pts", pts)
            )
        }
        file(dir).writeText(arr.toString())
    }

    fun add(dir: File, track: Track) {
        if (track.points.size < 2) return
        val all = load(dir)
        all.add(track)
        save(dir, all)
    }

    fun grouped(dir: File, unit: String): List<Pair<String, List<Track>>> {
        val map = LinkedHashMap<String, ArrayList<Track>>()
        load(dir).sortedByDescending { it.startMs }.forEach { t ->
            map.getOrPut(t.bucket(unit)) { ArrayList() }.add(t)
        }
        return map.entries.map { it.key to it.value }
    }

    fun mergePoints(tracks: List<Track>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        tracks.sortedBy { it.startMs }.forEach { out.addAll(it.points) }
        return out
    }
}

internal fun haversine(a: DoubleArray, b: DoubleArray): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b[0] - a[0]); val dLon = Math.toRadians(b[1] - a[1])
    val l1 = Math.toRadians(a[0]); val l2 = Math.toRadians(b[0])
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(l1) * cos(l2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(h))
}
