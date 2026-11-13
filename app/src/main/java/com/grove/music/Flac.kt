package com.grove.music

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Re-encodes a song to lossless FLAC (decoded PCM -> Android's FLAC encoder) and saves it to
 * Music/Grove FLAC with title/artist/album/track tags and the cover art embedded.
 */
object FlacConverter {
    const val FOLDER = "Grove FLAC"

    fun isFlac(s: Song) = s.mime?.contains("flac", true) == true || s.displayName.endsWith(".flac", true)

    /** Returns true on success. Throws nothing; failures return false. */
    fun convert(ctx: Context, song: Song, onProgress: (Float) -> Unit): Boolean = runCatching {
        val tmp = File(ctx.cacheDir, "flac_${song.id}.frames")
        val (header, totalSamples) = encodeFrames(ctx, song, tmp, onProgress) ?: return false
        val out = ByteArrayOutputStream()
        out.write(buildHeader(header, totalSamples, song, cover(ctx, song)))
        val name = safe("${song.artist} - ${song.title}") + ".flac"
        write(ctx, name) { os -> os.write(out.toByteArray()); tmp.inputStream().use { it.copyTo(os, 1 shl 16) } }
        tmp.delete()
        true
    }.getOrDefault(false)

    private fun safe(s: String) = s.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)

    // ── decode → encode ──────────────────────────────────────────────────────

    private fun encodeFrames(ctx: Context, song: Song, tmp: File, onProgress: (Float) -> Unit): Pair<ByteArray, Long>? {
        val ex = MediaExtractor().apply { setDataSource(ctx, song.uri, null) }
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: return null
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        val durUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) else song.duration * 1000
        val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(inFmt, null, null, 0); dec.start()

        var enc: MediaCodec? = null
        var channels = 2; var floatPcm = false
        var header: ByteArray? = null
        var total = 0L
        val info = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        val frames = FileOutputStream(tmp)
        var inDone = false; var decDone = false; var encDone = false

        fun drainEncoder(wait: Long) {
            val e = enc ?: return
            while (true) {
                val i = e.dequeueOutputBuffer(encInfo, wait)
                if (i < 0) { if (i == MediaCodec.INFO_TRY_AGAIN_LATER) return else continue }
                val b = e.getOutputBuffer(i)!!
                val bytes = ByteArray(encInfo.size); b.position(encInfo.offset); b.get(bytes)
                if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) header = (header ?: ByteArray(0)) + bytes
                else if (bytes.isNotEmpty()) frames.write(bytes)
                e.releaseOutputBuffer(i, false)
                if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encDone = true; return }
            }
        }

        fun startEncoder(f: MediaFormat) {
            val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
                configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
            }
        }

        fun feed(pcm: ByteArray, pts: Long) {
            val e = enc!!
            var off = 0
            while (off < pcm.size) {
                val i = e.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val b = e.getInputBuffer(i)!!; b.clear()
                    val n = minOf(b.remaining(), pcm.size - off)
                    b.put(pcm, off, n); e.queueInputBuffer(i, 0, n, pts, 0); off += n
                }
                drainEncoder(0)
            }
        }

        try {
            while (!decDone) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (enc == null) startEncoder(dec.outputFormat)
                    o >= 0 -> {
                        if (enc == null) startEncoder(dec.outputFormat)
                        val b = dec.getOutputBuffer(o)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        var pcm = ByteArray(info.size).also { b.get(it) }
                        if (floatPcm) pcm = floatTo16(pcm)
                        dec.releaseOutputBuffer(o, false)
                        if (pcm.isNotEmpty()) {
                            total += pcm.size / (2 * channels)
                            feed(pcm, info.presentationTimeUs)
                            if (durUs > 0) onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decDone = true
                    }
                }
            }
            val e = enc ?: return null
            while (true) {
                val i = e.dequeueInputBuffer(10_000)
                if (i >= 0) { e.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                drainEncoder(0)
            }
            while (!encDone) drainEncoder(10_000)
        } finally {
            frames.close()
            runCatching { dec.stop() }; dec.release()
            enc?.let { runCatching { it.stop() }; it.release() }
            ex.release()
        }
        return header?.let { it to total }
    }

    private fun floatTo16(b: ByteArray): ByteArray {
        val fb = ByteBuffer.wrap(b).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocate(fb.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (fb.hasRemaining()) out.putShort((fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return out.array()
    }

    // ── FLAC header with tags ───────────────────────────────────────────────

    private fun streamInfo(cfg: ByteArray): ByteArray {
        if (cfg.size == 34) return cfg.copyOf()
        var p = if (cfg.size >= 4 && String(cfg, 0, 4, Charsets.US_ASCII) == "fLaC") 4 else 0
        while (p + 4 <= cfg.size) {
            val type = cfg[p].toInt() and 0x7F
            val len = ((cfg[p + 1].toInt() and 0xFF) shl 16) or ((cfg[p + 2].toInt() and 0xFF) shl 8) or (cfg[p + 3].toInt() and 0xFF)
            if (type == 0 && len == 34) return cfg.copyOfRange(p + 4, p + 4 + 34)
            p += 4 + len
        }
        error("no STREAMINFO")
    }

    private fun buildHeader(cfg: ByteArray, totalSamples: Long, song: Song, cover: ByteArray?): ByteArray {
        val si = streamInfo(cfg)
        // patch the 36-bit total-samples field so players can show length and seek
        var v = 0L
        for (i in 10..17) v = (v shl 8) or (si[i].toLong() and 0xFF)
        val mask = (1L shl 36) - 1
        v = (v and mask.inv()) or (totalSamples and mask)
        for (i in 17 downTo 10) { si[i] = (v and 0xFF).toByte(); v = v ushr 8 }

        val vc = ByteArrayOutputStream()
        fun le(n: Int) { vc.write(n and 0xFF); vc.write((n shr 8) and 0xFF); vc.write((n shr 16) and 0xFF); vc.write((n shr 24) and 0xFF) }
        val vendor = "Grove Music".toByteArray()
        le(vendor.size); vc.write(vendor)
        val tags = listOf("TITLE=${song.title}", "ARTIST=${song.artist}", "ALBUM=${song.album}",
            "TRACKNUMBER=${song.track % 1000}", "GENRE=${song.genre}").map { it.toByteArray() }
        le(tags.size); tags.forEach { le(it.size); vc.write(it) }

        val blocks = mutableListOf(0 to si, 4 to vc.toByteArray())
        if (cover != null) {
            val pic = ByteArrayOutputStream()
            fun be(n: Int) { pic.write((n shr 24) and 0xFF); pic.write((n shr 16) and 0xFF); pic.write((n shr 8) and 0xFF); pic.write(n and 0xFF) }
            val mime = (if (cover.size > 3 && cover[0] == 0x89.toByte()) "image/png" else "image/jpeg").toByteArray()
            be(3); be(mime.size); pic.write(mime); be(0); be(0); be(0); be(0); be(0); be(cover.size); pic.write(cover)
            blocks += 6 to pic.toByteArray()
        }
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        blocks.forEachIndexed { i, (type, data) ->
            out.write((if (i == blocks.lastIndex) 0x80 else 0) or type)
            out.write((data.size shr 16) and 0xFF); out.write((data.size shr 8) and 0xFF); out.write(data.size and 0xFF)
            out.write(data)
        }
        return out.toByteArray()
    }

    private fun cover(ctx: Context, song: Song): ByteArray? = runCatching {
        MediaMetadataRetriever().run {
            try { setDataSource(ctx, song.uri); embeddedPicture } finally { release() }
        }
    }.getOrNull() ?: runCatching { ctx.contentResolver.openInputStream(song.artUri)?.use { it.readBytes() } }.getOrNull()

    // ── save ────────────────────────────────────────────────────────────────

    private fun write(ctx: Context, name: String, body: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)!!
            try {
                r.openOutputStream(uri)!!.use(body)
                r.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { r.delete(uri, null, null); throw e }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER).apply { mkdirs() }
            val f = File(dir, name)
            FileOutputStream(f).use(body)
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("audio/flac"), null)
        }
    }
}
