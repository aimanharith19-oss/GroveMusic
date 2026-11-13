package com.grove.music

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.media3.common.MediaItem
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
    val pages = listOf("songs", "artists", "albums")
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val songs = vm.songs.value
    val play: (List<Song>, Int) -> Unit = { l, i -> conn.playSongs(l, i); open(Screen.Playing) }

    // read only inside graphicsLayer, so swiping never recomposes the screen (smooth at 90/120 Hz)
    val pos: () -> Float = { pager.currentPage + pager.currentPageOffsetFraction }
    var menu by remember { mutableStateOf(false) }
    BackHandler(menu) { menu = false }
    Box(Modifier.fillMaxSize()) {
    TintBackground(accent)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // panorama title: huge, light, lowercase, slides slower than the content (parallax)
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            Text("grove music", color = White, fontSize = 84.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = -pos() * 70.dp.toPx() }
                    .padding(start = 12.dp, top = 4.dp))
        }
        PivotHeaders(pages, pos, accent) { scope.launch { pager.animateScrollToPage(it, animationSpec = tween(450, easing = WpEase)) } }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { p ->
            when (pages[p]) {
                "songs" -> SongsPage(songs, accent, vm.loading.value, askPerms, onShuffle = {
                    conn.playSongs(songs, 0, shuffled = true); open(Screen.Playing)
                }) { play(songs, it) }
                "artists" -> ArtistsPage(songs, vm, accent) { open(Screen.Artist(it)) }
                "albums" -> AlbumsPage(songs, accent) { list ->
                    open(Screen.Detail(list[0].album, list[0].artist, list.sortedWith(compareBy({ it.track }, { it.title }))))
                }
            }
        }
        if (conn.current.value != null) MiniPlayer(conn, accent, onMore = { menu = !menu }) { open(Screen.Playing) }
        else AppBar(accent) { menu = !menu }
    }
    // Windows Phone app-bar menu: slides up from the bottom when "•••" is tapped
    AnimatedVisibility(menu, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { menu = false } })
    }
    AnimatedVisibility(menu, modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(380, easing = WpEase)) { it }, exit = slideOutVertically(tween(260, easing = WpEase)) { it }) {
        Column(Modifier.fillMaxWidth().background(lerp(accent, Color.Black, 0.62f)).navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp)) {
            listOf(
                "shuffle all" to { conn.playSongs(songs, 0, shuffled = true); open(Screen.Playing) },
                "rescan library" to { vm.load(); Unit },
                "settings" to { open(Screen.Settings) },
            ).forEach { (label, act) ->
                Text(label, color = White, fontSize = 24.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.fillMaxWidth().tilt { menu = false; act() }.padding(vertical = 9.dp))
            }
        }
    }
    }
}

