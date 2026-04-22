package com.sendspindroid.ui.main.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sendspindroid.R
import com.sendspindroid.ui.main.ArtworkSource

/**
 * TV variant of [AlbumArtCard]. Decorative-only: uses a non-focusable Box wrapper
 * so D-pad navigation does not land on the artwork (tv.material3 Card would).
 * Visuals match the shared variant: 16dp rounded corners + 8dp elevation shadow.
 */
@Composable
fun AlbumArtCardTv(
    artworkSource: ArtworkSource?,
    isBuffering: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 500.dp,
    contentDescription: String = stringResource(R.string.album_art)
) {
    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .aspectRatio(1f)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        val context = LocalContext.current
        val imageRequest = when (artworkSource) {
            is ArtworkSource.ByteArray -> ImageRequest.Builder(context)
                .data(artworkSource.data)
                .crossfade(true)
                .build()
            is ArtworkSource.Uri -> ImageRequest.Builder(context)
                .data(artworkSource.uri)
                .crossfade(true)
                .build()
            is ArtworkSource.Url -> ImageRequest.Builder(context)
                .data(artworkSource.url)
                .crossfade(true)
                .build()
            null -> null
        }

        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.placeholder_album_simple),
            error = painterResource(R.drawable.placeholder_album_simple),
            fallback = painterResource(R.drawable.placeholder_album_simple)
        )

        if (isBuffering) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp
            )
        }
    }
}
