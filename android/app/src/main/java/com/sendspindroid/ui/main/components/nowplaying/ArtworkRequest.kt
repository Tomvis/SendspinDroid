package com.sendspindroid.ui.main.components.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import com.sendspindroid.ui.main.ArtworkSource

/**
 * Coil's AsyncImage transitions through the Loading state on every model
 * change, draws the static placeholder painter for the duration of the
 * decode, then crossfades from that placeholder to the new image. Across
 * a track change this reads as a brief flash of the placeholder between
 * the old album cover and the new one.
 *
 * `placeholderMemoryCacheKey` tells Coil "while loading the new image,
 * draw the bitmap cached under THIS key as the placeholder". We give
 * each request an explicit memory cache key derived from the artwork
 * content + a namespace so each AsyncImage call-site (focus art, ambient
 * blur, etc.) caches under its own key and doesn't collide with another
 * call-site that applies different transformations. We then point the
 * next request's placeholderMemoryCacheKey at the previous request's
 * key — Coil pulls the previous bitmap out of memory cache and the
 * crossfade is old-image → new-image instead of placeholder → new-image.
 *
 * [namespace] separates per-call-site caches (the same bytes blurred for
 * the ambient background must not collide with the unblurred focus
 * artwork). [configure] is the per-call-site builder block — size,
 * transformations, placeholder, etc. Crossfade is applied here so every
 * caller benefits; pass [crossfadeMillis] = 0 to disable.
 */
@Composable
internal fun rememberCrossfadingArtworkRequest(
    artworkSource: ArtworkSource?,
    namespace: String,
    crossfadeMillis: Int = 100,
    configure: ImageRequest.Builder.() -> Unit = {},
): ImageRequest? {
    val context = LocalContext.current
    val artworkKey = artworkSource?.coilMemoryCacheKey(namespace)
    // previousKeyState holds the key from the prior frame. SideEffect updates
    // it after each composition; the `remember(artworkSource)` block reads
    // the value captured at evaluation time, which on a track change is the
    // prior track's key.
    var previousKey by remember { mutableStateOf<String?>(null) }
    val placeholderKey = previousKey

    val request = remember(artworkSource, namespace) {
        if (artworkSource == null) return@remember null
        val builder = ImageRequest.Builder(context).crossfade(crossfadeMillis)
        when (artworkSource) {
            is ArtworkSource.ByteArray -> builder.data(artworkSource.data)
            is ArtworkSource.Uri -> builder.data(artworkSource.uri)
            is ArtworkSource.Url -> builder.data(artworkSource.url)
        }
        artworkKey?.let { builder.memoryCacheKey(it) }
        if (placeholderKey != null && placeholderKey != artworkKey) {
            builder.placeholderMemoryCacheKey(placeholderKey)
        }
        configure(builder)
        builder.build()
    }

    SideEffect {
        if (artworkKey != null && artworkKey != previousKey) {
            previousKey = artworkKey
        }
    }

    return request
}

/**
 * Stable per-content cache key. ArtworkSource.ByteArray's hashCode already
 * uses contentHashCode (see MainUiState.kt), so this is stable for the
 * same image regardless of which ByteArray instance wraps it.
 */
private fun ArtworkSource.coilMemoryCacheKey(namespace: String): String = when (this) {
    is ArtworkSource.ByteArray -> "sendspin-$namespace-bytes-${hashCode()}"
    is ArtworkSource.Uri -> "sendspin-$namespace-uri-$uri"
    is ArtworkSource.Url -> "sendspin-$namespace-url-$url"
}
