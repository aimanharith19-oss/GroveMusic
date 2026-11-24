package com.grove.music

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** One page of a comic: a plain file/content path Coil can load directly. */
sealed interface ComicPage {
    data class Direct(val uri: Uri) : ComicPage
    /** An entry inside a .cbz/.zip, extracted to cache on demand. */
    data class Zip(val zipPath: String, val entry: String) : ComicPage
}

object ShelfComics {
    private val imgExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    /** Lists pages for a shelf item: a folder (SAF tree), a loose image folder path, or a .cbz/.zip file. */
    fun pages(ctx: Context, path: String): List<ComicPage> {
        val uri = Uri.parse(path)
        if (path.endsWith(".cbz", true) || path.endsWith(".zip", true)) {
            return runCatching {
                ZipFile(File(uri.path ?: path)).use { z ->
                    z.entries().asSequence()
                        .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in imgExt }
                        .map { it.name }.sorted().toList()
                }
            }.getOrNull()?.map { ComicPage.Zip(uri.path ?: path, it) } ?: emptyList()
        }
        // SAF tree (folder of images) or plain file path
        val doc = DocumentFile.fromTreeUri(ctx, uri) ?: DocumentFile.fromFile(File(uri.path ?: path))
        return doc?.listFiles()
            ?.filter { it.name?.substringAfterLast('.', "")?.lowercase() in imgExt }
            ?.sortedBy { it.name }
            ?.map { ComicPage.Direct(it.uri) }
            ?: emptyList()
    }

    fun extractToCache(ctx: Context, page: ComicPage.Zip): File {
        val out = File(ctx.cacheDir, "comic_page_" + page.entry.hashCode() + "_" + page.entry.substringAfterLast('/'))
        if (!out.exists()) runCatching {
            ZipFile(File(page.zipPath)).use { z ->
                val e = z.getEntry(page.entry) ?: return@runCatching
                z.getInputStream(e).use { ins -> out.outputStream().use { ins.copyTo(it) } }
            }
        }
        return out
    }
}

/** Simple page-by-page comic reader: swipe to turn pages, tap edges to go back/forward, remembers progress. */
class ComicReaderActivity : ComponentActivity() {
    companion object {
        fun open(ctx: Context, item: ShelfItem) {
            ctx.startActivity(Intent(ctx, ComicReaderActivity::class.java).putExtra("id", item.id).putExtra("path", item.path).putExtra("page", item.page))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("id", -1)
        val path = intent.getStringExtra("path") ?: ""
        val startPage = intent.getIntExtra("page", 0)
        setContent {
            val db = remember { ShelfDb(this) }
            var pages by remember { mutableStateOf<List<ComicPage>>(emptyList()) }
            LaunchedEffect(path) { pages = withContext(Dispatchers.IO) { ShelfComics.pages(this@ComicReaderActivity, path) } }
            if (pages.isEmpty()) {
                Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) { Text("no pages found", color = Color.White) }
                return@setContent
            }
            val state = rememberPagerState(initialPage = startPage.coerceIn(0, pages.size - 1)) { pages.size }
            val started = remember { System.currentTimeMillis() }
            DisposableEffectSave(db, id, state, pages.size, started)
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                HorizontalPager(state = state, modifier = Modifier.fillMaxSize()) { i ->
                    val p = pages[i]
                    val model = when (p) {
                        is ComicPage.Direct -> p.uri
                        is ComicPage.Zip -> ShelfComics.extractToCache(this@ComicReaderActivity, p)
                    }
                    AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.currentPage + 1} / ${pages.size}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DisposableEffectSave(db: ShelfDb, id: Long, state: androidx.compose.foundation.pager.PagerState, total: Int, started: Long) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(state.currentPage) {
        if (id >= 0) withContext(Dispatchers.IO) { db.progress(id, state.currentPage, total, 0) }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (id >= 0) scope.launch(Dispatchers.IO) { db.progress(id, state.currentPage, total, System.currentTimeMillis() - started) }
        }
    }
}

/** Opens a book file in the system's own viewer (PDF/EPUB), since GroveMedia reads progress but doesn't render formats itself yet. */
object ShelfBooks {
    fun open(ctx: Context, item: ShelfItem) {
        val uri = Uri.parse(item.path)
        val mime = ctx.contentResolver.getType(uri) ?: when (item.path.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            else -> "*/*"
        }
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