/** Minimised Windows Phone application bar: just the "•••" at the right. */
@Composable
fun AppBar(accent: Color, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp).background(Color.Black.copy(alpha = 0.55f)).background(accent.copy(alpha = 0.12f))
        .tilt(onMore), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.MoreHoriz, "more", tint = White, modifier = Modifier.padding(end = 18.dp).size(26.dp))
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
fun ArtistsPage(songs: List<Song>, vm: MusicViewModel, accent: Color, onOpen: (String) -> Unit) {
    // one entry per main artist: "A feat. B", "A, B", "a" all go under A
    val groups = remember(songs) {
        songs.groupBy { ArtistRepository.key(ArtistRepository.primary(it.artist)) }.map { (_, l) ->
            val name = l.map { ArtistRepository.primary(it.artist) }.groupingBy { it }.eachCount().maxBy { it.value }.key
            name to l.size
        }.sortedBy { it.first.lowercase() }
    }
    if (groups.isEmpty()) { EmptyMsg("nothing here yet"); return }
    val ctx = LocalContext.current
    val tick = vm.artistTick.intValue
    val intro = rememberIntro()
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        var prev: Char? = null
        var n = 0
        groups.forEachIndexed { i, (name, count) ->
            val c = letterOf(name)
            if (c != prev) { prev = c; val j = n++; item(key = "h$i") { Box(Modifier.feather(j, intro)) { LetterTile(c, accent) } } }
            val j = n++
            item(key = "a$name") {
                val info = remember(name, tick) { ArtistRepository.cached(ctx, name) }
                Row(Modifier.feather(j, intro).fillMaxWidth().tilt { onOpen(name) }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp).background(lerp(accent, Color.Black, 0.35f)), contentAlignment = Alignment.Center) {
                        Text(name.take(1).lowercase(), color = White, fontSize = 26.sp, fontWeight = FontWeight.Light)
                        if (info?.image != null) AsyncImage(info.image, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(name.lowercase(), color = White, fontSize = 25.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$count ${if (count == 1) "song" else "songs"}", color = Dim, fontSize = 13.sp)
                    }
                }
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
fun MiniPlayer(conn: PlayerConnection, accent: Color, onMore: (() -> Unit)? = null, onOpen: () -> Unit) {
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
            if (onMore != null) Icon(Icons.Rounded.MoreHoriz, "more", tint = White,
                modifier = Modifier.padding(start = 2.dp).size(38.dp).clip(CircleShape).tilt(onMore).padding(6.dp))
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
    var dir by remember { mutableIntStateOf(1) }
    val goNext = { dir = 1; conn.next() }
    val goPrev = { dir = -1; conn.prev() }
    LaunchedEffect(item.mediaId) { delay(700); dir = 1 } // auto-advance always slides forward
    val artistName = (md.artist ?: "unknown artist").toString()

    // swipe anywhere (except the seek bar) to change song; the cover follows the finger
    val drag = remember { Animatable(0f) }
    var exitId by remember { mutableStateOf<String?>(null) }
    var exitFrom by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val curId by rememberUpdatedState(item.mediaId)
    val nextFn by rememberUpdatedState(goNext)
    val prevFn by rememberUpdatedState(goPrev)
    val deep = lerp(accent, Color.Black, 0.74f)
    val lyricA by animateFloatAsState(if (showLyrics) 1f else 0f, tween(520, easing = WpEase), label = "lyr")
    var showQueue by remember { mutableStateOf(false) }
    BackHandler(showQueue) { showQueue = false }
    val inf = rememberInfiniteTransition(label = "kb")
    val kb by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Reverse), label = "kb")

    BoxWithConstraints(Modifier.fillMaxSize().background(deep).pointerInput(Unit) {
        val threshold = size.width * 0.22f
        detectHorizontalDragGestures(
            onDragEnd = {
                val v = drag.value
                if (abs(v) > threshold) {
                    exitId = curId; exitFrom = v
                    scope.launch { drag.snapTo(0f) }
                    if (v < 0) nextFn() else prevFn()
                } else scope.launch { drag.animateTo(0f, tween(380, easing = WpEase)) }
            },
            onDragCancel = { scope.launch { drag.animateTo(0f, tween(380, easing = WpEase)) } }
        ) { ch, d -> ch.consume(); scope.launch { drag.snapTo(drag.value + d) } }
    }) {
        val w = constraints.maxWidth.toFloat()
        // 1 · the album cover fills the whole phone, drifting slowly (Ken Burns)
        AnimatedContent(item.mediaId to md.artworkUri, contentKey = { it.first }, transitionSpec = {
            (slideInHorizontally(tween(560, easing = WpEase)) { it * dir } + fadeIn(tween(320))) togetherWith
                (slideOutHorizontally(tween(480, easing = WpEase)) { -it * dir / 2 } + fadeOut(tween(440)))
        }, label = "cover") { (id, uri) ->
            Box(Modifier.fillMaxSize().graphicsLayer {
                val x = when (id) { curId -> drag.value; exitId -> exitFrom; else -> 0f }
                translationX = x * 0.85f
                val sc = 1.05f + 0.07f * kb - abs(x) / w * 0.06f; scaleX = sc; scaleY = sc
                alpha = 1f - abs(x) / w * 0.5f
            }.background(accent)) {
                Icon(Icons.Rounded.MusicNote, null, tint = White.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.Center).size(120.dp))
                if (uri != null) AsyncImage(uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        // 2 · tint: accent wash over the cover, deep accent-black rising from the bottom
        Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.20f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.55f), 0.2f to Color.Transparent, 0.42f to Color.Transparent,
            0.66f to deep.copy(alpha = 0.9f), 1f to deep)))
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = lyricA }.background(deep.copy(alpha = 0.8f)))

        // 3 · content
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("NOW PLAYING", color = White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, "up next", tint = White,
                    modifier = Modifier.padding(end = 8.dp).size(42.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.25f)).tilt { showQueue = true }.padding(9.dp))
                Icon(Icons.Rounded.Lyrics, "lyrics", tint = White,
                    modifier = Modifier.size(42.dp).clip(CircleShape)
                        .background(if (showLyrics) accent else Color.Black.copy(alpha = 0.25f))
                        .tilt { showLyrics = !showLyrics }.padding(9.dp))
            }
            SlideText(artistName to (md.albumTitle ?: "").toString(), dir) { (a, al) ->
                Column {
                    Text(a.lowercase(), color = White, fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                        modifier = Modifier.tilt { open(Screen.Artist(a)) }.wrapContentWidth(Alignment.Start, unbounded = true))
                    Text(al.lowercase(), color = White.copy(alpha = 0.75f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (lyricA > 0.01f) Box(Modifier.fillMaxSize().padding(top = 12.dp)
                    .graphicsLayer { alpha = lyricA; translationY = (1f - lyricA) * 40.dp.toPx() }) {
                    LyricsView(lyrics, lyricsLoading, { posS.longValue }, accent, conn::seekTo, { pickLrc.launch(arrayOf("*/*")) }, Modifier.fillMaxSize())
                }
                if (!showLyrics) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { showLyrics = true } })
            }
            LiveLyric(if (showLyrics) null else lyrics, { posS.longValue }) { showLyrics = true }
            Spacer(Modifier.height(14.dp))
            SlideText(md.title?.toString().orEmpty() to format, dir) { (t, f) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t, color = White, fontSize = 28.sp, fontWeight = FontWeight.Light, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (f.isNotEmpty()) Text(f, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp).background(accent).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            SeekBar(posS, durS, accent, conn::seekTo)
            TimeRow(posS, durS)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ToggleIcon(Icons.Rounded.Shuffle, conn.shuffle.value, accent) { conn.toggleShuffle() }
                CircleButton(Icons.Rounded.SkipPrevious, 58.dp) { goPrev() }
                PlayButton(conn.isPlaying.value, accent) { conn.playPause() }
                CircleButton(Icons.Rounded.SkipNext, 58.dp) { goNext() }
                ToggleIcon(if (conn.repeatMode.intValue == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    conn.repeatMode.intValue != Player.REPEAT_MODE_OFF, accent) { conn.cycleRepeat() }
            }
            Spacer(Modifier.height(46.dp))
        }
        NextBar(conn, accent, Modifier.align(Alignment.BottomCenter)) { showQueue = true }
        AnimatedVisibility(showQueue,
            enter = fadeIn(tween(250)) + slideInVertically(tween(450, easing = WpEase)) { it / 3 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(300, easing = WpEase)) { it / 3 }) {
            QueuePanel(conn, accent, deep) { showQueue = false }
        }
    }
}

