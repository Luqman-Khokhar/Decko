package com.luqman.decko

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.File
import java.io.RandomAccessFile

/**
 * Splits an animated WebP into composited frames. The platform decoder only plays animations,
 * so the RIFF container is read directly and each frame is decoded as its own still WebP.
 */
object WebpFrames {
    internal class Frame(val x: Int, val y: Int, val data: ByteArray, val durationMs: Int, val blend: Boolean, val disposeToBackground: Boolean)

    private fun le24(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)
    private fun le32(b: ByteArray, o: Int) = le24(b, o) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun put24(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte()
    }

    private fun put32(b: ByteArray, o: Int, v: Int) {
        put24(b, o, v); b[o + 3] = (v shr 24).toByte()
    }

    /** One still WebP (extended format, alpha flagged) from an ANMF frame's image sub-chunks. */
    private fun still(width: Int, height: Int, imageChunks: ByteArray): ByteArray {
        val out = ByteArray(12 + 18 + imageChunks.size)
        "RIFF".toByteArray().copyInto(out, 0)
        put32(out, 4, out.size - 8)
        "WEBP".toByteArray().copyInto(out, 8)
        "VP8X".toByteArray().copyInto(out, 12)
        put32(out, 16, 10)
        out[20] = 0x10
        put24(out, 24, width - 1)
        put24(out, 27, height - 1)
        imageChunks.copyInto(out, 30)
        return out
    }

    private fun readFrames(file: File): Triple<Int, Int, List<Frame>>? {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(12)
            f.readFully(head)
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(head, 8, 4, Charsets.ISO_8859_1) != "WEBP") return null
            var canvasW = 0
            var canvasH = 0
            val frames = ArrayList<Frame>()
            val ch = ByteArray(8)
            while (f.filePointer + 8 <= f.length()) {
                f.readFully(ch)
                val type = String(ch, 0, 4, Charsets.ISO_8859_1)
                val size = le32(ch, 4)
                if (size < 0 || f.filePointer + size > f.length()) break
                val padded = size + (size and 1)
                when (type) {
                    "VP8X" -> {
                        val b = ByteArray(size)
                        f.readFully(b)
                        canvasW = le24(b, 4) + 1
                        canvasH = le24(b, 7) + 1
                        if (padded > size) f.skipBytes(1)
                    }
                    "ANMF" -> {
                        val b = ByteArray(size)
                        f.readFully(b)
                        if (padded > size) f.skipBytes(1)
                        if (size < 16) continue
                        val w = le24(b, 6) + 1
                        val h = le24(b, 9) + 1
                        val flags = b[15].toInt()
                        frames += Frame(
                            x = le24(b, 0) * 2, y = le24(b, 3) * 2,
                            data = still(w, h, b.copyOfRange(16, size)),
                            durationMs = le24(b, 12),
                            blend = flags and 0x02 == 0,
                            disposeToBackground = flags and 0x01 != 0,
                        )
                    }
                    else -> f.skipBytes(padded)
                }
            }
            return if (canvasW > 0 && canvasH > 0 && frames.isNotEmpty()) Triple(canvasW, canvasH, frames) else null
        }
    }

    /** A parsed animated WebP; [snapshots] composites frames on demand so size can be chosen after reading the header. */
    class Animation internal constructor(val width: Int, val height: Int, private val frames: List<Frame>) {
        val durationMs = frames.sumOf { it.durationMs.coerceAtLeast(10) }

        /** The most common frame delay, i.e. the animation's real frame rate. */
        val typicalDelayMs: Int = frames.groupingBy { it.durationMs.coerceAtLeast(10) }.eachCount().maxByOrNull { it.value }?.key ?: 100

        /** [count] snapshots evenly spaced across the animation, each composited and scaled to [outW]×[outH]. */
        fun snapshots(count: Int, outW: Int, outH: Int): List<Bitmap> = try {
            compose(width, height, frames, count, outW, outH)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Null when the file cannot be read as an animated WebP. */
    fun open(file: File): Animation? = try {
        readFrames(file)?.let { (w, h, frames) -> Animation(w, h, frames) }
    } catch (e: Exception) {
        null
    }

    private fun compose(canvasW: Int, canvasH: Int, frames: List<Frame>, count: Int, outW: Int, outH: Int): List<Bitmap> {
        val durations = frames.map { it.durationMs.coerceAtLeast(10) }
        val total = durations.sum()
        val full = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        val replace = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val out = ArrayList<Bitmap>()
        var next = 0
        var start = 0L
        for ((i, frame) in frames.withIndex()) {
            val bmp = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
            if (bmp != null) {
                canvas.drawBitmap(bmp, frame.x.toFloat(), frame.y.toFloat(), if (frame.blend) null else replace)
            }
            val end = start + durations[i]
            // Every sample time that falls inside this frame's interval shows the canvas as it stands now.
            while (next < count && next.toLong() * total / count < end) {
                out += Bitmap.createScaledBitmap(full, outW, outH, true)
                next++
            }
            if (frame.disposeToBackground && bmp != null) {
                canvas.drawRect(frame.x.toFloat(), frame.y.toFloat(), (frame.x + bmp.width).toFloat(), (frame.y + bmp.height).toFloat(), clear)
            }
            bmp?.recycle()
            start = end
        }
        return out
    }
}
