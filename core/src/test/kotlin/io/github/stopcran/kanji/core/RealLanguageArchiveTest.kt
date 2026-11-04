package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.KanjiCard
import io.github.stopcran.kanji.core.content.SafeZip
import io.github.stopcran.kanji.core.counters.CounterExpressions
import io.github.stopcran.kanji.core.counters.CounterGrader
import io.github.stopcran.kanji.core.counters.CounterIndex
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterVerdict
import io.github.stopcran.kanji.core.grammar.GrammarChoices
import io.github.stopcran.kanji.core.markdown.Block
import io.github.stopcran.kanji.core.markdown.Markdown
import io.github.stopcran.kanji.core.words.PictureQuestions
import io.github.stopcran.kanji.core.words.PictureScene
import io.github.stopcran.kanji.core.words.WordCard
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordQuizBuilder
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.core.words.wordLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Optional real-publication regressions, enabled by the archive path variables used below. */
class RealLanguageArchiveTest {
    @Test
    fun actualNewEnglishSchemaOneArchiveIsAccepted() {
        val parsed = parseArchive("KANJI_CARDS_EN_ARCHIVE")
        val manifest = parsed.manifest
        assertEquals(1, manifest.schemaVersion)
        assertEquals("en", manifest.baseLanguage)
        assertEquals("ja", manifest.targetLanguage)
        assertEquals(
            System.getenv("KANJI_CARDS_EN_CONTENT_VERSION") ?: "7fbe4319127d9356",
            manifest.contentVersion,
        )
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        assertTrue(parsed.kanji.isNotEmpty())
        assertTrue(parsed.words.isNotEmpty())
    }

    @Test
    fun correctedRussianPilotParsesLocalizedReadingTablesAndNormalWordChoices() {
        val parsed = parseArchive("KANJI_CARDS_RU_ARCHIVE")
        assertRussianManifest(parsed)
        assertRussianReadingRows(parsed)
        assertPilotLevelsAndQuizGates(parsed)
        assertNormalWordChoices(parsed)
    }

    @Test
    fun widerRussianPilotRetainsGrammarChoiceTypingAndVocabularySemantics() {
        val parsed = parseWiderRussianPilot()
        val lesson = parsed.grammar.single()
        assertEquals(lesson.exercises.map { it.id }.toSet(),
            lesson.introductionGroups.single().members.map { it.exerciseId }.toSet())
        assertTrue(lesson.introductionGroups.single().requires.isEmpty())
        assertEquals(15, lesson.exercises.sumOf { requireNotNull(it.vocabulary).spans.size })
        for (exercise in lesson.exercises) {
            assertEquals(null, exercise.vocabularyProblem)
            val choices = GrammarChoices.build(exercise, Random(42))
            val accepted = exercise.acceptedAnswers.map(GrammarChoices::normalizeAnswer).toSet()
            for (option in choices.options) {
                assertEquals("${exercise.id}/${option.id}",
                    option.id in choices.correctOptionIds, GrammarChoices.normalizeAnswer(option.label) in accepted)
                assertTrue(option.rationale.any { it in 'А'..'я' })
            }
            assertFalse(GrammarChoices.normalizeAnswer(exercise.translation) in accepted)
            assertTrue(exercise.translation.any { it in 'А'..'я' })
            assertTrue(exercise.explanation.any { it in 'А'..'я' })
            assertTrue(requireNotNull(exercise.vocabulary).spans.all { span ->
                !span.text.contains('_') && span.gloss.any { it in 'А'..'я' }
            })
        }
    }

