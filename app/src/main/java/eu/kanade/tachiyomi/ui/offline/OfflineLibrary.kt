package eu.kanade.tachiyomi.ui.offline

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.archive.archiveReader
import java.io.File
import java.security.MessageDigest

/** A book file found in one of the user's chosen folders. */
data class OfflineBook(
    val uri: Uri,
    val title: String,
    val extension: String,
)

/** One level of the offline rack: a folder (or sub-folder) and the books directly inside it. */
data class OfflineShelf(
    val path: String,
    val books: List<OfflineBook>,
)

/**
 * Grovebook offline library: books read straight from folders the user picks on the device.
 */
object OfflineLibrary {

    private const val PREFS = "grove_offline"
    private const val KEY_FOLDERS = "folders"
    private const val MAX_DEPTH = 6

    val BOOK_TYPES = setOf(
        "pdf", "epub", "cbz", "zip", "cbr", "rar", "cb7", "7z", "cbt", "tar",
        "txt", "md", "text", "html", "htm", "xhtml", "fb2",
    )
    private val ARCHIVE_TYPES = setOf("epub", "cbz", "zip", "cbr", "rar", "cb7", "7z", "cbt", "tar")
    private val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "heif", "jxl")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folders(context: Context): List<Uri> =
        prefs(context).getStringSet(KEY_FOLDERS, emptySet()).orEmpty().sorted().map(Uri::parse)

    fun addFolder(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val set = prefs(context).getStringSet(KEY_FOLDERS, emptySet()).orEmpty().toMutableSet()
        set += uri.toString()
        prefs(context).edit().putStringSet(KEY_FOLDERS, set).apply()
    }

    fun removeFolder(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val set = prefs(context).getStringSet(KEY_FOLDERS, emptySet()).orEmpty().toMutableSet()
        set -= uri.toString()
        prefs(context).edit().putStringSet(KEY_FOLDERS, set).apply()
    }

    // Grovebook: the user's own folders in the Books tab (a book lives in at most one)

    fun collections(context: Context): List<String> =
        prefs(context).getStringSet("collections", emptySet()).orEmpty().sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun createCollection(context: Context, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val set = prefs(context).getStringSet("collections", emptySet()).orEmpty().toMutableSet()
        set += clean
        prefs(context).edit().putStringSet("collections", set).apply()
    }

    fun deleteCollection(context: Context, name: String) {
        val set = prefs(context).getStringSet("collections", emptySet()).orEmpty().toMutableSet()
        set -= name
        prefs(context).edit().putStringSet("collections", set).remove("col:$name").apply()
    }

    fun booksIn(context: Context, name: String): Set<String> =
        prefs(context).getStringSet("col:$name", emptySet()).orEmpty()

    /** Puts [uri] into [name], or takes it out of every folder when [name] is null. */
    fun assign(context: Context, uri: Uri, name: String?) {
        val p = prefs(context)
        val edit = p.edit()
        collections(context).forEach { c ->
            val set = p.getStringSet("col:$c", emptySet()).orEmpty().toMutableSet()
            if (set.remove(uri.toString())) edit.putStringSet("col:$c", set)
        }
        if (name != null) {
            val set = p.getStringSet("col:$name", emptySet()).orEmpty().toMutableSet()
            set += uri.toString()
            edit.putStringSet("col:$name", set)
        }
        edit.apply()
    }

    fun lastPage(context: Context, uri: Uri): Int = prefs(context).getInt("page:$uri", 0)

    fun saveLastPage(context: Context, uri: Uri, page: Int) {
        prefs(context).edit().putInt("page:$uri", page).apply()
    }

    /** Adds reading time for a book (and to today's total). */
    fun addReadTime(context: Context, uri: Uri, ms: Long) {
        if (ms <= 0) return
        val p = prefs(context)
        val day = java.time.LocalDate.now().toString()
        p.edit()
            .putLong("time:$uri", p.getLong("time:$uri", 0) + ms)
            .putLong("day:$day", p.getLong("day:$day", 0) + ms)
            .apply()
    }

    fun readTime(context: Context, uri: Uri): Long = prefs(context).getLong("time:$uri", 0)

    /** Reading time on the given day (yyyy-mm-dd). */
    fun dayTime(context: Context, day: java.time.LocalDate): Long = prefs(context).getLong("day:$day", 0)

    fun savePageCount(context: Context, uri: Uri, count: Int) {
        prefs(context).edit().putInt("pages:$uri", count).apply()
    }

    /** Reading progress 0–100, or null if the book was never opened. */
    fun progress(context: Context, uri: Uri): Int? {
        val total = prefs(context).getInt("pages:$uri", 0)
        if (total <= 0) return null
        val page = prefs(context).getInt("page:$uri", 0)
        return ((page + 1) * 100 / total).coerceIn(0, 100)
    }

    /** Walks every chosen folder; each folder that holds books becomes one rack level. */
    suspend fun scan(context: Context): List<OfflineShelf> = withContext(Dispatchers.IO) {
        val shelves = mutableListOf<OfflineShelf>()
        folders(context).forEach { root ->
            val dir = UniFile.fromUri(context, root) ?: return@forEach
            walk(dir, dir.name ?: "Books", 0, shelves)
        }
        shelves
    }

    private fun walk(dir: UniFile, path: String, depth: Int, out: MutableList<OfflineShelf>) {
        val children = dir.listFiles().orEmpty().filterNot { it.name.orEmpty().startsWith('.') }
        val books = children
            .filter { !it.isDirectory }
            .mapNotNull { f ->
                val name = f.name ?: return@mapNotNull null
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in BOOK_TYPES) return@mapNotNull null
                OfflineBook(uri = f.uri, title = name.substringBeforeLast('.'), extension = ext)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        if (books.isNotEmpty()) out += OfflineShelf(path, books)
        if (depth < MAX_DEPTH) {
            children.filter { it.isDirectory }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                .forEach { walk(it, "$path / ${it.name.orEmpty()}", depth + 1, out) }
        }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Cover image cached on disk, or null when the book has no picture to use. */
    suspend fun cover(context: Context, book: OfflineBook): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "grove_covers").apply { mkdirs() }
        val file = File(dir, hash(book.uri.toString()) + ".jpg")
        val none = File(dir, hash(book.uri.toString()) + ".none")
        if (file.exists()) return@withContext file
        if (none.exists()) return@withContext null
        val bitmap = runCatching {
            when (book.extension) {
                "pdf" -> renderPdfCover(context, book.uri)
                in ARCHIVE_TYPES -> archiveCover(context, book.uri)
                else -> null
            }
        }.getOrNull()
        if (bitmap == null) {
            none.createNewFile()
            return@withContext null
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        file
    }

    /**
     * The book's real title: the title inside an EPUB when it has one, otherwise the
     * file name cleaned of tags like [group], (2020), _ and dots.
     */
    suspend fun title(context: Context, book: OfflineBook): String = withContext(Dispatchers.IO) {
        val key = "title:${book.uri}"
        prefs(context).getString(key, null)?.let { return@withContext it }
        val fromEpub = if (book.extension == "epub") {
            runCatching {
                val file = UniFile.fromUri(context, book.uri) ?: return@runCatching null
                mihon.core.archive.EpubReader(file.archiveReader(context, null)).use { epub ->
                    epub.getPackageDocument(epub.getPackageHref())
                        .getElementsByTag("dc:title").firstOrNull()?.text()?.trim()
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val title = fromEpub ?: cleanName(book.title)
        prefs(context).edit().putString(key, title).apply()
        title
    }

    fun cleanName(name: String): String {
        val cleaned = name
            .replace(Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}"""), " ")
            .replace('_', ' ')
            .replace(Regex("""(?<=\w)\.(?=\w)"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–')
        return cleaned.ifBlank { name }
    }

    private fun renderPdfCover(context: Context, uri: Uri): Bitmap? {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return fd.use {
            PdfRenderer(it).use { pdf ->
                if (pdf.pageCount == 0) return null
                pdf.openPage(0).use { page ->
                    val w = 360
                    val h = (page.height * (w.toFloat() / page.width.coerceAtLeast(1))).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun archiveCover(context: Context, uri: Uri): Bitmap? {
        val file = UniFile.fromUri(context, uri) ?: return null
        return file.archiveReader(context, null).use { reader ->
            val entry = reader.useEntries { entries ->
                entries
                    .filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in IMAGE_TYPES }
                    .map { it.name }
                    .toList()
                    .sortedWith(
                        compareBy<String> { !it.contains("cover", ignoreCase = true) }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it },
                    )
                    .firstOrNull()
            } ?: return null
            val bytes = reader.getInputStream(entry)?.use { it.readBytes() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 360) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
