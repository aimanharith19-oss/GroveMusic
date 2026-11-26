package com.grove.music

import android.content.Context
import kotlin.math.ln
import kotlin.math.pow

/**
 * "For you": learns what you like from how you listen, and picks songs from your library.
 *
 * - Every listen counts toward the song's artists, genre, era and album. Listening to the end counts
 *   fully, skipping in the first 30 s counts against it. Older listens slowly fade (half every 45 days),
 *   so the picks follow your taste as it changes.
 * - Each song gets a score from those, minus a penalty for songs you've just heard a lot, plus a small
 *   daily shake-up and a bonus for songs you've never played, so it keeps discovering.
 * - It learns which reason works for you: when you play a pick to the end, the reason it was picked
 *   ("same artist", "same genre"…) gets more weight next time; when you skip it, less.
 */
object Taste {
    class Profile(
        val artists: List<Pair<String, Double>>, val genres: List<Pair<String, Double>>,
        val decades: List<Pair<Int, Double>>, val listens: Int, val skips: Int, val weights: Map<String, Double>,
    )
    class Pick(val song: Song, val score: Double, val why: String)

    private val DEFAULT = mapOf("artist" to 1.0, "genre" to 0.7, "era" to 0.4, "album" to 0.3)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("taste", Context.MODE_PRIVATE)

    fun weights(ctx: Context): Map<String, Double> {
        val p = prefs(ctx)
        return DEFAULT.mapValues { (k, d) -> p.getFloat("w_$k", d.toFloat()).toDouble() }
    }

    private fun genreOf(s: Song) = s.genre.trim().lowercase().takeIf { it.isNotBlank() && it != "unknown" && it != "<unknown>" }
    private fun decadeOf(s: Song) = s.year.takeIf { it in 1900..2100 }?.let { it / 10 * 10 }

    private class Raw(val a: Map<String, Double>, val g: Map<String, Double>, val d: Map<Int, Double>, val al: Map<String, Double>,
                      val listens: Int, val skips: Int)

    private fun raw(plays: List<Play>, byId: Map<Long, Song>): Raw {
        val now = System.currentTimeMillis()
        val a = HashMap<String, Double>(); val g = HashMap<String, Double>(); val d = HashMap<Int, Double>(); val al = HashMap<String, Double>()
        var listens = 0; var skips = 0
        for (p in plays) {
            val s = byId[p.id] ?: continue
            val fade = 0.5.pow((now - p.ts) / (45 * 86_400_000.0))
            val skipped = p.ms < 30_000 && s.duration > 60_000
            if (skipped) skips++ else listens++
            val signal = if (skipped) -0.6 else (p.ms.toDouble() / s.duration.coerceAtLeast(1)).coerceIn(0.2, 1.0)
            val w = signal * fade
            ArtistRepository.split(s.artist).forEach { a.merge(it.lowercase(), w, Double::plus) }
            genreOf(s)?.let { g.merge(it, w, Double::plus) }
            decadeOf(s)?.let { d.merge(it, w, Double::plus) }
            if (s.album != "unknown album") al.merge(s.album.lowercase(), w * 0.5, Double::plus)
        }
        return Raw(a.norm(), g.norm(), d.norm(), al.norm(), listens, skips)
    }

    private fun <K> Map<K, Double>.norm(): Map<K, Double> { val m = values.maxOrNull()?.takeIf { it > 0 } ?: return this; return mapValues { it.value / m } }

    fun profile(ctx: Context, songs: List<Song>): Profile {
        val r = raw(StatsLog.read(ctx), songs.associateBy { it.id })
        val names = songs.flatMap { ArtistRepository.split(it.artist) }.associateBy { it.lowercase() }
        return Profile(
            r.a.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(8).map { (names[it.key] ?: it.key) to it.value },
            r.g.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(6).map { it.key to it.value },
            r.d.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            r.listens, r.skips, weights(ctx),
        )
    }

