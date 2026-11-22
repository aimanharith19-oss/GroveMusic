package com.grove.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DSD (.dsf / .dff) playback: the 1-bit stream is filtered and decimated on the fly into
 * 24-bit PCM at 88.2 kHz (176.4 kHz for DSD128+) and served to ExoPlayer as a virtual WAV file.
 */
object Dsd {
    const val SCHEME = "grovedsd"
    fun isDsdName(name: String) = name.endsWith(".dsf", true) || name.endsWith(".dff", true)
    fun wrap(real: Uri, name: String): Uri = Uri.Builder().scheme(SCHEME).authority("x").appendQueryParameter("u", real.toString())
        .appendQueryParameter("n", name).build()
    fun real(u: Uri): Uri = Uri.parse(u.getQueryParameter("u"))
    fun name(u: Uri): String = u.getQueryParameter("n") ?: ""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("grove", Context.MODE_PRIVATE)
    fun folders(ctx: Context): List<Uri> = prefs(ctx).getStringSet("dsd_folders", emptySet())!!.map(Uri::parse)
    fun addFolder(ctx: Context, u: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs(ctx).edit().putStringSet("dsd_folders", folders(ctx).map { it.toString() }.toSet() + u.toString()).apply()
    }
    fun removeFolder(ctx: Context, u: Uri) =
        prefs(ctx).edit().putStringSet("dsd_folders", (folders(ctx).map { it.toString() } - u.toString()).toSet()).apply()

