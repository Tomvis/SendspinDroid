package com.sendspindroid.ui.main.components.nowplaying

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
    Box(
        modifier = modifier
            .fillMaxSize()
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
    val model = rememberCrossfadingArtworkRequest(
        artworkSource = artworkSource,
        // Distinct namespace from the unblurred focus art so the blurred
        // result has its own memory-cache entry.
        namespace = "np-ambient",
        // Crossfade off: the blurred-image alpha-blend mid-transition reads
        // as a screen-wide brightness shift. With the held-bitmap underlay
        // below, the swap to the new bitmap is hidden because the underlay
        // already shows the prior content.
        crossfadeMillis = 0,
    ) {
        // 384x384 source + radius=24 keeps sigma proportional to the 96x6 baseline
        // while giving a much smoother ambient on 4K output.
        size(Size(384, 384))
        transformations(BoxBlurTransformation(radius = 24, iterations = 3))
    }

    // Always create the transition; gating it on `paused` would mutate the
    // slot table when paused flips and scramble Compose remembered state.
    // Bind to the State<Float> rather than reading .value here -- the read
    // happens inside the graphicsLayer block below, which only invalidates the
    // layer per frame instead of recomposing all of BlurredCover (60Hz
    // recompose churn was visible on the Shield Tegra).
    val drift = rememberInfiniteTransition(label = "np-drift")
    val driftState = drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 40_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "np-drift-progress",
    )

    // Persistent underlay holding the last successfully loaded bitmap.
    // Coil's placeholderMemoryCacheKey lookup uses bare-string equality on
    // MemoryCache.Key, but a stored entry for a *transformed* image (our
    // BoxBlurTransformation) carries the transformation list as Key extras --
    // so the placeholder lookup misses on every track change and the
    // AsyncImage briefly draws nothing while loading the new blurred bitmap.
    // The underlay covers that gap with the previous content; the foreground
    // AsyncImage paints over it once the new bitmap arrives.
    var heldBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val coverLayer: GraphicsLayerScope.() -> Unit = {
        scaleX = 1.18f
        scaleY = 1.18f
        alpha = 0.7f
        val driftProgress = if (paused) driftState.value else 0f
        translationX = -size.width * 0.015f * driftProgress
        translationY = -size.height * 0.01f * driftProgress
    }

    Box(modifier = Modifier.fillMaxSize()) {
        heldBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(block = coverLayer),
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, -0.4f),
            )
        }
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(block = coverLayer),
            onSuccess = { state ->
                val drawable = state.result.drawable
                if (drawable is BitmapDrawable) {
                    heldBitmap = drawable.bitmap.asImageBitmap()
                }
            },
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(0f, -0.4f),
        )
    }
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
        // Bitmap.Config.HARDWARE doesn't support getPixels or copy-to-software
        // (copy returns null). If we hit one, bail out with the input unchanged
        // rather than crashing -- the ambient cover will just render unblurred,
        // which is a minor visual regression but not a fatal one.
        val srcIsCopy = input.config != Bitmap.Config.ARGB_8888
        val src: Bitmap = if (!srcIsCopy) {
            input
        } else {
            input.copy(Bitmap.Config.ARGB_8888, true) ?: return input
        }
        val width = src.width
        val height = src.height
        var pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        // Drop the temporary ARGB_8888 copy as soon as we've extracted its
        // pixels; otherwise it stays alive (along with input) until GC. On
        // the Shield the recurring ambient refreshes were doubling bitmap
        // pressure.
        if (srcIsCopy) src.recycle()
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
        // Keyed on accent + paused + size: brushes back a Shader, and
        // re-allocating one per recompose churns GC on every animation frame.
        val brush = remember(accent, washAlpha, w, h) {
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = washAlpha), Color.Transparent),
                center = Offset(w * 0.30f, h * 0.35f),
                radius = maxOf(w, h) * 0.55f,
            )
        }
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
        val brush = remember(w, h) {
            Brush.radialGradient(
                colorStops = arrayOf(
                    0.35f to Color.Transparent,
                    1.0f to Color.Black,
                ),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = maxOf(w, h) * 0.75f,
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(brush))
    }
}

@Composable
private fun GrainOverlay() {
    val grain = SharedGrainBitmap
    val brush = remember(grain) {
        ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
    }
    // Use Modifier.background(brush, alpha) rather than drawRect inside
    // drawWithCache. The Shield Tegra renderer is unreliable with shader-
    // backed brushes inside Canvas-style DrawScopes (gradient brushes paint
    // black there); Modifier.background uses a different code path that
    // renders reliably. We lose BlendMode.Overlay vs the spec, but at this
    // alpha the visual delta is imperceptible.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = brush, alpha = 0.012f),
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
