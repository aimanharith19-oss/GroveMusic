package com.grove.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.key
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.graphics.toArgb
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
import coil.imageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
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
fun Modifier.tilt(onClick: () -> Unit): Modifier = tilt(null, onClick)

fun Modifier.tilt(onLongClick: (() -> Unit)?, onClick: () -> Unit): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val p by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 90 else 260, easing = WpEase), label = "tilt")
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
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
            detectTapGestures(onPress = { o -> touch = o; pressed = true; tryAwaitRelease(); pressed = false },
                onLongPress = { long?.invoke() }, onTap = { click() })
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
    data object NoInfo : Screen
    data class Artist(val name: String) : Screen
    data class PlaylistView(val id: String) : Screen
    data object Search : Screen
    data class VideoInfo(val id: Long) : Screen
    data class Show(val name: String) : Screen
    data class PersonView(val id: String) : Screen
    data class EditTags(val songId: Long) : Screen
    data class Detail(val title: String, val sub: String, val songs: List<Song>) : Screen
}

@Composable
fun GroveApp(vm: MusicViewModel, conn: PlayerConnection, prefs: Prefs) {
    var accentInt by remember { mutableIntStateOf(prefs.accent) }
    val accent by animateColorAsState(Color(accentInt), tween(500), label = "accent")
    val perms = remember {
        buildList {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.load() }
    LaunchedEffect(Unit) { permLauncher.launch(perms) }

    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    // keeps each screen's tab, scroll position etc. while you're on another screen
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var forward by remember { mutableStateOf(true) }
    val open: (Screen) -> Unit = { forward = true; stack = stack + it }
    BackHandler(stack.size > 1) { forward = false; stack = stack.dropLast(1) }
    // back on the home pivot just puts GroveMedia in the background, so it reopens where you left it
    val actx = LocalContext.current
    BackHandler(stack.size <= 1) { (actx as? android.app.Activity)?.moveTaskToBack(true) }

    var addTarget by remember { mutableStateOf<List<Long>?>(null) }
    vm.playRadio = { list ->
        val playingThis = conn.current.value?.mediaId == list.firstOrNull()?.id?.toString()
        if (playingThis) conn.startRadio(list) else conn.playSongs(list, 0)
        stack = stack + Screen.Playing
    }
    vm.editTags = { id -> forward = true; stack = stack + Screen.EditTags(id) }
    BackHandler(addTarget != null) { addTarget = null }
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = Color.Black, surface = Color.Black)) {
        CompositionLocalProvider(LocalAddToPlaylist provides { addTarget = it }, LocalVm provides vm) {
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
                Box(Modifier.fillMaxSize().then(if (rot != 0f) Modifier.graphicsLayer {
                    rotationY = rot; transformOrigin = TransformOrigin(0f, 0.5f); cameraDistance = 18f * density
                } else Modifier)) {
                saved.SaveableStateProvider(if (s == Screen.Home) "home" else "${s::class.simpleName}:${s.hashCode()}") {
                when (s) {
                    Screen.Home -> HomeScreen(vm, conn, accent, open) { permLauncher.launch(perms) }
                    Screen.Playing -> NowPlayingScreen(vm, conn, prefs, accent, open)
                    Screen.NoInfo -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        Text("no info", color = White, fontSize = 44.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
                        Box(Modifier.weight(1f)) { MissingPage(vm, accent) }
                    }
                    Screen.Settings -> SettingsScreen(vm, prefs, accent, { accentInt = it; prefs.accent = it }) { vm.load() }
                    is Screen.Artist -> ArtistScreen(s.name, vm, conn, accent, open)
                    is Screen.Detail -> DetailScreen(s, conn, accent, open)
                    Screen.Search -> SearchScreen(vm, conn, accent, open)
                    is Screen.VideoInfo -> VideoInfoScreen(s.id, vm, accent, open) { forward = false; stack = stack.dropLast(1) }
                    is Screen.Show -> ShowScreen(s.name, vm, accent, open)
                    is Screen.PersonView -> PersonScreen(s.id, vm, accent, open)
                    is Screen.EditTags -> EditTagsScreen(s.songId, vm, accent) { forward = false; stack = stack.dropLast(1) }
                    is Screen.PlaylistView -> PlaylistScreen(s.id, vm, conn, accent, open) { forward = false; stack = stack.dropLast(1) }
                }
                }
                }
            }
            AnimatedVisibility(addTarget != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures { addTarget = null } })
            }
            AnimatedVisibility(addTarget != null, modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(380, easing = WpEase)) { it }, exit = slideOutVertically(tween(260, easing = WpEase)) { it }) {
                AddToPlaylistSheet(vm, addTarget ?: emptyList(), accent) { addTarget = null }
            }
            // YouTube-style mini video window (after swiping the video player down)
            VideoMiniOverlay(accent, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 132.dp).navigationBarsPadding())
            // long-press peek card (songs, videos, comics, books)
            BackHandler(Peek.shown.value != null) { Peek.shown.value = null }
            PeekOverlay(accent)
        }
        }
    }
}

