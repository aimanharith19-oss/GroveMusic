package com.grove.music

import android.content.Context
import java.io.File
import java.util.Calendar

data class Play(val ts: Long, val id: Long, val ms: Long)

/** Listening history: one line per listen (start time, song id, milliseconds heard) in a small file. */
object StatsLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "plays.log")

    @Synchronized fun record(ctx: Context, ts: Long, id: Long, ms: Long) {
        if (ms < 5000) return
        runCatching { file(ctx).appendText("$ts,$id,$ms\n") }
    }

    fun read(ctx: Context): List<Play> = runCatching {
        file(ctx).takeIf { it.exists() }?.readLines()?.mapNotNull { l ->
            val p = l.split(','); if (p.size < 3) null else Play(p[0].toLong(), p[1].toLong(), p[2].toLong())
        }
    }.getOrNull() ?: emptyList()

    fun clear(ctx: Context) { file(ctx).delete() }
}

/** Everything the stats tab shows, worked out from the log. */
class Stats(plays: List<Play>) {
    private val cal = Calendar.getInstance()
    private fun key(ts: Long, f: Int): Int { cal.timeInMillis = ts; return when (f) {
        Calendar.DAY_OF_YEAR -> cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        Calendar.MONTH -> cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH)
        else -> cal.get(Calendar.YEAR) } }

    private val now = System.currentTimeMillis()
    val all = plays
    val totalMs = plays.sumOf { it.ms }
    val monthMs = plays.filter { key(it.ts, Calendar.MONTH) == key(now, Calendar.MONTH) }.sumOf { it.ms }
    val yearMs = plays.filter { key(it.ts, Calendar.YEAR) == key(now, Calendar.YEAR) }.sumOf { it.ms }
    val todayMs = plays.filter { key(it.ts, Calendar.DAY_OF_YEAR) == key(now, Calendar.DAY_OF_YEAR) }.sumOf { it.ms }

    /** listening minutes for each of the last [n] days, oldest first */
    fun lastDays(n: Int): List<Pair<Long, Long>> {
        val byDay = plays.groupBy { key(it.ts, Calendar.DAY_OF_YEAR) }.mapValues { e -> e.value.sumOf { it.ms } }
        return (n - 1 downTo 0).map { d -> val t = now - d * 86_400_000L; t to (byDay[key(t, Calendar.DAY_OF_YEAR)] ?: 0L) }
    }

    /** listening per month for the last 12 months, oldest first */
    fun lastMonths(): List<Pair<Long, Long>> {
        val byM = plays.groupBy { key(it.ts, Calendar.MONTH) }.mapValues { e -> e.value.sumOf { it.ms } }
        return (11 downTo 0).map { m ->
            val c = Calendar.getInstance(); c.add(Calendar.MONTH, -m); c.timeInMillis to (byM[key(c.timeInMillis, Calendar.MONTH)] ?: 0L)
        }
    }

    /** (song id, plays, ms) top songs in a period; a play counts after 30 seconds */
    fun top(period: String, n: Int = 10): List<Triple<Long, Int, Long>> {
        val f = when (period) {
            "today" -> plays.filter { key(it.ts, Calendar.DAY_OF_YEAR) == key(now, Calendar.DAY_OF_YEAR) }
            "month" -> plays.filter { key(it.ts, Calendar.MONTH) == key(now, Calendar.MONTH) }
            "year" -> plays.filter { key(it.ts, Calendar.YEAR) == key(now, Calendar.YEAR) }
            else -> plays
        }
        return f.groupBy { it.id }.map { (id, l) -> Triple(id, l.count { it.ms >= 30_000 }, l.sumOf { it.ms }) }
            .filter { it.second > 0 }.sortedWith(compareBy({ -it.second }, { -it.third })).take(n)
    }

    /** the record day / month / year: (start time, ms) */
    fun best(f: Int): Pair<Long, Long>? =
        plays.groupBy { key(it.ts, f) }.maxByOrNull { e -> e.value.sumOf { it.ms } }?.let { e -> e.value.first().ts to e.value.sumOf { it.ms } }
}
