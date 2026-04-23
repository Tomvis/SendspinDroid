@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.sendspindroid.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.sendspindroid.R

private fun wght(value: Int) = FontVariation.Settings(FontVariation.weight(value))

val NpFrauncesFamily = FontFamily(
    Font(R.font.fraunces, weight = FontWeight.W300, variationSettings = wght(300)),
    Font(R.font.fraunces, weight = FontWeight.W400, variationSettings = wght(400)),
    Font(R.font.fraunces, weight = FontWeight.W500, variationSettings = wght(500)),
    Font(R.font.fraunces_italic, weight = FontWeight.W400, style = FontStyle.Italic, variationSettings = wght(400)),
    Font(R.font.fraunces_italic, weight = FontWeight.W500, style = FontStyle.Italic, variationSettings = wght(500)),
)

val NpInterFamily = FontFamily(
    Font(R.font.inter, weight = FontWeight.W300, variationSettings = wght(300)),
    Font(R.font.inter, weight = FontWeight.W400, variationSettings = wght(400)),
    Font(R.font.inter, weight = FontWeight.W500, variationSettings = wght(500)),
    Font(R.font.inter, weight = FontWeight.W600, variationSettings = wght(600)),
)

val NpMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, weight = FontWeight.W500, variationSettings = wght(500)),
    Font(R.font.jetbrains_mono, weight = FontWeight.W600, variationSettings = wght(600)),
)