/** Edit a song's tags and cover — typed in, picked from the phone, or found online. Saved into the file itself. */
@Composable
fun EditTagsScreen(songId: Long, vm: MusicViewModel, accent: Color, close: () -> Unit) {
    val ctx = LocalContext.current
    val song = vm.song(songId.toString()) ?: run { LaunchedEffect(Unit) { close() }; return }
    val scope = rememberCoroutineScope()
    var t by remember(songId) { mutableStateOf(TagEditor.current(song)) }
    var cover by remember(songId) { mutableStateOf<ByteArray?>(null) }
    var coverPreview by remember(songId) { mutableStateOf<Any?>(song.artUri) }
    var results by remember { mutableStateOf<List<OnlineMatch>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun toast(m: String) = android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_SHORT).show()
    fun doSave() {
        busy = "saving…"
        scope.launch {
            val r = TagEditor.write(ctx, song, t, cover)
            busy = null
            r.onSuccess {
                ctx.imageLoader.memoryCache?.clear()
                toast("saved"); vm.load(); close()
            }.onFailure { e -> toast("couldn't save: ${e.message ?: e.javaClass.simpleName}") }
        }
    }
    val writePerm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) doSave() else toast("permission needed to change the file")
    }
    fun save() {
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = android.provider.MediaStore.createWriteRequest(ctx.contentResolver, listOf(song.uri))
            writePerm.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
        } else doSave()
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { cover = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }; coverPreview = cover }
    }
    fun findOnline() {
        busy = "searching online…"
        scope.launch {
            results = runCatching { TagEditor.search("${t.title} ${ArtistRepository.primary(t.artist)}".trim()) }.getOrDefault(emptyList())
            busy = null
            if (results.isNullOrEmpty()) toast("nothing found online")
        }
    }
    fun apply(m: OnlineMatch) {
        t = t.copy(title = m.tags.title, artist = m.tags.artist, album = m.tags.album, albumArtist = m.tags.albumArtist,
            track = m.tags.track.ifBlank { t.track }, year = m.tags.year.ifBlank { t.year }, genre = m.tags.genre.ifBlank { t.genre })
        results = null
        m.coverUrl?.let { u ->
            busy = "downloading picture…"
            scope.launch { TagEditor.download(u)?.let { cover = it; coverPreview = it }; busy = null }
        }
    }

    Box(Modifier.fillMaxSize()) {
        TintBackground(accent, 0.30f)
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Text("EDIT SONG", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                Text("info", color = White, fontSize = 56.sp, fontWeight = FontWeight.Light)
                Text(song.displayName, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.size(140.dp).background(lerp(accent, Color.Black, 0.3f))) {
                        AsyncImage(coverPreview, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                    Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("change picture", color = White, fontSize = 16.sp, modifier = Modifier.border(2.dp, White)
                            .tilt { pickImage.launch("image/*") }.padding(horizontal = 14.dp, vertical = 7.dp))
                        Text("find online", color = White, fontSize = 16.sp, modifier = Modifier.background(accent)
                            .tilt { findOnline() }.padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
                busy?.let { Text(it, color = White, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            }
            results?.takeIf { it.isNotEmpty() }?.let { list ->
                item { SectionLabel("tap the right match", accent) }
                itemsIndexed(list) { _, m ->
                    Row(Modifier.fillMaxWidth().tilt { apply(m) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).background(lerp(accent, Color.Black, 0.3f))) {
                            if (m.coverUrl != null) AsyncImage(m.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(m.tags.title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(m.tags.artist, m.tags.album, m.tags.year).filter { it.isNotBlank() }.joinToString(" · "),
                                color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item { Text("cancel", color = accent, fontSize = 15.sp, modifier = Modifier.tilt { results = null }.padding(vertical = 10.dp)) }
            }
            item {
                @Composable fun F(label: String, v: String, num: Boolean = false, set: (String) -> Unit) =
                    OutlinedTextField(v, set, label = { Text(label) }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = if (num) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = White, unfocusedBorderColor = White.copy(alpha = 0.45f),
                            focusedLabelColor = White, unfocusedLabelColor = Dim, cursorColor = White,
                            focusedTextColor = White, unfocusedTextColor = White),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                F("title", t.title) { t = t.copy(title = it) }
                F("artist", t.artist) { t = t.copy(artist = it) }
                F("album", t.album) { t = t.copy(album = it) }
                F("album artist", t.albumArtist) { t = t.copy(albumArtist = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { F("track", t.track, true) { t = t.copy(track = it) } }
                    Box(Modifier.weight(1f)) { F("year", t.year, true) { t = t.copy(year = it) } }
                }
                F("genre", t.genre) { t = t.copy(genre = it) }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("save", color = White, fontSize = 18.sp, modifier = Modifier.background(accent)
                        .tilt { if (busy == null) save() }.padding(horizontal = 26.dp, vertical = 10.dp))
                    Text("cancel", color = White, fontSize = 18.sp, modifier = Modifier.border(2.dp, White).tilt(close)
                        .padding(horizontal = 22.dp, vertical = 8.dp))
                }
                Text("changes are written into the song file, so other apps see them too", color = Dim, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** Search across songs, artists, albums and playlists. */
@Composable
fun SearchScreen(vm: MusicViewModel, conn: PlayerConnection, accent: Color, open: (Screen) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val ctx = LocalContext.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val songs = vm.songs.value
    val t = q.trim().lowercase()
    val res = remember(t, songs, vm.playlists.lists.value) {
        if (t.isEmpty()) null else {
            val sm = songs.filter { it.title.lowercase().contains(t) || it.artist.lowercase().contains(t) }
            val ar = songs.flatMap { ArtistRepository.split(it.artist) }.distinctBy { ArtistRepository.key(it) }
                .filter { it.lowercase().contains(t) }.sortedBy { it.lowercase() }
            val al = songs.groupBy { it.albumId }.values.filter { it[0].album.lowercase().contains(t) || it[0].artist.lowercase().contains(t) }
            val pl = vm.playlists.lists.value.filter { it.name.lowercase().contains(t) }
            listOf(sm, ar, al, pl)
        }
    }
    Box(Modifier.fillMaxSize()) {
        TintBackground(accent, 0.30f)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Text("GROVE MUSIC", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 14.dp))
            Text("search", color = White, fontSize = 56.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 20.dp))
            OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("songs, artists, albums, playlists") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = White) },
                trailingIcon = { if (q.isNotEmpty()) Icon(Icons.Rounded.Close, "clear", tint = White, modifier = Modifier.tilt { q = "" }) },
                shape = androidx.compose.ui.graphics.RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = White, unfocusedBorderColor = White.copy(alpha = 0.5f),
                    cursorColor = White, focusedTextColor = White, unfocusedTextColor = White,
                    focusedContainerColor = Color.Black.copy(alpha = 0.3f), unfocusedContainerColor = Color.Black.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).focusRequester(focus))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                if (res == null) item { Text("type to search your whole library", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp)) }
                else {
                    val (sm, ar, al, pl) = res
                    if (sm.isEmpty() && ar.isEmpty() && al.isEmpty() && pl.isEmpty())
                        item { Text("nothing found for \"$q\"", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp)) }
                    if (ar.isNotEmpty()) {
                        item { SectionLabel("artists", accent) }
                        itemsIndexed((ar as List<String>).take(8)) { _, name ->
                            val info = remember(name, vm.artistTick.intValue) { ArtistRepository.cached(ctx, name) }
                            Row(Modifier.fillMaxWidth().tilt { open(Screen.Artist(name)) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(50.dp).background(lerp(accent, Color.Black, 0.35f)), contentAlignment = Alignment.Center) {
                                    Text(name.take(1).lowercase(), color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                                    if (info?.image != null) AsyncImage(info.image, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                }
                                Text(name.lowercase(), color = White, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
                            }
                        }
                    }
                    if (al.isNotEmpty()) {
                        item { SectionLabel("albums", accent) }
                        itemsIndexed((al as List<List<Song>>).take(10)) { _, l ->
                            Row(Modifier.fillMaxWidth().tilt {
                                open(Screen.Detail(l[0].album, l[0].artist, l.sortedWith(compareBy({ it.track }, { it.title }))))
                            }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Art(l[0].artUri, accent, Modifier.size(50.dp))
                                Column(Modifier.padding(start = 14.dp)) {
                                    Text(l[0].album.lowercase(), color = White, fontSize = 20.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(l[0].artist, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (pl.isNotEmpty()) {
                        item { SectionLabel("playlists", accent) }
                        itemsIndexed(pl as List<Playlist>) { _, p ->
                            Row(Modifier.fillMaxWidth().tilt { open(Screen.PlaylistView(p.id)) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(50.dp).background(accent), contentAlignment = Alignment.Center) {
                                    Icon(if (p.id == PlaylistStore.LIKED) Icons.Rounded.Favorite else Icons.AutoMirrored.Rounded.QueueMusic, null, tint = White)
                                }
                                Text(p.name.lowercase(), color = White, fontSize = 20.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 14.dp))
                            }
                        }
                    }
                    if (sm.isNotEmpty()) {
                        item { SectionLabel("songs · ${sm.size}", accent) }
                        val list = sm as List<Song>
                        itemsIndexed(list.take(100), key = { _, s -> "s${s.id}" }) { i, s ->
                            SongRow(s.title, "${s.artist} · ${s.album}", s.id) { conn.playSongs(list, i); open(Screen.Playing) }
                        }
                    }
                }
            }
        }
    }
}

/** Bottom sheet: pick a playlist (or make a new one) for the given songs. */
@Composable
fun AddToPlaylistSheet(vm: MusicViewModel, songs: List<Long>, accent: Color, onDone: () -> Unit) {
    val store = vm.playlists
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).background(lerp(accent, Color.Black, 0.62f))
        .pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding().imePadding()
        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
        Text("add to playlist", color = White, fontSize = 34.sp, fontWeight = FontWeight.Light)
        Text(if (songs.size == 1) "1 song" else "${songs.size} songs", color = White.copy(alpha = 0.7f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp))
        val one = songs.singleOrNull()?.let { vm.song(it.toString()) }
        if (!naming && one != null) {
            val pk = "song:${one.id}"
            Row(Modifier.fillMaxWidth().tilt { vm.togglePin(pk); onDone() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(accent), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PushPin, null, tint = White) }
                Text(if (vm.isPinned(pk)) "unpin from speed dial" else "pin to speed dial", color = White, fontSize = 21.sp,
                    fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 14.dp))
            }
            Row(Modifier.fillMaxWidth().tilt { onDone(); vm.editTags?.invoke(one.id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).border(2.dp, White), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Edit, null, tint = White) }
                Text("edit info & picture", color = White, fontSize = 21.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 14.dp))
            }
            Row(Modifier.fillMaxWidth().tilt {
                vm.radio(one); onDone()
            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(accent), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Radio, null, tint = White) }
                Text("start radio", color = White, fontSize = 21.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 14.dp))
            }
        }
        if (naming) {
            OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("playlist name") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = White, unfocusedBorderColor = White.copy(alpha = 0.5f),
                    cursorColor = White, focusedTextColor = White, unfocusedTextColor = White))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("create", color = White, fontSize = 18.sp, modifier = Modifier.background(accent).tilt {
                    store.create(name, songs); android.widget.Toast.makeText(ctx, "added to ${name.ifBlank { "new playlist" }}", android.widget.Toast.LENGTH_SHORT).show(); onDone()
                }.padding(horizontal = 20.dp, vertical = 9.dp))
                Text("cancel", color = White, fontSize = 18.sp, modifier = Modifier.border(2.dp, White).tilt { naming = false }
                    .padding(horizontal = 20.dp, vertical = 7.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().tilt { naming = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).border(2.dp, White), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = White) }
                Text("new playlist", color = White, fontSize = 21.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 14.dp))
            }
            LazyColumn {
                itemsIndexed(store.lists.value, key = { _, p -> p.id }) { _, p ->
                    Row(Modifier.fillMaxWidth().tilt {
                        store.add(p.id, songs); android.widget.Toast.makeText(ctx, "added to ${p.name}", android.widget.Toast.LENGTH_SHORT).show(); onDone()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).background(accent), contentAlignment = Alignment.Center) {
                            Icon(if (p.id == PlaylistStore.LIKED) Icons.Rounded.Favorite else Icons.AutoMirrored.Rounded.QueueMusic, null, tint = White)
                        }
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(p.name, color = White, fontSize = 21.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.ids.size} songs", color = White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
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
    // short top level; music keeps its own sections inside (songs, artists, albums…)
    val pages = listOf("home", "music", "videos", "comics", "book", "stats")
    val pager = rememberPagerState(initialPage = UiMemory.page.coerceIn(0, pages.size - 1)) { pages.size }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { UiMemory.page = it } }
    val goto = HomeNav.goto.value
    LaunchedEffect(goto) {
        if (goto != null) { pages.indexOf(goto).takeIf { it >= 0 }?.let { pager.animateScrollToPage(it) }; HomeNav.goto.value = null }
    }
    val deep = BookBridge.deep.values.any { it }
    val cctx = LocalContext.current
    var crash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { crash = withContext(Dispatchers.IO) { CrashReport.pending(cctx) } }
    crash?.let { c ->
        AlertDialog(onDismissRequest = { crash = null }, containerColor = lerp(accent, Color.Black, 0.6f),
            title = { Text("GroveMedia closed unexpectedly", color = White, fontSize = 20.sp) },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) { item { Text(c, color = White, fontSize = 11.sp) } } },
            confirmButton = {
                Text("share", color = White, modifier = Modifier.tilt {
                    cctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, c), "share crash report"))
                }.padding(8.dp))
            },
            dismissButton = { Text("close", color = White, modifier = Modifier.tilt { crash = null }.padding(8.dp)) })
    }
    val scope = rememberCoroutineScope()
    val songs = vm.songs.value
    val play: (List<Song>, Int) -> Unit = { l, i -> conn.playSongs(l, i); open(Screen.Playing) }

    // read only inside graphicsLayer, so swiping never recomposes the screen (smooth at 90/120 Hz)
    val pos: () -> Float = { pager.currentPage + pager.currentPageOffsetFraction }
    var menu by remember { mutableStateOf(false) }
    BackHandler(menu) { menu = false }
    var streamOpen by remember { mutableStateOf(false) }
    if (streamOpen) AlertDialog(onDismissRequest = { streamOpen = false }, containerColor = lerp(accent, Color.Black, 0.6f),
        title = { Text("open network stream", color = White, fontWeight = FontWeight.Light) },
        text = { StreamBox(accent) }, confirmButton = {
            Text("close", color = White, modifier = Modifier.tilt { streamOpen = false }.padding(8.dp)) })
    Box(Modifier.fillMaxSize()) {
    TintBackground(accent)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        AnimatedVisibility(!deep, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { Column {
        // panorama title: huge, light, lowercase, slides slower than the content (parallax)
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            Text("grovemedia", color = White, fontSize = 84.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = -pos() * 70.dp.toPx() }
                    .padding(start = 12.dp, top = 4.dp))
        }
        PivotHeaders(pages, pos, accent, onDrag = { dx -> pager.dispatchRawDelta(-dx * 2.2f) },
            onDragEnd = { scope.launch { pager.animateScrollToPage((pager.currentPage + pager.currentPageOffsetFraction).let { Math.round(it) }, animationSpec = tween(350, easing = WpEase)) } })
        { scope.launch { pager.animateScrollToPage(it, animationSpec = tween(450, easing = WpEase)) } }
        } }
        // the web tab handles its own swipes; leave it by tapping a header
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1, userScrollEnabled = !deep) { p ->
            when (pages[p]) {
                "home" -> HubPage(vm, conn, accent, open)
                "music" -> MusicPage(accent) { sec ->
                    when (sec) {
                        "songs" -> SongsPage(songs, accent, vm.loading.value, askPerms, onShuffle = {
                            conn.playSongs(songs, 0, shuffled = true); open(Screen.Playing)
                        }) { play(songs, it) }
                        "artists" -> ArtistsPage(songs, vm, accent) { open(Screen.Artist(it)) }
                        "albums" -> {
                            val byGenreS = rememberTab("albums_by", "album")
                            val byGenre = byGenreS.value == "genre"
                            Column {
                                Row(Modifier.padding(start = 20.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    listOf(false to "by album", true to "by genre").forEach { (g, l) ->
                                        Text(l, color = if (byGenre == g) White else Dim, fontSize = 14.sp, modifier = Modifier.tilt { byGenreS.value = if (g) "genre" else "album" })
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    if (byGenre) GroupTiles(vm, accent, "genre", { Meta.genreOf(vm.getApplication(), it) }) { n, l -> open(Screen.Detail(n, "genre · ${l.size} songs", l)) }
                                    else AlbumsPage(songs, accent) { list ->
                                        open(Screen.Detail(list[0].album, list[0].artist, list.sortedWith(compareBy({ it.track }, { it.title }))))
                                    }
                                }
                            }
                        }
                        "playlists" -> PlaylistsPage(vm, accent, onMix = { n, l -> open(Screen.Detail(n, "radio mix · ${l.size} songs", l)) }) { open(Screen.PlaylistView(it)) }
                    }
                }
                "videos" -> VideosPage(vm, accent, open)
                "comics" -> ComicsPage(accent)
                "book" -> key(BookBridge.tick.intValue) { BookBridge.embed?.invoke("book", Modifier.fillMaxSize()) }
                "stats" -> StatsPage(vm, accent)
            }
        }
        val undo = vm.lastHidden.value
        if (undo != null && !deep) {
            LaunchedEffect(undo) { kotlinx.coroutines.delay(5000); if (vm.lastHidden.value == undo) vm.lastHidden.value = null }
            Row(Modifier.fillMaxWidth().background(accent).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("moved to hidden", color = White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("undo", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.tilt { vm.hideVideo(undo, false); vm.lastHidden.value = null })
            }
        }
        if (!deep) {
            if (conn.current.value != null) MiniPlayer(conn, accent, onMore = { menu = !menu }, onSearch = { open(Screen.Search) }) { open(Screen.Playing) }
            else AppBar(accent, onSearch = { open(Screen.Search) }) { menu = !menu }
        }
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
                "search" to { open(Screen.Search) },
                "open network stream" to { streamOpen = true },
                "shuffle all" to { conn.playSongs(songs, 0, shuffled = true); open(Screen.Playing) },
                "rescan library" to { vm.rescanAll(cctx); Unit },
                "songs with no info" to { open(Screen.NoInfo) },
                "settings" to { open(Screen.Settings) },
            ).forEach { (label, act) ->
                Text(label, color = White, fontSize = 24.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.fillMaxWidth().tilt { menu = false; act() }.padding(vertical = 9.dp))
            }
        }
    }
    }
}

/** "continue reading": last chapter of each book; tap to jump straight back into the reader */
@Composable
fun ContinueReading(accent: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<BookRead>>(emptyList()) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { Books.recent(ctx) } }
    if (list.isEmpty()) return
    Column(modifier) {
        SectionLabel("continue reading", accent)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(list) { _, b ->
                Column(Modifier.width(100.dp).tilt(onLongClick = { Peek.comic(ctx, b) }) { Books.openReader(ctx, b) }) {
                    Box(Modifier.width(100.dp).height(150.dp).background(lerp(accent, Color.Black, 0.4f))) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = White.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center))
                        if (b.cover != null) AsyncImage(b.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                    Text(b.title, color = White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(b.chapter, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Back up / restore: books (library, history, sources & their settings, app settings) and music & video data. */
@Composable
fun BackupBox(accent: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    fun toast(m: String) = android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_LONG).show()
    val stamp = remember { java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date()) }
    val bookOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
        if (u != null) { BookBridge.backup?.invoke(ctx, u); toast("backing up books & sources… you'll get a notification when it's done") }
    }
    val bookIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { BookBridge.restore?.invoke(ctx, u); toast("restoring books & sources… watch the notification") }
    }
    Column(modifier) {
        SectionLabel("backup & restore", accent)
        val items = listOf(
            Triple("back up books", "library, history, sources & their settings", { bookOut.launch("GroveMedia_comics_$stamp.gmbk") }),
            Triple("restore books", "from a GroveMedia (.gmbk) or older Grove Book (.tachibk) backup", { bookIn.launch(arrayOf("*/*")) }),
        )
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                row.forEachIndexed { i, (t, sub, act) ->
                    Column(Modifier.weight(1f).height(96.dp).background(if (i == 0) lerp(accent, Color.Black, 0.45f) else Color.White.copy(alpha = 0.07f))
                        .tilt(act).padding(10.dp), verticalArrangement = Arrangement.Bottom) {
                        Text(t, color = White, fontSize = 17.sp, fontWeight = FontWeight.Light)
                        Text(sub, color = White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 2, lineHeight = 13.sp)
                    }
                }
            }
        }
    }
}

/** Remembers tabs and sub-tabs for as long as GroveMedia is running (until it's force-closed). */
object UiMemory {
    var page = 0
    val tabs = HashMap<String, String>()
}

/** lets the home page jump to another top tab (and open a comic there) */
object HomeNav {
    val goto = mutableStateOf<String?>(null)
    val comic = mutableStateOf<OManga?>(null)
}

