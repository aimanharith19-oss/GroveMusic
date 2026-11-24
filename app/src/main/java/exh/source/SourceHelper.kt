package exh.source

import eu.kanade.tachiyomi.source.AndroidSourceManager
import eu.kanade.tachiyomi.source.online.all.Lanraragi
import eu.kanade.tachiyomi.source.online.all.MangaDex

/**
 * Source helpers
 */

private val DELEGATED_METADATA_SOURCES by lazy {
    listOf(
        Lanraragi::class,
    )
}

fun handleSourceLibrary() {
    metadataDelegatedSourceIds = AndroidSourceManager.currentDelegatedSources
        .filter {
            it.value.newSourceClass in DELEGATED_METADATA_SOURCES
        }
        .flatMap { it.value.sourceIds }
        .distinct()
        .sorted()

    mangaDexSourceIds = AndroidSourceManager.currentDelegatedSources
        .filter {
            it.value.newSourceClass == MangaDex::class
        }
        .flatMap { it.value.sourceIds }
        .toSet()

    lanraragiSourceIds = AndroidSourceManager.currentDelegatedSources
        .filter {
            it.value.newSourceClass == Lanraragi::class
        }
        .flatMap { it.value.sourceIds }
        .toSet()

    LIBRARY_UPDATE_EXCLUDED_SOURCES = emptyList()
}
