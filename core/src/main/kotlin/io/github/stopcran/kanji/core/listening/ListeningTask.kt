package io.github.stopcran.kanji.core.listening

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ListeningTaskType(val wire: String, val optionCount: Int) {
    @SerialName("task-based") TaskBased("task-based", 4),
    @SerialName("key-point") KeyPoint("key-point", 4),
    @SerialName("verbal-expression") VerbalExpression("verbal-expression", 3),
    @SerialName("quick-response") QuickResponse("quick-response", 3);

    val audioChoices: Boolean get() = optionCount == 3
}

@Serializable
enum class TaskChoiceMode {
    @SerialName("text") Text,
    @SerialName("visual") Visual,
    @SerialName("audio") Audio,
}

@Serializable
enum class TaskUsage {
    @SerialName("scored") Scored,
    @SerialName("example") Example,
}

@Serializable
enum class TaskStageRole {
    @SerialName("instruction") Instruction,
    @SerialName("focus") Focus,
    @SerialName("passage") Passage,
    @SerialName("focus-repeat") FocusRepeat,
    @SerialName("situation") Situation,
    @SerialName("utterance") Utterance,
    @SerialName("choice") Choice,
}

@Serializable
data class ListeningTaskPackage(
    val schemaVersion: Int,
    val policyVersion: Int,
    val jlpt: Int,
    val id: String,
    val exposureId: String,
    val taskType: ListeningTaskType,
    val kind: String,
    val usage: TaskUsage,
    val title: String,
    val topic: String,
    val levelRationale: String,
    val speakers: List<TaskSpeaker>,
    val turns: List<ListeningTurn>,
    val stages: List<TaskStage>,
    val choiceMode: TaskChoiceMode,
    val options: List<TaskOption>,
    val answer: String,
    val evidence: List<String>,
    val explanation: String,
    val blueprint: String? = null,
    val visual: TaskVisual? = null,
    val vocabulary: List<ListeningWord> = emptyList(),
    val media: TaskMedia? = null,
    val approval: TaskApproval? = null,
)

@Serializable
data class TaskSpeaker(val id: String, val role: String, val voice: String, val ratePercent: Int)

@Serializable
data class TaskStage(val id: String, val role: TaskStageRole, val turns: List<String>, val optionId: String? = null)

@Serializable
data class TaskOption(val id: String, val number: Int, val label: String, val translation: String, val rationale: String)

@Serializable
data class TaskVisual(val role: String, val description: String)

@Serializable
data class TaskAudio(val path: String, val sha256: String, val durationMs: Long, val turns: List<ListeningTiming>)

@Serializable
data class TaskMedia(val contentSha256: String, val audio: Map<String, TaskAudio>, val image: ListeningImage? = null)

@Serializable
data class TaskApproval(
    val status: String, val contentSha256: String, val mediaSha256: String, val reviewedOn: String, val note: String,
)

data class ListeningTaskRecord(
    val task: ListeningTaskPackage, val json: String, val contentHash: String, val mediaHash: String?,
) {
    val ready: Boolean get() = task.media != null && task.approval != null
    val reason: String? get() = when {
        task.media == null -> "Task media has not been generated"
        task.approval == null -> "Awaiting complete-task listening approval"
        else -> null
    }
    val assetHashes: Set<String> get() = task.media?.let { media ->
        media.audio.values.mapTo(mutableSetOf()) { it.sha256 }.also { set -> media.image?.let { set += it.sha256 } }
    }.orEmpty()
    val durationMs: Long? get() = task.media?.audio?.values?.sumOf { it.durationMs }
    val estimatedMs: Long? get() = task.media?.let { media ->
        val instruction = media.audio.getValue(task.stages.first().id).durationMs
        instruction + 2 * (requireNotNull(durationMs) - instruction) + 45_000
    }
}

data class UnsupportedListeningTask(val id: String, val reason: String)

data class ListeningTaskCatalog(
    val records: List<ListeningTaskRecord> = emptyList(),
    val unsupported: List<UnsupportedListeningTask> = emptyList(),
    val assets: Map<String, ByteArray> = emptyMap(),
    val problems: List<String> = emptyList(),
)
