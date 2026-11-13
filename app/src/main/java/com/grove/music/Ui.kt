package com.grove.music

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val White = Color.White
private val Dim = Color(0xFF8A8A8A)

/** Windows Phone 8 accent colours. */
val Accents = listOf(
    0xFFA4C400, 0xFF60A917, 0xFF008A00, 0xFF00ABA9, 0xFF1BA1E2, 0xFF0050EF, 0xFF6A00FF,
    0xFFAA00FF, 0xFFF472D0, 0xFFD80073, 0xFFA20025, 0xFFE51400, 0xFFFA6800, 0xFFF0A30A,
    0xFFE3C800, 0xFF825A2C, 0xFF6D8764, 0xFF647687, 0xFF76608A, 0xFF87794E,
).map { it.toInt() }

private val WpEase = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)

/** Windows Phone "tilt" press effect: the item tips toward the finger. */
fun Modifier.tilt(onClick: () -> Unit): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val p by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 90 else 260, easing = WpEase), label = "tilt")
    val click by rememberUpdatedState(onClick)
    this.onSizeChanged { sz = it }
        .graphicsLayer {
            val nx = if (sz.width > 0) (touch.x / sz.width - 0.5f) * 2f else 0f
            val ny = if (sz.height > 0) (touch.y / sz.height - 0.5f) * 2f else 0f
            val maxDeg = if (sz.width > 600) 4f else 10f
            rotationY = -nx * maxDeg * p
            rotationX = ny * maxDeg * p
            val sc = 1f - 0.035f * p; scaleX = sc; scaleY = sc
            cameraDistance = 14f * density
        }
        .pointerInput(Unit) {
            detectTapGestures(onPress = { o -> touch = o; pressed = true; tryAwaitRelease(); pressed = false }, onTap = { click() })
        }
}

/** Windows Phone "feather" entrance: items slide in from the right one after another. */
@Composable
fun Modifier.feather(i: Int, enabled: Boolean): Modifier {
    if (!enabled || i > 14) return this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(i * 35L); a.animateTo(1f, tween(420, easing = WpEase)) }
    return this.graphicsLayer { alpha = a.value; translationX = (1f - a.value) * 90.dp.toPx() }
}

@Composable
fun rememberIntro(): Boolean {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(900); on = false }
    return on
}

@Composable
fun TintBackground(accent: Color, strength: Float = 0.38f) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to accent.copy(alpha = strength), 0.45f to accent.copy(alpha = strength * 0.25f), 1f to Color.Black)))
}

sealed interface Screen {
    data object Home : Screen
    data object Playing : Screen
    data class Detail(val title: String, val sub: String, val songs: List<Song>) : Screen
}

@Composable
fun GroveApp(vm: MusicViewModel, conn: PlayerConnection, prefs: Prefs) {
    var accentInt by remember { mutableIntStateOf(prefs.accent) }
    val accent by animateColorAsState(Color(accentInt), tween(500), label = "accent")
    val perms = remember {
        buildList {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.load() }
    LaunchedEffect(Unit) { permLauncher.launch(perms) }

    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    var forward by remember { mutableStateOf(true) }
    val open: (Screen) -> Unit = { forward = true; stack = stack + it }
    BackHandler(stack.size > 1) { forward = false; stack = stack.dropLast(1) }

    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = Color.Black, surface = Color.Black)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AnimatedContent(
                targetState = stack.last(),
                transitionSpec = { fadeIn(tween(160, delayMillis = 140)) togetherWith fadeOut(tween(140)) },
                label = "nav"
            ) { s ->
                // Windows Phone turnstile: pages swing on their left edge like a door
                val dir = if (forward) 1f else -1f
                val rot by transition.animateFloat(
                    transitionSpec = { tween(if (targetState == EnterExitState.Visible) 420 else 160, easing = WpEase) },
                    label = "turnstile"
                ) { when (it) { EnterExitState.PreEnter -> -70f * dir; EnterExitState.Visible -> 0f; EnterExitState.PostExit -> 70f * dir } }
                Box(Modifier.fillMaxSize().graphicsLayer {
                    rotationY = rot; transformOrigin = TransformOrigin(0f, 0.5f); cameraDistance = 18f * density
                }) {
                when (s) {
                    Screen.Home -> HomeScreen(vm, conn, prefs, accent, { accentInt = it; prefs.accent = it }, open) { permLauncher.launch(perms) }
                    Screen.Playing -> NowPlayingScreen(vm, conn, prefs, accent)
                    is Screen.Detail -> DetailScreen(s, conn, accent, open)
                }
                }
            }
        }
    }
}

