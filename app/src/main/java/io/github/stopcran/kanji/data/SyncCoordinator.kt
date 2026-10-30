package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.RepoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SyncKind { Ordinary, Forced, Launch }
internal data class SyncRequest(val source: RepoSource, val kind: SyncKind)

/** Process-lifetime admission; callers may stop awaiting without cancelling accepted work. */
internal class SyncCoordinator(
    scope: CoroutineScope,
    private val engineActivity: StateFlow<Map<RepoSource, SyncActivityEvent>>,
    private val reportFailure: (Exception) -> Unit = {},
    execute: suspend (SyncRequest) -> SyncResult,
) {
    private class Pending(val request: SyncRequest, val result: CompletableDeferred<SyncResult> = CompletableDeferred())
    private data class Completion(val revision: Long?, val state: SyncActivity)
    private val lock = Any()
    private val pending = mutableListOf<Pending>()
    private val completed = mutableMapOf<RepoSource, Completion>()
    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private val _activity = MutableStateFlow(engineStates())
    val activity = _activity.asStateFlow()

    init {
        scope.launch {
            engineActivity.collect { synchronized(lock) { publish() } }
        }
        scope.launch {
            for (item in queue) {
                val source = item.request.source
                val before = engineActivity.value[source]
                val result = syncOutcome(reportFailure) { execute(item.request) }
                synchronized(lock) {
                    pending.remove(item)
                    // An executor failure before entering the engine must not revive an older success.
                    // Newer engine activity (including periodic work) remains authoritative.
                    if (engineActivity.value[source] == before && before?.state != SyncActivity.Running) {
                        completed[source] = Completion(before?.revision, SyncActivity.Finished(result))
                    }
                    publish()
                }
                item.result.complete(result)
            }
        }.invokeOnCompletion { error ->
            synchronized(lock) {
                queue.close(error)
                val cancelled = CancellationException("Sync owner stopped", error)
                pending.forEach { it.result.cancel(cancelled) }
                _activity.value = engineStates() + pending.associate { it.request.source to SyncActivity.Interrupted }
                pending.clear()
            }
        }
    }

    fun request(source: RepoSource, kind: SyncKind = SyncKind.Ordinary): Deferred<SyncResult> = synchronized(lock) {
        val request = SyncRequest(source, kind)
        // Do not coalesce across an intervening branch replacement, even when an older identical request is still active.
        pending.lastOrNull()?.takeIf { it.request == request }?.let { return@synchronized it.result }
        val item = Pending(request)
        pending += item
        publish()
        if (queue.trySend(item).isFailure) {
            pending.remove(item)
            _activity.value = engineStates() + (source to SyncActivity.Interrupted)
            item.result.cancel(CancellationException("Sync owner stopped"))
        }
        item.result
    }

    private fun publish() {
        val engine = engineActivity.value
        completed.entries.removeAll { (source, result) -> engine[source]?.revision != result.revision }
        _activity.value = engine.mapValues { it.value.state } + completed.mapValues { it.value.state } +
            pending.associate { it.request.source to SyncActivity.Running }
    }

    private fun engineStates(): Map<RepoSource, SyncActivity> = engineActivity.value.mapValues { it.value.state }
}
