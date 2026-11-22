package com.grove.music

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Background playback (ExoPlayer decodes MP3, AAC, OGG, OPUS, WAV and FLAC incl. 24-bit hi-res).
 * Runs as a MediaLibraryService so Android treats Grove as a real music app: system media controls,
 * lock screen, Bluetooth / headset buttons, playback resumption after reboot, Android Auto and other
 * media browsers can all see and control it.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var library: List<Song>? = null

    @Synchronized private fun songs(): List<Song> = library ?: runCatching { MusicRepository.query(this) }.getOrDefault(emptyList())
        .also { ArtistRepository.learn(it) }
        .let { val p = Prefs(this); visibleSongs(it, p.hiddenFolders, p.preferFlac) }.also { library = it }

    /** car / browser requests are answered off the main thread so Android Auto never times out with an empty list */
    private val bg = com.google.common.util.concurrent.MoreExecutors.listeningDecorator(java.util.concurrent.Executors.newFixedThreadPool(2))

    private fun byId() = songs().associateBy { it.id.toString() }

    // ── browse tree for Android Auto & other media browsers ────────────────
    // root → playlists / albums / artists / songs
    // playable ids are "<parent>::<songId>" so tapping a song plays its whole list from there

    private fun folder(id: String, title: String, art: Uri? = null, sub: String? = null, grid: Boolean = false) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(sub).setArtworkUri(art)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(Bundle().apply {
                    putInt(STYLE_BROWSABLE, if (grid) 2 else 1); putInt(STYLE_PLAYABLE, 1)
                }).build()
        ).build()

    private fun playable(parent: String, s: Song): MediaItem {
        val base = s.toMediaItem()
        return base.buildUpon().setMediaId("$parent::${s.id}")
            .setMediaMetadata(base.mediaMetadata.buildUpon().setArtworkUri(ArtProvider.album(s.albumId)).build()).build()
    }

    private fun artistKey(s: Song) = ArtistRepository.key(ArtistRepository.primary(s.artist))

    /** songs belonging to a browse folder, in play order */
    private fun songsOf(parent: String): List<Song> {
        val all = songs()
        return when {
            parent == "songs" -> all.sortedBy { it.title.lowercase() }
            parent.startsWith("al:") -> all.filter { it.albumId.toString() == parent.drop(3) }.let { l ->
                AlbumOrder.sort(l, l.firstOrNull()?.let { AlbumOrder.cached(this, it.album, it.artist) })
            }
            parent.startsWith("ar:") -> parent.drop(3).let { k ->
                all.filter { s -> ArtistRepository.split(s.artist).any { ArtistRepository.key(it) == k } }
                    .sortedWith(compareBy({ it.album.lowercase() }, { it.track }))
            }
            parent.startsWith("pl:") -> PlaylistStore.get(this).get(parent.drop(3))?.ids?.let { ids ->
                val m = byId(); ids.mapNotNull { m[it.toString()] }
            } ?: emptyList()
            parent == "search" -> lastSearch
            parent.startsWith("mix:") -> mixes().getOrNull(parent.drop(4).toIntOrNull() ?: -1)?.second ?: emptyList()
            else -> emptyList()
        }
    }

    private fun children(parent: String): List<MediaItem> = when (parent) {
        ROOT -> listOf(
            folder("playlists", "Playlists"), folder("albums", "Albums", grid = true),
            folder("artists", "Artists", grid = true), folder("songs", "Songs"),
        )
        "playlists" -> mixes().mapIndexed { i, (name, l) ->
            folder("mix:$i", name, sub = "${l.size} songs · radio mix", art = l.firstOrNull()?.let { ArtProvider.album(it.albumId) })
        } + PlaylistStore.get(this).lists.value.map { p ->
            folder("pl:${p.id}", p.name, sub = "${p.ids.size} songs",
                art = p.ids.firstNotNullOfOrNull { byId()[it.toString()] }?.let { ArtProvider.album(it.albumId) })
        }
        "albums" -> songs().groupBy { it.albumId }.values.sortedBy { it[0].album.lowercase() }.map { l ->
            folder("al:${l[0].albumId}", l[0].album, ArtProvider.album(l[0].albumId), l[0].artist, grid = true)
        }
        "artists" -> songs().groupBy { artistKey(it) }.map { (k, l) ->
            val name = l.map { ArtistRepository.primary(it.artist) }.groupingBy { it }.eachCount().maxBy { it.value }.key
            val img = ArtistRepository.cached(this, name)?.image
            folder("ar:$k", name, img?.let { ArtProvider.url(it) } ?: ArtProvider.album(l[0].albumId), "${l.size} songs", grid = true)
        }.sortedBy { it.mediaMetadata.title.toString().lowercase() }
        else -> songsOf(parent).map { playable(parent, it) }
    }

    // ── live lyrics in Android Auto ─────────────────────────────────────────
    // The car can't show custom screens, so while Auto is connected the line being sung
    // replaces the artist line under the song title (restored when the car disconnects).
    private val scope = kotlinx.coroutines.MainScope()
    private var cars = 0
    private var lyricJob: kotlinx.coroutines.Job? = null

    private fun isCar(c: MediaSession.ControllerInfo) =
        c.packageName.contains("gearhead") || c.packageName.contains("android.car") || c.packageName.contains("automotive")

    private fun startCarLyrics(player: Player) {
        if (lyricJob?.isActive == true || !Prefs(this).carLyrics) return
        lyricJob = scope.launch {
            var forId: String? = null
            var lyrics: Lyrics? = null
            var shown: String? = null
            while (true) {
                val item = player.currentMediaItem
                if (item != null) {
                    if (item.mediaId != forId) {
                        forId = item.mediaId; shown = null
                        val song = byId()[item.mediaId]
                        lyrics = LyricsRepository.load(this@PlaybackService, trackKey(item, song, player.duration.coerceAtLeast(0)))
                    }
                    val l = lyrics
                    val line = if (l != null && l.synced) {
                        val i = l.lines.indexOfLast { it.time <= player.currentPosition + 200 }
                        l.lines.getOrNull(i)?.text?.takeIf { it.isNotBlank() }?.let { "♪ $it" }
                    } else null
                    val artist = byId()[item.mediaId]?.artist ?: item.mediaMetadata.artist?.toString()
                    val want = line ?: artist
                    if (want != shown && want != null) {
                        shown = want
                        setArtistLine(player, want)
                    }
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    private fun stopCarLyrics(player: Player) {
        lyricJob?.cancel(); lyricJob = null
        val item = player.currentMediaItem ?: return
        byId()[item.mediaId]?.artist?.let { setArtistLine(player, it) }
    }

    private fun setArtistLine(player: Player, text: String) {
        val idx = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        if (item.mediaMetadata.artist?.toString() == text) return
        player.replaceMediaItem(idx, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtist(text).build()).build())
    }

    private var mixCache: Pair<String, List<Pair<String, List<Song>>>>? = null
    @Synchronized private fun mixes(): List<Pair<String, List<Song>>> {
        val p = Prefs(this)
        val key = "${java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)}|${p.mixSalt}|${songs().size}"
        mixCache?.takeIf { it.first == key }?.let { return it.second }
        return Radio.dailyMixes(songs(), PlaylistStore.get(this).liked.ids, p.mixSalt, { Meta.genreOf(this, it) }).also { mixCache = key to it }
    }

    // ── pause when the media volume is turned all the way down; resume when it comes back up ──
    private var muteReceiver: android.content.BroadcastReceiver? = null
    private fun watchMute(player: ExoPlayer, prefs: Prefs) {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        var pausedByMute = false
        muteReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                if (!prefs.pauseOnMute) return
                val v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                if (v == 0 && player.isPlaying) { pausedByMute = true; player.pause() }
                else if (v > 0 && pausedByMute) { pausedByMute = false; player.play() }
            }
        }
        val f = android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(muteReceiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(muteReceiver, f)
    }

    // ── crossfade ───────────────────────────────────────────────────────────
    // Near the end of a song, a second (session-less) player takes over the outgoing song's tail and fades
    // it out while the main player moves on to the next song and fades in — so the two overlap.
    private var fader: ExoPlayer? = null

    private var blendJob: kotlinx.coroutines.Job? = null
    private lateinit var xPrefs: Prefs

    /** fade the current song out on the side player while [skip] moves the main player on and fades in */
    private fun blend(player: ExoPlayer, skip: () -> Unit) {
        val f = fader
        val ms = xPrefs.crossfade * 1000L
        val item = player.currentMediaItem
        if (f == null || ms <= 0 || perfectNow || !player.isPlaying || item == null) { skip(); return }
        blendJob?.cancel(); f.stop(); player.volume = 1f
        val from = player.currentPosition
        blendJob = scope.launch {
            f.setMediaItem(item, from); f.volume = 1f; f.playWhenReady = false; f.prepare()
            var waited = 0
            while (f.playbackState != Player.STATE_READY && waited < 600) { kotlinx.coroutines.delay(20); waited += 20 }
            if (f.playbackState != Player.STATE_READY) { f.stop(); skip(); return@launch }
            f.seekTo(player.currentPosition); f.play()
            player.volume = 0f
            skip()
            try {
                val steps = (ms / 50).coerceAtLeast(1)
                for (i in 1..steps) {
                    kotlinx.coroutines.delay(50)
                    if (!player.playWhenReady) { f.pause(); while (!player.playWhenReady) kotlinx.coroutines.delay(100); f.play() }
                    val x = i.toFloat() / steps
                    // equal-power curve keeps the loudness steady through the blend
                    f.volume = kotlin.math.cos(x * Math.PI / 2).toFloat()
                    player.volume = kotlin.math.sin(x * Math.PI / 2).toFloat()
                }
            } finally { f.stop(); f.clearMediaItems(); player.volume = 1f }
        }
    }

    private fun startCrossfade(player: ExoPlayer, prefs: Prefs) {
        xPrefs = prefs
        fader = ExoPlayer.Builder(this, renderers(prefs)).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(GroveDataSource.Factory(this))).setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false
        ).build()
        // automatic: near the end of a song
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(100)
                val ms = prefs.crossfade * 1000L
                if (ms <= 0 || perfectNow || !player.isPlaying || blendJob?.isActive == true || player.repeatMode == Player.REPEAT_MODE_ONE || !player.hasNextMediaItem()) continue
                // gapless albums: songs of the same album flow straight into each other, no fade
                if (prefs.gaplessAlbums && !player.shuffleModeEnabled) {
                    val next = player.getMediaItemAt(player.nextMediaItemIndex).mediaMetadata.albumTitle
                    if (next != null && next == player.mediaMetadata.albumTitle && next != "unknown album") continue
                }
                val dur = player.duration
                if (dur <= 0 || dur < ms * 2 || dur - player.currentPosition > ms) continue
                blend(player) { player.seekToNextMediaItem() }
                kotlinx.coroutines.delay(ms + 300)
            }
        }
    }

    /** the player the app, notification and car control: skips go through [blend] so they crossfade too */
    private fun xfade(p: ExoPlayer) = object : androidx.media3.common.ForwardingPlayer(p) {
        override fun seekToNext() = blend(p) { p.seekToNext() }
        override fun seekToNextMediaItem() = blend(p) { p.seekToNextMediaItem() }
        override fun seekToPrevious() { if (p.currentPosition > 3000) p.seekToPrevious() else blend(p) { p.seekToPrevious() } }
        override fun seekToPreviousMediaItem() = blend(p) { p.seekToPreviousMediaItem() }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItemIndex != p.currentMediaItemIndex) blend(p) { p.seekTo(mediaItemIndex, positionMs) } else p.seekTo(mediaItemIndex, positionMs)
        }
    }

    private var lastSearch: List<Song> = emptyList()
    private fun search(q: String): List<Song> {
        val t = q.trim().lowercase()
        lastSearch = if (t.isEmpty()) songs().shuffled() else songs().filter {
            it.title.lowercase().contains(t) || it.artist.lowercase().contains(t) || it.album.lowercase().contains(t)
        }
        return lastSearch
    }

    /** turns browse ids ("albums::123") back into playable items */
    private fun resolve(items: List<MediaItem>): MutableList<MediaItem> {
        val m by lazy { byId() }
        return items.mapNotNull { item ->
            when {
                item.requestMetadata.mediaUri != null -> item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
                item.localConfiguration != null -> item
                item.requestMetadata.searchQuery != null -> search(item.requestMetadata.searchQuery!!).firstOrNull()?.toMediaItem()
                else -> m[item.mediaId.substringAfterLast("::")]?.toMediaItem()
            }
        }.toMutableList()
    }

    /** true while a USB DAC plays bit-perfect: no software volume, so no crossfade */
    @Volatile private var perfectNow = false

    private fun renderers(prefs: Prefs) = androidx.media3.exoplayer.DefaultRenderersFactory(this)
        .setEnableAudioFloatOutput(prefs.hiRes || prefs.bitPerfect)

    /** output check: bit-perfect USB, hi-res path, and what the now-playing screen shows */
    private fun checkOutput(player: ExoPlayer, prefs: Prefs) {
        val f = player.audioFormat
        val dsd = player.currentMediaItem?.localConfiguration?.uri?.scheme == Dsd.SCHEME
        val bits = when (f?.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24; C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> if (dsd) 24 else 0
        }
        val rate = f?.sampleRate?.takeIf { it > 0 } ?: 0
        perfectNow = AudioOut.update(this, prefs, rate, bits, prefs.hiRes || prefs.bitPerfect,
            if (dsd && rate > 0) "DSD → ${rate / 1000.0} kHz 24-bit" else "")
        if (perfectNow) player.volume = 1f
    }

    override fun onCreate() {
        super.onCreate()
        val prefs0 = Prefs(this)
        val player = ExoPlayer.Builder(this, renderers(prefs0))
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(GroveDataSource.Factory(this)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val prefs = Prefs(this)
        startCrossfade(player, prefs)
        bg.submit { songs(); runCatching { mixes() } }
        // listening history for the stats tab
        scope.launch {
            var id: Long? = null; var start = 0L; var heard = 0L
            while (true) {
                kotlinx.coroutines.delay(5000)
                val cur = player.currentMediaItem?.mediaId?.substringAfterLast("::")?.toLongOrNull()
                if (cur != id) {
                    id?.let { StatsLog.record(this@PlaybackService, start, it, heard) }
                    id = cur; start = System.currentTimeMillis(); heard = 0
                }
                if (player.isPlaying) heard += 5000
                flushStats = { id?.let { StatsLog.record(this@PlaybackService, start, it, heard) }; heard = 0 }
            }
        }
        watchMute(player, prefs)
        // re-check bit-perfect / hi-res when a DAC or earbuds are plugged in or removed
        getSystemService(android.media.AudioManager::class.java).registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(d: Array<out android.media.AudioDeviceInfo>?) { checkOutput(player, prefs) }
            override fun onAudioDevicesRemoved(d: Array<out android.media.AudioDeviceInfo>?) { checkOutput(player, prefs) }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        // remember the queue so Android can offer "resume" in the media controls later
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { checkOutput(player, prefs) }
            override fun onMediaMetadataChanged(m: androidx.media3.common.MediaMetadata) {
                Widgets.save(this@PlaybackService, m.title?.toString(), m.artist?.toString(), m.artworkUri, player.isPlaying)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                session?.setCustomLayout(buttons(player))
                val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                prefs.saveQueue(ids, player.currentMediaItemIndex, 0)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                player.mediaMetadata.let { m -> Widgets.save(this@PlaybackService, m.title?.toString(), m.artist?.toString(), m.artworkUri, isPlaying) }
                if (!isPlaying && player.mediaItemCount > 0) prefs.saveQueuePosition(player.currentMediaItemIndex, player.currentPosition)
            }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val root = MediaItem.Builder().setMediaId(ROOT).setMediaMetadata(
            MediaMetadata.Builder().setTitle("GroveMedia").setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
        ).build()

        session = MediaLibrarySession.Builder(this, xfade(player), object : MediaLibrarySession.Callback {
            override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (isCar(c)) { cars++; startCarLyrics(s.player) }
                val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(CMD_LIKE).add(CMD_RADIO).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                    .setAvailableSessionCommands(cmds).setCustomLayout(buttons(s.player)).build()
            }

            // like / radio buttons in the notification, lock screen and car
            override fun onCustomCommand(s: MediaSession, c: MediaSession.ControllerInfo, cmd: SessionCommand, args: Bundle)
                : ListenableFuture<SessionResult> {
                val p = s.player
                val song = p.currentMediaItem?.mediaId?.substringAfterLast("::")?.let { byId()[it] }
                    ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                when (cmd.customAction) {
                    CMD_LIKE.customAction -> PlaylistStore.get(this@PlaybackService).toggleLike(song.id)
                    CMD_RADIO.customAction -> {
                        // keep the current song playing; the radio follows it
                        val rest = Radio.build(song, songs()).drop(1).map { it.toMediaItem() }
                        p.shuffleModeEnabled = false
                        val idx = p.currentMediaItemIndex
                        if (idx + 1 < p.mediaItemCount) p.removeMediaItems(idx + 1, p.mediaItemCount)
                        if (idx > 0) p.removeMediaItems(0, idx)
                        p.addMediaItems(rest)
                    }
                }
                s.setCustomLayout(buttons(p))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onDisconnected(s: MediaSession, c: MediaSession.ControllerInfo) {
                if (isCar(c)) { cars = (cars - 1).coerceAtLeast(0); if (cars == 0) stopCarLyrics(s.player) }
            }

            override fun onGetLibraryRoot(s: MediaLibrarySession, b: MediaSession.ControllerInfo, params: LibraryParams?)
                : ListenableFuture<LibraryResult<MediaItem>> {
                bg.submit { synchronized(this@PlaybackService) { library = null; mixCache = null }; songs(); mixes() } // refresh quietly
                val extras = Bundle().apply {
                    putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                    putInt(STYLE_BROWSABLE, 1); putInt(STYLE_PLAYABLE, 1)
                }
                return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
            }

            override fun onGetChildren(s: MediaLibrarySession, b: MediaSession.ControllerInfo, parentId: String,
                                       page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                return bg.submit<LibraryResult<ImmutableList<MediaItem>>> {
                    val all = runCatching { children(parentId) }.getOrDefault(emptyList())
                    val list = if (pageSize in 1 until Int.MAX_VALUE) all.drop(page * pageSize).take(pageSize) else all
                    LibraryResult.ofItemList(ImmutableList.copyOf(list), params)
                }
            }

            override fun onGetItem(s: MediaLibrarySession, b: MediaSession.ControllerInfo, mediaId: String)
                : ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(
                    byId()[mediaId.substringAfterLast("::")]?.let { LibraryResult.ofItem(it.toMediaItem(), null) }
                        ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )

            override fun onSearch(s: MediaLibrarySession, b: MediaSession.ControllerInfo, query: String, params: LibraryParams?)
                : ListenableFuture<LibraryResult<Void>> {
                val n = search(query).size
                s.notifySearchResultChanged(b, query, n, params)
                return Futures.immediateFuture(LibraryResult.ofVoid())
            }

            override fun onGetSearchResult(s: MediaLibrarySession, b: MediaSession.ControllerInfo, query: String,
                                           page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                return bg.submit<LibraryResult<ImmutableList<MediaItem>>> {
                    val list = search(query).drop(page * pageSize).take(pageSize).map { playable("search", it) }
                    LibraryResult.ofItemList(ImmutableList.copyOf(list), params)
                }
            }

            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                         mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(resolve(mediaItems))

            // tapping one song in a car list plays that whole list, starting at the tapped song
            override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                         mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long)
                : ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val one = mediaItems.singleOrNull()
                if (one != null && one.mediaId.contains("::")) {
                    val parent = one.mediaId.substringBeforeLast("::")
                    val id = one.mediaId.substringAfterLast("::")
                    val list = songsOf(parent)
                    val idx = list.indexOfFirst { it.id.toString() == id }
                    if (idx >= 0) return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, idx, startPositionMs))
                }
                if (one != null && one.requestMetadata.searchQuery != null) {
                    val list = search(one.requestMetadata.searchQuery!!)
                    if (list.isNotEmpty()) return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, 0, 0))
                }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(resolve(mediaItems),
                    if (startIndex == C.INDEX_UNSET) 0 else startIndex, startPositionMs))
            }

            override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo)
                : ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val (ids, index, pos) = prefs.loadQueue()
                val m = byId()
                val items = ids.mapNotNull { m[it]?.toMediaItem() }
                val start = if (items.isEmpty()) 0 else index.coerceIn(0, items.lastIndex)
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, start, pos))
            }
        }).setSessionActivity(open).build()
        // keep the notification heart in step when a song is liked inside the app
        scope.launch {
            androidx.compose.runtime.snapshotFlow { PlaylistStore.get(this@PlaybackService).liked.ids }
                .collect { session?.let { s -> s.setCustomLayout(buttons(s.player)) } }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    private var flushStats: (() -> Unit)? = null

    override fun onDestroy() {
        flushStats?.invoke()
        scope.cancel(); bg.shutdownNow()
        fader?.release(); fader = null
        muteReceiver?.let { runCatching { unregisterReceiver(it) } }
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private fun buttons(p: Player): ImmutableList<CommandButton> {
        val id = p.currentMediaItem?.mediaId?.substringAfterLast("::")?.toLongOrNull()
        val liked = id != null && PlaylistStore.get(this).isLiked(id)
        return ImmutableList.of(
            CommandButton.Builder().setDisplayName(if (liked) "Unlike" else "Like")
                .setIconResId(if (liked) R.drawable.ic_n_heart else R.drawable.ic_n_heart_off).setSessionCommand(CMD_LIKE).build(),
            CommandButton.Builder().setDisplayName("Start radio")
                .setIconResId(R.drawable.ic_n_radio).setSessionCommand(CMD_RADIO).build(),
        )
    }

    companion object {
        val CMD_LIKE = SessionCommand("grove.LIKE", Bundle.EMPTY)
        val CMD_RADIO = SessionCommand("grove.RADIO", Bundle.EMPTY)
        const val ROOT = "root"
        const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    }
}

/** UI-side handle to the playback service, exposing Compose state. */
class PlayerConnection(private val ctx: Context) {
    val current = mutableStateOf<MediaItem?>(null)
    val isPlaying = mutableStateOf(false)
    val shuffle = mutableStateOf(false)
    val repeatMode = mutableIntStateOf(Player.REPEAT_MODE_OFF)
    val upNext = mutableStateOf<List<MediaItem>>(emptyList())
    /** upcoming songs in play order, with their position in the player's list */
    val queue = mutableStateOf<List<Pair<Int, MediaItem>>>(emptyList())

    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    private val future = MediaController.Builder(
        ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
    ).buildAsync()

    init {
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            sync(c)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun run(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.add(block)
    }

    private fun sync(p: Player) {
        current.value = p.currentMediaItem
        isPlaying.value = p.isPlaying
        shuffle.value = p.shuffleModeEnabled
        repeatMode.intValue = p.repeatMode
        val t = p.currentTimeline
        val next = ArrayList<Pair<Int, MediaItem>>()
        if (!t.isEmpty && p.currentMediaItemIndex != C.INDEX_UNSET) {
            var i = p.currentMediaItemIndex
            while (next.size < 500) {
                i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
                if (i == C.INDEX_UNSET) break
                next += i to p.getMediaItemAt(i)
            }
        }
        queue.value = next
        upNext.value = next.take(5).map { it.second }
    }

    fun playSongs(songs: List<Song>, start: Int, shuffled: Boolean = false) = run {
        if (songs.isEmpty()) return@run
        it.shuffleModeEnabled = shuffled
        it.setMediaItems(songs.map { s -> s.toMediaItem() }, if (shuffled) songs.indices.random() else start, 0)
        it.prepare(); it.play()
    }

    /** Radio without interrupting: keep the current song playing, replace everything after it with the radio list. */
    fun startRadio(list: List<Song>) = run { p ->
        val cur = p.currentMediaItem
        if (cur == null || p.mediaItemCount == 0) { playSongs(list, 0); return@run }
        val rest = list.filter { it.id.toString() != cur.mediaId }.map { it.toMediaItem() }
        p.shuffleModeEnabled = false
        val idx = p.currentMediaItemIndex
        if (idx + 1 < p.mediaItemCount) p.removeMediaItems(idx + 1, p.mediaItemCount)
        if (idx > 0) p.removeMediaItems(0, idx)
        p.addMediaItems(rest)
        if (!p.isPlaying) p.play()
    }

    fun playUri(uri: Uri) {
        var name = uri.lastPathSegment ?: "track"
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        }
        val item = MediaItem.Builder().setMediaId(uri.toString()).setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(name.substringBeforeLast('.'))
                    .setExtras(android.os.Bundle().apply {
                        putString("name", name); putString("fmt", formatOf(null, name))
                    }).build()
            ).build()
        run { it.setMediaItem(item); it.prepare(); it.play() }
    }

    fun playAt(index: Int) = run { it.seekTo(index, 0); it.play() }
    fun removeAt(index: Int) = run { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }
    fun playPause() = run { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
    fun next() = run { it.seekToNext() }
    fun prev() = run { it.seekToPrevious() }
    fun seekTo(ms: Long) = run { it.seekTo(ms.coerceAtLeast(0)) }
    fun toggleShuffle() = run { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = run {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun position(): Long = controller?.currentPosition ?: 0L
    fun duration(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L

    fun release() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
    }
}
