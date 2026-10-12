package io.github.stopcran.kanji.core.kana

enum class KanaMode(val title: String) {
    QuizKanaToRomaji("Kana → romaji"),
    QuizRomajiToKana("Romaji → kana"),
    Draw("Draw from romaji"),
    TypeKanaToRomaji("Type the romaji"),
    TypeRomajiToKana("Type the kana"),
}

const val KANA_SOURCE_ID = "builtin-kana"
