package com.grove.music

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val W = Color.White
private val D = Color.White.copy(alpha = 0.55f)

/** Where the comics tab is: a source list, one source's catalogue, or one comic's page. */
private sealed interface Nav {
    data object Sources : Nav
    data class Catalogue(val src: OSource) : Nav
    data class Detail(val m: OManga) : Nav
}

/** comics → browse: installed sources → catalogue (popular / latest / search) → comic page → reader */
@Composable
fun OnlineBrowse(accent: Color, modifier: Modifier = Modifier, start: OManga? = null, onDeep: (Boolean) -> Unit = {}) {
    val stack = remember { mutableStateListOf<Nav>(Nav.Sources).apply { if (start != null) add(Nav.Detail(start)) } }
    LaunchedEffect(stack.size) { onDeep(stack.size > 1) }
    DisposableEffect(Unit) { onDispose { onDeep(false) } }
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
    Box(modifier.fillMaxSize()) {
        when (val top = stack.last()) {
            Nav.Sources -> SourceList(accent) { stack.add(Nav.Catalogue(it)) }
            is Nav.Catalogue -> Catalogue(top.src, accent, back = { stack.removeAt(stack.lastIndex) }) { stack.add(Nav.Detail(it)) }
            is Nav.Detail -> ComicDetail(top.m, accent) { stack.removeAt(stack.lastIndex) }
        }
    }
}

@Composable
private fun SourceList(accent: Color, open: (OSource) -> Unit) {
    var list by remember { mutableStateOf<List<OSource>?>(null) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { runCatching { OnlineBridge.sources?.invoke() }.getOrNull() ?: emptyList() } }
    val l = list
    when {
        l == null -> Note("loading sources…")
        l.isEmpty() -> Note("no sources installed")
        else -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            l.groupBy { it.lang }.toSortedMap().forEach { (lang, srcs) ->
                item(key = "h_$lang") { Text(lang.ifBlank { "all" }, color = accent, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(srcs, key = { it.id }) { s ->
                    Text(s.name, color = W, fontSize = 20.sp, fontWeight = FontWeight.Light,
                        modifier = Modifier.fillMaxWidth().clickable { open(s) }.padding(vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun Catalogue(src: OSource, accent: Color, back: () -> Unit, open: (OManga) -> Unit) {
    var mode by remember { mutableStateOf("popular") }
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    val items = remember(mode, searched) { mutableStateListOf<OManga>() }
    var page by remember(mode, searched) { mutableIntStateOf(1) }
    var more by remember(mode, searched) { mutableStateOf(true) }
    var busy by remember(mode, searched) { mutableStateOf(false) }
    var err by remember(mode, searched) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (busy || !more) return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when (mode) {
                        "latest" -> OnlineBridge.latest?.invoke(src.id, page)
                        "search" -> OnlineBridge.search?.invoke(src.id, searched, page)
                        else -> OnlineBridge.popular?.invoke(src.id, page)
                    }
                }
            }
            r.onSuccess { res ->
                if (res != null) {
                    items += res.first.filterNot { Adult.isAdult(it.genre) || items.any { o -> o.url == it.url } }
                    more = res.second; page++
                } else more = false
            }.onFailure { err = it.message ?: "couldn't load"; more = false }
            busy = false
        }
    }
    LaunchedEffect(mode, searched) { load() }
    Column(Modifier.fillMaxSize()) {
        Text(src.name, color = W, fontSize = 26.sp, fontWeight = FontWeight.Light, maxLines = 1,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp).clickable { back() })
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("popular", "latest").forEach { m ->
                Text(m, color = if (mode == m) W else D, fontSize = 15.sp, modifier = Modifier.clickable { mode = m })
            }
        }
        BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(accent),
            textStyle = TextStyle(color = W, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) { searched = query.trim(); mode = "search" } }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).border(1.dp, D).padding(10.dp),
            decorationBox = { inner -> if (query.isEmpty()) Text("search ${src.name}", color = D, fontSize = 16.sp); inner() })
        if (items.isEmpty() && !busy) { Note(err ?: "nothing here"); return@Column }
        LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.url }) { m -> Cover(m.title, m.cover) { open(m) } }
            item(span = { GridItemSpan(3) }) {
                LaunchedEffect(items.size) { load() }
                if (busy) Note("loading…") else if (err != null) Note(err!!)
            }
        }
    }
}

