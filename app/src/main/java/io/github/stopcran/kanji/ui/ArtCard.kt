package io.github.stopcran.kanji.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.R

/**
 * A home entry with a faint illustration behind it. The picture is decorative; a scrim fading to the card colour keeps the
 * caption readable and leaves the right edge (status, chevron) on plain paper.
 */
@Composable
fun ArtCard(title: String, summary: String, @DrawableRes art: Int?, onClick: () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    Card(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = surface),
    ) {
        Box {
            if (art != null) {
                Image(painterResource(art), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, alpha = ART_ALPHA)
                Box(
                    Modifier.matchParentSize().background(
                        Brush.horizontalGradient(
                            0f to surface.copy(alpha = 0.35f),
                            0.6f to surface.copy(alpha = 0.75f),
                            1f to surface,
                        ),
                    ),
                )
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.chevron), modifier = Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

private const val ART_ALPHA = 0.5f