/** Full "up next" list over a deep tint. Tap a song to play it, slide it away to remove it. */
@Composable
fun QueuePanel(conn: PlayerConnection, accent: Color, deep: Color, onClose: () -> Unit) {
    val q = conn.queue.value
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { onClose() } }) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.78f)
            .background(Brush.verticalGradient(listOf(lerp(accent, Color.Black, 0.55f).copy(alpha = 0.97f), deep.copy(alpha = 0.99f))))
            .pointerInput(Unit) { detectTapGestures { } }   // swallow taps so the panel doesn't close
            .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("up next", color = White, fontSize = 48.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.KeyboardArrowDown, "close", tint = White,
                    modifier = Modifier.padding(bottom = 10.dp).size(40.dp).clip(CircleShape).tilt(onClose).padding(6.dp))
            }
            Text(if (q.isEmpty()) "nothing after this song" else "${q.size} songs · slide a song away to remove it",
                color = White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(q, key = { pos, it -> "${it.second.mediaId}#${it.first}#$pos" }) { pos, (index, item) ->
                    QueueRow(item, accent, pos, Modifier.animateItem(), onPlay = { conn.playAt(index) }) { conn.removeAt(index) }
                }
            }
        }
    }
}

@Composable
fun QueueRow(item: MediaItem, accent: Color, pos: Int, modifier: Modifier, onPlay: () -> Unit, onRemove: () -> Unit) {
    val off = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var w by remember { mutableIntStateOf(1) }
    val remove by rememberUpdatedState(onRemove)
    val md = item.mediaMetadata
    Box(modifier.fillMaxWidth().onSizeChanged { w = it.width }) {
        Row(Modifier.matchParentSize().graphicsLayer { alpha = (abs(off.value) / w * 2.5f).coerceIn(0f, 1f) }
            .background(Color(0xFFE51400)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (off.value >= 0) Arrangement.Start else Arrangement.End) {
            Icon(Icons.Rounded.Delete, "remove", tint = White)
        }
        Row(Modifier.fillMaxWidth()
            .graphicsLayer { translationX = off.value; alpha = 1f - (abs(off.value) / w).coerceIn(0f, 1f) * 0.6f }
            .background(Color.Transparent)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(off.value) > w * 0.33f) {
                                off.animateTo(if (off.value > 0) w.toFloat() else -w.toFloat(), tween(180))
                                remove()
                            } else off.animateTo(0f, tween(300, easing = WpEase))
                        }
                    },
                    onDragCancel = { scope.launch { off.animateTo(0f, tween(300, easing = WpEase)) } }
                ) { ch, d -> ch.consume(); scope.launch { off.snapTo(off.value + d) } }
            }
            .tilt(onPlay).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(md.artworkUri, accent, Modifier.size(50.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(md.title?.toString().orEmpty(), color = White, fontSize = 19.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(md.artist?.toString().orEmpty().lowercase(), color = White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pos == 0) Text("NEXT", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(accent).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/** Big play button: accent-filled circle with a soft accent halo. */
@Composable
fun PlayButton(playing: Boolean, accent: Color, onClick: () -> Unit) {
    Box(Modifier.size(80.dp).tilt(onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)), CircleShape))
        Box(Modifier.size(66.dp).clip(CircleShape).background(accent).border(2.dp, White, CircleShape), contentAlignment = Alignment.Center) {
            AnimatedContent(playing, transitionSpec = { (fadeIn(tween(180)) + scaleIn(tween(220))) togetherWith (fadeOut(tween(120)) + scaleOut(tween(150))) }, label = "pp") { p ->
                Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

/** Bottom bar in the style of the WP app bar: "next · title". Tap or swipe up for the full up-next list. */
@Composable
fun NextBar(conn: PlayerConnection, accent: Color, modifier: Modifier, onOpen: () -> Unit) {
    val n = conn.upNext.value.firstOrNull()
    val open by rememberUpdatedState(onOpen)
    Row(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.30f)).navigationBarsPadding()
        .pointerInput(Unit) { detectVerticalDragGestures { ch, d -> if (d < -12) { ch.consume(); open() } } }
        .tilt(onOpen).height(46.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.KeyboardArrowUp, null, tint = White.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
        Text("NEXT", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 8.dp, end = 10.dp))
        AnimatedContent(n?.mediaId, transitionSpec = {
            (slideInVertically(tween(420, easing = WpEase)) { it } + fadeIn(tween(300))) togetherWith
                (slideOutVertically(tween(300, easing = WpEase)) { -it } + fadeOut(tween(200)))
        }, modifier = Modifier.weight(1f), label = "nextbar") { _ ->
            val md = n?.mediaMetadata
            Text(if (md == null) "end of the list" else buildString {
                append(md.title ?: ""); md.artist?.let { append("  ·  "); append(it.toString().lowercase()) }
            }, color = White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.MoreHoriz, null, tint = White.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
    }
}

/** The lyric line being sung right now, with the next line faintly below; slides up as the song moves on. */
@Composable
fun LiveLyric(lyrics: Lyrics?, pos: () -> Long, onOpen: () -> Unit) {
    if (lyrics == null || !lyrics.synced || lyrics.lines.isEmpty()) return
    val cur by remember(lyrics) { derivedStateOf { lyrics.lines.indexOfLast { it.time <= pos() + 150 } } }
    Box(Modifier.fillMaxWidth().tilt(onOpen).padding(vertical = 4.dp)) {
        AnimatedContent(cur, transitionSpec = {
            (slideInVertically(tween(420, easing = WpEase)) { it / 2 } + fadeIn(tween(300))) togetherWith
                (slideOutVertically(tween(320, easing = WpEase)) { -it / 2 } + fadeOut(tween(220)))
        }, label = "liveLyric") { i ->
            Column {
                Text(lyrics.lines.getOrNull(i)?.text?.takeIf { it.isNotBlank() } ?: "♪", color = White, fontSize = 22.sp,
                    fontWeight = FontWeight.Light, lineHeight = 27.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(lyrics.lines.getOrNull(i + 1)?.text.orEmpty(), color = White.copy(alpha = 0.45f), fontSize = 16.sp,
                    fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
    val key = ArtistRepository.key(name)
    // every song where this artist is credited (main or featured)
    val songs = remember(name, vm.songs.value) {
        vm.songs.value.filter { s -> ArtistRepository.split(s.artist).any { ArtistRepository.key(it) == key } }
            .sortedWith(compareBy({ it.album.lowercase() }, { it.track }, { it.title }))
    }
    val albums = remember(songs) { songs.groupBy { it.albumId }.values.toList() }
    val owned = remember(albums) { albums.associateBy { ArtistRepository.norm(it[0].album) } }
    var info by remember(name) { mutableStateOf(ArtistRepository.cached(ctx, name)) }
    var loading by remember(name) { mutableStateOf(info == null) }
    LaunchedEffect(name) { if (info == null) { info = ArtistRepository.load(ctx, name); loading = false } }
    var bioOpen by remember { mutableStateOf(false) }
    val intro = rememberIntro()
    val play: (Int) -> Unit = { conn.playSongs(songs, it); open(Screen.Playing) }
    val deep = lerp(accent, Color.Black, 0.74f)

    // smooth parallax: add up every pixel scrolled instead of jumping when the first item leaves
    var scrollY by remember { mutableFloatStateOf(0f) }
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                scrollY = (scrollY - consumed.y).coerceAtLeast(0f); return Offset.Zero
            }
        }
    }
    val (albumRel, singles) = remember(info) {
        (info?.releases ?: emptyList()).partition { it.type == "album" || it.type == "compile" }
    }

    Box(Modifier.fillMaxSize().background(deep)) {
        AnimatedContent(info?.image, transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(300)) }, label = "photo") { img ->
            Box(Modifier.fillMaxSize()) {
                if (img != null) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.82f)
                        .graphicsLayer {
                            translationY = -scrollY * 0.45f
                            alpha = (1f - scrollY / 1600f).coerceIn(0.12f, 1f)
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        // fade the photo's lower edge into the tint — no hard line
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                        })
            }
        }
        Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.16f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.45f), 0.22f to Color.Transparent, 0.45f to deep.copy(alpha = 0.55f), 0.7f to deep)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            LazyColumn(Modifier.weight(1f).nestedScroll(nested), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                item { Spacer(Modifier.height(190.dp)) }
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
                        SectionLabel("biography", accent)
                        when {
                            loading -> Text("finding out about this artist…", color = Dim, fontSize = 16.sp, fontWeight = FontWeight.Light)
                            info?.bio == null -> Text(if (info == null) "no information yet — connect to the internet once" else "no biography found",
                                color = Dim, fontSize = 16.sp, fontWeight = FontWeight.Light)
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
                    SectionLabel("in your library", accent)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(albums) { _, a ->
                            ReleaseCard(a[0].album, a[0].artUri, "${a.size} songs", true, accent) {
                                open(Screen.Detail(a[0].album, a[0].artist, a.sortedWith(compareBy({ it.track }, { it.title }))))
                            }
                        }
                    }
                }
                if (albumRel.isNotEmpty()) item { Discography("albums", albumRel, owned, accent, open) }
                if (singles.isNotEmpty()) item { Discography("singles & EPs", singles, owned, accent, open) }
                item { SectionLabel("songs", accent, Modifier.padding(top = 18.dp)) }
                itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                    SongRow(s.title, "${s.album} · ${s.format}") { play(i) }
                }
            }
            if (conn.current.value != null) MiniPlayer(conn, accent) { open(Screen.Playing) }
        }
    }
}

