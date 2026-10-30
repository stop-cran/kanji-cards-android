package io.github.stopcran.kanji.core.kana

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState

enum class KanaScript(val id: String, val label: String) {
    Hiragana("hiragana", "Hiragana"),
    Katakana("katakana", "Katakana"),
}

data class KanaCard(val script: KanaScript, val kana: String, val romaji: String, val alternateRomaji: List<String> = emptyList())

object KanaCatalog {
    private val basic = listOf(
        "あいうえお".toList() to listOf("a", "i", "u", "e", "o"),
        "かきくけこ".toList() to listOf("ka", "ki", "ku", "ke", "ko"),
        "さしすせそ".toList() to listOf("sa", "shi", "su", "se", "so"),
        "たちつてと".toList() to listOf("ta", "chi", "tsu", "te", "to"),
        "なにぬねの".toList() to listOf("na", "ni", "nu", "ne", "no"),
        "はひふへほ".toList() to listOf("ha", "hi", "fu", "he", "ho"),
        "まみむめも".toList() to listOf("ma", "mi", "mu", "me", "mo"),
        "やゆよ".toList() to listOf("ya", "yu", "yo"),
        "らりるれろ".toList() to listOf("ra", "ri", "ru", "re", "ro"),
        "わをん".toList() to listOf("wa", "wo", "n"),
    )

    private val voiced = listOf(
        "がぎぐげご".toList() to listOf("ga", "gi", "gu", "ge", "go"),
        "ざじずぜぞ".toList() to listOf("za", "ji", "zu", "ze", "zo"),
        "だぢづでど".toList() to listOf("da", "di", "du", "de", "do"),
        "ばびぶべぼ".toList() to listOf("ba", "bi", "bu", "be", "bo"),
        "ぱぴぷぺぽ".toList() to listOf("pa", "pi", "pu", "pe", "po"),
    )

    private val combinations = listOf(
        listOf("きゃ", "きゅ", "きょ") to listOf("kya", "kyu", "kyo"),
        listOf("しゃ", "しゅ", "しょ") to listOf("sha", "shu", "sho"),
        listOf("ちゃ", "ちゅ", "ちょ") to listOf("cha", "chu", "cho"),
        listOf("にゃ", "にゅ", "にょ") to listOf("nya", "nyu", "nyo"),
        listOf("ひゃ", "ひゅ", "ひょ") to listOf("hya", "hyu", "hyo"),
        listOf("みゃ", "みゅ", "みょ") to listOf("mya", "myu", "myo"),
        listOf("りゃ", "りゅ", "りょ") to listOf("rya", "ryu", "ryo"),
        listOf("ぎゃ", "ぎゅ", "ぎょ") to listOf("gya", "gyu", "gyo"),
        listOf("じゃ", "じゅ", "じょ") to listOf("ja", "ju", "jo"),
        listOf("びゃ", "びゅ", "びょ") to listOf("bya", "byu", "byo"),
        listOf("ぴゃ", "ぴゅ", "ぴょ") to listOf("pya", "pyu", "pyo"),
    )

    val cards: Map<KanaScript, List<KanaCard>> = KanaScript.entries.associateWith { script ->
        (basic + voiced).flatMap { (characters, romanizations) ->
            characters.mapIndexed { index, character ->
                val alternate = when (character) {
                    'ぢ' -> listOf("ji")
                    'づ' -> listOf("zu")
                    else -> emptyList()
                }
                KanaCard(script, script.form(character.toString()), romanizations[index], alternate)
            }
        } + combinations.flatMap { (characters, romanizations) ->
            characters.mapIndexed { index, kana ->
                KanaCard(script, script.form(kana), romanizations[index])
            }
        }
    }

    fun all(script: KanaScript): List<KanaCard> = cards.getValue(script)

    fun card(script: KanaScript, kana: String): KanaCard? = all(script).firstOrNull { it.kana == kana }

    private val small = mapOf('ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ')

    /** Plain kana a combination is built from (ちゅ -> ち, ゆ); empty for non-combinations. */
    fun components(card: KanaCard): List<String> {
        if (card.kana.length != 2) return emptyList()
        val hira = card.kana.map { if (card.script == KanaScript.Hiragana) it else (it.code - KATAKANA_OFFSET).toChar() }
        val tail = small[hira[1]] ?: return emptyList()
        return listOf(card.script.form(hira[0].toString()), card.script.form(tail.toString()))
    }

    private val voicedBases: Map<Char, Char> = buildMap {
        "がぎぐげご".zip("かきくけこ").forEach { put(it.first, it.second) }
        "ざじずぜぞ".zip("さしすせそ").forEach { put(it.first, it.second) }
        "だぢづでど".zip("たちつてと").forEach { put(it.first, it.second) }
        "ばびぶべぼ".zip("はひふへほ").forEach { put(it.first, it.second) }
        "ぱぴぷぺぽ".zip("はひふへほ").forEach { put(it.first, it.second) }
    }

    /** The unvoiced kana a voiced or semi-voiced kana is derived from (が -> か); null for other cards. */
    fun voicedBase(card: KanaCard): String? {
        if (card.kana.length != 1) return null
        val hira = if (card.script == KanaScript.Hiragana) card.kana[0] else (card.kana[0].code - KATAKANA_OFFSET).toChar()
        return voicedBases[hira]?.let { card.script.form(it.toString()) }
    }

    /**
     * Cards open for study: everything except combinations whose components, and voiced kana whose base, are not yet
     * solid in this mode. Cards that were already started stay available.
     */
    fun unlocked(script: KanaScript, states: Map<String, SrsState>): List<KanaCard> = all(script).filter { card ->
        if (card.kana in states) return@filter true
        val voicedBase = voicedBase(card)
        if (voicedBase != null) return@filter isSolid(states[voicedBase], VOICED_UNLOCK_STABILITY_DAYS)
        components(card).all { isSolid(states[it], UNLOCK_STABILITY_DAYS) }
    }

    private fun isSolid(state: SrsState?, days: Double) = state != null && state.phase == CardPhase.Review && state.stability >= days

    const val UNLOCK_STABILITY_DAYS = 7.0
    const val VOICED_UNLOCK_STABILITY_DAYS = 3.0

    fun KanaScript.form(hiragana: String): String =
        if (this == KanaScript.Hiragana) hiragana else hiragana.map { (it.code + KATAKANA_OFFSET).toChar() }.joinToString("")

    private const val KATAKANA_OFFSET = 0x60
}
