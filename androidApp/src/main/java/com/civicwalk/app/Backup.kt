package com.civicwalk.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Notes + walked cells + photos as one zip. Model weights are not included
 * (they are 2.6 GB and re-downloaded). Photo paths inside records.json are
 * rewritten to photos/<name> so the zip is portable across devices.
 */
object Backup {

    fun exportZip(filesDir: File, dest: OutputStream): Int {
        val photosDir = File(filesDir, "photos")
        val records = File(filesDir, "records.json")
        val cells = File(filesDir, "cells.json")
        var n = 0
        ZipOutputStream(dest.buffered()).use { zip ->
            if (records.isFile) {
                zip.putNextEntry(ZipEntry("records.json"))
                zip.write(portableRecords(records, photosDir).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                n++
            }
            if (cells.isFile) {
                zip.putNextEntry(ZipEntry("cells.json"))
                zip.write(cells.readBytes())
                zip.closeEntry()
            }
            val tracks = File(filesDir, "tracks.json")
            if (tracks.isFile) {
                zip.putNextEntry(ZipEntry("tracks.json"))
                zip.write(tracks.readBytes())
                zip.closeEntry()
            }
            photosDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg", true) }?.forEach { f ->
                zip.putNextEntry(ZipEntry("photos/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                n++
            }
        }
        return n
    }

    fun importZip(filesDir: File, src: InputStream): Int {
        val photosDir = File(filesDir, "photos").apply { mkdirs() }
        var imported = 0
        var recordsText: String? = null
        ZipInputStream(src.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.replace('\\', '/').trimStart('/')
                if (name.contains("..") || name.startsWith("/")) continue
                when {
                    name == "records.json" -> recordsText = zip.readBytes().toString(Charsets.UTF_8)
                    name == "cells.json" -> {
                        File(filesDir, "cells.json").outputStream().use { zip.copyTo(it) }
                    }
                    name == "tracks.json" -> {
                        File(filesDir, "tracks.json").outputStream().use { zip.copyTo(it) }
                    }
                    name.startsWith("photos/") && name.endsWith(".jpg", true) -> {
                        val out = File(photosDir, File(name).name)
                        out.outputStream().use { zip.copyTo(it) }
                        imported++
                    }
                }
                zip.closeEntry()
            }
        }
        if (recordsText != null) {
            File(filesDir, "records.json").writeText(relocatePhotos(recordsText!!, photosDir))
        }
        return imported
    }

    private fun portableRecords(records: File, photosDir: File): String {
        val arr = JSONArray(records.readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = o.optString("photo")
            if (p.isNotEmpty()) {
                val name = File(p).name
                o.put("photo", "photos/$name")
            }
        }
        return arr.toString()
    }

    private fun relocatePhotos(json: String, photosDir: File): String {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = o.optString("photo")
            if (p.isNotEmpty()) {
                val name = File(p).name
                o.put("photo", File(photosDir, name).absolutePath)
            }
        }
        return arr.toString(2)
    }
}