@Composable
fun SectionLabel(text: String, accent: Color, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = modifier.padding(top = 10.dp, bottom = 8.dp))
}

/** Full discography from the internet; releases you don't have are dimmed and tagged. */
@Composable
fun Discography(title: String, list: List<Release>, owned: Map<String, List<Song>>, accent: Color, open: (Screen) -> Unit) {
    val have = list.count { owned.containsKey(ArtistRepository.norm(it.title)) }
    Column {
        SectionLabel("$title · $have of ${list.size} in library", accent, Modifier.padding(top = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(list) { _, r ->
                val mine = owned[ArtistRepository.norm(r.title)]
                ReleaseCard(r.title, r.cover?.let { Uri.parse(it) }, listOf(r.year, r.type).filter { it.isNotBlank() }.joinToString(" · "),
                    mine != null, accent) {
                    if (mine != null) open(Screen.Detail(mine[0].album, mine[0].artist, mine.sortedWith(compareBy({ it.track }, { it.title }))))
                }
            }
        }
    }
}

@Composable
fun ReleaseCard(title: String, cover: Uri?, sub: String, mine: Boolean, accent: Color, onClick: () -> Unit) {
    Column(Modifier.width(130.dp).tilt(onClick)) {
        Box {
            Art(cover, accent, Modifier.size(130.dp).graphicsLayer { alpha = if (mine) 1f else 0.5f })
            if (!mine) Text("NOT IN LIBRARY", color = White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 5.dp, vertical = 2.dp))
            else Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).background(accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = White, modifier = Modifier.size(14.dp))
            }
        }
        Text(title.lowercase(), color = if (mine) White else White.copy(alpha = 0.6f), fontSize = 15.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(sub, color = Dim, fontSize = 12.sp, maxLines = 1)
    }
}

private fun fans(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fK".format(n / 1_000f)
    else -> n.toString()
}

/** Text that slides in the direction the song changed. */
@Composable
fun <T> SlideText(value: T, dir: Int, content: @Composable (T) -> Unit) {
    AnimatedContent(value, transitionSpec = {
        (fadeIn(tween(260, delayMillis = 60)) + slideInHorizontally(tween(420, easing = WpEase)) { it / 4 * dir }) togetherWith
            (fadeOut(tween(160)) + slideOutHorizontally(tween(300, easing = WpEase)) { -it / 4 * dir })
    }, label = "slideText") { content(it) }
}

