package exh.debug

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.source.AndroidSourceManager
import eu.kanade.tachiyomi.util.system.workManager
import exh.util.ThrottleManager
import exh.util.jobScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator
import mihon.app.di.appGraph
import mihon.core.migration.MigrationContext
import mihon.core.migration.MigrationJobFactory
import mihon.core.migration.MigrationStrategyFactory
import mihon.core.migration.Migrator
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.domain.eh.repository.EhRepository
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetSearchMetadata
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.search.repository.SavedSearchRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/**
 * SY-specific file
 */
@Suppress("unused")
@Inject
class DebugFunctions(
    private val context: Context,
    private val ehRepository: EhRepository,
    private val savedSearchRepository: SavedSearchRepository,
    private val sourceManager: SourceManager,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val getFavorites: GetFavorites,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val insertFlatMetadata: InsertFlatMetadata,
    private val getSearchMetadata: GetSearchMetadata,
    private val getAllManga: GetAllManga,
) {

    fun forceUpgradeMigration(): Boolean {
        val migrationContext = MigrationContext(dryrun = false, 0)
        val migrationJobFactory = MigrationJobFactory(migrationContext, Migrator.scope)
        val migrationStrategyFactory = MigrationStrategyFactory(migrationJobFactory, {})
        val strategy = migrationStrategyFactory.create(1, BuildConfig.VERSION_CODE)
        return runBlocking { strategy(context.appGraph.migrations.toList()).await() }
    }

    fun forceSetupJobs(): Boolean {
        val migrationContext = MigrationContext(dryrun = false, 0)
        val migrationJobFactory = MigrationJobFactory(migrationContext, Migrator.scope)
        val migrationStrategyFactory = MigrationStrategyFactory(migrationJobFactory, {})
        val strategy = migrationStrategyFactory.create(0, BuildConfig.VERSION_CODE)
        return runBlocking { strategy(context.appGraph.migrations.toList()).await() }
    }

    fun getDelegatedSourceList(): String = AndroidSourceManager.currentDelegatedSources.map {
        it.value.sourceName + " : " + it.value.sourceIds + " : " + it.value.factory
    }.joinToString(separator = "\n")

    fun addAllMangaInDatabaseToLibrary() {
        runBlocking { ehRepository.addAllMangaInDatabaseToLibrary() }
    }

    fun countMangaInDatabaseInLibrary() = runBlocking { getFavorites.await().size }

    fun countMangaInDatabaseNotInLibrary() = runBlocking { getAllManga.await() }.count { !it.favorite }

    fun countMangaInDatabase() = runBlocking { getAllManga.await() }.size

    fun countMetadataInDatabase() = runBlocking { getSearchMetadata.await().size }

    fun countMangaInLibraryWithMissingMetadata() = runBlocking {
        getAllManga.await().count {
            it.favorite && getSearchMetadata.await(it.id) == null
        }
    }

    fun clearSavedSearches() = runBlocking { savedSearchRepository.deleteAll() }

    fun listAllSources() = runBlocking {
        sourceManager.getAll().joinToString("\n") {
            "${it.id}: ${it.name} (${it.lang.uppercase()})"
        }
    }

    fun listAllSourcesClassName() = runBlocking {
        sourceManager.getAll().joinToString("\n") {
            "${it::class.qualifiedName}: ${it.name} (${it.lang.uppercase()})"
        }
    }

    fun listVisibleSources() = runBlocking {
        sourceManager.getVisibleSources().joinToString("\n") {
            "${it.id}: ${it.name} (${it.lang.uppercase()})"
        }
    }

    fun listAllHttpSources() = runBlocking {
        sourceManager.getOnlineSources().joinToString("\n") {
            "${it.id}: ${it.name} (${it.lang.uppercase()})"
        }
    }
    fun listVisibleHttpSources() = runBlocking {
        sourceManager.getVisibleOnlineSources().joinToString("\n") {
            "${it.id}: ${it.name} (${it.lang.uppercase()})"
        }
    }

    fun listScheduledJobs() = context.jobScheduler.allPendingJobs.joinToString(",\n") { j ->
        val info = j.extras.getString("EXTRA_WORK_SPEC_ID")?.let {
            context.workManager.getWorkInfoById(UUID.fromString(it)).get()
        }

        if (info != null) {
            """
            {
                id: ${info.id},
                isPeriodic: ${j.extras.getBoolean("EXTRA_IS_PERIODIC")},
                state: ${info.state.name},
                tags: [
                    ${info.tags.joinToString(separator = ",\n                    ")}
                ],
            }
            """.trimIndent()
        } else {
            """
            {
                info: ${j.id},
                isPeriodic: ${j.isPeriodic},
                isPersisted: ${j.isPersisted},
                intervalMillis: ${j.intervalMillis},
            }
            """.trimIndent()
        }
    }

    fun cancelAllScheduledJobs() = context.jobScheduler.cancelAll()

    private fun convertSources(from: Long, to: Long) {
        runBlocking {
            ehRepository.migrateSource(to, from)
        }
    }


    fun fixReaderViewerBackupBug() {
        runBlocking { ehRepository.fixReaderViewerBackupBug() }
    }

    fun resetReaderViewerForAllManga() {
        runBlocking { ehRepository.resetReaderViewerForAllManga() }
    }

    fun exportProtobufScheme() = ProtoBufSchemaGenerator.generateSchemaText(Backup.serializer().descriptor)

    fun killSyncJobs() {
        val context = Injekt.get<Context>()
        context.appGraph.appScope.launch { SyncDataJob.stop(context) }
    }

    fun killLibraryJobs() {
        val context = Injekt.get<Context>()
        context.appGraph.appScope.launch { LibraryUpdateWorker.stop(context) }
    }
}
