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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
    data object Settings : Screen
    data class Artist(val name: String) : Screen
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
                    Screen.Home -> HomeScreen(vm, conn, accent, open) { permLauncher.launch(perms) }
                    Screen.Playing -> NowPlayingScreen(vm, conn, prefs, accent, open)
                    Screen.Settings -> SettingsScreen(vm, prefs, accent, { accentInt = it; prefs.accent = it }) { vm.load() }
                    is Screen.Artist -> ArtistScreen(s.name, vm, conn, accent, open)
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
    vm: MusicViewModel, conn: PlayerConnection, accent: Color,
    open: (Screen) -> Unit, askPerms: () -> Unit,
) {
    val pages = listOf("songs", "artists", "albums", "genres")
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val songs = vm.songs.value
    val play: (List<Song>, Int) -> Unit = { l, i -> conn.playSongs(l, i); open(Screen.Playing) }

    // read only inside graphicsLayer, so swiping never recomposes the screen (smooth at 90/120 Hz)
    val pos: () -> Float = { pager.currentPage + pager.currentPageOffsetFraction }
    Box(Modifier.fillMaxSize()) {
    TintBackground(accent)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // panorama title: huge, light, lowercase, slides slower than the content (parallax)
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            Text("grove music", color = White, fontSize = 84.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = -pos() * 70.dp.toPx() }
                    .padding(start = 12.dp, top = 4.dp, end = 60.dp))
            Icon(Icons.Rounded.Settings, "settings", tint = White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp).size(40.dp)
                    .clip(CircleShape).tilt { open(Screen.Settings) }.padding(8.dp))
        }
        PivotHeaders(pages, pos, accent) { scope.launch { pager.animateScrollToPage(it, animationSpec = tween(450, easing = WpEase)) } }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { p ->
            when (pages[p]) {
                "songs" -> SongsPage(songs, accent, vm.loading.value, askPerms, onShuffle = {
                    conn.playSongs(songs, 0, shuffled = true); open(Screen.Playing)
                }) { play(songs, it) }
                "artists" -> GroupPage(songs.groupBy { it.artist }, accent) { name, _ -> open(Screen.Artist(name)) }
                "albums" -> AlbumsPage(songs, accent) { list ->
                    open(Screen.Detail(list[0].album, list[0].artist, list.sortedWith(compareBy({ it.track }, { it.title }))))
                }
                "genres" -> GroupPage(songs.groupBy { it.genre }, accent) { name, list ->
                    open(Screen.Detail(name, "${list.size} songs", list))
                }
            }
        }
        if (conn.current.value != null) MiniPlayer(conn, accent) { open(Screen.Playing) }
    }
    }
}

