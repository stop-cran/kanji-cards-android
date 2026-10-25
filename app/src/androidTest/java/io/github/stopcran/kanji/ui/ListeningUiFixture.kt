package io.github.stopcran.kanji.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.ParsedContent
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Original synthetic UI fixtures, isolated from learner data and never a listening approval. */
internal class ListeningUiFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    private val preferences = "listening-ui-${UUID.randomUUID()}"
    private val isolated = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
    }
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val settings = Settings(isolated).also {
        it.setSource("https://github.com/fixture/listening", "main")
        it.setWeekPlan("FFFFFFF")
    }
    val services = Services(db, settings, context)
    val source = settings.source.value.id
    private val tone = asset("tone.ogg")
    val picture = asset("picture.webp")
    private val scene = asset("expression-scene.webp")
    private val choiceCues = (1..3).map { asset("choice-$it.ogg") }
    val assets = (listOf(tone, picture, scene) + choiceCues).associateBy(ContentParser::rawSha256)
    val records = listOf(record("task.json", "n5-task-open-window", visual = true),
        record("key-point.json", "n5-task-rainy-class"),
        record("verbal-expression.json", "n5-task-borrow-pencil"),
        record("quick-response.json", "n5-task-park-invitation"))

    init {
        runBlocking {
            db.content().insertWords(listOf(WordEntity(source, "窓", "まど", "Window", null, "窓", "", "# Captured window article")))
            db.withTransaction {
                db.importListeningTasks(ParsedContent(Manifest(1, "native-ui-fixture", emptyMap()), emptyList(), emptyList(),
                    emptyList(), emptyMap(), emptyList(), listeningTasks = ListeningTaskCatalog(records = records,
                        assets = assets)), settings.source.value, "a".repeat(40))
            }
        }
    }

    private fun asset(name: String) = instrumentation.context.assets.open("listening-tasks/$name").use { it.readBytes() }

    private fun record(file: String, id: String, visual: Boolean = false): ListeningTaskRecord {
        val raw = asset(file).decodeToString()
        var task = ListeningTaskContent.parse("listening-tasks/$id.json", raw).task
        if (visual) task = task.copy(choiceMode = TaskChoiceMode.Visual,
            visual = TaskVisual("required", "Four numbered drawings: open window, closed window, open door, closed door."),
            options = listOf(task.options[0],
                task.options[1].copy(label = "窓を閉めます。", translation = "Close the window.",
                    rationale = "The window should be opened, not closed."),
                task.options[2].copy(label = "ドアを開けます。", translation = "Open the door.",
                    rationale = "Opening the door was not requested."),
                task.options[3].copy(label = "ドアを閉めます。", translation = "Close the door.",
                    rationale = "Closing the door was not requested.")),
            vocabulary = listOf(ListeningWord("窓", "まど", "window", "窓")))
        val contentHash = ListeningTaskContent.parse("listening-tasks/$id.json",
            Json.encodeToString(ListeningTaskPackage.serializer(), task)).contentHash
        val image = if (visual) picture else task.visual?.let { scene }
        val media = TaskMedia(contentHash, task.stages.associate { stage ->
            val number = stage.optionId?.let { option -> task.options.single { it.id == option }.number }
            val bytes = number?.let { choiceCues[it - 1] } ?: tone
            val duration = number?.let { it * 200L + 100 } ?: 250L
            stage.id to TaskAudio("task-audio/ui-${number ?: 0}.ogg", ContentParser.rawSha256(bytes), duration,
                stage.turns.mapIndexed { index, turn -> ListeningTiming(turn, index * duration / stage.turns.size,
                    (index + 1) * duration / stage.turns.size) })
        }, image?.let { ListeningImage("task-images/${if (visual) "ui" else "scene"}.webp", ContentParser.rawSha256(it)) })
        task = task.copy(media = media, approval = TaskApproval("approved", contentHash, ListeningTaskContent.mediaHash(media),
            "2026-10-24", "Synthetic UI fixture only; no Japanese listening or artwork approval."))
        return ListeningTaskContent.parse("listening-tasks/$id.json", Json.encodeToString(ListeningTaskPackage.serializer(), task))
    }

    fun start(type: ListeningTaskType, described: Boolean = false): String = runBlocking {
        services.listeningTasks.start(source, records.single { it.task.taskType == type }.task.id,
            study = described, extra = !described, described = described)
    }

    fun addN4Fixtures(): List<ListeningTaskRecord> = runBlocking {
        val n4 = records.map { original ->
            val id = original.task.id.replaceFirst("n5-", "n4-")
            val draft = original.task.copy(id = id, exposureId = id, schemaVersion = 2, jlpt = 4,
                title = "N4 fixture - ${original.task.taskType.wire}", media = null, approval = null)
            val hash = ListeningTaskContent.parse("listening-tasks/$id.json",
                Json.encodeToString(ListeningTaskPackage.serializer(), draft)).contentHash
            val media = requireNotNull(original.task.media).copy(contentSha256 = hash)
            val task = draft.copy(media = media, approval = TaskApproval("approved", hash, ListeningTaskContent.mediaHash(media),
                "2026-10-25", "Synthetic level/filter UI fixture only; not N4 learning content or listening approval."))
            ListeningTaskContent.parse("listening-tasks/$id.json", Json.encodeToString(ListeningTaskPackage.serializer(), task))
        }
        db.withTransaction {
            val result = db.importListeningTasks(ParsedContent(Manifest(1, "mixed-level-ui-fixture", emptyMap()), emptyList(),
                emptyList(), emptyList(), emptyMap(), emptyList(),
                listeningTasks = ListeningTaskCatalog(records = records + n4, assets = assets)), settings.source.value, "c".repeat(40))
            require(result.problems.isEmpty()) { result.problems.joinToString() }
        }
        n4
    }

    fun assessmentPool(): List<ListeningTaskRecord> = runBlocking {
        val long = asset("assessment-tone.ogg")
        val pool = ListeningAssessmentBlueprint.groups.flatMap { group ->
            val original = records.single { it.task.taskType == group.type }
            (0..group.pool).map { index ->
                val id = "n5-task-assessment-${group.type.wire}-$index"
                val draft = original.task.copy(id = id, exposureId = id, blueprint = ListeningAssessmentBlueprint.ID,
                    usage = if (index == 0) TaskUsage.Example else TaskUsage.Scored, media = null, approval = null)
                val hash = ListeningTaskContent.parse("listening-tasks/$id.json",
                    Json.encodeToString(ListeningTaskPackage.serializer(), draft)).contentHash
                val media = requireNotNull(original.task.media).copy(contentSha256 = hash,
                    audio = draft.stages.associate { stage ->
                        stage.id to if (stage.role == TaskStageRole.Choice) requireNotNull(original.task.media).audio.getValue(stage.id)
                        else TaskAudio("task-audio/assessment-tone.ogg", ContentParser.rawSha256(long), 20_000,
                            stage.turns.mapIndexed { turnIndex, turn ->
                                ListeningTiming(turn, turnIndex * 20_000L / stage.turns.size, (turnIndex + 1) * 20_000L / stage.turns.size)
                            })
                    })
                val task = draft.copy(media = media, approval = TaskApproval("approved", hash, ListeningTaskContent.mediaHash(media),
                    "2026-10-24", "Repeated synthetic fixture scripts only; not independent stories or actual listening approval."))
                ListeningTaskContent.parse("listening-tasks/$id.json", Json.encodeToString(ListeningTaskPackage.serializer(), task))
            }
        }
        db.withTransaction {
            val problems = db.importListeningTasks(ParsedContent(Manifest(1, "assessment-native-fixture", emptyMap()), emptyList(),
                emptyList(), emptyList(), emptyMap(), emptyList(), listeningTasks = ListeningTaskCatalog(records = pool,
                    assets = assets + (ContentParser.rawSha256(long) to long))), settings.source.value, "b".repeat(40)).problems
            require(problems.isEmpty()) { problems.joinToString() }
        }
        pool
    }

    fun authorPreview(record: ListeningTaskRecord, bytes: Map<String, ByteArray>, words: Map<String, String>,
        player: ListeningPlayer): ListeningTaskViewModel = runBlocking {
        require(!record.ready && record.task.approval == null && record.mediaHash != null)
        require(bytes.keys.containsAll(record.assetHashes))
        require(words.keys.containsAll(record.task.vocabulary.mapNotNull { it.word }))
        val id = "listening-task:${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.requireNoOtherListeningActivity(source)
            db.listeningTasks().putAssets(record.assetHashes.map { hash ->
                val data = bytes.getValue(hash)
                require(ContentParser.rawSha256(data) == hash)
                ListeningTaskAssetEntity(source, hash, data)
            })
            db.listeningTasks().putEncounter(ListeningTaskEncounterEntity(
                id, source, record.task.id, record.task.exposureId, "author-preview", null, "private-unapproved",
                record.json, record.contentHash, requireNotNull(record.mediaHash), record.task.policyVersion,
                JsonObject(words.mapValues { JsonPrimitive(it.value) }).toString(),
                Json.encodeToString(ListeningTaskAttempt.serializer(), ListeningTaskAttempt(phase = "study")),
                "study", null, now, requireNotNull(record.estimatedMs), newReservation = false,
            ))
            db.listeningTasks().pin(record.assetHashes.map { ListeningAssetPinEntity(source, "encounter", id, it) })
            db.reviews().insertSession(StudySessionEntity(id, source, LISTENING_STACK, LISTENING_MODE, true, now))
        }
        ListeningTaskViewModel(services, id, player,
            ListeningTaskStudy(db, settings, allowUnapprovedStudyCapture = true))
    }

    override fun close() {
        db.close()
        context.deleteSharedPreferences(preferences)
    }
}
