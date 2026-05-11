package com.sendspindroid.ui.main.components.nowplaying

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.sendspindroid.ui.main.ArtworkSource
import kotlin.random.Random

private val AmbientBase = Color(0xFF05040A)

@Composable
fun AmbientBg(
    artworkSource: ArtworkSource?,
    accent: Color,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    // Force the whole ambient stack into an offscreen compositing layer so the
    // BlendMode.Screen / BlendMode.Overlay draws below have a defined backdrop.
    // Without this, on the Shield/Tegra GPU the blend reads back from whatever
    // happened to be in the framebuffer that frame, which manifests as random
    // flicker.
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .background(AmbientBase),
    ) {
        BlurredCover(artworkSource = artworkSource, paused = paused)
        AccentWash(accent = accent, paused = paused)
        Vignette()
        GrainOverlay()
    }
}

@Composable
private fun BlurredCover(artworkSource: ArtworkSource?, paused: Boolean) {
    if (artworkSource == null) return
    val context = LocalContext.current
    val model = remember(artworkSource) {
        // 384x384 source + radius=24 keeps sigma proportional to the 96x6 baseline
        // while giving a much smoother ambient on 4K output.
        val builder = ImageRequest.Builder(context)
            .size(Size(384, 384))
            .crossfade(400)
            .transformations(BoxBlurTransformation(radius = 24, iterations = 3))
        when (artworkSource) {
            is ArtworkSource.ByteArray -> builder.data(artworkSource.data)
            is ArtworkSource.Uri -> builder.data(artworkSource.uri)
            is ArtworkSource.Url -> builder.data(artworkSource.url)
        }.build()
    }

    // Always create the transition; gating it on `paused` would mutate the
    // slot table when paused flips and scramble Compose remembered state.
    val drift = rememberInfiniteTransition(label = "np-drift")
    val driftValue by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 40_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "np-drift-progress",
    )
    val driftProgress = if (paused) driftValue else 0f

    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.18f
                scaleY = 1.18f
                alpha = 0.7f
                translationX = -size.width * 0.015f * driftProgress
                translationY = -size.height * 0.01f * driftProgress
            },
        contentScale = ContentScale.Crop,
        alignment = androidx.compose.ui.BiasAlignment(0f, -0.4f),
    )
}

/**
 * Separable box blur run N times on a decoded bitmap. Three iterations of a
 * box blur is a cheap approximation of a Gaussian blur. The 96x96 input is
 * small enough that this is ~1ms on any recent CPU.
 *
 * Used instead of Modifier.blur because that modifier requires API 31+ and
 * this app targets Android TV 11 (API 30).
 */
private class BoxBlurTransformation(
    private val radius: Int = 6,
    private val iterations: Int = 3,
) : Transformation {
    override val cacheKey: String = "sendspin-boxblur-r${radius}-i${iterations}"

    override suspend fun transform(input: Bitmap, size: coil.size.Size): Bitmap {
        val src = if (input.config == Bitmap.Config.ARGB_8888) input
                  else input.copy(Bitmap.Config.ARGB_8888, true)
        val width = src.width
        val height = src.height
        var pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        var scratch = IntArray(width * height)
        repeat(iterations) {
            boxBlurHorizontal(pixels, scratch, width, height, radius)
            boxBlurVertical(scratch, pixels, width, height, radius)
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0; var n = 0
                val x0 = maxOf(0, x - r)
                val x1 = minOf(w - 1, x + r)
                for (xi in x0..x1) {
                    val p = src[yOff + xi]
                    sumA += (p ushr 24) and 0xFF
                    sumR += (p ushr 16) and 0xFF
                    sumG += (p ushr 8) and 0xFF
                    sumB += p and 0xFF
                    n++
                }
                dst[yOff + x] = ((sumA / n) shl 24) or ((sumR / n) shl 16) or ((sumG / n) shl 8) or (sumB / n)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0; var n = 0
                val y0 = maxOf(0, y - r)
                val y1 = minOf(h - 1, y + r)
                for (yi in y0..y1) {
                    val p = src[yi * w + x]
                    sumA += (p ushr 24) and 0xFF
                    sumR += (p ushr 16) and 0xFF
                    sumG += (p ushr 8) and 0xFF
                    sumB += p and 0xFF
                    n++
                }
                dst[y * w + x] = ((sumA / n) shl 24) or ((sumR / n) shl 16) or ((sumG / n) shl 8) or (sumB / n)
            }
        }
    }
}

@Composable
private fun AccentWash(accent: Color, paused: Boolean) {
    // BoxWithConstraints reads layout size so the radial brush can be sized,
    // then the brush is applied via Modifier.background. Shield Tegra renders
    // drawRect(brush=...) inside DrawScope as black, which made the prior
    // implementation invisible on the target device; Modifier.background
    // takes a different code path that works. The BlendMode.Screen that the
    // design originally called for can't be expressed via Modifier.background.
    // For the accent colors and low alpha values used here, the visual delta
    // between Screen and normal alpha composition is subtle, so the wash
    // alphas are kept identical to the pre-fix code.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val washAlpha = if (paused) 0.08f else 0.2f
        val brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = washAlpha), Color.Transparent),
            center = Offset(w * 0.30f, h * 0.35f),
            radius = maxOf(w, h) * 0.55f,
        )
        Box(modifier = Modifier.fillMaxSize().background(brush))
    }
}

@Composable
private fun Vignette() {
    // BoxWithConstraints reads layout size so the brush radius can be
    // computed and applied via Modifier.background. Shield Tegra renders
    // drawRect(brush=...) as black; Modifier.background works.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.35f to Color.Transparent,
                1.0f to Color.Black,
            ),
            center = Offset(w * 0.5f, h * 0.5f),
            radius = maxOf(w, h) * 0.75f,
        )
        Box(modifier = Modifier.fillMaxSize().background(brush))
    }
}

@Composable
private fun GrainOverlay() {
    val grain = SharedGrainBitmap
    val brush = remember(grain) {
        ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                onDrawBehind {
                    drawRect(brush = brush, alpha = 0.05f, blendMode = BlendMode.Overlay)
                }
            },
    )
}

/**
 * Shared 256x256 grain tile. Allocated once for the lifetime of the process
 * and reused by both AmbientBg and NowPlayingIdleScreen.
 */
internal val SharedGrainBitmap: ImageBitmap by lazy {
    val tileSize = 256
    val bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(tileSize * tileSize)
    val random = Random(2L)
    for (i in pixels.indices) {
        val v = random.nextInt(0, 256)
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    bitmap.setPixels(pixels, 0, tileSize, 0, 0, tileSize, tileSize)
    bitmap.asImageBitmap()
}