    /** DSD songs: ones Android indexed + ones in folders picked in settings */
    fun songs(ctx: Context): List<Song> {
        val out = LinkedHashMap<String, Song>()
        runCatching {
            val base = MediaStore.Files.getContentUri("external")
            ctx.contentResolver.query(base, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DATE_ADDED),
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.dsf' OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.dff'", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val uri = ContentUris.withAppendedId(base, c.getLong(0))
                    out[name] = song(ctx, uri, name, c.getString(2), c.getLong(3))
                }
            }
        }
        folders(ctx).forEach { tree -> runCatching { walk(ctx, tree, DocumentsContract.getTreeDocumentId(tree), "", 0, out) } }
        return out.values.toList()
    }

    private fun walk(ctx: Context, tree: Uri, docId: String, folder: String, depth: Int, out: MutableMap<String, Song>) {
        if (depth > 6) return
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        ctx.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) walk(ctx, tree, id, name, depth + 1, out)
                else if (isDsdName(name) && name !in out) out[name] = song(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, id), name,
                    "$folder/$name", c.getLong(3) / 1000)
            }
        }
    }

    private fun song(ctx: Context, uri: Uri, name: String, path: String?, added: Long): Song {
        val base = name.substringBeforeLast('.')
        val (artist, title) = base.split(" - ", limit = 2).let { if (it.size == 2) it[0].trim() to it[1].trim() else "unknown artist" to base }
        val album = path?.substringBeforeLast('/', "")?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "DSD"
        val info = runCatching { open(ctx, uri).use { it.info } }.getOrNull()
        return Song(id = -(uri.toString().hashCode().toLong() and 0x7fffffffL) - 1, title = title, artist = artist, album = album,
            albumId = -(album.hashCode().toLong() and 0x7fffffffL) - 1, track = 0, duration = info?.durationMs ?: 0, uri = wrap(uri, name),
            path = path, mime = "audio/dsd", displayName = name, genre = "unknown genre", added = added)
    }

    class Info(val channels: Int, val rate: Int, val bytesPerChannel: Long, val dataStart: Long, val dsf: Boolean, val blockSize: Int) {
        val outRate = if (rate >= 5_644_800) 176_400 else 88_200
        val decim = rate / outRate               // DSD bits per output sample
        val bytesStep = decim / 8               // DSD bytes per output sample
        val frames = bytesPerChannel / bytesStep
        val durationMs = frames * 1000 / outRate
        val label get() = "DSD${rate / 44_100} · ${rate / 1000.0 / 1000}MHz → ${outRate / 1000.0}kHz 24-bit"
    }

    class Reader(val fc: FileChannel, val stream: FileInputStream, val info: Info) : AutoCloseable {
        override fun close() { runCatching { fc.close() }; runCatching { stream.close() } }
        /** [count] bytes of channel [ch] starting at byte index [start]; out-of-range bytes are DSD silence */
        fun channel(ch: Int, start: Long, count: Int, out: ByteArray) {
            java.util.Arrays.fill(out, 0, count, 0x69)
            val i = info
            if (i.dsf) {
                var idx = maxOf(start, 0L); val end = minOf(start + count, i.bytesPerChannel)
                while (idx < end) {
                    val block = idx / i.blockSize; val off = (idx % i.blockSize).toInt()
                    val n = minOf((i.blockSize - off).toLong(), end - idx).toInt()
                    val pos = i.dataStart + (block * i.channels + ch) * i.blockSize + off
                    read(pos, out, (idx - start).toInt(), n)
                    idx += n
                }
            } else {
                val s = maxOf(start, 0L); val end = minOf(start + count, i.bytesPerChannel)
                if (end <= s) return
                val raw = ByteArray(((end - s) * i.channels).toInt())
                read(i.dataStart + s * i.channels, raw, 0, raw.size)
                var k = (s - start).toInt()
                var j = ch
                while (j < raw.size) { out[k++] = raw[j]; j += i.channels }
            }
        }
        private fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
            val bb = ByteBuffer.wrap(dst, off, len); var p = pos
            while (bb.hasRemaining()) { val r = fc.read(bb, p); if (r <= 0) break; p += r }
        }
    }

    fun open(ctx: Context, uri: Uri): Reader {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("can't open")
        val st = FileInputStream(pfd.fileDescriptor); val fc = st.channel
        fun bytes(pos: Long, n: Int): ByteBuffer { val b = ByteBuffer.allocate(n); fc.read(b, pos); b.flip(); return b }
        val head = String(bytes(0, 4).array(), Charsets.US_ASCII)
        val info = if (head == "DSD ") {
            val fmtPos = 28L
            val f = bytes(fmtPos, 52).order(ByteOrder.LITTLE_ENDIAN)
            val fmtSize = f.getLong(4)
            val ch = f.getInt(24); val rate = f.getInt(28); val bits = f.getInt(32)
            val samples = f.getLong(36); val block = f.getInt(44)
            require(bits == 1) { "8-bit DSF not supported" }
            val dataPos = fmtPos + fmtSize
            Info(ch, rate, samples / 8, dataPos + 12, true, block)
        } else if (head == "FRM8") {
            var pos = 16L; var ch = 2; var rate = 2_822_400; var dataStart = 0L; var dataLen = 0L
            val total = bytes(4, 8).order(ByteOrder.BIG_ENDIAN).getLong() + 12
            while (pos < total) {
                val h = bytes(pos, 12).order(ByteOrder.BIG_ENDIAN)
                val id = String(ByteArray(4).also { h.get(it) }, Charsets.US_ASCII); val size = h.getLong()
                when (id) {
                    "PROP" -> {
                        var p = pos + 16; val e = pos + 12 + size
                        while (p < e) {
                            val sh = bytes(p, 12).order(ByteOrder.BIG_ENDIAN)
                            val sid = String(ByteArray(4).also { sh.get(it) }, Charsets.US_ASCII); val ss = sh.getLong()
                            when (sid) {
                                "FS  " -> rate = bytes(p + 12, 4).order(ByteOrder.BIG_ENDIAN).getInt()
                                "CHNL" -> ch = bytes(p + 12, 2).order(ByteOrder.BIG_ENDIAN).getShort().toInt()
                                "CMPR" -> require(String(bytes(p + 12, 4).array(), Charsets.US_ASCII) == "DSD ") { "compressed (DST) DFF not supported" }
                            }
                            p += 12 + ss + (ss and 1)
                        }
                    }
                    "DSD " -> { dataStart = pos + 12; dataLen = size }
                }
                pos += 12 + size + (size and 1)
            }
            Info(ch, rate, dataLen / ch, dataStart, false, 0)
        } else error("not a DSD file")
        return Reader(fc, st, info)
    }

    // ── filter: windowed-sinc low-pass at ~24 kHz, applied 8 bits at a time through lookup tables ──
    private val tables = HashMap<String, Array<FloatArray>>()
    @Synchronized fun table(rate: Int, lsbFirst: Boolean): Array<FloatArray> = tables.getOrPut("$rate$lsbFirst") {
        val taps = 512 * (rate / 2_822_400).coerceAtLeast(1)
        val fc = 24_000.0 / rate
        val h = DoubleArray(taps) { n ->
            val m = n - (taps - 1) / 2.0
            val sinc = if (m == 0.0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            val w = 0.35875 - 0.48829 * cos(2 * PI * n / (taps - 1)) + 0.14128 * cos(4 * PI * n / (taps - 1)) - 0.01168 * cos(6 * PI * n / (taps - 1))
            sinc * w
        }
        val sum = h.sum()
        Array(taps / 8) { j ->
            FloatArray(256) { v ->
                var acc = 0.0
                for (k in 0 until 8) {
                    val bit = if (lsbFirst) (v shr k) and 1 else (v shr (7 - k)) and 1
                    acc += (if (bit == 1) 1.0 else -1.0) * h[j * 8 + 7 - k] / sum
                }
                acc.toFloat()
            }
        }
    }
}

