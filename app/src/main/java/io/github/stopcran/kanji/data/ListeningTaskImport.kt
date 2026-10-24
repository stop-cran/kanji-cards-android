package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.ParsedContent
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.listening.ListeningTaskContent

/** Called inside the same transaction as ordinary content replacement. Task rejection preserves this catalog independently. */
internal suspend fun AppDatabase.importListeningTasks(parsed: ParsedContent, source: RepoSource, revision: String?): List<String> {
    val dao = listeningTasks()
    val old = dao.sync(source.id)
    val catalog = parsed.listeningTasks
    val bindings = dao.identities(source.id).associate { it.taskId to it.exposureId }
    val legacyIds = listening().knownPassageIds(source.id).toSet()
    val problems = catalog.problems + catalog.records.mapNotNull { record ->
        val previous = bindings[record.task.id]
        when {
            record.task.id in legacyIds -> "Listening task ${record.task.id} reused a legacy scored identity"
            previous != null && previous != record.task.exposureId -> "Listening task ${record.task.id} changed its exposure identity"
            else -> null
        }
    }
    if (problems.isNotEmpty()) {
        catalog.unsupported.forEach { dao.blockTask(source.id, it.id, it.reason) }
        val message = "Japanese listening tasks rejected; keeping the last accepted task catalog.\n${problems.joinToString("\n")}"
        dao.putSync(ListeningTaskSyncEntity(source.id, old?.acceptedBranch, old?.acceptedRevision, old?.acceptedContentVersion,
            old?.parserEpoch ?: 0, source.branch, revision, message, System.currentTimeMillis()))
        return listOf(message)
    }
    val rows = catalog.records.mapIndexed { index, record ->
        val p = record.task
        ListeningTaskEntity(source.id, p.id, record.json, record.contentHash, p.exposureId, p.taskType.wire,
            p.title, p.usage.name, record.estimatedMs, record.ready, record.reason, index)
    } + catalog.unsupported.mapIndexed { index, task ->
        ListeningTaskEntity(source.id, task.id, null, null, null, null, "Unsupported Japanese listening task",
            null, null, false, task.reason, catalog.records.size + index)
    }
    dao.clearTasks(source.id)
    dao.clearCatalogPins(source.id)
    dao.putTasks(rows)
    dao.putAssets(catalog.assets.map { (hash, bytes) -> ListeningTaskAssetEntity(source.id, hash, bytes) })
    dao.pin(catalog.records.flatMap { record -> record.assetHashes.map { ListeningAssetPinEntity(source.id, "catalog", record.task.id, it) } })
    dao.putIdentities(catalog.records.map { ListeningTaskIdentityEntity(source.id, it.task.id, it.task.exposureId) })
    dao.putSync(ListeningTaskSyncEntity(source.id, source.branch, revision, parsed.manifest.contentVersion,
        ListeningTaskContent.PARSER_EPOCH, source.branch, revision, null, System.currentTimeMillis()))
    dao.pruneAssets(source.id)
    return catalog.unsupported.map { "${it.id}: ${it.reason}" }
}
