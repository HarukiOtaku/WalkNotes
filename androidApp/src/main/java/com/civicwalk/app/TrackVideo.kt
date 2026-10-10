package com.civicwalk.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.View
import java.io.File

/** Draw the fog map onto bitmaps and mux an H.264 MP4. Runs on a worker thread. */
object TrackVideo {

    fun render(ctx: Context, cells: Set<String>, points: List<DoubleArray>, dest: File): String? {
        if (points.size < 2) return "need a path"
        val w = 720
        val h = 1280
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val view = FogMapView(ctx)
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        val wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        view.measure(wSpec, hSpec)
        view.layout(0, 0, w, h)

        val frames = (minOf(120, maxOf(48, points.size))).coerceAtLeast(2)
        val hold = 18
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val argb = IntArray(w * h)
        val yuv = ByteArray(w * h * 3 / 2)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_800_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 24)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val bufInfo = MediaCodec.BufferInfo()
        val usPerFrame = 1_000_000L / 24L
        var pts = 0L

        fun drain(end: Boolean) {
            var idle = 0
            while (true) {
                val i = codec.dequeueOutputBuffer(bufInfo, 10_000)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!end) return
                        if (++idle > 80) return
                    }
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxing) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                    }
                    i >= 0 -> {
                        val out = codec.getOutputBuffer(i)
                        if (out != null && bufInfo.size > 0 && muxing && bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            out.position(bufInfo.offset)
                            out.limit(bufInfo.offset + bufInfo.size)
                            muxer.writeSampleData(track, out, bufInfo)
                        }
                        val eos = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(i, false)
                        if (eos) return
                    }
                }
            }
        }

        try {
            for (f in 0 until frames + hold) {
                val n = if (f >= frames) points.size
                else (1 + ((f.toLong() * (points.size - 1)) / (frames - 1)).toInt()).coerceIn(2, points.size)
                view.update(cells, points.subList(0, n), emptyList())
                bitmap.eraseColor(0xFFFFFFFF.toInt())
                view.draw(canvas)
                bitmap.getPixels(argb, 0, w, 0, 0, w, h)
                rgbToNv12(argb, yuv, w, h)
                val inIx = codec.dequeueInputBuffer(50_000)
                if (inIx >= 0) {
                    val inBuf = codec.getInputBuffer(inIx)!!
                    inBuf.clear()
                    inBuf.put(yuv)
                    codec.queueInputBuffer(inIx, 0, yuv.size, pts, 0)
                    pts += usPerFrame
                }
                drain(false)
            }
            val eos = codec.dequeueInputBuffer(50_000)
            if (eos >= 0) codec.queueInputBuffer(eos, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
            try { if (muxing) muxer.stop() } catch (_: Throwable) {}
            try { muxer.release() } catch (_: Throwable) {}
            bitmap.recycle()
        }
        return if (dest.isFile && dest.length() > 1000) null else "empty file"
    }

    private fun rgbToNv12(argb: IntArray, yuv: ByteArray, width: Int, height: Int) {
        val frame = width * height
        var yIndex = 0
        var uvIndex = frame
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[index]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
                index++
            }
        }
    }
}