/** Serves a DSD file as a 24-bit PCM WAV stream, decoded on the fly (seekable). */
class DsdDataSource(private val ctx: Context) : BaseDataSource(false) {
    private var reader: Dsd.Reader? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var total = 0L
    private var header = ByteArray(0)
    private var buf = ByteArray(0); private var bufStart = 0L; private var bufLen = 0
    private lateinit var table: Array<FloatArray>
    private val gain = 1.6f

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        uri = spec.uri
        val r = Dsd.open(ctx, Dsd.real(spec.uri)); reader = r
        val i = r.info
        table = Dsd.table(i.rate, i.dsf)
        val data = i.frames * i.channels * 3
        total = 44 + data
        header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + data).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(i.channels.toShort()); putInt(i.outRate)
            putInt(i.outRate * i.channels * 3); putShort((i.channels * 3).toShort()); putShort(24)
            put("data".toByteArray()); putInt(data.toInt())
        }.array()
        pos = spec.position; bufLen = 0
        transferStarted(spec)
        return if (spec.length != C.LENGTH_UNSET.toLong()) spec.length else total - pos
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pos >= total) return C.RESULT_END_OF_INPUT
        var n = 0
        while (n < length && pos < total) {
            if (pos < 44) { target[offset + n++] = header[pos.toInt()]; pos++; continue }
            if (pos < bufStart || pos >= bufStart + bufLen) fill(pos)
            val k = minOf((bufStart + bufLen - pos).toInt(), length - n)
            System.arraycopy(buf, (pos - bufStart).toInt(), target, offset + n, k)
            n += k; pos += k
        }
        bytesTransferred(n)
        return n
    }

    /** decode a chunk of PCM frames covering byte position [p] */
    private fun fill(p: Long) {
        val r = reader!!; val i = r.info
        val frameBytes = i.channels * 3
        val f0 = (p - 44) / frameBytes
        val nFrames = minOf(4096L, i.frames - f0).toInt()
        val L = table.size
        val span = nFrames * i.bytesStep + L
        val src = ByteArray(span)
        if (buf.size < nFrames * frameBytes) buf = ByteArray(nFrames * frameBytes)
        for (ch in 0 until i.channels) {
            // bytes needed: from (f0+1)*step - L up to (f0+nFrames)*step
            val start = (f0 + 1) * i.bytesStep - L
            r.channel(ch, start, span, src)
            for (f in 0 until nFrames) {
                val last = f * i.bytesStep + L - 1
                var acc = 0f
                var j = 0
                var idx = last
                while (j < L) { acc += table[j][src[idx].toInt() and 0xff]; j++; idx-- }
                var v = (acc * gain * 8_388_607f).toInt()
                if (v > 8_388_607) v = 8_388_607 else if (v < -8_388_608) v = -8_388_608
                val o = f * frameBytes + ch * 3
                buf[o] = v.toByte(); buf[o + 1] = (v shr 8).toByte(); buf[o + 2] = (v shr 16).toByte()
            }
        }
        bufStart = 44 + f0 * frameBytes; bufLen = nFrames * frameBytes
    }

    override fun getUri(): Uri? = uri
    override fun close() {
        if (reader != null) { reader?.close(); reader = null; transferEnded() }
    }
}

/** Plays normal files through ExoPlayer's default source and DSD files through [DsdDataSource]. */
class GroveDataSource(private val ctx: Context) : DataSource {
    private val normal = DefaultDataSource.Factory(ctx).createDataSource()
    private var cur: DataSource? = null
    private val listeners = ArrayList<androidx.media3.datasource.TransferListener>()
    override fun addTransferListener(l: androidx.media3.datasource.TransferListener) { listeners += l; normal.addTransferListener(l) }
    override fun open(spec: DataSpec): Long {
        val d = if (spec.uri.scheme == Dsd.SCHEME) DsdDataSource(ctx).also { s -> listeners.forEach(s::addTransferListener) } else normal
        cur = d
        return d.open(spec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = cur!!.read(buffer, offset, length)
    override fun getUri(): Uri? = cur?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = cur?.responseHeaders ?: emptyMap()
    override fun close() { cur?.close(); cur = null }

    class Factory(private val ctx: Context) : DataSource.Factory { override fun createDataSource() = GroveDataSource(ctx) }
}