/** Pivot headers that glide with the finger, exactly in step with the pager. */
@Composable
fun PivotHeaders(pages: List<String>, pos: () -> Float, accent: Color, onSelect: (Int) -> Unit) {
    val widths = remember { mutableStateListOf<Float>().apply { repeat(pages.size) { add(0f) } } }
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
            .graphicsLayer {
                val p = pos()
                val i = p.toInt().coerceIn(0, pages.lastIndex)
                val f = p - i
                translationX = -(widths.take(i).sum() + widths.getOrElse(i) { 0f } * f)
            }
            .padding(start = 18.dp)) {
            pages.forEachIndexed { i, name ->
                Text(name, fontSize = 46.sp, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false, color = White,
                    modifier = Modifier.onSizeChanged { widths[i] = it.width.toFloat() }
                        .graphicsLayer { alpha = 0.35f + 0.65f * (1f - abs(pos() - i)).coerceIn(0f, 1f) }
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
fun SettingsScreen(vm: MusicViewModel, prefs: Prefs, accent: Color, onAccent: (Int) -> Unit, rescan: () -> Unit) {
    val ctx = LocalContext.current
    var folder by remember { mutableStateOf(prefs.lyricsFolder) }
    var online by remember { mutableStateOf(prefs.onlineLyrics) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.lyricsFolder = uri.toString(); folder = uri.toString(); LyricsRepository.invalidateFolder()
        }
    }
    val hidden = vm.hidden.value
    Box(Modifier.fillMaxSize()) {
    TintBackground(accent, 0.30f)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("GROVE MUSIC", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Text("settings", color = White, fontSize = 56.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(bottom = 8.dp))
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
        var tiles by remember { mutableStateOf(prefs.liveTiles) }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("live tiles now playing", color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                Text("show album, singer and up next as flipping Metro tiles", color = Dim, fontSize = 14.sp)
            }
            Switch(tiles, { tiles = it; prefs.liveTiles = it },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = White))
        }
        SettingItem("rescan library", "look for new music on this phone", rescan)
        Text("hidden folders", color = White, fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 10.dp))
        Text("untick a folder to hide its songs from your collection", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        vm.folders.value.forEach { (f, n) ->
            val shown = f !in hidden
            Row(Modifier.fillMaxWidth().tilt { vm.setHidden(f, shown) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).border(2.dp, White).background(if (shown) accent else Color.Transparent), contentAlignment = Alignment.Center) {
                    if (shown) Icon(Icons.Rounded.Check, null, tint = White, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(f.substringAfterLast('/').ifEmpty { f }, color = if (shown) White else Dim, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$n songs · ${f.removePrefix("/storage/emulated/0")}", color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (vm.folders.value.isEmpty()) Text("no music folders found", color = Dim, fontSize = 15.sp)
        SettingItem("about", "Grove Music 1.0 · plays MP3, FLAC (incl. 24-bit), OGG, OPUS, M4A, WAV") {}
        Spacer(Modifier.height(24.dp))
    }
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

/** Windows Phone style "now playing" app bar: thin accent progress line, square art,
 *  light lowercase text that slides on song change, and outlined round app-bar buttons. */
@Composable
fun MiniPlayer(conn: PlayerConnection, accent: Color, onOpen: () -> Unit) {
    val md = conn.current.value?.mediaMetadata ?: return
    val prog = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) { val d = conn.duration(); prog.floatValue = if (d > 0) conn.position().toFloat() / d else 0f; delay(250) }
    }
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.88f))) {
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            drawRect(Color.White.copy(alpha = 0.12f))
            drawRect(accent, size = size.copy(width = size.width * prog.floatValue))
        }
        Row(Modifier.fillMaxWidth().tilt(onOpen).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Art(md.artworkUri, accent, Modifier.size(46.dp))
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                SlideText(md.title?.toString().orEmpty() to md.artist?.toString().orEmpty(), 1) { (t, a) ->
                    Column {
                        Text(t.lowercase(), color = White, fontSize = 19.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.uppercase(), color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            AppBarButton(if (conn.isPlaying.value) Icons.Rounded.Pause else Icons.Rounded.PlayArrow) { conn.playPause() }
            AppBarButton(Icons.Rounded.SkipNext) { conn.next() }
        }
    }
}

@Composable
fun AppBarButton(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 4.dp).size(38.dp).tilt(onClick).border(1.5.dp, White, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = White, modifier = Modifier.size(20.dp))
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
fun NowPlayingScreen(vm: MusicViewModel, conn: PlayerConnection, prefs: Prefs, accent: Color, open: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val item = conn.current.value
    if (item == null) { EmptyMsg("nothing playing"); return }
    val md = item.mediaMetadata
    val song = vm.song(item.mediaId)

    // position lives in state objects that only the seek bar / clock / lyrics read,
    // so the rest of the screen (blurred art etc.) is not redrawn every tick
    val posS = remember { mutableLongStateOf(0L) }
    val durS = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { posS.longValue = conn.position(); durS.longValue = conn.duration(); delay(100) } }

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
    val liveTiles = prefs.liveTiles
    var dir by remember { mutableIntStateOf(1) }
    val goNext = { dir = 1; conn.next() }
    val goPrev = { dir = -1; conn.prev() }
    LaunchedEffect(item.mediaId) { delay(700); dir = 1 } // auto-advance always slides forward
    val artistName = (md.artist ?: "unknown artist").toString()
    var artistInfo by remember(artistName) { mutableStateOf<ArtistInfo?>(null) }
    LaunchedEffect(artistName) { if (liveTiles) artistInfo = ArtistRepository.load(ctx, artistName) }

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
        SlideText(artistName to (md.albumTitle ?: "").toString(), dir) { (a, al) ->
            Column {
                Text(a.lowercase(), color = White, fontSize = 54.sp,
                    fontWeight = FontWeight.Light, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(top = 6.dp).tilt { open(Screen.Artist(a)) }
                        .wrapContentWidth(Alignment.Start, unbounded = true))
                Text(al.lowercase(), color = Dim, fontSize = 18.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(14.dp))

        // album art (or live tiles) on the front, lyrics on the back — flips like a Windows Phone tile
        val flip by animateFloatAsState(if (showLyrics) 180f else 0f, tween(650, easing = WpEase), label = "flip")
        Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer {
            rotationY = if (flip < 90f) flip else flip - 180f
            cameraDistance = 22f * density
        }) {
            if (flip >= 90f) {
                LyricsView(lyrics, lyricsLoading, { posS.longValue }, accent, conn::seekTo, { pickLrc.launch(arrayOf("*/*")) }, Modifier.fillMaxSize())
            } else {
                SwipeDeck(item.mediaId, dir, { goNext() }, { goPrev() }) {
                    if (liveTiles) TileGrid(md, song, artistName, artistInfo, vm, conn, accent, format,
                        onLyrics = { showLyrics = true }, onArtist = { open(Screen.Artist(artistName)) })
                    else GlowArt(md.artworkUri, accent) { showLyrics = true }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        SeekBar(posS, durS, accent, conn::seekTo)
        TimeRow(posS, durS)
        SlideText(md.title?.toString().orEmpty() to format, dir) { (t, f) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(t, color = White, fontSize = 24.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (f.isNotEmpty()) Text(f, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp).background(accent).padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
        conn.upNext.value.take(2).forEach {
            Text(it.mediaMetadata.title?.toString().orEmpty(), color = Dim, fontSize = 18.sp, fontWeight = FontWeight.Light,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ToggleIcon(Icons.Rounded.Shuffle, conn.shuffle.value, accent) { conn.toggleShuffle() }
            CircleButton(Icons.Rounded.SkipPrevious, 58.dp) { goPrev() }
            CircleButton(if (conn.isPlaying.value) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 74.dp) { conn.playPause() }
            CircleButton(Icons.Rounded.SkipNext, 58.dp) { goNext() }
            ToggleIcon(if (conn.repeatMode.intValue == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                conn.repeatMode.intValue != Player.REPEAT_MODE_OFF, accent) { conn.cycleRepeat() }
        }
        Text(if (showLyrics) (if (liveTiles) "tiles" else "album art") else "lyrics", color = if (showLyrics) accent else White, fontSize = 16.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).tilt { showLyrics = !showLyrics }.padding(12.dp))
    }
    }
}

@Composable
fun TimeRow(posS: State<Long>, durS: State<Long>) {
    val pos = posS.value; val dur = durS.value
    Row(Modifier.fillMaxWidth()) {
        Text(fmt(pos), color = Dim, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text("-" + fmt(dur - pos), color = Dim, fontSize = 13.sp)
    }
}

@Composable
fun LyricsView(lyrics: Lyrics?, loading: Boolean, pos: () -> Long, accent: Color, onSeek: (Long) -> Unit, onPick: () -> Unit, modifier: Modifier) {
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
                val cur by remember(lyrics) { derivedStateOf { if (lyrics.synced) lyrics.lines.indexOfLast { it.time <= pos() + 150 } else -1 } }
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
fun SeekBar(posS: State<Long>, durS: State<Long>, accent: Color, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val d by durS
    Canvas(Modifier.fillMaxWidth().height(26.dp)
        .pointerInput(Unit) { detectTapGestures { o -> if (d > 0) seek((o.x / size.width * d).toLong()) } }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = { drag?.let { if (d > 0) seek((it * d).toLong()) }; drag = null },
                onDragCancel = { drag = null }
            ) { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
        }) {
        val pos = posS.value; val dur = durS.value
        val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
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

// ───────────────────────── artist ─────────────────────────

@Composable
fun ArtistScreen(name: String, vm: MusicViewModel, conn: PlayerConnection, accent: Color, open: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val songs = remember(name, vm.songs.value) {
        vm.songs.value.filter { it.artist == name }.sortedWith(compareBy({ it.album.lowercase() }, { it.track }, { it.title }))
    }
    val albums = remember(songs) { songs.groupBy { it.albumId }.values.toList() }
    var info by remember(name) { mutableStateOf<ArtistInfo?>(null) }
    var loading by remember(name) { mutableStateOf(true) }
    LaunchedEffect(name) { info = ArtistRepository.load(ctx, name); loading = false }
    var bioOpen by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val intro = rememberIntro()
    val play: (Int) -> Unit = { conn.playSongs(songs, it); open(Screen.Playing) }

    Box(Modifier.fillMaxSize()) {
        // artist photo fills the background, drifting slower than the list (parallax), like WP8 Music
        AnimatedContent(info?.image, transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(300)) }, label = "photo") { img ->
            Box(Modifier.fillMaxSize()) {
                if (img != null) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.8f).graphicsLayer {
                        val off = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 2000f
                        translationY = -off * 0.4f
                    })
                else TintBackground(accent, 0.45f)
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to accent.copy(alpha = 0.25f), 0.35f to Color.Black.copy(alpha = 0.35f), 0.62f to Color.Black)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                item { Spacer(Modifier.height(170.dp)) }
                item {
                    Text((info?.name ?: name).lowercase(), color = White, fontSize = 62.sp, fontWeight = FontWeight.Light,
                        letterSpacing = (-2).sp, maxLines = 1, softWrap = false,
                        modifier = Modifier.feather(0, intro).wrapContentWidth(Alignment.Start, unbounded = true))
                    val sub = buildList {
                        add("${songs.size} songs"); if (albums.size > 1) add("${albums.size} albums")
                        info?.fans?.takeIf { it > 0 }?.let { add(fans(it) + " fans") }
                    }.joinToString(" · ")
                    Text(sub, color = White.copy(alpha = 0.75f), fontSize = 15.sp, modifier = Modifier.feather(1, intro))
                }
                item {
                    Row(Modifier.feather(2, intro).padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("play", color = White, fontSize = 18.sp, modifier = Modifier.background(accent).tilt { if (songs.isNotEmpty()) play(0) }
                            .padding(horizontal = 22.dp, vertical = 10.dp))
                        Text("shuffle", color = White, fontSize = 18.sp, modifier = Modifier.border(2.dp, White)
                            .tilt { if (songs.isNotEmpty()) { conn.playSongs(songs, 0, shuffled = true); open(Screen.Playing) } }
                            .padding(horizontal = 22.dp, vertical = 8.dp))
                    }
                }
                item {
                    Column(Modifier.feather(3, intro).fillMaxWidth().padding(bottom = 10.dp)) {
                        Text("biography", color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        when {
                            loading -> Text("finding out about this artist…", color = Dim, fontSize = 16.sp, fontWeight = FontWeight.Light)
                            info?.bio == null -> Text("no information found online", color = Dim, fontSize = 16.sp, fontWeight = FontWeight.Light)
                            else -> {
                                Text(info!!.bio!!, color = White.copy(alpha = 0.9f), fontSize = 16.sp, fontWeight = FontWeight.Light,
                                    maxLines = if (bioOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.tilt { bioOpen = !bioOpen }.padding(vertical = 4.dp))
                                Text(if (bioOpen) "less" else "more", color = accent, fontSize = 15.sp,
                                    modifier = Modifier.tilt { bioOpen = !bioOpen }.padding(vertical = 4.dp))
                                Text("from Wikipedia", color = Dim, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (albums.isNotEmpty()) item {
                    Text("albums", color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(albums) { _, a ->
                            Column(Modifier.width(130.dp).tilt {
                                open(Screen.Detail(a[0].album, a[0].artist, a.sortedWith(compareBy({ it.track }, { it.title }))))
                            }) {
                                Art(a[0].artUri, accent, Modifier.size(130.dp))
                                Text(a[0].album.lowercase(), color = White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
                item { Text("songs", color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
                itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                    SongRow(s.title, "${s.album} · ${s.format}") { play(i) }
                }
            }
            if (conn.current.value != null) MiniPlayer(conn, accent) { open(Screen.Playing) }
        }
    }
}

private fun fans(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fK".format(n / 1_000f)
    else -> n.toString()
}

// ───────────────────────── now playing pieces ─────────────────────────

/** Text that slides in the direction the song changed. */
@Composable
fun <T> SlideText(value: T, dir: Int, content: @Composable (T) -> Unit) {
    AnimatedContent(value, transitionSpec = {
        (fadeIn(tween(260, delayMillis = 60)) + slideInHorizontally(tween(420, easing = WpEase)) { it / 4 * dir }) togetherWith
            (fadeOut(tween(160)) + slideOutHorizontally(tween(300, easing = WpEase)) { -it / 4 * dir })
    }, label = "slideText") { content(it) }
}

/** Swipe left/right to change song; the card follows the finger, then flies out as the next one slides in. */
@Composable
fun SwipeDeck(id: String, dir: Int, onNext: () -> Unit, onPrev: () -> Unit, content: @Composable () -> Unit) {
    val drag = remember { Animatable(0f) }
    var exitId by remember { mutableStateOf<String?>(null) }
    var exitFrom by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val curId by rememberUpdatedState(id)
    val next by rememberUpdatedState(onNext)
    val prev by rememberUpdatedState(onPrev)
    BoxWithConstraints(Modifier.fillMaxSize()
        .pointerInput(Unit) {
            val threshold = size.width * 0.22f
            detectHorizontalDragGestures(
                onDragEnd = {
                    val v = drag.value
                    if (abs(v) > threshold) {
                        exitId = curId; exitFrom = v
                        scope.launch { drag.snapTo(0f) }
                        if (v < 0) next() else prev()
                    } else scope.launch { drag.animateTo(0f, tween(380, easing = WpEase)) }
                },
                onDragCancel = { scope.launch { drag.animateTo(0f, tween(380, easing = WpEase)) } }
            ) { ch, d -> ch.consume(); scope.launch { drag.snapTo(drag.value + d) } }
        }) {
        val w = constraints.maxWidth
        AnimatedContent(id, transitionSpec = {
            (slideInHorizontally(tween(480, easing = WpEase)) { it * dir } + fadeIn(tween(250))) togetherWith
                (slideOutHorizontally(tween(420, easing = WpEase)) { -it * dir } + fadeOut(tween(380)))
        }, label = "deck") { shown ->
            Box(Modifier.fillMaxSize().graphicsLayer {
                val x = when (shown) { id -> drag.value; exitId -> exitFrom; else -> 0f }
                translationX = x
                rotationY = (x / w) * -18f
                val sc = 1f - (abs(x) / w) * 0.12f; scaleX = sc; scaleY = sc
                cameraDistance = 16f * density
            }) { content() }
        }
    }
}

/** Album art with a slowly breathing sunset glow behind it. */
@Composable
fun GlowArt(uri: Uri?, accent: Color, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "glow")
    val breathe by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // sunset: warm orange core fading through the accent colour into the dark
        Box(Modifier.fillMaxSize().graphicsLayer { val sc = 1f + 0.06f * breathe; scaleX = sc; scaleY = sc; alpha = 0.75f + 0.25f * breathe }
            .background(Brush.radialGradient(listOf(Color(0xFFFF8A3D).copy(alpha = 0.55f), Color(0xFFE8457A).copy(alpha = 0.35f),
                accent.copy(alpha = 0.25f), Color.Transparent))))
        if (uri != null && Build.VERSION.SDK_INT >= 31) AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth(0.94f).aspectRatio(1f)
                .graphicsLayer { val sc = 1.08f + 0.05f * breathe; scaleX = sc; scaleY = sc; alpha = 0.8f }
                .blur(40.dp))
        Art(uri, accent, Modifier.fillMaxWidth(0.86f).aspectRatio(1f).tilt(onClick))
    }
}

/** A Metro live tile: shows its front, then flips over (top-to-bottom) to show its back, and back again. */
@Composable
fun LiveTile(modifier: Modifier, startDelay: Long, period: Long = 5200, color: Color, onClick: () -> Unit = {},
             front: @Composable BoxScope.() -> Unit, back: (@Composable BoxScope.() -> Unit)? = null) {
    var flipped by remember { mutableStateOf(false) }
    if (back != null) LaunchedEffect(Unit) { delay(startDelay); while (true) { flipped = !flipped; delay(period) } }
    val r by animateFloatAsState(if (flipped) 180f else 0f, tween(800, easing = WpEase), label = "tile")
    Box(modifier.tilt(onClick).graphicsLayer {
        rotationX = if (r < 90f) r else r - 180f
        cameraDistance = 14f * density
    }.background(color).clipToBounds()) {
        if (r < 90f || back == null) front() else back()
    }
}

@Composable
private fun BoxScope.TileLabel(big: String, small: String? = null) {
    Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
        Text(big, color = White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
        if (small != null) Text(small, color = White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TileGrid(md: androidx.media3.common.MediaMetadata, song: Song?, artist: String, info: ArtistInfo?, vm: MusicViewModel,
             conn: PlayerConnection, accent: Color, format: String, onLyrics: () -> Unit, onArtist: () -> Unit) {
    val next = conn.upNext.value
    val others = remember(artist, vm.songs.value) {
        vm.songs.value.filter { it.artist == artist && it.albumId != song?.albumId }.distinctBy { it.albumId }.take(2)
    }
    val dark = lerp(accent, Color.Black, 0.35f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 8.dp
        val u = minOf((maxWidth - gap * 3) / 4, (maxHeight - gap * 2) / 3)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                // 2x2 album tile: cover ↔ song + album
                LiveTile(Modifier.size(u * 2 + gap), 2600, color = accent, onClick = onLyrics, front = {
                    if (md.artworkUri != null) AsyncImage(md.artworkUri, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }, back = {
                    Icon(Icons.Rounded.MusicNote, null, tint = White.copy(alpha = 0.25f), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp))
                    TileLabel(md.title?.toString().orEmpty(), (md.albumTitle ?: "").toString().lowercase())
                })
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    // 2x1 singer tile: photo ↔ name
                    LiveTile(Modifier.size(u * 2 + gap, u), 4100, color = dark, onClick = onArtist, front = {
                        if (info?.image != null) AsyncImage(info.image, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        else TileLabel(artist.lowercase())
                    }, back = { TileLabel(artist.lowercase(), info?.fans?.takeIf { it > 0 }?.let { fans(it) + " fans" } ?: "singer") })
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        val n = next.getOrNull(0)?.mediaMetadata
                        LiveTile(Modifier.size(u), 5600, color = dark, onClick = { conn.next() }, front = {
                            if (n?.artworkUri != null) AsyncImage(n.artworkUri, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                            else Icon(Icons.Rounded.SkipNext, null, tint = White, modifier = Modifier.align(Alignment.Center))
                        }, back = { TileLabel(n?.title?.toString() ?: "end of list", "next") })
                        LiveTile(Modifier.size(u), 7000, color = accent, onClick = onLyrics, front = {
                            Icon(Icons.Rounded.Lyrics, null, tint = White, modifier = Modifier.align(Alignment.Center).size(u * 0.42f))
                        }, back = { TileLabel(format.ifEmpty { "audio" }, "lyrics") })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                val n2 = next.getOrNull(1)?.mediaMetadata
                LiveTile(Modifier.size(u), 3300, color = dark, front = {
                    if (n2?.artworkUri != null) AsyncImage(n2.artworkUri, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }, back = { TileLabel(n2?.title?.toString() ?: "", "later") })
                others.forEachIndexed { i, o ->
                    LiveTile(Modifier.size(u), 4800L + i * 900, color = accent, front = {
                        AsyncImage(o.artUri, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }, back = { TileLabel(o.album.lowercase(), "also by ${artist.lowercase()}") })
                }
                repeat(3 - others.size) { i ->
                    LiveTile(Modifier.size(u), 6200L + i * 700, color = if (i % 2 == 0) accent else dark, front = {
                        Text(if (i == 0) "grove" else "♪", color = White, fontSize = 20.sp, fontWeight = FontWeight.Light,
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                    })
                }
            }
        }
    }
}