@Composable
private fun ComicDetail(start: OManga, accent: Color, back: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ShelfDb(ctx) }
    var m by remember { mutableStateOf(start) }
    var chapters by remember { mutableStateOf<List<OChapter>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var fav by remember { mutableStateOf(false) }
    var read by remember { mutableStateOf(mapOf<String, Pair<Int, Int>>()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(start.url) {
        fav = withContext(Dispatchers.IO) { db.isFav(start.source, start.url) }
        withContext(Dispatchers.IO) { runCatching { OnlineBridge.details?.invoke(start) } }
            .onSuccess { r -> if (r != null) { m = r.first.copy(cover = r.first.cover ?: start.cover); chapters = r.second } }
            .onFailure { err = it.message ?: "couldn't load"; chapters = emptyList() }
        chapters?.let { c -> if (fav) withContext(Dispatchers.IO) { db.setChapters(start.source, start.url, c.size); db.clearNew(start.source, start.url) } }
    }
    // reading progress refreshes when you come back from the reader
    LaunchedEffect(start.url, tick) { read = withContext(Dispatchers.IO) { db.readChapters(start.source, start.url) } }
    ResumeTick { tick++ }
    if (Adult.isAdult(m.genre)) { Note("18+ hidden"); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(20.dp)) {
                Box(Modifier.width(110.dp).aspectRatio(0.7f).background(Color(0xFF1A1A1A))) {
                    AsyncImage(m.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(m.title, color = W, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { back() })
                    m.author?.let { Text(it, color = D, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                    Spacer(Modifier.height(10.dp))
                    Text(if (fav) "in library" else "+ add to library", color = W, fontSize = 15.sp,
                        modifier = Modifier.background(if (fav) Color.Transparent else accent).border(1.dp, W)
                            .clickable {
                                val c = chapters?.size ?: 0
                                if (fav) db.removeFav(m.source, m.url) else db.addFav(m, c)
                                fav = !fav
                            }.padding(horizontal = 14.dp, vertical = 8.dp))
                    val next = chapters?.lastOrNull { it.url !in read } ?: chapters?.firstOrNull()
                    val resume = chapters?.firstOrNull { read[it.url]?.let { p -> p.second == 0 || p.first < p.second - 1 } == true }
                    (resume ?: next)?.let { c ->
                        Text(if (resume != null) "continue ${c.name}" else "start ${c.name}", color = accent, fontSize = 14.sp, maxLines = 1,
                            modifier = Modifier.padding(top = 10.dp).clickable { OnlineReaderActivity.open(ctx, m, c, chapters!!) })
                    }
                }
            }
            m.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = W.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            m.genre?.takeIf { it.isNotBlank() }?.let { Text(it, color = D, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
            Text(chapters?.let { "${it.size} chapters" } ?: (err ?: "loading chapters…"), color = accent, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
        items(chapters ?: emptyList(), key = { it.url }) { c ->
            val r = read[c.url]
            val done = r != null && r.second > 0 && r.first >= r.second - 1
            Column(Modifier.fillMaxWidth().clickable { OnlineReaderActivity.open(ctx, m, c, chapters!!) }.padding(horizontal = 20.dp, vertical = 9.dp)) {
                Text(c.name, color = if (done) D else W, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    c.date.takeIf { it > 0 }?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)) },
                    c.scanlator?.takeIf { it.isNotBlank() },
                    r?.takeIf { !done && it.second > 0 }?.let { "page ${it.first + 1}/${it.second}" },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = D, fontSize = 12.sp)
            }
        }
    }
}

/** comics → library: online comics you added, then your own files */
@Composable
fun OnlineLibrary(accent: Color, open: (OManga) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ShelfDb(ctx) }
    var favs by remember { mutableStateOf<List<OnlineFav>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { OnlineUpdates.importLegacy(ctx) }
        favs = withContext(Dispatchers.IO) { db.onlineAll() }
    }
    ResumeTick { tick++ }
    if (favs.isEmpty()) { Note("no online comics yet — find them in browse"); return }
    LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(favs, key = { "${it.source}|${it.url}" }) { f ->
            Cover(f.title, f.cover, badge = f.newCount.takeIf { it > 0 }?.toString(), accent = accent) {
                open(OManga(f.source, f.url, f.title, f.cover))
            }
        }
    }
}

/** comics → updates: library comics with new chapters, and a button to check now */
@Composable
fun OnlineUpdatesScreen(accent: Color, open: (OManga) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ShelfDb(ctx) }
    var list by remember { mutableStateOf<List<OnlineFav>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tick) { list = withContext(Dispatchers.IO) { db.onlineAll().filter { it.updatedAt > 0 }.sortedByDescending { it.updatedAt } } }
    ResumeTick { tick++ }
    Column(Modifier.fillMaxSize()) {
        Text(checking ?: "check for new chapters", color = accent, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clickable(enabled = checking == null) {
                scope.launch {
                    checking = "checking…"
                    val n = withContext(Dispatchers.IO) { OnlineUpdates.check(ctx) { i, t -> checking = "checking $i of $t…" } }
                    checking = null; tick++
                    android.widget.Toast.makeText(ctx, if (n > 0) "$n new chapters" else "no new chapters", android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        if (list.isEmpty()) { Note("no updates yet"); return@Column }
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp)) {
            items(list, key = { "${it.source}|${it.url}" }) { f ->
                Row(Modifier.fillMaxWidth().clickable { open(OManga(f.source, f.url, f.title, f.cover)) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp, 66.dp).background(Color(0xFF1A1A1A))) {
                        AsyncImage(f.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(f.title, color = W, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(buildString {
                            if (f.newCount > 0) append("${f.newCount} new · ")
                            append(android.text.format.DateUtils.getRelativeTimeSpanString(f.updatedAt))
                        }, color = if (f.newCount > 0) accent else D, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** comics → history: last chapter read of each online comic */
@Composable
fun OnlineHistoryScreen(accent: Color, open: (OManga) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<OnlineRead>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { list = withContext(Dispatchers.IO) { ShelfDb(ctx).onlineHistory() } }
    ResumeTick { tick++ }
    if (list.isEmpty()) { Note("nothing read yet"); return }
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp)) {
        items(list, key = { "${it.source}|${it.chapterUrl}" }) { r ->
            Row(Modifier.fillMaxWidth().clickable { open(OManga(r.source, r.mangaUrl, r.title, r.cover)) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp, 66.dp).background(Color(0xFF1A1A1A))) {
                    AsyncImage(r.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(r.title.ifBlank { r.chapter }, color = W, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.chapter}${if (r.pages > 0) " · page ${r.page + 1}/${r.pages}" else ""} · " +
                        android.text.format.DateUtils.getRelativeTimeSpanString(r.readAt), color = D, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Cover(title: String, cover: String?, badge: String? = null, accent: Color = Color.Transparent, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).background(Color(0xFF1A1A1A))) {
            AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (badge != null) Text(badge, color = W, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).background(accent).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Text(title, color = W, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Note(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { Text(text, color = D, fontSize = 14.sp) }
}

/** Calls [onResume] every time the screen comes back to the front (e.g. after closing the reader). */
@Composable
private fun ResumeTick(onResume: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) onResume() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
}

/** Reads one online chapter page by page; swipe past the last page to go to the next chapter. */
class OnlineReaderActivity : ComponentActivity() {
    companion object {
        private var pending: Pair<OManga, List<OChapter>>? = null
        fun open(ctx: Context, m: OManga, c: OChapter, chapters: List<OChapter>) {
            pending = m to chapters
            ctx.startActivity(Intent(ctx, OnlineReaderActivity::class.java).putExtra("chapter", c.url))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (m, chapters) = pending ?: run { finish(); return }
        val first = intent.getStringExtra("chapter")
        setContent {
            var chapterUrl by remember { mutableStateOf(first ?: chapters.last().url) }
            val ch = chapters.firstOrNull { it.url == chapterUrl } ?: chapters.last()
            val db = remember { ShelfDb(this) }
            var count by remember(chapterUrl) { mutableStateOf<Int?>(null) }
            var err by remember(chapterUrl) { mutableStateOf<String?>(null) }
            val startPage = remember(chapterUrl) { db.readChapters(m.source, m.url)[chapterUrl]?.let { (p, t) -> if (t > 0 && p >= t - 1) 0 else p } ?: 0 }
            LaunchedEffect(chapterUrl) {
                withContext(Dispatchers.IO) { runCatching { OnlineBridge.pageCount?.invoke(m.source, chapterUrl) } }
                    .onSuccess { count = it ?: 0 }.onFailure { err = it.message ?: "couldn't load"; count = 0 }
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val n = count
                if (n == null) { Note("loading ${ch.name}…"); return@Box }
                if (n == 0) { Note(err ?: "no pages"); return@Box }
                // chapters are newest first, so the next chapter is the one before this in the list
                val nextIdx = chapters.indexOfFirst { it.url == chapterUrl } - 1
                val state = rememberPagerState(startPage.coerceIn(0, n - 1)) { n + if (nextIdx >= 0) 1 else 0 }
                val started = remember(chapterUrl) { System.currentTimeMillis() }
                LaunchedEffect(state.currentPage) {
                    if (state.currentPage < n) withContext(Dispatchers.IO) { db.saveRead(m.source, m.url, chapterUrl, ch.name, state.currentPage, n, 0) }
                    else chapterUrl = chapters[nextIdx].url
                }
                DisposableEffect(chapterUrl) {
                    onDispose { val p = state.currentPage.coerceAtMost(n - 1); val t = System.currentTimeMillis() - started
                        Thread { db.saveRead(m.source, m.url, ch.url, ch.name, p, n, t) }.start() }
                }
                HorizontalPager(state, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
                    if (i >= n) { Note("next chapter…"); return@HorizontalPager }
                    var file by remember(chapterUrl, i) { mutableStateOf<File?>(null) }
                    var failed by remember(chapterUrl, i) { mutableStateOf(false) }
                    LaunchedEffect(chapterUrl, i) {
                        file = withContext(Dispatchers.IO) { runCatching { OnlineBridge.page?.invoke(this@OnlineReaderActivity, m.source, chapterUrl, i) }.getOrNull() }
                        failed = file == null
                    }
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        file?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                            ?: Note(if (failed) "couldn't load page ${i + 1}" else "${i + 1}")
                    }
                }
                Text("${ch.name} · ${(state.currentPage + 1).coerceAtMost(n)}/$n", color = D, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
            }
        }
    }
}
