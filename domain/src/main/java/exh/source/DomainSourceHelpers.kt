package exh.source

import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.manga.model.Manga

/**
 * SY-specific file
 */
// Used to speed up isLewdSource
var metadataDelegatedSourceIds: List<Long> = emptyList()

var lanraragiSourceIds: Set<Long> = emptySet()

var mangaDexSourceIds: Set<Long> = emptySet()

var LIBRARY_UPDATE_EXCLUDED_SOURCES: List<Long> = emptyList()

// This method MUST be fast!
fun isMetadataSource(source: Long) = metadataDelegatedSourceIds.binarySearch(source) >= 0

fun Source.isMdBasedSource() = id in mangaDexSourceIds

fun Source.getMainSource(): Source = if (this is EnhancedHttpSource) {
    this.source()
} else {
    this
}

@JvmName("getMainSourceInline")
inline fun <reified T : Source> Source.getMainSource(): T? = if (this is EnhancedHttpSource) {
    this.source() as? T
} else {
    this as? T
}

inline fun <reified T> Source.anyIs(): Boolean {
    return if (this is EnhancedHttpSource) {
        originalSource is T || enhancedSource is T
    } else {
        this is T
    }
}
