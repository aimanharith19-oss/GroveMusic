package com.grove.music

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val W = Color.White
private val D = Color.White.copy(alpha = 0.55f)

/** A frame from the video itself, for videos without a poster. */
@Composable
fun VideoThumb(v: Video, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, v.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(v.uri, Size(480, 270), null)
                else @Suppress("DEPRECATION") android.media.ThumbnailUtils.createVideoThumbnail(v.path ?: "", MediaStore.Video.Thumbnails.MINI_KIND)
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
            ?: Icon(Icons.Rounded.Movie, null, tint = D)
    }
}

private fun safe(s: String) = s.replace(Regex("""[\\/:*?"<>|]"""), "").trim()

/** rename files on the phone; asks Android once for permission (Android 11+) */
class Renamer(private val ctx: Context, private val onDone: (Int) -> Unit) {
    var pending: List<Pair<Video, String>> = emptyList()
    fun run(): Int {
        var n = 0
        for ((v, name) in pending) {
            if (name == v.name) continue
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    ctx.contentResolver.update(v.uri, ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, name) }, null, null) > 0
                } else {
                    val f = java.io.File(v.path ?: return@runCatching false); val t = java.io.File(f.parentFile, name)
                    f.renameTo(t).also { if (it) android.media.MediaScannerConnection.scanFile(ctx, arrayOf(t.path), null, null) }
                }
            }.getOrDefault(false)
            if (ok) n++
        }
        onDone(n); return n
    }
}

@Composable
fun rememberRenamer(onDone: (Int) -> Unit): Pair<Renamer, (List<Pair<Video, String>>) -> Unit> {
    val ctx = LocalContext.current
    val r = remember { Renamer(ctx, onDone) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) r.run()
    }
    return r to { list ->
        r.pending = list.filter { it.second != it.first.name }
        if (r.pending.isEmpty()) onDone(0)
        else if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createWriteRequest(ctx.contentResolver, r.pending.map { it.first.uri })
            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else r.run()
    }
}

