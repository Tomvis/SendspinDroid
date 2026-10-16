package com.sendspindroid.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sendspindroid.R
import com.sendspindroid.playback.PlaybackService
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.rememberFocusFallback
import com.sendspindroid.ui.adaptive.tvFocusable
import com.sendspindroid.ui.main.ArtworkSource
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.launch

/**
 * Album art card with optional buffering overlay.
 * Displays artwork from various sources (byte array, URI, or URL).
 *
 * When the server has sent an image of the artist, the card can be swiped
 * sideways to it and back, with two dots showing which one is in view. A TV
 * remote cannot swipe: there the card takes focus, and OK turns it.
 *
 * @param focusFallback where focus goes if the card has it when it stops
 *   taking focus, which happens when the next track has no artist image.
 */
@Composable
fun AlbumArtCard(
    artworkSource: ArtworkSource?,
    isBuffering: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 320.dp,
    contentDescription: String = stringResource(R.string.album_art),
    artistImage: ByteArray? = PlaybackService.artistArtwork.collectAsStateWithLifecycle().value,
    focusFallback: FocusRequester? = null,
) {
    // Starts on the album art each time an artist image appears
    val pagerState = key(artistImage != null) { rememberPagerState(pageCount = { 2 }) }
    val scope = rememberCoroutineScope()
    val isTv = LocalFormFactor.current == FormFactor.TV
    val turnable = artistImage != null && isTv
    val keepFocus = focusFallback?.let { rememberFocusFallback(turnable, it) } ?: Modifier
    val turnOnTv = if (turnable) {
        Modifier
            .onPreviewKeyEvent { event ->
                val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (ok && event.type == KeyEventType.KeyUp) {
                    scope.launch { pagerState.animateScrollToPage(1 - pagerState.currentPage) }
                }
                ok
            }
            .tvFocusable(shape = RoundedCornerShape(16.dp))
    } else {
        Modifier
    }

    Card(
        modifier = modifier
            .widthIn(max = maxWidth)
            .aspectRatio(1f)
            .then(keepFocus)
            .then(turnOnTv),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Album Art Image
            val context = LocalContext.current
            val imageRequest = when (artworkSource) {
                is ArtworkSource.ByteArray -> {
                    ImageRequest.Builder(context)
                        .data(artworkSource.data)
                        .crossfade(true)
                        .build()
                }
                is ArtworkSource.Uri -> {
                    ImageRequest.Builder(context)
                        .data(artworkSource.uri)
                        .crossfade(true)
                        .build()
                }
                is ArtworkSource.Url -> {
                    ImageRequest.Builder(context)
                        .data(artworkSource.url)
                        .crossfade(true)
                        .build()
                }
                null -> null
            }

            val albumArt: @Composable () -> Unit = {
                if (isTv) {
                    // The light grey placeholder glares in the dark room a TV
                    // is watched in: the theme's muted colours instead, which
                    // the art covers once it loads.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxSize(0.4f)
                        )
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = contentDescription,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = contentDescription,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(R.drawable.placeholder_album_simple),
                        error = painterResource(R.drawable.placeholder_album_simple),
                        fallback = painterResource(R.drawable.placeholder_album_simple)
                    )
                }
            }

            val artist = artistImage
            if (artist == null) {
                albumArt()
            } else {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    if (page == 0) {
                        albumArt()
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(artist).crossfade(true).build(),
                            contentDescription = stringResource(R.string.artist_photo),
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(2) { page ->
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    Color.White.copy(alpha = if (pagerState.currentPage == page) 0.95f else 0.45f)
                                )
                        )
                    }
                }
            }

            // Buffering Overlay
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier,
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumArtCardPreview() {
    SendSpinTheme {
        AlbumArtCard(
            artworkSource = null,
            isBuffering = false
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumArtCardBufferingPreview() {
    SendSpinTheme {
        AlbumArtCard(
            artworkSource = null,
            isBuffering = true
        )
    }
}
