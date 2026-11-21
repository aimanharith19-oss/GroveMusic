package com.grove.music

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader

/**
 * Serves cover art and artist photos to Android Auto (which can't read MediaStore or web URLs itself).
 * content://com.grove.music.art/album/<albumId>   → MediaStore album art
 * content://com.grove.music.art/url/<encoded url> → picture from Grove's offline image cache
 */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    @OptIn(ExperimentalCoilApi::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val seg = uri.pathSegments
        return when (seg.firstOrNull()) {
            "album" -> {
                val id = seg.getOrNull(1)?.toLongOrNull() ?: return null
                val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), id)
                runCatching { ctx.contentResolver.openFileDescriptor(art, "r") }.getOrNull()
            }
            "url" -> {
                val url = Uri.decode(seg.getOrNull(1) ?: return null)
                val snap = ctx.imageLoader.diskCache?.openSnapshot(url) ?: return null
                snap.use { ParcelFileDescriptor.open(it.data.toFile(), ParcelFileDescriptor.MODE_READ_ONLY) }
            }
            else -> null
        }
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        private const val AUTH = "com.grove.music.art"
        fun album(id: Long): Uri = Uri.parse("content://$AUTH/album/$id")
        fun url(u: String): Uri = Uri.parse("content://$AUTH/url/" + Uri.encode(u))
    }
}
