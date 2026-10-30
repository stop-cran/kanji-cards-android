package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.data.DocumentContent
import io.github.stopcran.kanji.data.SceneImageResult

sealed interface DocumentUi {
    data object Loading : DocumentUi
    data object Missing : DocumentUi
    data class Ready(val document: DocumentContent, val selectedScene: Int = 0, val image: SceneImageResult? = null) : DocumentUi
}