    /** the best songs for you right now (changes a little every day, and as it learns) */
    fun recommend(ctx: Context, songs: List<Song>, n: Int = 25): List<Pick> {
        if (songs.isEmpty()) return emptyList()
        val plays = StatsLog.read(ctx)
        learn(ctx, plays)
        val byId = songs.associateBy { it.id }
        val r = raw(plays, byId)
        val w = weights(ctx)
        val now = System.currentTimeMillis()
        val count = plays.filter { now - it.ts < 30 * 86_400_000L }.groupingBy { it.id }.eachCount()
        val last = plays.groupBy { it.id }.mapValues { e -> e.value.maxOf { it.ts } }
        val rnd = kotlin.random.Random(now / 86_400_000L)
        val picks = songs.map { s ->
            val parts = linkedMapOf(
                "artist" to (ArtistRepository.split(s.artist).maxOfOrNull { r.a[it.lowercase()] ?: 0.0 } ?: 0.0) * w["artist"]!!,
                "genre" to (genreOf(s)?.let { r.g[it] } ?: 0.0) * w["genre"]!!,
                "era" to (decadeOf(s)?.let { r.d[it] } ?: 0.0) * w["era"]!!,
                "album" to (r.al[s.album.lowercase()] ?: 0.0) * w["album"]!!,
            )
            var score = parts.values.sum()
            val heard = last[s.id]
            if (heard == null) score += 0.25 // never played: worth discovering
            else if (now - heard < 3 * 86_400_000L) score -= 0.6 // just heard it
            score -= 0.12 * ln(1.0 + (count[s.id] ?: 0))
            score += rnd.nextDouble() * 0.2
            val top = parts.maxByOrNull { it.value }
            val why = when {
                top == null || top.value <= 0.05 -> "something new to try"
                top.key == "artist" -> "you listen to ${ArtistRepository.primary(s.artist)}"
                top.key == "genre" -> "you like ${genreOf(s)}"
                top.key == "era" -> "more from the ${decadeOf(s)}s"
                else -> "from ${s.album}"
            } + if (heard == null) " · never played" else ""
            Triple(s, score, top?.key ?: "new") to why
        }.sortedByDescending { it.first.second }
        // don't let one artist take over the list
        val perArtist = HashMap<String, Int>()
        val out = picks.filter { (t, _) -> val k = ArtistRepository.primary(t.first.artist).lowercase(); perArtist.merge(k, 1, Int::plus)!! <= 3 }.take(n)
        // remember why each was picked, to learn from what you do with them
        prefs(ctx).edit().putString("shown", out.joinToString(";") { (t, _) -> "${t.first.id}:${t.third}" })
            .putLong("shown_at", now).apply()
        return out.map { (t, why) -> Pick(t.first, t.second, why) }
    }

    /** picks you played to the end make their reason stronger; picks you skipped make it weaker */
    private fun learn(ctx: Context, plays: List<Play>) {
        val p = prefs(ctx)
        val at = p.getLong("shown_at", 0); if (at == 0L) return
        val shown = p.getString("shown", "").orEmpty().split(';').mapNotNull { e ->
            val (id, k) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toLongOrNull()?.let { it to k }
        }.toMap()
        val after = plays.filter { it.ts > at && it.id in shown }
        if (after.isEmpty()) return
        val w = weights(ctx).toMutableMap()
        after.forEach { pl ->
            val k = shown[pl.id] ?: return@forEach
            if (k !in w) return@forEach
            w[k] = (w[k]!! + if (pl.ms < 30_000) -0.04 else 0.05).coerceIn(0.1, 2.0)
        }
        p.edit().apply { w.forEach { (k, v) -> putFloat("w_$k", v.toFloat()) } }.putLong("shown_at", plays.maxOf { it.ts }).apply()
    }

    fun reset(ctx: Context) = prefs(ctx).edit().clear().apply()
}