// ───────────────────────── collection (pivot) ─────────────────────────

@Composable
fun HomeScreen(
    vm: MusicViewModel, conn: PlayerConnection, prefs: Prefs, accent: Color,
    onAccent: (Int) -> Unit, open: (Screen) -> Unit, askPerms: () -> Unit,
) {
    val pages = listOf("songs", "artists", "albums", "genres", "settings")
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val songs = vm.songs.value
    val play: (List<Song>, Int) -> Unit = { l, i -> conn.playSongs(l, i); open(Screen.Playing) }

    val pos = pager.currentPage + pager.currentPageOffsetFraction
    Box(Modifier.fillMaxSize()) {
    TintBackground(accent)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // panorama title: huge, light, lowercase, slides slower than the content (parallax)
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            Text("grove music", color = White, fontSize = 84.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = -pos * 70.dp.toPx() }
                    .padding(start = 12.dp, top = 4.dp))
        }
        PivotHeaders(pages, pos, accent) { scope.launch { pager.animateScrollToPage(it, animationSpec = tween(450, easing = WpEase)) } }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { p ->
            when (pages[p]) {
                "songs" -> SongsPage(songs, accent, vm.loading.value, askPerms, onShuffle = {
                    conn.playSongs(songs, 0, shuffled = true); open(Screen.Playing)
                }) { play(songs, it) }
                "artists" -> GroupPage(songs.groupBy { it.artist }, accent) { name, list ->
                    open(Screen.Detail(name, "${list.size} songs", list.sortedWith(compareBy({ it.album }, { it.track }, { it.title }))))
                }
                "albums" -> AlbumsPage(songs, accent) { list ->
                    open(Screen.Detail(list[0].album, list[0].artist, list.sortedWith(compareBy({ it.track }, { it.title }))))
                }
                "genres" -> GroupPage(songs.groupBy { it.genre }, accent) { name, list ->
                    open(Screen.Detail(name, "${list.size} songs", list))
                }
                else -> SettingsPage(prefs, accent, onAccent) { vm.load() }
            }
        }
        if (conn.current.value != null) MiniPlayer(conn, accent) { open(Screen.Playing) }
    }
    }
}

/** Pivot headers that glide with the finger, exactly in step with the pager. */
@Composable
fun PivotHeaders(pages: List<String>, pos: Float, accent: Color, onSelect: (Int) -> Unit) {
    val widths = remember { mutableStateListOf<Float>().apply { repeat(pages.size) { add(0f) } } }
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
            .graphicsLayer {
                val i = pos.toInt().coerceIn(0, pages.lastIndex)
                val f = pos - i
                translationX = -(widths.take(i).sum() + widths.getOrElse(i) { 0f } * f)
            }
            .padding(start = 18.dp)) {
            pages.forEachIndexed { i, name ->
                val a = (1f - abs(pos - i)).coerceIn(0f, 1f)
                Text(name, fontSize = 46.sp, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false,
                    color = lerp(accent.copy(alpha = 0.55f), White, a),
                    modifier = Modifier.onSizeChanged { widths[i] = it.width.toFloat() }
                        .clickable { onSelect(i) }.padding(end = 22.dp, bottom = 4.dp))
            }
        }
    }
}

private fun letterOf(s: String): Char = s.firstOrNull()?.lowercaseChar()?.takeIf { it.isLetter() } ?: '#'

