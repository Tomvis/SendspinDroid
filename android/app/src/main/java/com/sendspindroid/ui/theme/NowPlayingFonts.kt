@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.sendspindroid.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.sendspindroid.R

private fun wght(value: Int) = FontVariation.Settings(FontVariation.weight(value))

private fun rubik(weight: Int, italic: Boolean = false) = Font(
    if (italic) R.font.rubik_italic else R.font.rubik,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = wght(weight),
)

/** Home theme (HW-48): Rubik variable font, the only UI face. */
val RubikFamily = FontFamily(
    rubik(300), rubik(400), rubik(500), rubik(600), rubik(700),
    rubik(300, italic = true), rubik(400, italic = true), rubik(500, italic = true),
)

/** TV Now Playing surfaces use the same family as the rest of the app. */
val NpRubikFamily = RubikFamily

/** Tabular figures: numbers that update in place (clocks, timecodes, stats) don't jitter. */
val NpTabular = TextStyle(fontFeatureSettings = "tnum")
