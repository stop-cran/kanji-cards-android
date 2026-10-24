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
    val records = listOf(record("task.json", "n5-task-open-window", visual = true),
        record("key-point.json", "n5-task-rainy-class", visual = false))

    init {
        runBlocking {
            db.content().insertWords(listOf(WordEntity(source, "窓", "まど", "Window", null, "窓", "", "# Captured window article")))
            db.withTransaction {
                db.importListeningTasks(ParsedContent(Manifest(1, "native-ui-fixture", emptyMap()), emptyList(), emptyList(),
                    emptyList(), emptyMap(), emptyList(), listeningTasks = ListeningTaskCatalog(records = records,
                        assets = listOf(tone, picture).associateBy(ContentParser::rawSha256))), settings.source.value, "a".repeat(40))
            }
        }
    }

    private fun asset(name: String) = instrumentation.context.assets.open("listening-tasks/$name").use { it.readBytes() }

    private fun record(file: String, id: String, visual: Boolean): ListeningTaskRecord {
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
        val media = TaskMedia(contentHash, task.stages.associate { stage ->
            stage.id to TaskAudio("task-audio/ui.ogg", ContentParser.rawSha256(tone), 250,
                stage.turns.mapIndexed { index, turn -> ListeningTiming(turn, index * 250L / stage.turns.size,
                    (index + 1) * 250L / stage.turns.size) })
        }, if (visual) ListeningImage("task-images/ui.webp", ContentParser.rawSha256(picture)) else null)
        task = task.copy(media = media, approval = TaskApproval("approved", contentHash, ListeningTaskContent.mediaHash(media),
            "2026-10-24", "Synthetic UI fixture only; no Japanese listening or artwork approval."))
        return ListeningTaskContent.parse("listening-tasks/$id.json", Json.encodeToString(ListeningTaskPackage.serializer(), task))
    }

    fun start(type: ListeningTaskType, described: Boolean = false): String = runBlocking {
        services.listeningTasks.start(source, records.single { it.task.taskType == type }.task.id,
            study = described, extra = !described, described = described)
    }

    override fun close() {
        db.close()
        context.deleteSharedPreferences(preferences)
    }
}