/** Videos tab: TV shows (posters), movies (posters), everything else (frames). */
@Composable
fun VideosPage(vm: MusicViewModel, accent: Color, open: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val videos = vm.videos.value
    LaunchedEffect(Unit) { if (videos.isEmpty()) vm.loadVideos() }
    val shows = remember(videos) { videos.filter { it.parsed.isEpisode }.groupBy { it.parsed.title.lowercase() }.values.sortedBy { it[0].parsed.title.lowercase() } }
    val movies = remember(videos) { videos.filter { !it.parsed.isEpisode } }
    val noKey = Prefs(ctx).tmdbKey.isBlank()
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        if (videos.isEmpty()) item { Text("no videos found on your phone", color = D, fontSize = 16.sp) }
        if (noKey && movies.isNotEmpty()) item {
            Text("add a free TMDB key in settings to get movie posters, cast and info (TV shows work without it)",
                color = D, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (shows.isNotEmpty()) item {
            SectionLabel("tv shows", accent)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(shows) { _, eps ->
                    val name = eps[0].parsed.title
                    val t by produceState<Title?>(null, name) { value = VideoMeta.show(ctx, name) }
                    Column(Modifier.width(120.dp).tilt { open(Screen.Show(name)) }) {
                        Box(Modifier.width(120.dp).height(180.dp)) {
                            if (t?.poster != null) AsyncImage(t!!.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                            else VideoThumb(eps[0], Modifier.matchParentSize())
                        }
                        Text((t?.name ?: name).lowercase(), color = W, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text("${eps.size} episodes", color = D, fontSize = 11.sp)
                    }
                }
            }
        }
        if (movies.isNotEmpty()) item { SectionLabel("movies & videos", accent, Modifier.padding(top = 14.dp)) }
        items(movies.chunked(3).size) { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                movies.chunked(3)[r].forEach { v ->
                    val t by produceState<Title?>(null, v.id) { value = VideoMeta.movie(ctx, v.parsed) }
                    Column(Modifier.weight(1f).tilt { open(Screen.VideoInfo(v.id)) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                            if (t?.poster != null) AsyncImage(t!!.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                            else VideoThumb(v, Modifier.matchParentSize())
                        }
                        Text((t?.name ?: v.parsed.title).lowercase(), color = W, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(listOfNotNull(t?.year ?: v.parsed.year, fmtDur(v.duration)).joinToString(" · "), color = D, fontSize = 11.sp)
                    }
                }
                repeat(3 - movies.chunked(3)[r].size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun fmtDur(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }

@Composable
private fun Hero(img: String?, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    val deep = lerp(accent, Color.Black, 0.78f)
    Box(Modifier.fillMaxWidth()) {
        if (img != null) AsyncImage(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.4f), 0.5f to Color.Transparent, 1f to deep)))
        Column(Modifier.align(Alignment.BottomStart).statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 150.dp), content = content)
    }
}

@Composable
private fun CastRow(cast: List<CastMember>, accent: Color, open: (Screen) -> Unit) {
    if (cast.isEmpty()) return
    SectionLabel("cast", accent, Modifier.padding(top = 14.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(cast) { _, c ->
            Column(Modifier.width(92.dp).tilt { open(Screen.PersonView(c.personId)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).clip(CircleShape).background(lerp(accent, Color.Black, 0.4f)), contentAlignment = Alignment.Center) {
                    Text(c.name.take(1), color = W, fontSize = 28.sp, fontWeight = FontWeight.Light)
                    if (c.photo != null) AsyncImage(c.photo, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                Text(c.name, color = W, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(c.role, color = D, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One movie / video: poster, info, cast, play, rename file. */
@Composable
fun VideoInfoScreen(id: Long, vm: MusicViewModel, accent: Color, open: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val v = vm.videos.value.firstOrNull { it.id == id } ?: return
    val t by produceState<Title?>(null, id) { value = VideoMeta.movie(ctx, v.parsed) }
    val (_, rename) = rememberRenamer { n -> android.widget.Toast.makeText(ctx, if (n > 0) "renamed" else "name already matches", android.widget.Toast.LENGTH_SHORT).show(); vm.loadVideos() }
    val deep = lerp(accent, Color.Black, 0.78f)
    LazyColumn(Modifier.fillMaxSize().background(deep)) {
        item {
            Hero(t?.backdrop, accent) {
                Text((t?.name ?: v.parsed.title).lowercase(), color = W, fontSize = 44.sp, fontWeight = FontWeight.Light, lineHeight = 46.sp)
                Text(listOfNotNull(t?.year?.toString(), t?.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }, fmtDur(v.duration),
                    t?.genres?.take(3)?.joinToString(", ")).joinToString(" · "), color = W.copy(alpha = 0.8f), fontSize = 14.sp)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("play", color = W, fontSize = 18.sp, modifier = Modifier.background(accent)
                        .tilt { VideoActivity.open(ctx, v, t?.name ?: v.parsed.title) }.padding(horizontal = 26.dp, vertical = 10.dp))
                    if (t != null) Text("rename file", color = W, fontSize = 16.sp, modifier = Modifier.border(2.dp, W)
                        .tilt { rename(listOf(v to safe("${t!!.name}${t!!.year?.let { " ($it)" } ?: ""}") + ".${v.ext}")) }
                        .padding(horizontal = 16.dp, vertical = 9.dp))
                }
                Text(v.name, color = D, fontSize = 11.sp)
                t?.overview?.takeIf { it.isNotBlank() }?.let { Text(it, color = W.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 10.dp)) }
                if (t == null) Text(if (Prefs(ctx).tmdbKey.isBlank()) "add a TMDB key in settings to find this movie online" else "not found online",
                    color = D, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                CastRow(t?.cast ?: emptyList(), accent, open)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** A TV show: info, cast and episodes in the official order (yours playable, missing ones dimmed). */
@Composable
fun ShowScreen(name: String, vm: MusicViewModel, accent: Color, open: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val mine = remember(vm.videos.value, name) { vm.videos.value.filter { it.parsed.isEpisode && it.parsed.title.equals(name, true) } }
    val t by produceState<Title?>(null, name) { value = VideoMeta.show(ctx, name) }
    var eps by remember { mutableStateOf<List<Episode>>(emptyList()) }
    LaunchedEffect(t) { t?.let { eps = VideoMeta.episodes(ctx, it) } }
    var onlyMine by remember { mutableStateOf(true) }
    val (_, rename) = rememberRenamer { n -> android.widget.Toast.makeText(ctx, "renamed $n files", android.widget.Toast.LENGTH_SHORT).show(); vm.loadVideos() }
    val deep = lerp(accent, Color.Black, 0.78f)
    val have = mine.associateBy { it.parsed.season!! * 1000 + it.parsed.episode!! }
    val list: List<Pair<Episode, Video?>> = if (eps.isEmpty())
        mine.sortedWith(compareBy({ it.parsed.season }, { it.parsed.episode })).map { Episode(it.parsed.season!!, it.parsed.episode!!, "", "", null, "") to it }
    else eps.map { it to have[it.season * 1000 + it.number] }.filter { !onlyMine || it.second != null }
    LazyColumn(Modifier.fillMaxSize().background(deep)) {
        item {
            Hero(t?.backdrop ?: t?.poster, accent) {
                Text((t?.name ?: name).lowercase(), color = W, fontSize = 44.sp, fontWeight = FontWeight.Light, lineHeight = 46.sp)
                Text(listOfNotNull(t?.year?.toString(), t?.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }, "${mine.size} episodes on your phone")
                    .joinToString(" · "), color = W.copy(alpha = 0.8f), fontSize = 14.sp)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                t?.overview?.takeIf { it.isNotBlank() }?.let { Text(it, color = W.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.Light,
                    maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp)) }
                CastRow(t?.cast ?: emptyList(), accent, open)
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("episodes", accent, Modifier.weight(1f))
                    if (eps.isNotEmpty()) Text(if (onlyMine) "show all" else "only mine", color = W, fontSize = 14.sp, modifier = Modifier.tilt { onlyMine = !onlyMine }.padding(8.dp))
                }
                if (t != null && eps.isNotEmpty()) Text("rename my files to match", color = accent, fontSize = 15.sp, modifier = Modifier.tilt {
                    rename(mine.mapNotNull { v -> eps.firstOrNull { it.season == v.parsed.season && it.number == v.parsed.episode }?.let { e ->
                        v to safe("${t!!.name} - S%02dE%02d - ${e.name}".format(e.season, e.number)) + ".${v.ext}" } })
                }.padding(vertical = 6.dp))
            }
        }
        var lastSeason = -1
        list.forEach { (e, v) ->
            if (e.season != lastSeason) { lastSeason = e.season; val sn = e.season
                item(key = "s$sn") { Text("season $sn", color = W, fontSize = 26.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)) } }
            item(key = "e${e.season}_${e.number}") {
                Row(Modifier.fillMaxWidth().graphicsLayer { alpha = if (v != null) 1f else 0.45f }
                    .tilt { if (v != null) VideoActivity.open(ctx, v, "${t?.name ?: name} · S${e.season}E${e.number}${if (e.name.isNotBlank()) " · ${e.name}" else ""}") }
                    .padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(128.dp).height(72.dp)) {
                        if (e.still != null) AsyncImage(e.still, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        else if (v != null) VideoThumb(v, Modifier.matchParentSize()) else Box(Modifier.matchParentSize().background(Color(0xFF1A1A1A)))
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("${e.number}. ${e.name.ifBlank { "episode ${e.number}" }}", color = W, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (v == null) "not on your phone" else listOf(e.air, fmtDur(v.duration)).filter { it.isNotBlank() }.joinToString(" · "), color = D, fontSize = 12.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** An actor: photo, bio and all their films and TV shows (the ones you have are marked). */
@Composable
fun PersonScreen(id: String, vm: MusicViewModel, accent: Color, open: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val p by produceState<Person?>(null, id) { value = VideoMeta.person(ctx, id) }
    val deep = lerp(accent, Color.Black, 0.78f)
    val mine = remember(vm.videos.value) { vm.videos.value.map { ArtistRepository.norm(it.parsed.title) }.toSet() }
    var bioOpen by remember { mutableStateOf(false) }
    val person = p
    LazyColumn(Modifier.fillMaxSize().background(deep)) {
        item {
            Hero(person?.photo, accent) {
                Text((person?.name ?: "loading…").lowercase(), color = W, fontSize = 44.sp, fontWeight = FontWeight.Light, lineHeight = 46.sp)
                person?.born?.takeIf { it.isNotBlank() }?.let { Text("born $it", color = W.copy(alpha = 0.8f), fontSize = 14.sp) }
            }
        }
        if (person != null) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (person.bio.isNotBlank()) Text(person.bio, color = W.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.Light,
                        maxLines = if (bioOpen) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp).tilt { bioOpen = !bioOpen })
                    SectionLabel("films & tv shows · ${person.credits.size}", accent, Modifier.padding(top = 14.dp))
                }
            }
            items(person.credits.chunked(3).size) { r ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    person.credits.chunked(3)[r].forEach { c ->
                        val have = ArtistRepository.norm(c.title) in mine
                        Column(Modifier.weight(1f).tilt {
                            if (c.tv) open(Screen.Show(vm.videos.value.firstOrNull { ArtistRepository.norm(it.parsed.title) == ArtistRepository.norm(c.title) }?.parsed?.title ?: c.title))
                            else vm.videos.value.firstOrNull { ArtistRepository.norm(it.parsed.title) == ArtistRepository.norm(c.title) }?.let { open(Screen.VideoInfo(it.id)) }
                        }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Color(0xFF1A1A1A))) {
                                if (c.poster != null) AsyncImage(c.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                if (have) Text("ON PHONE", color = W, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopStart).background(accent).padding(horizontal = 4.dp, vertical = 1.dp))
                                if (c.tv) Text("TV", color = W, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                            Text(c.title, color = W, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                            Text(listOfNotNull(c.year?.toString(), c.role.takeIf { it.isNotBlank() }).joinToString(" · "), color = D, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(3 - person.credits.chunked(3)[r].size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
