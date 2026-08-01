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
    // Always allocate the same remember slots so the slot table is stable
    // across artwork null<->non-null transitions. The original early return
    // re-keyed `heldBitmap` on every transition through null and defeated the
    // held-bitmap underlay's purpose (covering the gap until the new blurred
    // bitmap arrives).

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
        if (model != null) {
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
}

/**
 * Separable box blur run N times on a decoded bitmap. Three iterations of a
 * box blur is a cheap approximation of a Gaussian blur. The sole caller blurs a
 * downscaled 384x384 ambient cover at radius 24 over 3 iterations; transform()
 * is suspend and runs on Coil's background dispatcher, so the cost stays off
 * the UI path.
 *
 * Used instead of Modifier.blur because that modifier requires API 31+ and
 * this app targets Android TV 11 (API 30).
 */
private class BoxBlurTransformation(
    private val radius: Int,
    private val iterations: Int,
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

    // Both passes keep a running window sum instead of re-accumulating the
    // whole (2r+1)-wide kernel per pixel. The window for output pixel i is
    // [max(0, i-r), min(last, i+r)] exactly as before, and the divisor is that
    // window's true length, so the output is bit-identical to the naive form --
    // it just does O(1) work per pixel instead of O(r). At the caller's
    // 384x384 / r=24 / 3 iterations that is ~49x fewer accumulate steps, which
    // matters because the transform competes with the decode dispatcher and
    // the audio playback loop for the Shield's cores on every track change.

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        for (y in 0 until h) {
            val yOff = y * w
            var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
            // Seed with the window for x = 0.
            for (xi in 0..minOf(w - 1, r)) {
                val p = src[yOff + xi]
                sumA += (p ushr 24) and 0xFF
                sumR += (p ushr 16) and 0xFF
                sumG += (p ushr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (x in 0 until w) {
                if (x > 0) {
                    // Slide: drop the pixel that fell off the left edge, add
                    // the one that entered on the right.
                    val outIdx = x - r - 1
                    if (outIdx >= 0) {
                        val p = src[yOff + outIdx]
                        sumA -= (p ushr 24) and 0xFF
                        sumR -= (p ushr 16) and 0xFF
                        sumG -= (p ushr 8) and 0xFF
                        sumB -= p and 0xFF
                    }
                    val inIdx = x + r
                    if (inIdx <= w - 1) {
                        val p = src[yOff + inIdx]
                        sumA += (p ushr 24) and 0xFF
                        sumR += (p ushr 16) and 0xFF
                        sumG += (p ushr 8) and 0xFF
                        sumB += p and 0xFF
                    }
                }
                val n = minOf(w - 1, x + r) - maxOf(0, x - r) + 1
                dst[yOff + x] = ((sumA / n) shl 24) or ((sumR / n) shl 16) or ((sumG / n) shl 8) or (sumB / n)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        for (x in 0 until w) {
            var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
            // Seed with the window for y = 0.
            for (yi in 0..minOf(h - 1, r)) {
                val p = src[yi * w + x]
                sumA += (p ushr 24) and 0xFF
                sumR += (p ushr 16) and 0xFF
                sumG += (p ushr 8) and 0xFF
                sumB += p and 0xFF
            }
            for (y in 0 until h) {
                if (y > 0) {
                    val outIdx = y - r - 1
                    if (outIdx >= 0) {
                        val p = src[outIdx * w + x]
                        sumA -= (p ushr 24) and 0xFF
                        sumR -= (p ushr 16) and 0xFF
                        sumG -= (p ushr 8) and 0xFF
                        sumB -= p and 0xFF
                    }
                    val inIdx = y + r
                    if (inIdx <= h - 1) {
                        val p = src[inIdx * w + x]
                        sumA += (p ushr 24) and 0xFF
                        sumR += (p ushr 16) and 0xFF
                        sumG += (p ushr 8) and 0xFF
                        sumB += p and 0xFF
                    }
                }
                val n = minOf(h - 1, y + r) - maxOf(0, y - r) + 1
                dst[y * w + x] = ((sumA / n) shl 24) or ((sumR / n) shl 16) or ((sumG / n) shl 8) or (sumB / n)
            }
        }
    }
}

/**
 * Draws a size-derived gradient overlay filling [modifier]'s bounds.
 *
 * Every gradient on the Now Playing screens goes through Modifier.background
 * rather than a DrawScope drawRect(brush=...): the Shield Tegra renderer drops
 * Canvas-shader brushes and paints black, while Modifier.background takes a
 * different code path that renders reliably. The trade-off is that the design's
 * BlendMode.Screen / Overlay can't be expressed this way; at the alphas used
 * here the delta against normal alpha composition is imperceptible.
 *
 * [buildBrush] receives the resolved pixel size and is cached under
 * [key1]/[key2] plus that size -- brushes back a Shader, and re-allocating one
 * per recompose churns GC on every animation frame. Callers must pass every
 * value the lambda reads as a key.
 */
@Composable
internal fun RadialWash(
    modifier: Modifier = Modifier,
    key1: Any? = null,
    key2: Any? = null,
    buildBrush: (w: Float, h: Float) -> Brush,
) {
    BoxWithConstraints(modifier = modifier) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val brush = remember(key1, key2, w, h) { buildBrush(w, h) }
        Box(modifier = Modifier.fillMaxSize().background(brush))
    }
}

@Composable
private fun AccentWash(accent: Color, paused: Boolean) {
    val washAlpha = if (paused) 0.08f else 0.2f
    RadialWash(modifier = Modifier.fillMaxSize(), key1 = accent, key2 = washAlpha) { w, h ->
        Brush.radialGradient(
            colors = listOf(accent.copy(alpha = washAlpha), Color.Transparent),
            center = Offset(w * 0.30f, h * 0.35f),
            radius = maxOf(w, h) * 0.55f,
        )
    }
}

/**
 * Radial darken-to-edges vignette. [radiusFactor] scales the gradient radius
 * relative to the larger screen dimension (smaller = tighter/darker). Shared by
 * AmbientBg (0.75) and the idle screen (0.8).
 */
@Composable
internal fun Vignette(radiusFactor: Float = 0.75f) {
    RadialWash(modifier = Modifier.fillMaxSize(), key1 = radiusFactor) { w, h ->
        Brush.radialGradient(
            colorStops = arrayOf(
                0.35f to Color.Transparent,
                1.0f to Color.Black,
            ),
            center = Offset(w * 0.5f, h * 0.5f),
            radius = maxOf(w, h) * radiusFactor,
        )
    }
}

/**
 * Faint repeating-noise grain overlay. Shared by AmbientBg and the idle screen.
 */
@Composable
internal fun GrainOverlay() {
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
