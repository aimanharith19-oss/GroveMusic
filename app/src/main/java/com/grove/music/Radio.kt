package com.grove.music

import java.util.Calendar
import kotlin.random.Random

/** "Radio": a random mix that leans toward songs related to a seed song (same singer, genre, album). */
object Radio {
    private fun artistKeys(s: Song) = ArtistRepository.split(s.artist).map { ArtistRepository.key(it) }.toSet()

    /** seed first, then up to [size]-1 songs: ~40% related, the rest random, all shuffled */
    fun build(seed: Song, all: List<Song>, size: Int = 50, rnd: Random = Random.Default): List<Song> {
        val others = all.filter { it.id != seed.id }
        if (others.isEmpty()) return listOf(seed)
        val keys = artistKeys(seed)
        val related = others.filter { s ->
            artistKeys(s).any { it in keys } || (seed.genre.isNotBlank() && s.genre.equals(seed.genre, true)) || s.albumId == seed.albumId
        }.shuffled(rnd)
        val nRel = minOf(related.size, ((size - 1) * 0.4).toInt())
        val picked = related.take(nRel).toMutableList()
        val rest = others.filter { it !in picked }.shuffled(rnd)
        picked += rest.take(size - 1 - picked.size)
        return listOf(seed) + picked.shuffled(rnd)
    }

    /** 3 radio mixes of up to 20 songs, new every day (or when [salt] changes), seeded from favourite singers */
    fun dailyMixes(all: List<Song>, liked: List<Long>, salt: Int = 0): List<Pair<String, List<Song>>> {
        if (all.size < 5) return emptyList()
        val day = Calendar.getInstance().run { get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR) }
        val rnd = Random(day * 31 + salt)
        val byId = all.associateBy { it.id }
        val likedSongs = liked.mapNotNull { byId[it] }
        // seeds: prefer liked songs, then anything; one per main singer
        val pool = (likedSongs.shuffled(rnd) + all.shuffled(rnd))
        val seeds = pool.distinctBy { ArtistRepository.key(ArtistRepository.primary(it.artist)) }.take(3)
        return seeds.map { seed ->
            "${ArtistRepository.primary(seed.artist).lowercase()} radio" to build(seed, all, 20, rnd)
        }
    }
}