/** a tab choice that survives leaving and reopening the app */
@Composable
fun rememberTab(key: String, default: String): MutableState<String> {
    val st = remember { mutableStateOf(UiMemory.tabs[key] ?: default) }
    LaunchedEffect(st.value) { UiMemory.tabs[key] = st.value }
    return st
}

/**
 * The tabs inside a tab (songs · artists…, library · updates…), as a bar at the BOTTOM of the page
 * where your thumb is: every tab is a big, equal-width target. Swipe sideways on the bar to step tabs.
 */
@Composable
fun SubPivot(items: List<String>, sel: String, accent: Color, badge: (String) -> Int = { 0 }, onSel: (String) -> Unit) {
    val idx = items.indexOf(sel).coerceAtLeast(0)
    var dragged by remember { mutableFloatStateOf(0f) }
    Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f))
        .pointerInput(items, sel) {
            detectHorizontalDragGestures(onDragEnd = {
                val step = when { dragged < -60f -> 1; dragged > 60f -> -1; else -> 0 }
                dragged = 0f
                items.getOrNull(idx + step)?.takeIf { step != 0 }?.let(onSel)
            }) { ch, d -> ch.consume(); dragged += d }
        }) {
        items.forEach { it ->
            val on = it == sel
            Box(Modifier.weight(1f).height(56.dp).tilt { onSel(it) }, contentAlignment = Alignment.Center) {
                Box(Modifier.align(Alignment.TopCenter).height(3.dp).fillMaxWidth(if (on) 0.6f else 0f).background(accent))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = if (on) White else Dim, fontSize = if (items.size > 4) 13.sp else 15.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    val b = badge(it)
                    if (b > 0) Text("$b", color = White, fontSize = 10.sp, modifier = Modifier.padding(start = 3.dp).background(accent).padding(horizontal = 3.dp))
                }
            }
        }
    }
}

/** Comics: Grove Book's library, updates, history, browse and more right here. */
@Composable
fun ComicsPage(accent: Color) {
    val ctx = LocalContext.current
    var sub by rememberTab("comics", "library")
    var updates by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { updates = withContext(Dispatchers.IO) { Books.newChapters(ctx) } }
    val deep = BookBridge.deep.values.any { it }
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(!deep && sub == "library") {
            ContinueReading(accent, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp))
        }
        key(sub) { BookBridge.embed?.invoke(sub, Modifier.weight(1f).fillMaxWidth()) }
        AnimatedVisibility(!deep) {
            SubPivot(listOf("library", "updates", "history", "browse", "more"), sub, accent, { if (it == "updates") updates else 0 }) { sub = it }
        }
    }
}

/** Music: a row of section chips (songs, artists, albums, genres, playlists, no info) over the content. */
@Composable
fun MusicPage(accent: Color, content: @Composable (String) -> Unit) {
    var sec by rememberTab("music", "songs")
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { content(sec) }
        SubPivot(listOf("songs", "artists", "albums", "playlists"), sec, accent) { sec = it }
    }
}

/** Books: the full Grove Book (library, updates, history, browse, downloads) as Metro tiles. */
@Composable
fun BooksPage(accent: Color) {
    val ctx = LocalContext.current
    fun go(action: String?) = Books.open(ctx, action)
    val dark = lerp(accent, Color.Black, 0.45f)
    val tiles = listOf(
        Triple("library", Icons.AutoMirrored.Rounded.MenuBook, "eu.kanade.tachiyomi.SHOW_LIBRARY"),
        Triple("updates", Icons.Rounded.NewReleases, "eu.kanade.tachiyomi.SHOW_RECENTLY_UPDATED"),
        Triple("history", Icons.Rounded.History, "eu.kanade.tachiyomi.SHOW_RECENTLY_READ"),
        Triple("browse", Icons.Rounded.Explore, "eu.kanade.tachiyomi.SHOW_CATALOGUES"),
        Triple("downloads", Icons.Rounded.Download, "eu.kanade.tachiyomi.SHOW_DOWNLOADS"),
        Triple("more", Icons.Rounded.MoreHoriz, null),
    )
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(120.dp).background(accent).tilt { go("eu.kanade.tachiyomi.SHOW_LIBRARY") }) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = White, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).size(40.dp))
                Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    Text("library", color = White, fontSize = 30.sp, fontWeight = FontWeight.Light)
                    Text("your full Grove Book — racks, manga sources, offline books", color = White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(tiles.drop(1)) { i, (label, icon, action) ->
                    Row(Modifier.background(if (i % 2 == 0) dark else accent).tilt { go(action) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = White, modifier = Modifier.size(18.dp))
                        Text(label, color = White, fontSize = 15.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        item { ContinueReading(accent, Modifier.padding(top = 14.dp)) }
        item { BackupBox(accent, Modifier.padding(top = 14.dp)) }
    }
}

/** Minimised Windows Phone application bar: just the "•••" at the right. */
@Composable
fun AppBar(accent: Color, onSearch: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).background(Color.Black.copy(alpha = 0.55f)).background(accent.copy(alpha = 0.12f))
        .tilt(onMore), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        AppBarButton(Icons.Rounded.Search, onSearch)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.MoreHoriz, "more", tint = White, modifier = Modifier.padding(end = 18.dp).size(26.dp))
    }
}

