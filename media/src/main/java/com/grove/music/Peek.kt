package com.grove.music

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
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
    /** slower details read from the file, filled in while the card is open */
    val more: (suspend () -> List<Pair<String, String>>)? = null,
    val actions: List<Pair<String, () -> Unit>> = emptyList(),
)

object Peek {
    val shown = mutableStateOf<PeekData?>(null)
    fun show(d: PeekData) { shown.value = d }

    private fun mb(b: Long) = if (b <= 0) null else if (b > 1024L * 1024 * 1024) "%.2f GB".format(b / 1073741824.0) else "%.1f MB".format(b / 1048576.0)
    private fun dur(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun ago(t: Long) = android.text.format.DateUtils.getRelativeTimeSpanString(t).toString()
    private fun hm(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }

    /** codec, sample rate, bit depth, frame rate… straight from the file */
    private fun tracks(ctx: Context, uri: android.net.Uri): List<Pair<String, String>> = runCatching {
        val x = MediaExtractor(); x.setDataSource(ctx, uri, null)
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until x.trackCount) {
            val f = x.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            val codec = mime.substringAfter('/').uppercase().replace("MP4A-LATM", "AAC").replace("AVC", "H.264").replace("HEVC", "H.265")
            fun int(k: String) = if (f.containsKey(k)) f.getInteger(k) else null
            if (mime.startsWith("audio/")) {
                out += "audio" to listOfNotNull(codec,
                    int(MediaFormat.KEY_SAMPLE_RATE)?.let { "%.1f kHz".format(it / 1000f).replace(".0 ", " ") },
                    int(MediaFormat.KEY_CHANNEL_COUNT)?.let { if (it == 1) "mono" else if (it == 2) "stereo" else "$it ch" },
                    if (android.os.Build.VERSION.SDK_INT >= 24) int(MediaFormat.KEY_PCM_ENCODING)?.let { when (it) { 2 -> "16-bit"; 21 -> "24-bit"; 22, 4 -> "32-bit"; else -> null } } else null,
                    int(MediaFormat.KEY_BIT_RATE)?.let { "${it / 1000} kbps" }).joinToString(" · ")
            } else if (mime.startsWith("video/")) {
                out += "video" to listOfNotNull(codec,
                    int(MediaFormat.KEY_WIDTH)?.let { w -> int(MediaFormat.KEY_HEIGHT)?.let { "$w×$it" } },
                    int(MediaFormat.KEY_FRAME_RATE)?.let { "$it fps" }).joinToString(" · ")
            }
        }
        x.release(); out
    }.getOrDefault(emptyList())

    fun song(ctx: Context, s: Song, conn: PlayerConnection?, add: (List<Long>) -> Unit) = show(PeekData(
        kind = "SONG", title = s.title, sub = listOf(s.artist, s.album).joinToString(" · "), image = s.artUri,
        rows = listOfNotNull(
            "length" to dur(s.duration),
            s.year.takeIf { it > 0 }?.let { "year" to it.toString() },
            s.genre.takeIf { it.isNotBlank() && it != "unknown" }?.let { "genre" to it },
            s.track.takeIf { it > 0 }?.let { "track" to (it % 1000).toString() },
            "file" to s.displayName,
            s.path?.let { File(it) }?.length()?.let { mb(it) }?.let { "size" to it },
            s.path?.let { File(it).length() }?.takeIf { it > 0 && s.duration > 0 }?.let { "average" to "${it * 8 / s.duration} kbps" },
            "folder" to s.folder,
            s.added.takeIf { it > 0 }?.let { "added" to ago(it * 1000) },
        ),
        more = { tracks(ctx, s.uri) },
        actions = listOfNotNull(
            conn?.let { c -> "play" to { c.playSongs(listOf(s), 0) } },
            "add to playlist" to { add(listOf(s.id)) },
        ),
    ))

