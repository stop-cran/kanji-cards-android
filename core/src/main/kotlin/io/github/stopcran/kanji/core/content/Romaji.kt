package io.github.stopcran.kanji.core.content

/** Hepburn (and common Kunrei) romaji to hiragana, enough for searching readings. */
object Romaji {
    private val table: Map<String, String> = buildMap {
        fun add(kana: String, vararg romaji: String) = romaji.forEach { put(it, kana) }
        add("あ", "a"); add("い", "i"); add("う", "u"); add("え", "e"); add("お", "o")
        val rows = mapOf(
            "k" to "かきくけこ", "s" to "さしすせそ", "t" to "たちつてと", "n" to "なにぬねの", "h" to "はひふへほ",
            "m" to "まみむめも", "r" to "らりるれろ", "g" to "がぎぐげご", "z" to "ざじずぜぞ", "d" to "だぢづでど",
            "b" to "ばびぶべぼ", "p" to "ぱぴぷぺぽ",
        )
        for ((c, kana) in rows) "aiueo".forEachIndexed { i, v -> put("$c$v", kana[i].toString()) }
        add("や", "ya"); add("ゆ", "yu"); add("よ", "yo"); add("わ", "wa"); add("を", "wo")
        add("し", "shi"); add("ち", "chi"); add("つ", "tsu"); add("ふ", "fu"); add("じ", "ji"); add("ぢ", "dji")
        val small = mapOf("a" to "ゃ", "u" to "ゅ", "o" to "ょ")
        val palatal = mapOf(
            "ky" to "き", "gy" to "ぎ", "sh" to "し", "ch" to "ち", "ny" to "に", "hy" to "ひ", "my" to "み", "ry" to "り",
            "by" to "び", "py" to "ぴ", "j" to "じ", "sy" to "し", "ty" to "ち", "zy" to "じ", "dy" to "ぢ",
        )
        for ((c, base) in palatal) for ((v, s) in small) put("$c$v", base + s)
        put("jya", "じゃ"); put("jyu", "じゅ"); put("jyo", "じょ")
    }

    private val macrons = mapOf('ā' to "aa", 'ī' to "ii", 'ū' to "uu", 'ē' to "ee", 'ō' to "ou")

    /** Null unless the whole input converts, so ordinary English words are not mistaken for romaji. */
    fun toHiragana(input: String): String? {
        val s = input.lowercase().map { macrons[it] ?: it.toString() }.joinToString("")
        if (s.isEmpty() || s.any { it !in 'a'..'z' && it != '\'' && it != '-' }) return null
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\'' || c == '-') { i++; continue }
            val next = s.getOrNull(i + 1)
            if (c == 'n' && (next == null || next == '\'' || (next !in "aiueoy"))) {
                out.append('ん'); i += if (next == 'n') 2 else 1; continue
            }
            if (next == c && c !in "aiueon") { out.append('っ'); i++; continue }
            if (c == 't' && s.startsWith("tch", i)) { out.append('っ'); i++; continue }
            val len = (3 downTo 1).firstOrNull { it + i <= s.length && table.containsKey(s.substring(i, i + it)) } ?: return null
            out.append(table.getValue(s.substring(i, i + len)))
            i += len
        }
        return out.toString()
    }
}