/** Pivot headers that glide with the finger, exactly in step with the pager. */
@Composable
fun PivotHeaders(pages: List<String>, pos: () -> Float, accent: Color, onDrag: (Float) -> Unit = {}, onDragEnd: () -> Unit = {},
                 onSelect: (Int) -> Unit) {
    val widths = remember { mutableStateListOf<Float>().apply { repeat(pages.size) { add(0f) } } }
    val drag by rememberUpdatedState(onDrag); val end by rememberUpdatedState(onDragEnd)
    Box(Modifier.fillMaxWidth().clipToBounds().pointerInput(Unit) {
        detectHorizontalDragGestures(onDragEnd = { end() }, onDragCancel = { end() }) { ch, d -> ch.consume(); drag(d) }
    }) {
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

/** Windows Phone jump list: every letter in a grid; letters you have are lit in the accent colour. */
@Composable
fun JumpGrid(have: Set<Char>, accent: Color, onPick: (Char) -> Unit, onDismiss: () -> Unit) {
    val letters = listOf('#') + ('a'..'z')
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)).pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
        Column(Modifier.align(Alignment.Center).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            letters.chunked(4).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { i, c ->
                        val on = c in have
                        Box(Modifier.feather(r * 4 + i, true).size(72.dp)
                            .background(if (on) accent else Color.White.copy(alpha = 0.08f))
                            .then(if (on) Modifier.tilt { onPick(c) } else Modifier),
                            contentAlignment = Alignment.BottomStart) {
                            Text(c.toString(), color = if (on) White else White.copy(alpha = 0.3f), fontSize = 34.sp,
                                fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** List + jump grid: headers register their item index, tapping a header opens the grid. */
@Composable
fun JumpList(accent: Color, content: @Composable (state: LazyListState, headers: MutableMap<Char, Int>) -> Unit) {
    val state = rememberLazyListState()
    val headers = remember { mutableMapOf<Char, Int>() }
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(open) { open = false }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalJump provides { open = true }) { content(state, headers) }
        AnimatedVisibility(open, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            JumpGrid(headers.keys.toSet(), accent, onPick = { c ->
                open = false; headers[c]?.let { scope.launch { state.scrollToItem(it) } }
            }) { open = false }
        }
    }
}

private fun letterOf(s: String): Char = s.firstOrNull()?.lowercaseChar()?.takeIf { it.isLetter() } ?: '#'

val LocalVm = staticCompositionLocalOf<MusicViewModel?> { null }

/** Tapping a letter tile opens the Windows Phone jump grid. */
val LocalJump = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun LetterTile(c: Char, accent: Color) {
    val jump = LocalJump.current
    Box(Modifier.padding(top = 14.dp, bottom = 6.dp).size(50.dp).then(if (jump != null) Modifier.tilt(jump) else Modifier)
        .background(accent), contentAlignment = Alignment.BottomStart) {
        Text(c.toString(), color = White, fontSize = 30.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
fun SongRow(title: String, sub: String, songId: Long? = null, art: Uri? = null, accent: Color? = null, onClick: () -> Unit) {
    val add = LocalAddToPlaylist.current
    val vm = LocalVm.current
    val ctx = LocalContext.current
    // long press: peek at the song's details (add to playlist is one of the card's buttons)
    Row(Modifier.fillMaxWidth().tilt(onLongClick = songId?.let { id -> {
        val s = vm?.songs?.value?.firstOrNull { it.id == id }
        if (s != null) Peek.song(ctx, s, null, add) else add(listOf(id))
    } }, onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (accent != null) Art(art, lerp(accent, Color.Black, 0.4f), Modifier.size(46.dp).padding(end = 0.dp))
        Column(Modifier.padding(start = if (accent != null) 12.dp else 0.dp)) {
            Text(title, color = White, fontSize = if (accent != null) 19.sp else 23.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
    JumpList(accent) { state, headers ->
    LazyColumn(state = state, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        headers.clear(); var idx = 1
        item { Text("shuffle all (${songs.size})", color = accent, fontSize = 20.sp,
            modifier = Modifier.feather(0, intro).fillMaxWidth().tilt(onShuffle).padding(vertical = 8.dp)) }
        var prev: Char? = null
        var n = 1
        songs.forEachIndexed { i, s ->
            val c = letterOf(s.title)
            if (c != prev) { prev = c; headers[c] = idx++; val k = n++; item(key = "h$i") { Box(Modifier.feather(k, intro)) { LetterTile(c, accent) } } }
            val k = n++; idx++
            item(key = s.id) { Box(Modifier.feather(k, intro)) { SongRow(s.title, "${s.artist} · ${s.album}", s.id, s.artUri, accent) { onPlay(i) } } }
        }
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
    val sync = vm.artistSync.value
    JumpList(accent) { state, headers ->
    LazyColumn(state = state, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        headers.clear(); var idx = if (sync != null) 1 else 0
        if (sync != null) item {
            Column(Modifier.padding(bottom = 6.dp)) {
                Text("downloading artist info ${sync.first} of ${sync.second}", color = Dim, fontSize = 13.sp)
                Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp)) {
                    drawRect(Color.White.copy(alpha = 0.12f)); drawRect(accent, size = size.copy(width = size.width * sync.first / sync.second))
                }
            }
        }
        var prev: Char? = null
        var n = 0
        groups.forEachIndexed { i, (name, count) ->
            val c = letterOf(name)
            if (c != prev) { prev = c; headers[c] = idx++; val j = n++; item(key = "h$i") { Box(Modifier.feather(j, intro)) { LetterTile(c, accent) } } }
            val j = n++; idx++
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
}

@Composable
fun PlaylistsPage(vm: MusicViewModel, accent: Color, onMix: (String, List<Song>) -> Unit, onOpen: (String) -> Unit) {
    val store = vm.playlists
        val ctxM = LocalContext.current
        val mixes = remember(vm.songs.value, store.liked.ids, vm.mixSalt.intValue, vm.metaTick.intValue) {
            Radio.dailyMixes(vm.songs.value, store.liked.ids, vm.mixSalt.intValue,
                { Meta.genreOf(ctxM, it) })
        }
    val intro = rememberIntro()
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        item {
            if (naming) Column(Modifier.padding(vertical = 6.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("playlist name") }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = White.copy(alpha = 0.5f),
                        cursorColor = White, focusedTextColor = White, unfocusedTextColor = White))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("create", color = White, fontSize = 18.sp, modifier = Modifier.background(accent)
                        .tilt { val p = store.create(name); name = ""; naming = false; onOpen(p.id) }.padding(horizontal = 20.dp, vertical = 9.dp))
                    Text("cancel", color = White, fontSize = 18.sp, modifier = Modifier.border(2.dp, White).tilt { naming = false }
                        .padding(horizontal = 20.dp, vertical = 7.dp))
                }
            } else Text("+ new playlist", color = accent, fontSize = 20.sp,
                modifier = Modifier.feather(0, intro).fillMaxWidth().tilt { naming = true }.padding(vertical = 8.dp))
        }
        if (mixes.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("radio mixes · new every day", accent, Modifier.weight(1f))
                    Text("shuffle", color = White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.tilt { vm.newMixes() }.padding(8.dp))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                    itemsIndexed(mixes) { _, (name, list) ->
                        Column(Modifier.width(150.dp).tilt { onMix(name, list) }) {
                            Box(Modifier.size(150.dp)) {
                                // 2x2 cover mosaic
                                val covers = list.distinctBy { it.albumId }.take(4)
                                Column {
                                    for (r in 0..1) Row { for (c in 0..1) {
                                        val s = covers.getOrNull(r * 2 + c) ?: covers.firstOrNull()
                                        Art(s?.artUri, accent, Modifier.size(75.dp))
                                    } }
                                }
                                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.85f)))))
                                Icon(Icons.Rounded.Radio, null, tint = White, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(26.dp))
                            }
                            Text(name, color = White, fontSize = 17.sp, fontWeight = FontWeight.Light, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            Text("${list.size} songs", color = Dim, fontSize = 12.sp)
                        }
                    }
                }
                SectionLabel("your playlists", accent)
            }
        }
        itemsIndexed(store.lists.value, key = { _, p -> p.id }) { i, p ->
            val first = p.ids.firstNotNullOfOrNull { vm.song(it.toString()) }
            Row(Modifier.feather(i + 1, intro).fillMaxWidth().tilt { onOpen(p.id) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp).background(if (p.id == PlaylistStore.LIKED) accent else lerp(accent, Color.Black, 0.4f)),
                    contentAlignment = Alignment.Center) {
                    if (p.id == PlaylistStore.LIKED) Icon(Icons.Rounded.Favorite, null, tint = White, modifier = Modifier.size(34.dp))
                    else if (first != null) Art(first.artUri, accent, Modifier.matchParentSize())
                    else Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = White, modifier = Modifier.size(32.dp))
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(p.name.lowercase(), color = White, fontSize = 26.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.ids.size} ${if (p.ids.size == 1) "song" else "songs"}", color = Dim, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(id: String, vm: MusicViewModel, conn: PlayerConnection, accent: Color, open: (Screen) -> Unit, close: () -> Unit) {
    val store = vm.playlists
    val p = store.get(id) ?: run { LaunchedEffect(Unit) { close() }; return }
    val songs = remember(p, vm.songs.value) { p.ids.mapNotNull { vm.song(it.toString()) } }
    val liked = id == PlaylistStore.LIKED
    var renaming by remember { mutableStateOf(false) }
    var name by remember(p.name) { mutableStateOf(p.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    val play: (Int, Boolean) -> Unit = { i, sh -> if (songs.isNotEmpty()) { conn.playSongs(songs, i, shuffled = sh); open(Screen.Playing) } }
    val intro = rememberIntro()
    Box(Modifier.fillMaxSize()) {
        TintBackground(accent, 0.34f)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                item {
                    Text("PLAYLIST", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                    if (renaming) Column {
                        OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, cursorColor = White,
                                focusedTextColor = White, unfocusedTextColor = White))
                        Text("save", color = White, fontSize = 18.sp, modifier = Modifier.background(accent)
                            .tilt { store.rename(id, name); renaming = false }.padding(horizontal = 20.dp, vertical = 9.dp))
                    } else Text(p.name.lowercase(), color = White, fontSize = 54.sp, fontWeight = FontWeight.Light, maxLines = 1,
                        softWrap = false, modifier = Modifier.feather(0, intro).wrapContentWidth(Alignment.Start, unbounded = true))
                    Text("${songs.size} songs" + if (songs.isNotEmpty()) " · slide a song away to remove it" else "",
                        color = Dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
                    Row(Modifier.feather(1, intro).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("play", color = White, fontSize = 18.sp, modifier = Modifier.background(accent).tilt { play(0, false) }
                            .padding(horizontal = 22.dp, vertical = 10.dp))
                        Text("shuffle", color = White, fontSize = 18.sp, modifier = Modifier.border(2.dp, White).tilt { play(0, true) }
                            .padding(horizontal = 22.dp, vertical = 8.dp))
                    }
                    if (songs.isEmpty()) Text(if (liked) "tap the heart on the player to like a song" else "long-press any song to add it here",
                        color = Dim, fontSize = 16.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(vertical = 20.dp))
                }
                itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                    SwipeToRemove(Modifier.animateItem(), onRemove = { store.remove(id, s.id) }) {
                        Row(Modifier.fillMaxWidth().tilt { play(i, false) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Art(s.artUri, accent, Modifier.size(50.dp))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(s.title, color = White, fontSize = 20.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${s.artist} · ${s.format}", color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (!liked) item {
                    Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("rename", color = accent, fontSize = 17.sp, modifier = Modifier.tilt { renaming = true }.padding(vertical = 6.dp))
                        Text(if (confirmDelete) "tap again to delete" else "delete playlist", color = Color(0xFFFF5A4E), fontSize = 17.sp,
                            modifier = Modifier.tilt { if (confirmDelete) { store.delete(id); close() } else confirmDelete = true }.padding(vertical = 6.dp))
                    }
                }
            }
            if (conn.current.value != null) MiniPlayer(conn, accent) { open(Screen.Playing) }
        }
    }
}

/** Slide a row left or right past a third of the screen to remove it (red bin shows underneath). */
@Composable
fun SwipeToRemove(modifier: Modifier, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val off = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var w by remember { mutableIntStateOf(1) }
    val remove by rememberUpdatedState(onRemove)
    Box(modifier.fillMaxWidth().onSizeChanged { w = it.width }) {
        Row(Modifier.matchParentSize().graphicsLayer { alpha = (abs(off.value) / w * 2.5f).coerceIn(0f, 1f) }
            .background(Color(0xFFE51400)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (off.value >= 0) Arrangement.Start else Arrangement.End) {
            Icon(Icons.Rounded.Delete, "remove", tint = White)
        }
        Box(Modifier.fillMaxWidth()
            .graphicsLayer { translationX = off.value; alpha = 1f - (abs(off.value) / w).coerceIn(0f, 1f) * 0.6f }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(off.value) > w * 0.33f) {
                                off.animateTo(if (off.value > 0) w.toFloat() else -w.toFloat(), tween(180)); remove()
                            } else off.animateTo(0f, tween(300, easing = WpEase))
                        }
                    },
                    onDragCancel = { scope.launch { off.animateTo(0f, tween(300, easing = WpEase)) } }
                ) { ch, d -> ch.consume(); scope.launch { off.snapTo(off.value + d) } }
            }) { content() }
    }
}

/** Genres / countries as Metro tiles with a cover mosaic; tap to see the songs, play or shuffle. */
@Composable
fun GroupTiles(vm: MusicViewModel, accent: Color, what: String, keyOf: (Song) -> String, onOpen: (String, List<Song>) -> Unit) {
    val tick = vm.metaTick.intValue
    val groups = remember(vm.songs.value, tick) {
        vm.songs.value.groupBy(keyOf).entries.sortedWith(compareBy({ it.key == "other" || it.key == "unknown" }, { -it.value.size }))
            .map { it.key to it.value }
    }
    val sync = vm.metaSync.value
    val intro = rememberIntro()
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        if (sync != null) item {
            Text("finding ${what}s online ${sync.first} of ${sync.second}", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (groups.isEmpty()) item { EmptyMsg("nothing here yet") }
        items(groups.chunked(2).size) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                groups.chunked(2)[row].forEachIndexed { i, (name, list) ->
                    Box(Modifier.feather(row * 2 + i, intro).weight(1f).aspectRatio(1f).background(lerp(accent, Color.Black, 0.3f))
                        .tilt { onOpen(name, list) }) {
                        val covers = list.distinctBy { it.albumId }.take(4)
                        Column {
                            for (r in 0..1) Row(Modifier.weight(1f)) { for (c in 0..1) {
                                Art((covers.getOrNull(r * 2 + c) ?: covers.firstOrNull())?.artUri, accent, Modifier.weight(1f).fillMaxHeight())
                            } }
                        }
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.9f)))))
                        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                            Text(name.lowercase(), color = White, fontSize = 20.sp, fontWeight = FontWeight.Light, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${list.size} songs", color = White.copy(alpha = 0.8f), fontSize = 12.sp)
                        }
                    }
                }
                if (groups.chunked(2)[row].size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun hrs(ms: Long): String = when {
    ms >= 3_600_000 -> "%.1f h".format(ms / 3_600_000f)
    else -> "${ms / 60_000} min"
}

/** Listening statistics: hours, chart, favourites, top 10 by period, record day/month/year. */
/** Stats: all · music · videos · comics · book */
@Composable
fun StatsPage(vm: MusicViewModel, accent: Color) {
    var sub by rememberTab("stats", "all")
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (sub) {
                "music" -> MusicStats(vm, accent)
                "videos" -> VideoStats(vm, accent)
                "comics" -> ComicStats(accent)
                "book" -> BookStats(accent)
                else -> AllStats(vm, accent)
            }
        }
        SubPivot(listOf("all", "music", "videos", "comics", "book"), sub, accent) { sub = it }
    }
}

private class AllData(val music: Stats, val watch: Stats, val comics: Books.ReadStats, val book: BookTime)

@Composable
private fun AllStats(vm: MusicViewModel, accent: Color) {
    val ctx = LocalContext.current
    var d by remember { mutableStateOf<AllData?>(null) }
    var period by rememberTab("stats_period", "month")
    LaunchedEffect(Unit) { d = withContext(Dispatchers.IO) {
        AllData(Stats(StatsLog.read(ctx)), Stats(StatsLog.readWatch(ctx)), Books.ReadStats(Books.history(ctx)), BookTime(ctx)) } }
    val x = d ?: run { EmptyMsg("loading…"); return }
    fun ms(s: Stats) = when (period) { "today" -> s.todayMs; "month" -> s.monthMs; "year" -> s.yearMs; else -> s.totalMs }
    val parts = listOf("music" to ms(x.music), "videos" to ms(x.watch), "comics" to x.comics.of(period), "book" to x.book.of(period))
    val total = parts.sumOf { it.second }
    val shades = listOf(1f, 0.6f, 0.35f, 0.18f)
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("today", "month", "year", "all").forEach { p ->
                    Text(if (p == "all") "all time" else if (p == "today") p else "this $p", color = if (period == p) White else Dim, fontSize = 14.sp,
                        modifier = Modifier.tilt { period = p })
                }
            }
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().background(accent).padding(12.dp)) {
                Text(hrs(total), color = White, fontSize = 36.sp, fontWeight = FontWeight.Light)
                Text("total time", color = White.copy(alpha = 0.85f), fontSize = 12.sp)
                Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp)) {
                    parts.forEachIndexed { i, (_, v) ->
                        if (v > 0) Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(lerp(Color.Black, White, shades[i])))
                    }
                    if (total == 0L) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                }
            }
            val subs = listOf("${x.music.top(period, 9999).sumOf { it.second }} plays", "${x.watch.top(period, 9999).size} watched",
                "${x.comics.chapters} chapters", "${x.book.top.size} books")
            parts.chunked(2).forEachIndexed { r, row ->
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { j, (l, v) ->
                        Column(Modifier.weight(1f).background(lerp(accent, Color.Black, 0.55f)).padding(10.dp)) {
                            Text(hrs(v), color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                            Text("$l · ${subs[r * 2 + j]}", color = White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
            SectionLabel("top", accent, Modifier.padding(top = 14.dp))
            @Composable fun TopRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(title, color = White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, color = Dim, fontSize = 11.sp)
                    }
                }
            }
            val songs = vm.songs.value.associateBy { it.id }
            x.music.top(period, 1).firstOrNull()?.let { (id, n, _) -> TopRow(Icons.Rounded.MusicNote, songs[id]?.title ?: "song", "song · $n plays") }
            x.watch.top(period, 1).firstOrNull()?.let { (id, _, m) ->
                TopRow(Icons.Rounded.Movie, vm.videos.value.firstOrNull { it.id == id }?.parsed?.title ?: "video", "video · ${hrs(m)}") }
            x.comics.top.firstOrNull()?.let { (b, m, ch) -> TopRow(Icons.AutoMirrored.Rounded.MenuBook, b.title, "comic · $ch chapters · ${hrs(m)}") }
            x.book.top.firstOrNull()?.let { (t, m, _) -> TopRow(Icons.Rounded.Book, t, "book · ${hrs(m)}") }
            if (total == 0L) Text("nothing yet in this period", color = Dim, fontSize = 14.sp)
        }
    }
}