    fun video(ctx: Context, v: Video, open: () -> Unit) {
        val pos = ctx.getSharedPreferences("video_pos", Context.MODE_PRIVATE).getLong(v.id.toString(), 0)
        show(PeekData(
            kind = if (v.parsed.isEpisode) "EPISODE" else "VIDEO",
            title = if (v.parsed.isEpisode) VideoQueue.label(v) else v.parsed.title.ifBlank { v.name },
            sub = v.name, image = v.uri, ratio = 16f / 9f,
            rows = listOfNotNull(
                "length" to dur(v.duration),
                (v.w > 0).takeIf { it }?.let { "picture" to "${v.w}×${v.h}" + when { maxOf(v.w, v.h) >= 7000 -> " · 8K"; maxOf(v.w, v.h) >= 3800 -> " · 4K"; maxOf(v.w, v.h) >= 1900 -> " · Full HD"; maxOf(v.w, v.h) >= 1200 -> " · HD"; else -> "" } },
                pos.takeIf { it > 5000 }?.let { "watched" to "${dur(it)} of ${dur(v.duration)}" + if (v.duration > 0) " (${it * 100 / v.duration}%)" else "" },
                mb(v.size)?.let { "size" to it },
                "type" to v.ext.uppercase(),
                "folder" to v.folder,
                v.added.takeIf { it > 0 }?.let { "added" to ago(it * 1000) },
            ),
            more = { tracks(ctx, v.uri) },
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
            listOfNotNull(
                ("chapters read" to h.size.toString()).takeIf { h.isNotEmpty() },
                h.sumOf { it.readMs }.takeIf { it > 0 }?.let { "total reading time" to hm(it) },
                Books.library(ctx).firstOrNull { it.id == b.mangaId }?.unread?.takeIf { it > 0 }?.let { "unread" to "$it chapters" },
            )
        } },
        actions = listOf("continue" to { Books.openReader(ctx, b) }, "details" to { Books.openBook(ctx, b.mangaId) }),
    ))

    fun comic(ctx: Context, l: Books.Lib) = show(PeekData(
        kind = "COMIC", title = l.title, sub = if (l.unread > 0) "${l.unread} unread chapters" else "all caught up", image = l.cover, ratio = 0.7f,
        rows = listOf("unread" to l.unread.toString()),
        more = { withContext(Dispatchers.IO) {
            val h = Books.history(ctx).filter { it.mangaId == l.id }
            listOfNotNull(
                h.maxByOrNull { it.lastRead }?.let { "last read" to "${it.chapter} · ${ago(it.lastRead)}" },
                ("chapters read" to h.size.toString()).takeIf { h.isNotEmpty() },
                h.sumOf { it.readMs }.takeIf { it > 0 }?.let { "total reading time" to hm(it) },
            )
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

/** The pop-up card for a long press: grows out of the screen centre, tap anywhere outside to close. */
@Composable
fun PeekOverlay(accent: Color) {
    val d = Peek.shown.value ?: return
    val view = LocalView.current
    val pop = remember(d) { Animatable(0.85f) }
    var more by remember(d) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(d) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 600f))
    }
    LaunchedEffect(d) { d.more?.let { f -> more = withContext(Dispatchers.IO) { runCatching { f() }.getOrDefault(emptyList()) } } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
        .pointerInput(d) { detectTapGestures { Peek.shown.value = null } }, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.86f).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - 0.85f) / 0.15f).coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(18.dp)).background(Color(0xFF151515))
            .pointerInput(d) { detectTapGestures { } }) {
            Box(Modifier.fillMaxWidth().then(if (d.ratio < 1f) Modifier.height(220.dp) else Modifier.aspectRatio(d.ratio))
                .background(androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.5f)), contentAlignment = Alignment.Center) {
                if (d.image != null) AsyncImage(d.image, null, contentScale = if (d.ratio < 1f) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(d.title.take(1).uppercase(), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
            }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp).verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                Text(d.kind, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Text(d.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (d.sub.isNotBlank()) Text(d.sub, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    (d.rows + more).forEach { (k, v) ->
                        Row {
                            Text(k, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, modifier = Modifier.width(110.dp))
                            Text(v, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (d.actions.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
                d.actions.forEach { (label, act) ->
                    Box(Modifier.weight(1f).background(Color.White.copy(alpha = 0.06f)).tilt { Peek.shown.value = null; act() }
                        .padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
