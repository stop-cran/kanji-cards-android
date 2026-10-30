package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.data.HomeRepository
import io.github.stopcran.kanji.data.HomeSelection
import io.github.stopcran.kanji.data.SyncActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

internal data class HomeState(val data: HomeData, val content: HomeContentState, val selection: HomeSelection? = null) {
    companion object {
        fun loading(source: RepoSource) = HomeState(HomeData.loading(source.id), HomeContentState(null, null))
    }
}

/** One cold, selection-bound producer. Leaving Home cancels work, not cached content or sync. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HomeStateProducer(
    repository: HomeRepository,
    activity: Flow<Map<RepoSource, SyncActivity>>,
    clock: Clock = Clock.systemDefaultZone(),
    ticks: Flow<Instant> = flow { while (true) { emit(clock.instant()); delay(60_000) } },
) {
    val states: Flow<HomeState> = repository.selection.distinctUntilChanged().flatMapLatest { selection ->
        val projection = ticks.flatMapLatest { now ->
            repository.observe(selection, now, clock.zone).map { snapshot ->
                withContext(Dispatchers.Default) { projectHome(snapshot, now) }
            }.catch { error ->
                currentCoroutineContext().ensureActive()
                throw error
            }
        }.onStart { emit(HomeData.loading(selection.source.id)) }
        combine(
            projection, repository.ready(selection.source).map<Boolean, Boolean?> { it }.onStart { emit(null) }, activity,
        ) { data, ready, activities -> HomeState(data, HomeContentState(ready, activities[selection.source]), selection) }
    }.buffer(0)
}

@Composable
internal fun rememberHomeState(
    app: Services,
    activity: Flow<Map<RepoSource, SyncActivity>>,
    clock: Clock = Clock.systemDefaultZone(),
): HomeState = key(app, clock) {
    val producer = remember(activity) { HomeStateProducer(app.home, activity, clock) }
    val state by producer.states.collectAsState(HomeState.loading(app.home.currentSource))
    state
}

@Composable
internal fun rememberHomeData(app: Services, clock: Clock = Clock.systemDefaultZone()): HomeData {
    val activity = remember { flowOf(emptyMap<RepoSource, SyncActivity>()) }
    return rememberHomeState(app, activity, clock).data
}