@Composable
private fun VideoStats(vm: MusicViewModel, accent: Color) {
    val ctx = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        // videos
        item {
            val w = remember { Stats(StatsLog.readWatch(ctx)) }
            if (w.all.isEmpty()) Text("watch something — your video stats will show up here", color = Dim, fontSize = 16.sp)
            else {
                SectionLabel("videos watched", accent, Modifier.padding(top = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("today" to w.todayMs, "this month" to w.monthMs, "this year" to w.yearMs, "all time" to w.totalMs).forEachIndexed { i, (l, v) ->
                        Column(Modifier.weight(1f).background(if (i == 3) accent else lerp(accent, Color.Black, 0.55f)).padding(8.dp)) {
                            Text(hrs(v), color = White, fontSize = 18.sp, fontWeight = FontWeight.Light, maxLines = 1)
                            Text(l, color = White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                val vids = vm.videos.value.associateBy { it.id }
                w.top("all", 5).forEachIndexed { i, (id, _, ms) ->
                    val v = vids[id]
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = if (i < 3) accent else Dim, fontSize = 20.sp, fontWeight = FontWeight.Light, modifier = Modifier.width(30.dp))
                        if (v != null) VideoThumb(v, Modifier.width(80.dp).height(45.dp))
                        Text(v?.let { it.parsed.title + if (it.parsed.isEpisode) " · S${it.parsed.season}E${it.parsed.episode}" else "" } ?: "removed video",
                            color = White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        Text(hrs(ms), color = Dim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicStats(accent: Color) {
    val ctx = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        // books
        item {
            var rs by remember { mutableStateOf<Books.ReadStats?>(null) }
            LaunchedEffect(Unit) { rs = withContext(Dispatchers.IO) { Books.ReadStats(Books.history(ctx)) } }
            if (rs?.chapters == 0) Text("read some comics — your reading stats will show up here", color = Dim, fontSize = 16.sp)
            rs?.takeIf { it.chapters > 0 }?.let { r ->
                SectionLabel("comics read", accent, Modifier.padding(top = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("today" to r.today, "this month" to r.month, "this year" to r.year, "all time" to r.total).forEachIndexed { i, (l, v) ->
                        Column(Modifier.weight(1f).background(if (i == 3) accent else lerp(accent, Color.Black, 0.55f)).padding(8.dp)) {
                            Text(hrs(v), color = White, fontSize = 18.sp, fontWeight = FontWeight.Light, maxLines = 1)
                            Text(l, color = White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                Text("${r.chapters} chapters read", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                r.top.forEachIndexed { i, (b, ms, ch) ->
                    Row(Modifier.fillMaxWidth().tilt { Books.openBook(ctx, b.mangaId) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = if (i < 3) accent else Dim, fontSize = 20.sp, fontWeight = FontWeight.Light, modifier = Modifier.width(30.dp))
                        Box(Modifier.width(34.dp).height(50.dp).background(lerp(accent, Color.Black, 0.4f))) {
                            if (b.cover != null) AsyncImage(b.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        }
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(b.title, color = White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$ch chapters", color = Dim, fontSize = 11.sp)
                        }
                        Text(hrs(ms), color = Dim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookStats(accent: Color) {
    val ctx = LocalContext.current
    var b by remember { mutableStateOf<BookTime?>(null) }
    LaunchedEffect(Unit) { b = withContext(Dispatchers.IO) { BookTime(ctx) } }
    val x = b ?: return
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        item {
            if (x.total == 0L) Text("read a book in the book tab — your reading stats will show up here", color = Dim, fontSize = 16.sp)
            else {
                SectionLabel("books read", accent)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("today" to x.today, "this month" to x.month, "this year" to x.year, "all time" to x.total).forEachIndexed { i, (l, v) ->
                        Column(Modifier.weight(1f).background(if (i == 3) accent else lerp(accent, Color.Black, 0.55f)).padding(8.dp)) {
                            Text(hrs(v), color = White, fontSize = 18.sp, fontWeight = FontWeight.Light, maxLines = 1)
                            Text(l, color = White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                SectionLabel("most read", accent, Modifier.padding(top = 14.dp))
                x.top.forEachIndexed { i, (t, ms, pr) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = if (i < 3) accent else Dim, fontSize = 20.sp, fontWeight = FontWeight.Light, modifier = Modifier.width(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t, color = White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (pr != null) Text("$pr% read", color = Dim, fontSize = 11.sp)
                        }
                        Text(hrs(ms), color = Dim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicStats(vm: MusicViewModel, accent: Color) {
    val ctx = LocalContext.current
    var st by remember { mutableStateOf<Stats?>(null) }
    var period by remember { mutableStateOf("month") }
    var chart by remember { mutableStateOf("days") }
    LaunchedEffect(Unit) { while (true) { st = withContext(Dispatchers.IO) { Stats(StatsLog.read(ctx)) }; kotlinx.coroutines.delay(60_000) } }
    val s = st ?: run { EmptyMsg("loading…"); return }
    val fmtDay = remember { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()) }
    val fmtMonth = remember { java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()) }
    val fmtYear = remember { java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()) }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        if (s.all.isEmpty()) item { Text("play some music — your listening stats will show up here", color = Dim, fontSize = 16.sp) }
        item {
            // headline tiles
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("today" to s.todayMs, "this month" to s.monthMs, "this year" to s.yearMs, "all time" to s.totalMs).forEachIndexed { i, (l, v) ->
                    Column(Modifier.weight(1f).background(if (i == 3) accent else lerp(accent, Color.Black, 0.55f)).padding(8.dp)) {
                        Text(hrs(v), color = White, fontSize = 18.sp, fontWeight = FontWeight.Light, maxLines = 1)
                        Text(l, color = White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("listening", accent, Modifier.weight(1f))
                listOf("days", "months").forEach { c ->
                    Text(c, color = if (chart == c) White else Dim, fontSize = 14.sp, modifier = Modifier.tilt { chart = c }.padding(8.dp))
                }
            }
            val data = if (chart == "days") s.lastDays(30) else s.lastMonths()
            val max = (data.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val w = size.width / data.size
                data.forEachIndexed { i, (_, v) ->
                    val h = size.height * 0.92f * v / max
                    drawRect(if (i == data.lastIndex) accent else accent.copy(alpha = 0.55f),
                        topLeft = androidx.compose.ui.geometry.Offset(i * w + w * 0.12f, size.height - h),
                        size = androidx.compose.ui.geometry.Size(w * 0.76f, h))
                }
                drawLine(Color.White.copy(alpha = 0.2f), androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height))
            }
            Row { Text(if (chart == "days") "30 days ago" else "12 months ago", color = Dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (chart == "days") "today" else "this month", color = Dim, fontSize = 11.sp) }
        }
        // favourite (most played ever)
        s.top("all", 1).firstOrNull()?.let { (id, n, ms) -> vm.song(id.toString())?.let { song ->
            item {
                SectionLabel("favourite song", accent, Modifier.padding(top = 14.dp))
                Row(Modifier.fillMaxWidth().background(lerp(accent, Color.Black, 0.5f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(song.artUri, accent, Modifier.size(72.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(song.title, color = White, fontSize = 20.sp, fontWeight = FontWeight.Light, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, color = White.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1)
                        Text("$n plays · ${hrs(ms)}", color = White, fontSize = 13.sp)
                    }
                }
            }
        } }
        // record day / month / year
        item {
            SectionLabel("records", accent, Modifier.padding(top = 14.dp))
            listOf(Triple("best day", Calendar.DAY_OF_YEAR, fmtDay), Triple("best month", Calendar.MONTH, fmtMonth),
                Triple("best year", Calendar.YEAR, fmtYear)).forEach { (label, f, fmt) ->
                s.best(f)?.let { (ts, ms) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(label, color = Dim, fontSize = 15.sp, modifier = Modifier.width(100.dp))
                        Text(fmt.format(java.util.Date(ts)), color = White, fontSize = 16.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                        Text(hrs(ms), color = accent, fontSize = 16.sp)
                    }
                }
            }
        }
        // top 10 by period
        item {
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("top 10", accent, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("today", "month", "year", "all").forEach { p ->
                    Text(if (p == "all") "all time" else if (p == "today") p else "this $p", color = White, fontSize = 14.sp,
                        modifier = Modifier.background(if (period == p) accent else Color.White.copy(alpha = 0.08f)).tilt { period = p }
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
        val top = s.top(period)
        if (top.isEmpty()) item { Text("nothing played yet in this period", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp)) }
        itemsIndexed(top) { i, (id, n, ms) ->
            val song = vm.song(id.toString())
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = if (i < 3) accent else Dim, fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.width(34.dp))
                Art(song?.artUri, accent, Modifier.size(46.dp))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(song?.title ?: "removed song", color = White, fontSize = 17.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song?.artist.orEmpty(), color = Dim, fontSize = 12.sp, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("$n plays", color = White, fontSize = 13.sp)
                    Text(hrs(ms), color = Dim, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun dayNo(): Int = java.util.Calendar.getInstance().run { get(java.util.Calendar.YEAR) * 1000 + get(java.util.Calendar.DAY_OF_YEAR) }

/** A Windows Phone live tile: shows its front, then flips (top to bottom) to its back now and then, at random. */
@Composable
fun FlipTile(modifier: Modifier, color: Color, onClick: () -> Unit, onLong: (() -> Unit)? = null, front: @Composable BoxScope.() -> Unit, back: @Composable BoxScope.() -> Unit) {
    var flipped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((1500..6000).random().toLong())
        while (true) { flipped = !flipped; kotlinx.coroutines.delay((4000..9000).random().toLong()) }
    }
    val r by animateFloatAsState(if (flipped) 180f else 0f, tween(750, easing = WpEase), label = "flip")
    Box(modifier.tilt(onLong, onClick).graphicsLayer {
        rotationX = if (r < 90f) r else r - 180f; cameraDistance = 14f * density
    }.background(color).clipToBounds()) { if (r < 90f) front() else back() }
}

@Composable
private fun BoxScope.TileText(big: String, small: String) {
    Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
        Text(big, color = White, fontSize = 15.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(small, color = White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** First tab: speed dial, continue listening, rediscover, new arrivals, playlists & mixes, genres. */
@Composable
fun HubPage(vm: MusicViewModel, conn: PlayerConnection, accent: Color, open: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val songs = vm.songs.value
    val byId = remember(songs) { songs.associateBy { it.id } }
    val dark = lerp(accent, Color.Black, 0.45f)
    LaunchedEffect(Unit) { if (vm.videos.value.isEmpty()) vm.loadVideos() }
    val tick = vm.artistTick.intValue
    var plays by remember { mutableStateOf<List<Play>>(emptyList()) }
    LaunchedEffect(songs) { plays = withContext(Dispatchers.IO) { StatsLog.read(ctx) } }
    val play: (List<Song>, Int) -> Unit = { l, i -> if (l.isNotEmpty()) { conn.playSongs(l, i); open(Screen.Playing) } }
    val intro = rememberIntro()

    // speed dial: your pins first, then your most played singers / songs
    var dialAuto by remember { mutableStateOf(Prefs(ctx).dialAuto) }
    var pinAsk by remember { mutableStateOf<String?>(null) }
    pinAsk?.let { k ->
        val s0 = byId[k.substringAfter(':').toLongOrNull()]
        val pinnedNow = vm.isPinned(k)
        AlertDialog(onDismissRequest = { pinAsk = null }, containerColor = lerp(accent, Color.Black, 0.6f),
            title = { Text(if (pinnedNow) "remove from speed dial?" else "pin to speed dial?", color = White, fontWeight = FontWeight.Light) },
            text = { Text(s0?.let { "${it.title} · ${it.artist}" } ?: "", color = White.copy(alpha = 0.85f)) },
            confirmButton = { Text(if (pinnedNow) "remove" else "pin", color = White, fontSize = 16.sp,
                modifier = Modifier.background(accent).tilt { vm.togglePin(k); pinAsk = null }.padding(horizontal = 18.dp, vertical = 8.dp)) },
            dismissButton = { Text("cancel", color = White, fontSize = 16.sp,
                modifier = Modifier.border(2.dp, White).tilt { pinAsk = null }.padding(horizontal = 16.dp, vertical = 6.dp)) })
    }
    // speed dial is songs only: your pins first, then (if auto) your most played songs
    val dial = remember(vm.pins.value, plays, songs, dialAuto) {
        val pinned = vm.pins.value.filter { it.startsWith("song:") }
        val auto = plays.groupBy { it.id }.entries.sortedByDescending { e -> e.value.sumOf { it.ms } }.mapNotNull { byId[it.key] }.map { "song:${it.id}" }
        val fill = auto.ifEmpty { songs.shuffled(kotlin.random.Random(dayNo())).take(12).map { "song:${it.id}" } }
        (pinned + fill).distinct().take(12)
    }
    val recent = remember(plays, songs) { plays.sortedByDescending { it.ts }.mapNotNull { byId[it.id] }.distinctBy { it.id }.take(15) }
    val rediscover = remember(plays, songs) {
        val last = plays.groupBy { it.id }.mapValues { e -> e.value.maxOf { it.ts } }
        val cutoff = System.currentTimeMillis() - 30L * 86_400_000
        songs.filter { (last[it.id] ?: 0L) < cutoff }.shuffled(kotlin.random.Random(dayNo())).take(30)
    }
    val fresh = remember(songs) {
        val cutoff = System.currentTimeMillis() / 1000 - 30L * 86_400
        songs.filter { it.added >= cutoff }.sortedByDescending { it.added }.take(60).shuffled(kotlin.random.Random(dayNo())).take(30)
    }
    var askRediscover by remember { mutableStateOf(Prefs(ctx).rediscoverSkip != dayNo()) }

    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        item { HomePicks(vm, conn, accent, open) }
        item {
            SectionLabel("speed dial", accent)
            if (dial.isEmpty()) Text("nothing pinned yet — long-press any song and choose \"pin to speed dial\"", color = Dim, fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 10.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 8.dp
                val u = (maxWidth - gap * 2) / 3
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    dial.chunked(3).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEachIndexed { c, key ->
                                val (type, v) = key.substringBefore(':') to key.substringAfter(':')
                                val m = Modifier.feather(r * 3 + c, intro).size(u)
                                val pinned = key in vm.pins.value
                                when (type) {
                                    "song" -> byId[v.toLongOrNull()]?.let { s ->
                                        val photo = remember(s.artist, tick) { ArtistRepository.cached(ctx, ArtistRepository.primary(s.artist))?.image }
                                        FlipTile(m, if (pinned) accent else dark, { play(listOf(s) + Radio.build(s, songs).drop(1), 0) }, { pinAsk = key },
                                            { Art(s.artUri, accent, Modifier.matchParentSize())
                                              if (pinned) Icon(Icons.Rounded.PushPin, null, tint = White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp)) },
                                            {   // back: the singer's photo with the song name
                                                if (photo != null) AsyncImage(photo, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
                                                TileText(s.title, s.artist + if (s.year > 0) " · ${s.year}" else "") })
                                    }
                                    "album" -> songs.filter { it.albumId.toString() == v }.takeIf { it.isNotEmpty() }?.let { l ->
                                        FlipTile(m, if (pinned) accent else dark, { open(Screen.Detail(l[0].album, l[0].artist, l)) }, null,
                                            { Art(l[0].artUri, accent, Modifier.matchParentSize()) },
                                            { TileText(l[0].album.lowercase(), "${l[0].artist} · ${l.size} songs") })
                                    }
                                    "artist" -> {
                                        val info = remember(v, tick) { ArtistRepository.cached(ctx, v) }
                                        val l = songs.filter { ArtistRepository.split(it.artist).any { a -> ArtistRepository.key(a) == ArtistRepository.key(v) } }
                                        FlipTile(m, if (pinned) accent else dark, { open(Screen.Artist(v)) }, null, {
                                            if (info?.image != null) AsyncImage(info.image, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                            else Art(l.firstOrNull()?.artUri, accent, Modifier.matchParentSize())
                                            Text(v.lowercase(), color = White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart)
                                                .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 2.dp))
                                        }, {
                                            TileText(v.lowercase(), listOfNotNull("${l.size} songs",
                                                info?.fans?.takeIf { it > 0 }?.let { "${it / 1000}k fans" },
                                                info?.releases?.size?.takeIf { it > 0 }?.let { "$it releases" }).joinToString(" · "))
                                        })
                                    }
                                    "playlist" -> vm.playlists.get(v)?.let { p ->
                                        val l = p.ids.mapNotNull { byId[it] }
                                        FlipTile(m, if (pinned) accent else dark, { open(Screen.PlaylistView(p.id)) }, null,
                                            { Art(l.firstOrNull()?.artUri, accent, Modifier.matchParentSize()) },
                                            { TileText(p.name.lowercase(), "${l.size} songs") })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text("long-press a tile to pin it (accent = pinned, stays put) or unpin it", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (recent.isNotEmpty()) item {
            SectionLabel("continue listening", accent, Modifier.padding(top = 14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(recent) { i, s ->
                    Column(Modifier.width(120.dp).tilt { play(recent, i) }) {
                        Art(s.artUri, accent, Modifier.size(120.dp))
                        Text(s.title, color = White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(s.artist, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // videos: continue watching + recently added
        val vids = vm.videos.value
        if (vids.isNotEmpty()) item {
            val pos = remember(vids) { ctx.getSharedPreferences("video_pos", 0) }
            val watching = remember(vids) { vids.filter { pos.getLong(it.id.toString(), 0) > 5000 } }
            val newest = remember(vids) { vids.sortedByDescending { it.added }.take(12) }
            val row: @Composable (String, List<Video>) -> Unit = { label, l ->
                SectionLabel(label, accent, Modifier.padding(top = 14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(l) { _, v ->
                        Column(Modifier.width(170.dp).tilt {
                            if (v.parsed.isEpisode) open(Screen.Show(v.parsed.title)) else open(Screen.VideoInfo(v.id))
                        }) {
                            Box(Modifier.width(170.dp).height(96.dp)) {
                                VideoThumb(v, Modifier.matchParentSize())
                                val p = pos.getLong(v.id.toString(), 0)
                                if (p > 0 && v.duration > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p.toFloat() / v.duration).height(3.dp).background(accent))
                            }
                            Text(v.parsed.title + (if (v.parsed.isEpisode) " · S${v.parsed.season}E${v.parsed.episode}" else ""), color = White, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            Column {
                if (watching.isNotEmpty()) row("continue watching", watching)
                row("new videos", newest)
            }
        }
        item { HomeForToday(vm, accent) }
        item { ContinueReading(accent, Modifier.padding(top = 14.dp)) }
        if (rediscover.size >= 5) item {
            Column(Modifier.padding(top = 18.dp).fillMaxWidth().background(Brush.horizontalGradient(listOf(accent, lerp(accent, Color.Black, 0.5f)))).padding(14.dp)) {
                Text("rediscover?", color = White, fontSize = 28.sp, fontWeight = FontWeight.Light)
                Text("${rediscover.size} songs you haven't played in a month or more — want to hear them again?", color = White.copy(alpha = 0.85f), fontSize = 14.sp)
                // small covers of what's inside
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rediscover.distinctBy { it.albumId }.take(7).forEach { Art(it.artUri, accent, Modifier.size(40.dp)) }
                }
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<Triple<String, Boolean, () -> Unit>>(
                        Triple("play them", true) { play(rediscover, 0) },
                        Triple("see list", false) { open(Screen.Detail("rediscover", "songs you haven't played lately", rediscover)) },
                    ).forEach { (label, main, act) ->
                        Box(Modifier.weight(1f).fillMaxHeight().then(if (main) Modifier.background(White) else Modifier.border(2.dp, White)).tilt(act),
                            contentAlignment = Alignment.Center) {
                            Text(label, color = if (main) Color.Black else White, fontSize = 15.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (fresh.isNotEmpty()) item {
            SectionLabel("new arrivals · daily", accent, Modifier.padding(top = 14.dp))
            Row(Modifier.fillMaxWidth().background(dark).tilt { open(Screen.Detail("new arrivals", "songs added in the last 30 days · new mix daily", fresh)) }
                .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row { fresh.distinctBy { it.albumId }.take(3).forEach { Art(it.artUri, accent, Modifier.size(56.dp)) } }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("new for you", color = White, fontSize = 20.sp, fontWeight = FontWeight.Light)
                    Text("${fresh.size} songs that just arrived on your phone", color = White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
                Icon(Icons.Rounded.PlayArrow, "play", tint = White, modifier = Modifier.size(40.dp).border(2.dp, White, CircleShape).tilt { play(fresh, 0) }.padding(6.dp))
            }
        }
        item {
            SectionLabel("playlists & mixes", accent, Modifier.padding(top = 14.dp))
            val mixes = remember(songs, vm.mixSalt.intValue, vm.metaTick.intValue) {
                Radio.dailyMixes(songs, vm.playlists.liked.ids, vm.mixSalt.intValue, { Meta.genreOf(ctx, it) })
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(vm.playlists.lists.value) { _, p ->
                    val l = p.ids.mapNotNull { byId[it] }
                    Column(Modifier.width(120.dp).tilt { open(Screen.PlaylistView(p.id)) }) {
                        Box(Modifier.size(120.dp).background(if (p.id == PlaylistStore.LIKED) accent else dark), contentAlignment = Alignment.Center) {
                            if (p.id == PlaylistStore.LIKED) Icon(Icons.Rounded.Favorite, null, tint = White, modifier = Modifier.size(40.dp))
                            else Art(l.firstOrNull()?.artUri, accent, Modifier.matchParentSize())
                        }
                        Text(p.name.lowercase(), color = White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text("${l.size} songs", color = Dim, fontSize = 11.sp)
                    }
                }
                itemsIndexed(mixes) { _, (name, l) ->
                    Column(Modifier.width(120.dp).tilt { open(Screen.Detail(name, "radio mix · ${l.size} songs", l)) }) {
                        Box(Modifier.size(120.dp)) {
                            Art(l.firstOrNull()?.artUri, accent, Modifier.matchParentSize())
                            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.85f)))))
                            Icon(Icons.Rounded.Radio, null, tint = White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(22.dp))
                        }
                        Text(name, color = White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text("${l.size} songs", color = Dim, fontSize = 11.sp)
                    }
                }
            }
        }
        item {
            val genres = remember(songs, vm.metaTick.intValue) {
                songs.groupBy { Meta.genreOf(ctx, it) }.filterKeys { it != "other" }.entries.sortedByDescending { it.value.size }.take(12)
            }
            if (genres.isNotEmpty()) {
                SectionLabel("genres", accent, Modifier.padding(top = 14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(genres) { i, (g, l) ->
                        Box(Modifier.size(110.dp, 70.dp).background(if (i % 2 == 0) accent else dark)
                            .tilt { open(Screen.Detail(g, "genre · ${l.size} songs", l)) }) {
                            Text(g, color = White, fontSize = 17.sp, fontWeight = FontWeight.Light, maxLines = 2,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Browse your music by folder: tap into folders, play everything inside, back goes up a level. */
@Composable
fun FoldersPage(vm: MusicViewModel, accent: Color, active: Boolean, onPlay: (List<Song>, Int) -> Unit, onPlayAll: (String, List<Song>) -> Unit) {
    val songs = vm.songs.value
    val root = remember(songs) {
        // deepest folder that contains every music folder (usually /storage/emulated/0)
        val paths = songs.map { it.folder }.filter { it.startsWith("/") }.distinct()
        if (paths.isEmpty()) "/" else paths.reduce { a, b ->
            var i = 0; val pa = a.split('/'); val pb = b.split('/')
            while (i < pa.size && i < pb.size && pa[i] == pb[i]) i++
            pa.take(i).joinToString("/").ifEmpty { "/" }
        }
    }
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    val cur = path ?: root
    BackHandler(active && cur != root) { path = cur.substringBeforeLast('/').takeIf { it.length >= root.length } ?: root }
    val inside = remember(songs, cur) { songs.filter { it.folder == cur || it.folder.startsWith("$cur/") } }
    val subs = remember(inside, cur) {
        inside.filter { it.folder != cur }.groupBy { cur + "/" + it.folder.removePrefix("$cur/").substringBefore('/') }
            .entries.sortedBy { it.key.lowercase() }
    }
    val here = remember(inside, cur) { inside.filter { it.folder == cur }.sortedBy { it.title.lowercase() } }
    val intro = rememberIntro()
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        item {
            Text(cur.removePrefix(root).ifEmpty { "phone storage" }.trim('/').replace("/", "  ›  ").ifEmpty { "phone storage" },
                color = Dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (cur != root) Text("‹ up", color = accent, fontSize = 18.sp,
                    modifier = Modifier.tilt { path = cur.substringBeforeLast('/').takeIf { it.length >= root.length } ?: root }.padding(vertical = 4.dp))
                if (inside.isNotEmpty()) Text("play all ${inside.size}", color = accent, fontSize = 18.sp,
                    modifier = Modifier.tilt { onPlayAll(cur.substringAfterLast('/').ifEmpty { "phone storage" }, inside) }.padding(vertical = 4.dp))
            }
        }
        itemsIndexed(subs, key = { _, e -> "d" + e.key }) { i, (dir, list) ->
            Row(Modifier.feather(i, intro).fillMaxWidth().tilt { path = dir }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).background(lerp(accent, Color.Black, 0.35f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Folder, null, tint = White)
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(dir.substringAfterLast('/').lowercase(), color = White, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${list.size} songs", color = Dim, fontSize = 13.sp)
                }
            }
        }
        itemsIndexed(here, key = { _, s -> s.id }) { i, s ->
            SongRow(s.title, "${s.artist} · ${s.format}", s.id) { onPlay(here, i) }
        }
    }
}

/** Last tab: songs with no cover, artist or album — tap one to edit it, or fix them all online. */
@Composable
fun MissingPage(vm: MusicViewModel, accent: Color) {
    var list by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(vm.songs.value) { list = vm.findMissing() }
    val ctx = LocalContext.current
    val fix = vm.fixJob.value
    val writeAll = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) vm.fixMissing(vm.missingCache)
    }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        val l = list
        item {
            Text(when { l == null -> "checking your songs…"; l.isEmpty() -> "every song has its title, artist, album and cover"
                else -> "${l.size} songs missing a cover, artist or album" }, color = Dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            if (!l.isNullOrEmpty()) Text(fix?.let { "fixing ${it.first + 1} of ${it.second}…" } ?: "fix all online", color = White, fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 10.dp).background(accent).tilt {
                    if (fix != null) return@tilt
                    vm.missingCache = l
                    if (Build.VERSION.SDK_INT >= 30) {
                        val pi = android.provider.MediaStore.createWriteRequest(ctx.contentResolver, l.map { it.uri })
                        writeAll.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
                    } else vm.fixMissing(l)
                }.padding(horizontal = 20.dp, vertical = 9.dp))
            vm.fixResult.value?.let { Text(it, color = White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        }
        if (l != null) itemsIndexed(l, key = { _, s -> s.id }) { _, s ->
            val missing = buildList {
                if (s.artist == "unknown artist") add("artist"); if (s.album == "unknown album") add("album")
            }
            Row(Modifier.fillMaxWidth().tilt { vm.editTags?.invoke(s.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(s.artUri, accent, Modifier.size(50.dp))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(s.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("no " + (missing.ifEmpty { listOf("cover") } + if (missing.isNotEmpty()) emptyList() else emptyList()).joinToString(", ") +
                        " · " + s.displayName, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.Edit, "edit", tint = White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
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
        var cross by remember { mutableFloatStateOf(prefs.crossfade.toFloat()) }
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text("crossfade", color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Text(if (cross < 1f) "off — songs change straight away" else "songs blend into each other over ${cross.toInt()} seconds",
                color = Dim, fontSize = 14.sp)
            Slider(cross, { cross = it }, valueRange = 0f..12f, steps = 11, onValueChangeFinished = { prefs.crossfade = cross.toInt() },
                colors = SliderDefaults.colors(thumbColor = White, activeTrackColor = accent, inactiveTrackColor = White.copy(alpha = 0.2f)))
        }
        var muteP by remember { mutableStateOf(prefs.pauseOnMute) }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("pause when muted", color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                Text("volume all the way down pauses; turning it up plays again", color = Dim, fontSize = 14.sp)
            }
            Switch(muteP, { muteP = it; prefs.pauseOnMute = it }, colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = White))
        }
        var tmdb by remember { mutableStateOf(prefs.tmdbKey) }
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text("movie info (TMDB key)", color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Text("free key from themoviedb.org → settings → API. Needed for movies; TV shows work without it", color = Dim, fontSize = 14.sp)
            OutlinedTextField(tmdb, { tmdb = it; prefs.tmdbKey = it.trim() }, singleLine = true, placeholder = { Text("paste API key") },
                shape = androidx.compose.ui.graphics.RectangleShape, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, cursorColor = White, focusedTextColor = White, unfocusedTextColor = White))
        }
        var carLyrics by remember { mutableStateOf(prefs.carLyrics) }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("lyrics in Android Auto", color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                Text("in the car, the line being sung shows big as the title with the next line under it", color = Dim, fontSize = 14.sp)
            }
            Switch(carLyrics, { carLyrics = it; prefs.carLyrics = it },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = White))
        }
        // ── fix songs with missing info
        val fix = vm.fixJob.value
        val writeAll = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            if (r.resultCode == android.app.Activity.RESULT_OK) vm.fixMissing(vm.missingCache)
        }
        val ctxF = LocalContext.current
        val scopeF = rememberCoroutineScope()
        SettingItem("fix songs with missing info",
            fix?.let { "fixing ${it.first} of ${it.second}…" }
                ?: vm.fixResult.value ?: "find title, artist, album and cover online for songs without them") {
            if (fix != null) return@SettingItem
            scopeF.launch {
                val list = vm.findMissing()
                if (list.isEmpty()) { android.widget.Toast.makeText(ctxF, "every song has its info and cover", android.widget.Toast.LENGTH_SHORT).show(); return@launch }
                vm.missingCache = list
                if (Build.VERSION.SDK_INT >= 30) {
                    val pi = android.provider.MediaStore.createWriteRequest(ctxF.contentResolver, list.map { it.uri })
                    writeAll.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
                } else vm.fixMissing(list)
            }
        }
        // ── sound: bit-perfect, hi-res, gapless, DSD
        SectionLabel("sound", accent, Modifier.padding(top = 18.dp))
        @Composable fun Toggle(title: String, sub: String, init: Boolean, set: (Boolean) -> Unit) {
            var v by remember { mutableStateOf(init) }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                    Text(sub, color = Dim, fontSize = 14.sp)
                }
                Switch(v, { v = it; set(it) }, colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = White))
            }
        }
        Toggle("bit-perfect USB DAC", "send music untouched to a USB DAC at the song's own sample rate (Android 14+). Volume is then set on the DAC; crossfade pauses",
            prefs.bitPerfect) { prefs.bitPerfect = it }
        Toggle("hi-res audio path", "keep 24-bit and high sample rates all the way to USB DACs and LDAC / aptX Lossless / LE Audio earbuds instead of cutting to 16-bit. Applies after reopening GroveMedia",
            prefs.hiRes) { prefs.hiRes = it }
        Toggle("gapless albums", "songs from the same album play straight into each other without a fade", prefs.gaplessAlbums) { prefs.gaplessAlbums = it }
        var dsdDirs by remember { mutableStateOf(Dsd.folders(ctx)) }
        val pickDsd = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
            if (u != null) { Dsd.addFolder(ctx, u); dsdDirs = Dsd.folders(ctx); vm.load() }
        }
        SettingItem("DSD folders", if (dsdDirs.isEmpty()) "add a folder with .dsf / .dff files (DSD64–DSD512 play as 24-bit PCM)" else "${dsdDirs.size} folder(s) · tap to add another") { pickDsd.launch(null) }
        dsdDirs.forEach { u ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Uri.decode(u.lastPathSegment ?: "").substringAfterLast(':'), color = Dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("remove", color = accent, fontSize = 14.sp, modifier = Modifier.tilt { Dsd.removeFolder(ctx, u); dsdDirs = Dsd.folders(ctx); vm.load() }.padding(6.dp))
            }
        }
        // ── video engine
        SectionLabel("video player", accent, Modifier.padding(top = 18.dp))
        var eng by remember { mutableStateOf(prefs.videoEngine) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            listOf("auto" to "auto", "vlc" to "VLC", "exo" to "hardware").forEach { (k, l) ->
                Text(l, color = White, fontSize = 15.sp, modifier = Modifier.background(if (eng == k) accent else Color.White.copy(alpha = 0.08f))
                    .tilt { eng = k; prefs.videoEngine = k }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        val allFiles = remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }
        SettingItem("find every video (like VLC)", if (allFiles.value) "on — videos Android doesn't list (rmvb, vob, iso, mxf…) are found too · rescan to refresh"
            else "allow all-files access so GroveMedia can find every format VLC plays, not only the ones Android lists") {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}"))) }
        }
        Text("auto: 4K / 8K videos use the phone's hardware decoder (HEVC, AV1, 10-bit, HDR), everything else uses VLC. VLC plays the most formats; hardware handles the biggest videos.",
            color = Dim, fontSize = 12.sp)
        Toggle("swipe down to mini player", "swipe the now playing screen or a video down to shrink it, like the YouTube app (videos float in picture-in-picture)",
            prefs.swipeMini) { prefs.swipeMini = it }
        // ── icon style
        SectionLabel("icon style", accent, Modifier.padding(top = 18.dp))
        var iconStyle by remember { mutableStateOf(prefs.iconStyle) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            IconSwitcher.styles.forEach { st ->
                Text(st, color = White, fontSize = 15.sp, modifier = Modifier.background(if (iconStyle == st) accent else Color.White.copy(alpha = 0.08f))
                    .tilt { iconStyle = st; prefs.iconStyle = st; IconSwitcher.apply(ctx, prefs.accent) }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        Text("colour follows your accent; neon, mono and glass are fixed styles. The launcher may take a moment to update.", color = Dim, fontSize = 12.sp)
        // ── artist info
        val sync = vm.artistSync.value
        val tick = vm.artistTick.intValue
        val names = remember(vm.songs.value) { vm.artistNames() }
        val ctx = LocalContext.current
        val have = remember(names, tick) { ArtistRepository.cachedCount(ctx, names) }
        SettingItem("download all artist info",
            if (sync != null) "downloading ${sync.first} of ${sync.second}…"
            else "$have of ${names.size} artists saved for offline · photos, bios and discographies") { vm.syncArtists() }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("hide duplicate songs", color = White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                Text("same song in several files or formats — show only the best quality one", color = Dim, fontSize = 14.sp)
            }
            Switch(vm.preferFlac.value, { vm.setPreferFlac(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = White))
        }
        SettingItem("rescan library", "look for new music, videos and books, and new comic chapters") { vm.rescanAll(ctx) }
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
        val hiddenV = vm.hiddenItems.value
        if (hiddenV.isNotEmpty()) SettingItem("hidden videos (${hiddenV.size})", "tap to show them all again") {
            hiddenV.forEach { vm.hideVideo(it, false) }
        }
        Text("video folders", color = White, fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 14.dp))
        Text("tick the folders the videos tab should show", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        LaunchedEffect(Unit) { if (vm.videoFolders.value.isEmpty()) vm.loadVideos() }
        val hv = vm.hiddenVideo.value
        vm.videoFolders.value.forEach { (f, n) ->
            val shown = f !in hv
            Row(Modifier.fillMaxWidth().tilt { vm.setVideoFolder(f, !shown) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).border(2.dp, White).background(if (shown) accent else Color.Transparent), contentAlignment = Alignment.Center) {
                    if (shown) Icon(Icons.Rounded.Check, null, tint = White, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(f.substringAfterLast('/').ifEmpty { f }, color = if (shown) White else Dim, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$n videos · ${f.removePrefix("/storage/emulated/0")}", color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        var about by remember { mutableStateOf(false) }
        val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
        SettingItem("about", "GroveMedia $ver · tap to see what's new") { about = true }
        if (about) AboutDialog(ver, accent) { about = false }
        Spacer(Modifier.height(24.dp))
    }
    }
}

private val CHANGES = listOf(
    "one app" to "music, videos, comics and books together — Grove Music and Grove Book joined as GroveMedia",
    "comics" to "Grove Book's library, updates, history, browse and more as tabs inside GroveMedia",
    "book" to "offline books (epub, pdf, cbz…) from your folders in their own tab",
    "stats" to "all · music · videos · comics · book, with total time split by media",
    "videos" to "VLC player, picture-in-picture, audio-only, pinch zoom-to-fill, immersive full screen",
    "video info" to "posters, cast and episodes from TMDB / TVmaze, YouTube matches, rename files",
    "hidden" to "hide videos or shows — they move to videos → hidden, with undo",
    "favourites" to "heart videos and shows for a favourites tab",
    "network stream" to "play any video link from the ••• menu",
    "open with" to "set GroveMedia as default player for music and video files",
    "music" to "songs · artists · albums · playlists, album covers on every song, genres in albums",
    "lyrics" to "synced lyrics from LRCLIB, NetEase, Kugou and QQ Music, plus .lrc files and lyrics tags in any song file — the line being sung glows",
    "radio & mixes" to "endless radio from any song, daily mixes, rediscover",
    "crossfade" to "smooth crossfade between songs; skipping does a quick fade with no jump",
    "tag editor" to "edit song info and artwork, even on damaged tags",
    "speed dial" to "pin songs, albums, artists and playlists to home",
    "android auto" to "browse playlists, mixes and songs in the car",
    "backup" to "back up and restore your comics library, sources and settings",
    "new look" to "new GroveMedia icon and name everywhere — installer, app info, notifications",
    "sound" to "bit-perfect USB DAC output, hi-res 24-bit path for USB DACs and lossless earbuds, gapless albums",
    "DSD" to "plays .dsf / .dff (DSD64–DSD512) as 24-bit PCM, from your music or picked folders",
    "A-B loop" to "repeat any part of a song from the now playing screen",
    "widgets" to "pill, wide and square home screen widgets plus a quick settings play tile",
    "icon styles" to "colour, neon, mono and glass app icons",
    "now playing" to "heart stays on the right, title uses the full width",
    "rescan" to "rescan library now covers music, videos, books and new comic chapters",
    "picture-in-picture" to "play / pause button in the floating video updates right away",
    "4K / 8K video" to "4K / 8K videos use the phone's hardware decoder inside the same GroveMedia player — all controls stay",
    "hide" to "hide a video or show from its ••• menu, next to rename file",
    "every format" to "plays everything VLC plays (mkv, avi, rmvb, vob, iso, ts, webm, wmv…); optional all-files access finds unlisted videos",
    "car lyrics" to "Android Auto shows the line being sung big, with the next line under it",
    "mini player" to "swipe now playing or a video down to shrink it, like YouTube",
    "stays put" to "reopening GroveMedia keeps your tab and sub-tab, until the app is force-closed",
    "watch progress" to "videos show a progress line of how far you've watched",
    "stability" to "fixed random crashes — comics data is now read through Grove Book's own connection",
    "crash report" to "if GroveMedia closes unexpectedly, the reason shows next time you open it",
)

@Composable
fun AboutDialog(ver: String, accent: Color, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = lerp(accent, Color.Black, 0.6f),
        title = { Column { Text("GroveMedia", color = White, fontSize = 30.sp, fontWeight = FontWeight.Light); Text("version $ver", color = Dim, fontSize = 13.sp) } },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text("made by Harith · built with Claude by Anthropic", color = White, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                    SectionLabel("what's new", accent)
                }
                itemsIndexed(CHANGES) { _, (t, d) ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(t, color = accent, fontSize = 15.sp)
                        Text(d, color = White, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { Text("close", color = White, modifier = Modifier.tilt(onDismiss).padding(8.dp)) })
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
fun MiniPlayer(conn: PlayerConnection, accent: Color, onMore: (() -> Unit)? = null, onSearch: (() -> Unit)? = null, onOpen: () -> Unit) {
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
            if (onSearch != null) AppBarButton(Icons.Rounded.Search, onSearch)
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
fun DetailScreen(d0: Screen.Detail, conn: PlayerConnection, accent: Color, open: (Screen) -> Unit) {
    val intro = rememberIntro()
    val ctx = LocalContext.current
    // a single album: put its songs in the official order from the internet (cached for offline)
    val isAlbum = d0.songs.isNotEmpty() && d0.songs.all { it.albumId == d0.songs[0].albumId }
    var songs by remember(d0) { mutableStateOf(
        if (isAlbum) AlbumOrder.sort(d0.songs, AlbumOrder.cached(ctx, d0.songs[0].album, d0.songs[0].artist)) else d0.songs) }
    if (isAlbum) LaunchedEffect(d0) {
        AlbumOrder.load(ctx, d0.songs[0].album, d0.songs[0].artist)?.let { songs = AlbumOrder.sort(d0.songs, it) }
    }
    val d = d0.copy(songs = songs)
    Box(Modifier.fillMaxSize()) {
    TintBackground(accent, 0.30f)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Text(d.sub.uppercase(), color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp))
        Text(d.title.lowercase(), color = White, fontSize = 50.sp, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Clip, modifier = Modifier.padding(start = 16.dp).wrapContentWidth(Alignment.Start, unbounded = true))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("play all", color = accent, fontSize = 20.sp,
                        modifier = Modifier.feather(0, intro).weight(1f).tilt { conn.playSongs(d.songs, 0); open(Screen.Playing) }.padding(vertical = 10.dp))
                }
            }
            itemsIndexed(d.songs, key = { _, s -> s.id }) { i, s ->
                Box(Modifier.feather(i + 1, intro)) {
                    SongRow(s.title, "${s.album} · ${s.format}", s.id) { conn.playSongs(d.songs, i); open(Screen.Playing) }
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
    val artistName = (song?.artist ?: md.artist ?: "unknown artist").toString()

    // swipe anywhere (except the seek bar) to change song; the cover follows the finger
    val drag = remember { Animatable(0f) }
    LaunchedEffect(item.mediaId) { drag.snapTo(0f) } // the new song's cover starts centred
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

    // swipe down = shrink to the mini player (like YouTube)
    val down = remember { Animatable(0f) }
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val swipeMini = remember { prefs.swipeMini }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {
        translationY = down.value; val f = (down.value / size.height).coerceIn(0f, 1f)
        scaleX = 1f - f * 0.25f; scaleY = 1f - f * 0.25f; alpha = 1f - f * 0.6f
        transformOrigin = TransformOrigin(0.5f, 1f)
    }.background(deep).pointerInput(swipeMini) {
        if (!swipeMini) return@pointerInput
        detectVerticalDragGestures(
            onDragEnd = {
                if (down.value > size.height * 0.2f) scope.launch { down.animateTo(size.height.toFloat(), tween(220)); backDispatcher?.onBackPressed() }
                else scope.launch { down.animateTo(0f, tween(300, easing = WpEase)) }
            },
            onDragCancel = { scope.launch { down.animateTo(0f, tween(300, easing = WpEase)) } }
        ) { ch, d -> ch.consume(); scope.launch { down.snapTo((down.value + d).coerceAtLeast(0f)) } }
    }.pointerInput(Unit) {
        val threshold = size.width * 0.22f
        detectHorizontalDragGestures(
            onDragEnd = {
                val v = drag.value
                if (abs(v) > threshold) {
                    // carry the cover off the screen first, then change song (no snap back to the middle)
                    val target = if (v < 0) -size.width.toFloat() else size.width.toFloat()
                    val was = curId
                    scope.launch {
                        drag.animateTo(target, tween(170, easing = LinearEasing))
                        exitId = was; exitFrom = target
                        if (v < 0) nextFn() else prevFn()
                        delay(900)
                        if (curId == was) { exitId = null; drag.animateTo(0f, tween(300, easing = WpEase)) } // nothing to skip to
                    }
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
                val x = when (id) { exitId -> exitFrom; curId -> drag.value; else -> 0f }
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
                val addTo = LocalAddToPlaylist.current
                if (song != null) Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "add to playlist", tint = White,
                    modifier = Modifier.padding(end = 8.dp).size(42.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.25f)).tilt { addTo(listOf(song.id)) }.padding(9.dp))
                if (song != null) Icon(Icons.Rounded.Radio, "start radio", tint = White,
                    modifier = Modifier.padding(end = 8.dp).size(42.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.25f)).tilt {
                            conn.startRadio(Radio.build(song, vm.songs.value))
                            android.widget.Toast.makeText(ctx, "radio from ${song.title}", android.widget.Toast.LENGTH_SHORT).show()
                        }.padding(9.dp))
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
                    Text(al.lowercase(), color = White.copy(alpha = 0.75f), fontSize = 18.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
                // title uses all the room up to the heart; the heart stays pinned on the right
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(t, color = White, fontSize = if (t.length > 40) 22.sp else 28.sp, lineHeight = if (t.length > 40) 26.sp else 32.sp,
                            fontWeight = FontWeight.Light, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (f.isNotEmpty()) Text(f, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp).background(accent).padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                    if (song != null) Box(Modifier.padding(start = 8.dp)) { LikeButton(vm.playlists, song.id, accent) }
                }
            }
            // singer(s) under the title — tap one to open their artist page
            val credited = remember(artistName) { ArtistRepository.split(artistName) }
            Row(Modifier.padding(top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Person, null, tint = accent, modifier = Modifier.size(18.dp))
                credited.forEachIndexed { i, a ->
                    if (i > 0) Text(" · ", color = White.copy(alpha = 0.5f), fontSize = 17.sp)
                    Text(a, color = White.copy(alpha = 0.9f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        modifier = Modifier.padding(start = if (i == 0) 6.dp else 0.dp).tilt { open(Screen.Artist(a)) }.padding(vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            SeekBar(posS, durS, accent, onSeek = conn::seekTo)
            TimeRow(posS, durS)
            // output info + A-B loop
            var la by remember(md.title) { mutableStateOf<Long?>(null) }
            var lb by remember(md.title) { mutableStateOf<Long?>(null) }
            LaunchedEffect(la, lb) {
                val x = la; val y = lb
                if (x != null && y != null) while (true) { if (posS.longValue >= y) conn.seekTo(x); delay(60) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AudioOut.status.value, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val loopOn = la != null && lb != null
                Text(if (la == null) "A" else "A ${la!! / 60000}:${"%02d".format(la!! / 1000 % 60)}", color = if (la != null) White else Dim, fontSize = 13.sp,
                    modifier = Modifier.background(if (loopOn) accent else Color.White.copy(alpha = 0.08f)).tilt {
                        if (la == null) la = posS.longValue else { la = null; lb = null }
                    }.padding(horizontal = 10.dp, vertical = 4.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (lb == null) "B" else "loop", color = if (lb != null) White else Dim, fontSize = 13.sp,
                    modifier = Modifier.background(if (loopOn) accent else Color.White.copy(alpha = 0.08f)).tilt {
                        val x = la
                        if (lb != null) { la = null; lb = null } else if (x != null && posS.longValue > x + 500) lb = posS.longValue
                    }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
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

/** Heart: tap to add/remove the song from "liked songs", with a little pop. */
@Composable
fun LikeButton(store: PlaylistStore, songId: Long, accent: Color) {
    val liked = store.lists.value.let { songId in (it.firstOrNull { p -> p.id == PlaylistStore.LIKED }?.ids ?: emptyList()) }
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(Modifier.size(44.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape).tilt {
        val now = store.toggleLike(songId)
        if (now) scope.launch { pop.snapTo(0.6f); pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 500f)) }
    }, contentAlignment = Alignment.Center) {
        Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "unlike" else "like",
            tint = if (liked) Color(0xFFFF4D6D) else White, modifier = Modifier.size(28.dp))
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
                    fontWeight = FontWeight.Light, lineHeight = 27.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(
                        Color.White.copy(alpha = 0.75f), androidx.compose.ui.geometry.Offset.Zero, 24f)))
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
                            // the line being sung glows in the accent colour
                            style = if (i == cur && lyrics.synced) androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(
                                lerp(accent, White, 0.35f), androidx.compose.ui.geometry.Offset.Zero, 28f)) else LocalTextStyle.current,
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
fun SeekBar(posS: State<Long>, durS: State<Long>, accent: Color, onDrag: ((Long?, Float) -> Unit)? = null, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val dragCb by rememberUpdatedState(onDrag)
    val d by durS
    Canvas(Modifier.fillMaxWidth().height(26.dp)
        .pointerInput(Unit) { detectTapGestures { o -> if (d > 0) seek((o.x / size.width * d).toLong()) } }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = { drag?.let { if (d > 0) seek((it * d).toLong()) }; drag = null; dragCb?.invoke(null, 0f) },
                onDragCancel = { drag = null; dragCb?.invoke(null, 0f) }
            ) { ch, _ ->
                val f = (ch.position.x / size.width).coerceIn(0f, 1f)
                drag = f; if (d > 0) dragCb?.invoke((f * d).toLong(), f)
            }
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
                    SongRow(s.title, "${s.album} · ${s.format}", s.id) { play(i) }
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



/** Top of home: shuffle everything, and comics with new chapters. */
@Composable
private fun HomePicks(vm: MusicViewModel, conn: PlayerConnection, accent: Color, open: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val songs = vm.songs.value
    var lib by remember { mutableStateOf<List<Books.Lib>>(emptyList()) }
    LaunchedEffect(Unit) {
        lib = withContext(Dispatchers.IO) { Books.library(ctx) }
        ComicWidget.refresh(ctx)
    }
    val updates = remember(lib) { lib.filter { it.unread > 0 } }
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("shuffle all", color = White, fontSize = 15.sp, modifier = Modifier.background(accent).tilt {
                if (songs.isNotEmpty()) { conn.playSongs(songs, 0, shuffled = true); open(Screen.Playing) }
            }.padding(horizontal = 16.dp, vertical = 9.dp))
        }
        if (updates.isNotEmpty()) {
            SectionLabel("new chapters", accent)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(updates, key = { it.id }) { f ->
                    Column(Modifier.width(96.dp).tilt(onLongClick = { Peek.comic(ctx, f) }) { Books.openBook(ctx, f.id) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).background(Color(0xFF1A1A1A))) {
                            AsyncImage(f.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                            Text("${f.unread} new", color = White, fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.TopEnd).background(accent).padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                        Text(f.title, color = White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * Below speed dial: 6 random TV episodes (each from a different show, any season, any episode),
 * plus a random comic and book. Picks change once a day, or with "new picks".
 */
@Composable
private fun HomeForToday(vm: MusicViewModel, accent: Color) {
    val ctx = LocalContext.current
    val videos = vm.videos.value
    var reroll by remember { mutableIntStateOf(0) }
    val seed = dayNo() * 31 + reroll
    val episodes = remember(videos, seed) {
        val rnd = kotlin.random.Random(seed)
        val watched = ctx.getSharedPreferences("grove", Context.MODE_PRIVATE).getStringSet("watched_eps", emptySet()) ?: emptySet()
        val shows = videos.filter { it.parsed.isEpisode }.groupBy { it.parsed.title.lowercase() }.values.shuffled(rnd)
        val picks = shows.take(6).map { eps -> (eps.filter { it.id.toString() !in watched }.ifEmpty { eps }).random(rnd) }.toMutableList()
        // fewer than 6 shows: fill up with more random episodes
        if (picks.size < 6) picks += videos.filter { it.parsed.isEpisode && it !in picks }.shuffled(rnd).take(6 - picks.size)
        picks
    }
    var lib by remember { mutableStateOf<List<Books.Lib>>(emptyList()) }
    var books by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { lib = Books.library(ctx); books = BookTime(ctx).top.map { it.first } }
    }
    val book = remember(books, seed) { books.shuffled(kotlin.random.Random(seed)).firstOrNull() }
    val comic = remember(lib, seed) { lib.shuffled(kotlin.random.Random(seed + 7)).firstOrNull() }
    if (episodes.isEmpty() && book == null && comic == null) return
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("for today", accent) }
            Text("new picks", color = White, fontSize = 13.sp, modifier = Modifier.border(1.dp, White).tilt { reroll++ }
                .padding(horizontal = 10.dp, vertical = 5.dp))
        }
        if (episodes.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(episodes, key = { it.id }) { v ->
                Column(Modifier.width(170.dp).tilt(onLongClick = { Peek.video(ctx, v) { VideoActivity.open(ctx, v, VideoQueue.label(v)) } }) { VideoActivity.open(ctx, v, VideoQueue.label(v)) }) {
                    VideoThumb(v, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Text(v.parsed.title, color = White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("S%02dE%02d".format(v.parsed.season ?: 0, v.parsed.episode ?: 0), color = Dim, fontSize = 12.sp)
                }
            }
        }
        if (book != null || comic != null) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            comic?.let { f ->
                item(key = "comic") {
                    Column(Modifier.width(96.dp).tilt(onLongClick = { Peek.comic(ctx, f) }) { Books.openBook(ctx, f.id) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).background(Color(0xFF1A1A1A))) {
                            AsyncImage(f.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        }
                        Text(f.title, color = White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("read a comic", color = Dim, fontSize = 11.sp)
                    }
                }
            }
            book?.let { b ->
                item(key = "book") {
                    Column(Modifier.width(96.dp).tilt(onLongClick = { Peek.book(ctx, b) }) { HomeNav.goto.value = "book" }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).background(lerp(accent, Color.Black, 0.5f)), contentAlignment = Alignment.Center) {
                            Text(b.take(1).uppercase(), color = White, fontSize = 30.sp, fontWeight = FontWeight.Light)
                        }
                        Text(b, color = White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("read a book", color = Dim, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
