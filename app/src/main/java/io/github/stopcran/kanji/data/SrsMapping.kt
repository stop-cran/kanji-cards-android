package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import java.time.Instant

fun ReviewStateEntity.toSrs() = SrsState(
    CardPhase.valueOf(phase), stability, difficulty, Instant.ofEpochMilli(dueMs), lastReviewMs?.let(Instant::ofEpochMilli), reps, lapses,
)

fun SrsState.toEntity(source: String, kanji: String, mode: StudyMode) = ReviewStateEntity(
    source, kanji, mode.name, phase.name, stability, difficulty, due.toEpochMilli(), lastReview?.toEpochMilli(), reps, lapses,
)
