package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun RouteLifecycle(owners: RouteOwners, visible: String?) {
    val host = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(host, owners) {
        val observer = LifecycleEventObserver { _, _ -> owners.hostState(host.currentState) }
        host.addObserver(observer)
        owners.hostState(host.currentState)
        onDispose {
            host.removeObserver(observer)
            owners.hostState(Lifecycle.State.CREATED)
        }
    }
    SideEffect { owners.show(visible) }
}
