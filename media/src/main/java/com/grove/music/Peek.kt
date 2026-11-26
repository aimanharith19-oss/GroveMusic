package com.grove.music

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a long press shows: picture, the item's details, and a few quick actions (like 3D Touch peek). */
class PeekData(
    val kind: String,
    val title: String,
    val sub: String,
    val image: Any?,
    /** 16:9 picture (videos) instead of a square / book cover */
    val ratio: Float = 1f,
    val rows: List<Pair<String, String>> = emptyList(),
    /** slower details (looked up online / cached), filled in while the card is open */
    val more: (suspend () -> PeekMore)? = null,
    val actions: List<Pair<String, () -> Unit>> = emptyList(),
)

class PeekMore(val rows: List<Pair<String, String>> = emptyList(), val about: String? = null)

object Peek {
    val shown = mutableStateOf<PeekData?>(null)
    fun show(d: PeekData) { shown.value = d }

    private fun dur(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun ago(t: Long) = android.text.format.DateUtils.getRelativeTimeSpanString(t).toString()
    private fun hm(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }

    fun song(ctx: Context, s: Song, all: List<Song>, conn: PlayerConnection?, add: (List<Long>) -> Unit) {
        val artist = ArtistRepository.primary(s.artist)
        val lib = all.filter { it.album == s.album && s.album != "unknown album" }
        show(PeekData(
            kind = "SONG", title = s.title, sub = s.artist, image = s.artUri,
            rows = listOfNotNull(
                "artist" to s.artist,
                s.album.takeIf { it != "unknown album" }?.let { "album" to it },
                s.year.takeIf { it > 0 }?.let { "year" to it.toString() },
                Meta.genreOf(ctx, s).takeIf { it.isNotBlank() && !it.startsWith("unknown") }?.let { "genre" to it },
                lib.takeIf { it.size > 1 }?.let { "in your library" to "${it.size} songs from this album · ${hm(it.sumOf { x -> x.duration })}" },
            ),
            more = {
                val info = ArtistRepository.cached(ctx, artist) ?: ArtistRepository.load(ctx, artist)
                val rel = info?.releases?.firstOrNull { it.title.equals(s.album, true) }
                PeekMore(
                    rows = listOfNotNull(
                        rel?.let { "release" to listOf(it.type, it.year).filter { x -> x.isNotBlank() }.joinToString(" · ") }?.takeIf { it.second.isNotBlank() },
                        Meta.countryOf(ctx, s)?.let { "from" to it },
                        info?.releases?.size?.takeIf { it > 0 }?.let { "$artist has" to "$it releases" },
                    ),
                    about = info?.bio?.takeIf { it.isNotBlank() },
                )
            },
            actions = listOfNotNull(
                conn?.let { c -> "play" to { c.playSongs(listOf(s), 0) } },
                "add to playlist" to { add(listOf(s.id)) },
            ),
        ))
    }

    fun video(ctx: Context, v: Video, open: () -> Unit) {
        val pos = ctx.getSharedPreferences("video_pos", Context.MODE_PRIVATE).getLong(v.id.toString(), 0)
        show(PeekData(
            kind = if (v.parsed.isEpisode) "EPISODE" else "VIDEO",
            title = if (v.parsed.isEpisode) VideoQueue.label(v) else v.parsed.title.ifBlank { v.name },
            sub = "", image = v.uri, ratio = 16f / 9f,
            rows = listOfNotNull(
                "length" to dur(v.duration),
                pos.takeIf { it > 5000 }?.let { "watched" to "${dur(it)}" + if (v.duration > 0) " (${it * 100 / v.duration}%)" else "" },
            ),
            more = {
                val t = if (v.parsed.isEpisode) VideoMeta.show(ctx, v.parsed.title) else VideoMeta.movieFor(ctx, v)
                if (t == null) PeekMore() else PeekMore(
                    rows = listOfNotNull(
                        t.year?.let { "year" to it.toString() },
                        t.genres.takeIf { it.isNotEmpty() }?.let { "genre" to it.take(3).joinToString(", ") },
                        t.rating.takeIf { it > 0 }?.let { "rating" to "%.1f / 10".format(it) },
                        t.cast.takeIf { it.isNotEmpty() }?.let { "starring" to it.take(3).joinToString(", ") { c -> c.name } },
                    ),
                    about = t.overview.takeIf { it.isNotBlank() },
                )
            },
            actions = run {
                val mv = MvPick.of(ctx, v.id) ?: false
                listOf((if (pos > 5000) "resume" else "play") to open,
                    (if (mv) "not MTV" else "MTV") to { MvPick.set(ctx, v.id, !mv) })
            },
        ))
    }

    fun comic(ctx: Context, b: BookRead) = show(PeekData(
        kind = "COMIC", title = b.title, sub = b.chapter, image = b.cover, ratio = 0.7f,
        rows = listOfNotNull(
            "last chapter" to b.chapter,
            b.lastRead.takeIf { it > 0 }?.let { "last read" to ago(it) },
            b.readMs.takeIf { it > 0 }?.let { "time on this chapter" to hm(it) },
        ),
        more = { withContext(Dispatchers.IO) {
            val h = Books.history(ctx).filter { it.mangaId == b.mangaId }
            PeekMore(listOfNotNull(
                ("chapters read" to h.size.toString()).takeIf { h.isNotEmpty() },
                h.sumOf { it.readMs }.takeIf { it > 0 }?.let { "total reading time" to hm(it) },
                Books.library(ctx).firstOrNull { it.id == b.mangaId }?.unread?.takeIf { it > 0 }?.let { "unread" to "$it chapters" },
            ))
        } },
        actions = listOf("continue" to { Books.openReader(ctx, b) }, "details" to { Books.openBook(ctx, b.mangaId) }),
    ))

    fun comic(ctx: Context, l: Books.Lib) = show(PeekData(
        kind = "COMIC", title = l.title, sub = if (l.unread > 0) "${l.unread} unread chapters" else "all caught up", image = l.cover, ratio = 0.7f,
        rows = listOf("unread" to l.unread.toString()),
        more = { withContext(Dispatchers.IO) {
            val h = Books.history(ctx).filter { it.mangaId == l.id }
            PeekMore(listOfNotNull(
                h.maxByOrNull { it.lastRead }?.let { "last read" to "${it.chapter} · ${ago(it.lastRead)}" },
                ("chapters read" to h.size.toString()).takeIf { h.isNotEmpty() },
                h.sumOf { it.readMs }.takeIf { it > 0 }?.let { "total reading time" to hm(it) },
            ))
        } },
        actions = listOf("open" to { Books.openBook(ctx, l.id) }),
    ))

    fun book(ctx: Context, name: String) {
        val t = BookTime(ctx).top.firstOrNull { it.first == name }
        show(PeekData(
            kind = "BOOK", title = name, sub = "from your book folders", image = null, ratio = 0.7f,
            rows = listOfNotNull(
                t?.second?.let { "reading time" to hm(it) },
                t?.third?.let { "progress" to "$it%" },
            ),
            actions = listOf("open book tab" to { HomeNav.goto.value = "book" }),
        ))
    }
}

/** The small pop-up card for a long press; tap anywhere else to close. */
@Composable
fun PeekOverlay(accent: Color) {
    val d = Peek.shown.value ?: return
    val view = LocalView.current
    val pop = remember(d) { Animatable(0.85f) }
    var more by remember(d) { mutableStateOf(PeekMore()) }
    LaunchedEffect(d) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 700f))
    }
    LaunchedEffect(d) { d.more?.let { f -> more = withContext(Dispatchers.IO) { runCatching { f() }.getOrDefault(PeekMore()) } } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))
        .pointerInput(d) { detectTapGestures { Peek.shown.value = null } }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(300.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - 0.85f) / 0.15f).coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(14.dp)).background(Color(0xF01A1A1A))
            .pointerInput(d) { detectTapGestures { } }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val iw = if (d.ratio > 1.2f) 96.dp else 60.dp
                val ih = if (d.ratio > 1.2f) 54.dp else if (d.ratio < 1f) 86.dp else 60.dp
                Box(Modifier.width(iw).height(ih).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.5f)),
                    contentAlignment = Alignment.Center) {
                    if (d.image != null) AsyncImage(d.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text(d.title.take(1).uppercase(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(d.kind, color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Text(d.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Light, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (d.sub.isNotBlank()) Text(d.sub, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                (d.rows + more.rows).distinctBy { it.first }.forEach { (k, v) ->
                    Row {
                        Text(k, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.width(96.dp))
                        Text(v, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                more.about?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 5,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
            }
            if (d.actions.isNotEmpty()) Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f))) {
                d.actions.forEach { (label, act) ->
                    Box(Modifier.weight(1f).tilt { Peek.shown.value = null; act() }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
