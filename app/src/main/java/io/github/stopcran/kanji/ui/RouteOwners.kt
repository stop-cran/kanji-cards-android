package io.github.stopcran.kanji.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Retained by the Activity; coverage/rotation never clears a destination's store. */
class RouteOwners : ViewModel() {
    private val owners = mutableMapOf<String, RouteOwner>()
    private var visible: String? = null
    private var host = Lifecycle.State.CREATED

    fun owner(id: String): RouteOwner = owners.getOrPut(id) { RouteOwner() }.also {
        it.move(if (id == visible) host else Lifecycle.State.CREATED)
    }

    fun show(id: String?) {
        visible = id
        update()
    }

    fun hostState(state: Lifecycle.State) {
        // The Activity may be destroyed for rotation while this ViewModel and its entries survive.
        host = state.coerceAtLeast(Lifecycle.State.CREATED)
        update()
    }

    private fun update() = owners.forEach { (id, owner) -> owner.move(if (id == visible) host else Lifecycle.State.CREATED) }

    fun remove(id: String) { owners.remove(id)?.clear() }

    override fun onCleared() {
        owners.values.forEach { it.clear() }
        owners.clear()
    }
}

class RouteOwner : ViewModelStoreOwner, LifecycleOwner {
    override val viewModelStore = ViewModelStore()
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry

    internal fun move(state: Lifecycle.State) { registry.currentState = state }
    internal fun clear() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
