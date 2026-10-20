package io.github.stopcran.kanji.core.words

import io.github.stopcran.kanji.core.content.WordScene
import kotlin.random.Random

data class PictureScene(val word: String, val scene: WordScene, val imageHash: String)

data class PictureQuestion(
    val target: PictureScene,
    val options: List<WordOption>,
    val scenes: Map<String, PictureScene>,
) {
    fun requiredScenes(direction: WordDirection): List<PictureScene> =
        if (direction == WordDirection.Picture) listOf(target) else options.map { scenes.getValue(it.word) }
}

object PictureQuestions {
    fun build(
        target: WordCard,
        all: List<WordCard>,
        scenes: List<PictureScene>,
        random: Random,
        preferredSceneId: String? = null,
    ): PictureQuestion? {
        val byWord = scenes.filter { it.scene.quizEligible }.groupBy { it.word }
        val variants = byWord[target.word].orEmpty()
        val targetScene = (if (preferredSceneId == null) variants.randomOrNull(random)
            else variants.find { it.scene.id == preferredSceneId }) ?: return null
        val targetHashes = variants.mapTo(hashSetOf()) { it.imageHash }
        val usedHashes = hashSetOf(targetScene.imageHash)
        val chosen = mutableMapOf(target.word to targetScene)
        val options = WordQuizBuilder.select(target, all, random) { candidate ->
            val available = byWord[candidate.word].orEmpty()
            // A different word owning an identical target variant is a known ambiguous answer, not a new synonym rule.
            if (available.any { it.imageHash in targetHashes }) false else {
                val selected = available.filter { it.imageHash !in usedHashes }.randomOrNull(random)
                if (selected == null) false else {
                    chosen[candidate.word] = selected
                    usedHashes += selected.imageHash
                    true
                }
            }
        }.map { WordOption(it.word, WordQuizBuilder.label(it, WordDirection.Picture)) }
        return if (options.size < 2) null else PictureQuestion(targetScene, options, chosen)
    }
}
