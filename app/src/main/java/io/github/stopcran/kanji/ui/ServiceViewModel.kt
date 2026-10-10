package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.Services

/** A session ViewModel built with injected [Services], keyed per session. */
@Composable
inline fun <reified VM : ViewModel> serviceViewModel(key: String, crossinline create: (Services) -> VM): VM {
    val services = (LocalContext.current.applicationContext as KanjiApp).services
    return viewModel(key = key, factory = viewModelFactory { initializer { create(services) } })
}