@Composable
fun LetterTile(c: Char, accent: Color) {
    Box(Modifier.padding(top = 14.dp, bottom = 6.dp).size(50.dp).background(accent), contentAlignment = Alignment.BottomStart) {
        Text(c.toString(), color = White, fontSize = 30.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
fun SongRow(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().tilt(onClick).padding(vertical = 8.dp)) {
        Text(title, color = White, fontSize = 23.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EmptyMsg(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(text, color = Dim, fontSize = 20.sp, fontWeight = FontWeight.Light)
        if (action != null) Text(action, color = White, fontSize = 18.sp,
            modifier = Modifier.padding(top = 12.dp).clickable(onClick = onAction))
    }
}

@Composable
fun SongsPage(songs: List<Song>, accent: Color, loading: Boolean, askPerms: () -> Unit, onShuffle: () -> Unit, onPlay: (Int) -> Unit) {
    if (songs.isEmpty()) {
        EmptyMsg(if (loading) "loading your music…" else "no music found", if (loading) null else "allow access / rescan", askPerms)
        return
    }
    val intro = rememberIntro()
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        item { Text("shuffle all (${songs.size})", color = accent, fontSize = 20.sp,
            modifier = Modifier.feather(0, intro).fillMaxWidth().tilt(onShuffle).padding(vertical = 8.dp)) }
        var prev: Char? = null
        var n = 1
        songs.forEachIndexed { i, s ->
            val c = letterOf(s.title)
            if (c != prev) { prev = c; val k = n++; item(key = "h$i") { Box(Modifier.feather(k, intro)) { LetterTile(c, accent) } } }
            val k = n++
            item(key = s.id) { Box(Modifier.feather(k, intro)) { SongRow(s.title, "${s.artist} · ${s.format}") { onPlay(i) } } }
        }
    }
}

@Composable
fun GroupPage(groups: Map<String, List<Song>>, accent: Color, onOpen: (String, List<Song>) -> Unit) {
    val keys = remember(groups) { groups.keys.sortedBy { it.lowercase() } }
    if (keys.isEmpty()) { EmptyMsg("nothing here yet"); return }
    val intro = rememberIntro()
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        var prev: Char? = null
        var n = 0
        keys.forEachIndexed { i, k ->
            val c = letterOf(k)
            if (c != prev) { prev = c; val j = n++; item(key = "h$i") { Box(Modifier.feather(j, intro)) { LetterTile(c, accent) } } }
            val j = n++
            item(key = "k$k") {
                Text(k.lowercase(), color = White, fontSize = 30.sp, fontWeight = FontWeight.Light, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.feather(j, intro).fillMaxWidth().tilt { onOpen(k, groups.getValue(k)) }.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
fun AlbumsPage(songs: List<Song>, accent: Color, onOpen: (List<Song>) -> Unit) {
    val albums = remember(songs) { songs.groupBy { it.albumId }.values.sortedBy { it[0].album.lowercase() } }
    if (albums.isEmpty()) { EmptyMsg("nothing here yet"); return }
    val intro = rememberIntro()
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        itemsIndexed(albums, key = { _, l -> l[0].albumId }) { i, list ->
            Row(Modifier.feather(i, intro).fillMaxWidth().tilt { onOpen(list) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(list[0].artUri, accent, Modifier.size(76.dp))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(list[0].album.lowercase(), color = White, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(list[0].artist, color = Dim, fontSize = 15.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SettingsPage(prefs: Prefs, accent: Color, onAccent: (Int) -> Unit, rescan: () -> Unit) {
    val ctx = LocalContext.current
    var folder by remember { mutableStateOf(prefs.lyricsFolder) }
    var online by remember { mutableStateOf(prefs.onlineLyrics) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.lyricsFolder = uri.toString(); folder = uri.toString(); LyricsRepository.invalidateFolder()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("accent colour", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        Accents.chunked(5).forEach { row ->
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    Box(Modifier.size(56.dp).background(Color(c))
                        .then(if (Color(c) == accent) Modifier.border(3.dp, White) else Modifier)
                        .tilt { onAccent(c) })
                }
            }
        }
        SettingItem("lyrics folder", folder?.let { DocumentFile.fromTreeUri(ctx, Uri.parse(it))?.name } ?: "not set – tap to choose a folder with .lrc files") { pickFolder.launch(null) }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("online lyrics", color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                Text("download synced lyrics from lrclib.net", color = Dim, fontSize = 14.sp)
            }
            Switch(online, { online = it; prefs.onlineLyrics = it },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = White))
        }
        SettingItem("rescan library", "look for new music on this phone", rescan)
        SettingItem("about", "Grove Music 1.0 · plays MP3, FLAC (incl. 24-bit), OGG, OPUS, M4A, WAV") {}
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SettingItem(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().tilt(onClick).padding(vertical = 10.dp)) {
        Text(title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
        Text(sub, color = Dim, fontSize = 14.sp)
    }
}

@Composable
fun Art(uri: Uri?, accent: Color, modifier: Modifier) {
    Box(modifier.background(accent), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = White.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize(0.4f))
        if (uri != null) AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

@Composable
fun MiniPlayer(conn: PlayerConnection, accent: Color, onOpen: () -> Unit) {
    val md = conn.current.value?.mediaMetadata ?: return
    Row(Modifier.fillMaxWidth().background(Color(0xFF0E0E0E)).background(accent.copy(alpha = 0.30f)).tilt(onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Art(md.artworkUri, accent, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(md.title?.toString().orEmpty(), color = White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(md.artist?.toString().orEmpty(), color = Dim, fontSize = 13.sp, maxLines = 1)
        }
        IconButton({ conn.playPause() }) {
            Icon(if (conn.isPlaying.value) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "play/pause", tint = White)
        }
        IconButton({ conn.next() }) { Icon(Icons.Rounded.SkipNext, "next", tint = White) }
    }
}

@Composable
fun DetailScreen(d: Screen.Detail, conn: PlayerConnection, accent: Color, open: (Screen) -> Unit) {
    val intro = rememberIntro()
    Box(Modifier.fillMaxSize()) {
    TintBackground(accent, 0.30f)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Text(d.sub.uppercase(), color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp))
        Text(d.title.lowercase(), color = White, fontSize = 50.sp, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Clip, modifier = Modifier.padding(start = 16.dp).wrapContentWidth(Alignment.Start, unbounded = true))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            item {
                Text("play all", color = accent, fontSize = 20.sp,
                    modifier = Modifier.feather(0, intro).fillMaxWidth().tilt { conn.playSongs(d.songs, 0); open(Screen.Playing) }.padding(vertical = 10.dp))
            }
            itemsIndexed(d.songs, key = { _, s -> s.id }) { i, s ->
                Box(Modifier.feather(i + 1, intro)) {
                    SongRow(s.title, "${s.album} · ${s.format}") { conn.playSongs(d.songs, i); open(Screen.Playing) }
                }
            }
        }
        if (conn.current.value != null) MiniPlayer(conn, accent) { open(Screen.Playing) }
    }
    }
}

// ───────────────────────── now playing ─────────────────────────

private fun fmt(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun NowPlayingScreen(vm: MusicViewModel, conn: PlayerConnection, prefs: Prefs, accent: Color) {
    val ctx = LocalContext.current
    val item = conn.current.value
    if (item == null) { EmptyMsg("nothing playing"); return }
    val md = item.mediaMetadata
    val song = vm.song(item.mediaId)

    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { pos = conn.position(); dur = conn.duration(); delay(200) } }

    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var lyrics by remember(item.mediaId) { mutableStateOf<Lyrics?>(null) }
    var lyricsLoading by remember(item.mediaId) { mutableStateOf(true) }
    LaunchedEffect(item.mediaId, reload) {
        lyricsLoading = true
        lyrics = LyricsRepository.load(ctx, trackKey(item, song, conn.duration()))
        lyricsLoading = false
    }
    val pickLrc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.setLyricsFor(item.mediaId, uri.toString()); reload++
        }
    }
    val format = song?.format ?: md.extras?.getString("fmt").orEmpty()

    Box(Modifier.fillMaxSize()) {
    AnimatedContent(md.artworkUri, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) }, label = "bg") { u ->
        Box(Modifier.fillMaxSize()) {
            if (u != null) AsyncImage(model = u, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.45f })
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to accent.copy(alpha = 0.45f), 0.5f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.9f))))
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Text((md.artist ?: "unknown artist").toString().lowercase(), color = White, fontSize = 54.sp,
            fontWeight = FontWeight.Light, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            modifier = Modifier.padding(top = 6.dp).wrapContentWidth(Alignment.Start, unbounded = true))
        Text((md.albumTitle ?: "").toString().lowercase(), color = Dim, fontSize = 18.sp, maxLines = 1)
        Spacer(Modifier.height(14.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showLyrics) {
                LyricsView(lyrics, lyricsLoading, pos, accent, conn::seekTo, { pickLrc.launch(arrayOf("*/*")) }, Modifier.fillMaxSize())
            } else {
                var dx by remember { mutableFloatStateOf(0f) }
                val follow by animateFloatAsState(dx, if (dx == 0f) tween(380, easing = WpEase) else tween(0), label = "swipe")
                AnimatedContent(item.mediaId, transitionSpec = {
                    (fadeIn(tween(300)) + slideInHorizontally(tween(420, easing = WpEase)) { it / 3 }) togetherWith fadeOut(tween(150))
                }, label = "art") { _ ->
                    Art(md.artworkUri, accent, Modifier.aspectRatio(1f)
                        .graphicsLayer { translationX = follow * 0.6f; rotationY = follow / 40f; cameraDistance = 14f * density }
                        .tilt { showLyrics = true }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { dx = 0f },
                                onDragCancel = { dx = 0f },
                                onDragEnd = { if (dx < -120) conn.next() else if (dx > 120) conn.prev(); dx = 0f }
                            ) { _, d -> dx += d }
                        })
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        SeekBar(pos, dur, accent, conn::seekTo)
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(pos), color = Dim, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("-" + fmt(dur - pos), color = Dim, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(md.title?.toString().orEmpty(), color = White, fontSize = 24.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (format.isNotEmpty()) Text(format, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp).background(accent).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        conn.upNext.value.take(2).forEach {
            Text(it.mediaMetadata.title?.toString().orEmpty(), color = Dim, fontSize = 18.sp, fontWeight = FontWeight.Light,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ToggleIcon(Icons.Rounded.Shuffle, conn.shuffle.value, accent) { conn.toggleShuffle() }
            CircleButton(Icons.Rounded.SkipPrevious, 58.dp) { conn.prev() }
            CircleButton(if (conn.isPlaying.value) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 74.dp) { conn.playPause() }
            CircleButton(Icons.Rounded.SkipNext, 58.dp) { conn.next() }
            ToggleIcon(if (conn.repeatMode.intValue == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                conn.repeatMode.intValue != Player.REPEAT_MODE_OFF, accent) { conn.cycleRepeat() }
        }
        Text(if (showLyrics) "album art" else "lyrics", color = if (showLyrics) accent else White, fontSize = 16.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).tilt { showLyrics = !showLyrics }.padding(12.dp))
    }
    }
}

@Composable
fun LyricsView(lyrics: Lyrics?, loading: Boolean, pos: Long, accent: Color, onSeek: (Long) -> Unit, onPick: () -> Unit, modifier: Modifier) {
    Box(modifier) {
        when {
            loading -> Text("searching for lyrics…", color = Dim, fontSize = 20.sp, fontWeight = FontWeight.Light)
            lyrics == null -> Column {
                Text("no lyrics found", color = White, fontSize = 24.sp, fontWeight = FontWeight.Light)
                Text("put a .lrc file with the same name next to the song, set a lyrics folder in settings, or turn on online lyrics.",
                    color = Dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
                Text("load .lrc file", color = accent, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onPick).padding(vertical = 8.dp))
            }
            else -> {
                val state = rememberLazyListState()
                val cur = if (lyrics.synced) lyrics.lines.indexOfLast { it.time <= pos + 150 } else -1
                LaunchedEffect(cur) { if (cur >= 0) state.animateScrollToItem((cur - 2).coerceAtLeast(0)) }
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lyrics.lines) { i, l ->
                        Text(l.text.ifBlank { "♪" },
                            color = when { i == cur -> White; lyrics.synced -> White.copy(alpha = 0.35f); else -> White.copy(alpha = 0.85f) },
                            fontSize = if (i == cur) 24.sp else 20.sp,
                            fontWeight = if (i == cur) FontWeight.Normal else FontWeight.Light,
                            modifier = Modifier.fillMaxWidth()
                                .clickable(enabled = lyrics.synced) { onSeek(l.time) }
                                .padding(vertical = 6.dp))
                    }
                    item {
                        Column(Modifier.padding(top = 16.dp, bottom = 40.dp)) {
                            Text("lyrics: ${lyrics.source}${if (!lyrics.synced) " (not synced)" else ""}", color = Dim, fontSize = 13.sp)
                            Text("use a different .lrc file", color = accent, fontSize = 15.sp,
                                modifier = Modifier.clickable(onClick = onPick).padding(vertical = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeekBar(pos: Long, dur: Long, accent: Color, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val d by rememberUpdatedState(dur)
    val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Canvas(Modifier.fillMaxWidth().height(26.dp)
        .pointerInput(Unit) { detectTapGestures { o -> if (d > 0) seek((o.x / size.width * d).toLong()) } }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = { drag?.let { if (d > 0) seek((it * d).toLong()) }; drag = null },
                onDragCancel = { drag = null }
            ) { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
        }) {
        val y = size.height / 2
        val w = 5.dp.toPx()
        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), w)
        drawLine(accent, Offset(0f, y), Offset(size.width * frac, y), w)
    }
}

@Composable
fun CircleButton(icon: ImageVector, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).tilt(onClick).clip(CircleShape).border(2.5.dp, White, CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = White, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun ToggleIcon(icon: ImageVector, on: Boolean, accent: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).tilt(onClick).clip(CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) accent else Dim, modifier = Modifier.size(26.dp))
    }
}
