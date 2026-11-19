package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.readingtext.ReadingFurigana
import io.github.stopcran.kanji.core.readingtext.ReadingRubyPart
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingFuriganaTest {
    @Test fun kanaPrefixesAndOkuriganaStayOutsideRuby() {
        assertParts("行きました", "いきました", ruby("行", "い"), plain("きました"))
        assertParts("ある日", "あるひ", plain("ある"), ruby("日", "ひ"))
        assertParts("ご飯", "ごはん", plain("ご"), ruby("飯", "はん"))
        assertParts("小さい村", "ちいさいむら", ruby("小", "ちい"), plain("さい"), ruby("村", "むら"))
        assertParts("食べ物", "たべもの", ruby("食", "た"), plain("べ"), ruby("物", "もの"))
        assertParts("お母さん", "おかあさん", plain("お"), ruby("母", "かあ"), plain("さん"))
    }

    @Test fun compoundsSupplementaryKanjiAndIterationMarksKeepGroupedReadings() {
        assertParts("二人", "ふたり", ruby("二人", "ふたり"))
        assertParts("時々", "ときどき", ruby("時々", "ときどき"))
        assertParts("𠮷野へ", "よしのへ", ruby("𠮷野", "よしの"), plain("へ"))
        assertParts("ガラス戸", "がらすど", plain("ガラス"), ruby("戸", "ど"))
    }

    @Test fun kanaOnlyMismatchedOrAmbiguousReadingsRemainAvailableOnlyInLookup() {
        assertParts("いました", "いました", plain("いました"))
        assertParts("行く", "いった", plain("行く"))
        assertParts("山", "", plain("山"))
        assertParts("あ山い", "あい", plain("あ山い"))
        assertParts("山の川", "やまののかわ", plain("山の川"))
    }

    private fun ruby(text: String, reading: String) = ReadingRubyPart(text, reading)
    private fun plain(text: String) = ReadingRubyPart(text)
    private fun assertParts(text: String, reading: String, vararg expected: ReadingRubyPart) {
        val actual = ReadingFurigana.parts(text, reading)
        assertEquals(expected.toList(), actual)
        assertEquals(text, actual.joinToString("") { it.text })
    }
}