    @Test
    fun widerRussianPilotKeepsAlternativeCountersSeparateFromCanonicalReadings() {
        val index = CounterIndex(parseWiderRussianPilot().counters)
        val apple = index.cards.getValue("tsu-apple-1")
        assertTrue(apple.en.any { it in 'А'..'я' })
        for (answer in listOf("つ", "二つ", "二個")) {
            assertEquals(answer, CounterVerdict.Good, CounterGrader.grade(answer, apple, "つ").verdict)
        }
        val reading = index.expression(apple.id)
        assertEquals(CounterVerdict.Good, CounterExpressions.grade("ふたつ", reading, reading = true).verdict)
        assertEquals(CounterVerdict.Wrong, CounterExpressions.grade("にこ", reading, reading = true).verdict)
        val ten = index.expression("native-expression-1")
        for (answer in listOf("十", "10")) {
            assertEquals(answer, CounterVerdict.Good, CounterExpressions.grade(answer, ten, reading = false).verdict)
        }
        assertEquals(CounterVerdict.Wrong, CounterExpressions.grade("十つ", ten, reading = false).verdict)
        assertEquals(CounterVerdict.Good, CounterExpressions.grade("とお", ten, reading = true).verdict)
        assertEquals(CounterVerdict.Wrong, CounterExpressions.grade("じゅう", ten, reading = true).verdict)
        assertFalse(apple.id in index.ids(CounterMode.Expression))
        assertFalse("tsu-question-1" in index.ids(CounterMode.Reading))
        assertTrue(index.ids(CounterMode.Noun).isEmpty())
    }

    @Test
    fun widerRussianPilotBuildsBothRealPictureTargetsWithoutTheArticleOnlyScene() {
        val parsed = parseWiderRussianPilot()
        val cards = wordCards(parsed)
        val scenes = parsed.words.flatMap { word ->
            word.scenes.map { scene -> PictureScene(word.word, scene, parsed.manifest.images.getValue(scene.image)) }
        }
        for (word in listOf("きらきら", "にこにこ")) {
            val question = requireNotNull(PictureQuestions.build(cards.single { it.word == word }, cards, scenes, Random(42)))
            assertEquals(setOf("きらきら", "にこにこ"), question.options.map { it.word }.toSet())
            assertEquals(word, question.target.word)
            assertFalse(question.target.scene.id == "sparkling-interested-eyes")
            assertTrue(question.scenes.values.all { it.scene.quizEligible && parsed.images.containsKey(it.scene.image) })
            assertTrue(question.scenes.values.all { scene ->
                scene.scene.description.any { it in 'А'..'я' } && !scene.scene.description.contains(scene.word)
            })
            assertEquals(1, question.requiredScenes(WordDirection.Picture).size)
            assertEquals(2, question.requiredScenes(WordDirection.WordToPicture).size)
        }
    }

    private fun parseWiderRussianPilot(): io.github.stopcran.kanji.core.content.ParsedContent {
        val parsed = parseArchive("KANJI_CARDS_RU_PILOT_B_ARCHIVE")
        assertEquals("ru", parsed.manifest.baseLanguage)
        assertEquals(2, parsed.manifest.schemaVersion)
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        assertTrue(parsed.grammarProblems.toString(), parsed.grammarProblems.isEmpty())
        assertTrue(parsed.listeningProblems.toString(), parsed.listeningProblems.isEmpty())
        assertTrue(parsed.listening.isEmpty())
        assertTrue(parsed.audio.isEmpty())
        return parsed
    }

    private fun assertRussianManifest(parsed: io.github.stopcran.kanji.core.content.ParsedContent) {
        assertEquals(2, parsed.manifest.schemaVersion)
        assertEquals("ru", parsed.manifest.baseLanguage)
        assertEquals("ja", parsed.manifest.targetLanguage)
        assertEquals("afe5c99e66524d95", parsed.manifest.contentVersion)
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        assertEquals(12, parsed.kanji.size)
        assertEquals(27, parsed.words.size)
        assertEquals(12, parsed.strokes.size)
    }

    private fun assertRussianReadingRows(parsed: io.github.stopcran.kanji.core.content.ParsedContent) {
        val woodRows = readingRows(parsed.kanji.single { it.kanji == "木" })
        assertEquals(listOf("Чтение", "Тип", "Примеры"), readingTable(parsed.kanji.single { it.kanji == "木" }).header.map(::plain))
        assertTrue(woodRows.getValue("モク").contains("木材（もくざい"))
        assertFalse(woodRows.getValue("ボク").contains("木材（もくざい"))
        assertTrue(woodRows.getValue("ボク").contains("大木（たいぼく"))
        val mountainRows = readingRows(parsed.kanji.single { it.kanji == "山" })
        assertTrue(mountainRows.getValue("ザン").contains("火山（かざん"))
        assertFalse(mountainRows.getValue("サン").contains("火山（かざん"))
        val mouthRows = readingRows(parsed.kanji.single { it.kanji == "口" })
        assertTrue(mouthRows.getValue("-ぐち").contains("出口（でぐち"))
        assertTrue(mouthRows.getValue("-ぐち").contains("入口（いりぐち"))
        val sun = parsed.kanji.single { it.kanji == "日" }
        assertTrue(sun.body.contains("日本 читается にほん или にっぽん"))
        assertFalse(readingRows(sun).getValue("ニチ").contains("日本"))
    }

