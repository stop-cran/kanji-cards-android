package io.github.stopcran.kanji.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration

/** Text colours for right/wrong that stay readable on both light and dark surfaces. */
@Composable
fun correctTextColor(): Color = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

@Composable
fun wrongTextColor(): Color = if (isSystemInDarkTheme()) Color(0xFFEF9A9A) else Color(0xFFC62828)

/** Big kanji size that shrinks in landscape so the question and options still fit; the user's font scale is applied by sp. */
@Composable
fun bigKanjiSize(base: Int): TextUnit {
    val c = LocalConfiguration.current
    return if (c.screenHeightDp < 480) (base * 0.55f).sp else base.sp
}
/** Window wide enough for list and detail side by side (Material expanded width class). */
@Composable
fun isExpandedWidth(): Boolean = LocalConfiguration.current.screenWidthDp >= 840