    private fun assertPilotLevelsAndQuizGates(parsed: io.github.stopcran.kanji.core.content.ParsedContent) {
        val levels = parsed.kanji.associate { it.kanji to it.jlpt }
        val words = wordCards(parsed)
        val river = words.single { it.word == "川" }
        val smallStream = words.single { it.word == "小川" }
        assertEquals(5, wordLevel(river.kanji, levels, river.jlpt))
        assertEquals(4, wordLevel(words.single { it.word == "昼間" }.kanji, levels, 4))
        assertFalse("The unlabelled 小川 must not be relabelled N5",
            WordStacks.n5.matches(wordLevel(smallStream.kanji, levels, smallStream.jlpt), smallStream.tags))
        assertFalse("The all-words stack remains locked before N4",
            WordStacks.offered(n4Unlocked = false).any { it.id == WordStacks.all.id })
        assertTrue("The all-words stack remains available after N4 unlock",
            WordStacks.offered(n4Unlocked = true).any { it.id == WordStacks.all.id })
        assertTrue(smallStream.word in river.quizExclusions || river.word in smallStream.quizExclusions)
        assertTrue("河" in river.quizExclusions)
        assertTrue("昼間" in words.single { it.word == "昼" }.quizExclusions)
        assertTrue("昼" in words.single { it.word == "昼間" }.quizExclusions)
    }

    private fun assertNormalWordChoices(parsed: io.github.stopcran.kanji.core.content.ParsedContent) {
        val words = wordCards(parsed)
        val river = words.single { it.word == "川" }
        val smallStream = words.single { it.word == "小川" }
        for (direction in listOf(WordDirection.JpToEn, WordDirection.EnToJp)) {
            for ((target, excluded) in listOf(river to smallStream, smallStream to river)) {
                val options = WordQuizBuilder.options(target, words, direction, Random(42))
                assertEquals("$direction/${target.word} must keep the ordinary four-option format", 4, options.size)
                assertEquals(1, options.count { it.word == target.word })
                assertFalse("$direction/${target.word} must not present ${excluded.word} as a wrong choice",
                    options.any { it.word == excluded.word })
            }
        }
        val daytime = WordQuizBuilder.options(words.single { it.word == "昼" }, words, WordDirection.JpToEn, Random(1))
        assertEquals(4, daytime.size)
        assertFalse(daytime.any { it.word == "昼間" })
    }

    private fun wordCards(parsed: io.github.stopcran.kanji.core.content.ParsedContent) = parsed.words.map { article ->
        WordCard(
            article.word, article.reading, article.title, article.type, article.kanji, article.tags, article.jlpt,
            article.quizExclusions, article.quizDistractors,
        )
    }

    private fun parseArchive(environmentKey: String): io.github.stopcran.kanji.core.content.ParsedContent {
        val path = System.getenv(environmentKey)
        assumeTrue("$environmentKey was not supplied", !path.isNullOrBlank())
        val file = File(requireNotNull(path))
        assumeTrue("$environmentKey does not exist", file.isFile)
        val parsed = ContentParser.parse(file.inputStream().use(SafeZip::read))
        return parsed
    }

    private fun readingTable(card: KanjiCard): Block.Table {
        val blocks = Markdown.parse(card.body, "kanji/${card.kanji}.md")
        val heading = blocks.indexOfFirst { block ->
            block is Block.Heading && block.level == 2 && plain(block.spans) == "Чтения"
        }
        assertTrue("Missing Russian reading heading for ${card.kanji}", heading >= 0)
        return blocks.drop(heading + 1).first { it is Block.Table } as Block.Table
    }

    private fun readingRows(card: KanjiCard): Map<String, String> {
        val table = readingTable(card)
        val header = table.header.map(::plain)
        assertEquals(listOf("Чтение", "Тип", "Примеры"), header)
        return table.rows.associate { row -> plain(row.first()) to plain(row.last()) }
    }

    private fun plain(spans: List<io.github.stopcran.kanji.core.markdown.Span>) = spans.joinToString("") { it.text }
}
