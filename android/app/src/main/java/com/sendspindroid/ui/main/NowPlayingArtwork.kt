package com.sendspindroid.ui.main

import com.sendspindroid.sendspin.protocol.SendSpinProtocol

/**
 * Fork: the URL the now-playing views load. Music Assistant's artwork_url is
 * an imageproxy thumbnail at size=512, smaller than the phone's full-screen
 * art and the TV screen draw it, so ask the proxy for the same size the
 * artwork stream requests. Other URLs are used as they are.
 */
fun nowPlayingArtworkUrl(url: String): String {
    if (!url.contains("/imageproxy")) return url
    return SIZE_PARAM.replace(url) { "${it.groupValues[1]}${SendSpinProtocol.Artwork.REQUEST_SIZE}" }
}

private val SIZE_PARAM = Regex("""([?&]size=)\d+""")

/**
 * Fork: picks the now-playing art from a controller MediaMetadata emission.
 * The MediaSession carries a <=300px blob sized for the lock screen and
 * notification, so the in-app views prefer the artwork URL it was made from,
 * and for binary (artwork stream) art the unscaled bytes PlaybackService kept.
 * Returns null when there is neither.
 */
fun nowPlayingArtworkSource(
    artworkData: ByteArray?,
    artworkUri: String?,
    fullResBytes: ByteArray?,
): ArtworkSource? = when {
    artworkUri != null && (artworkUri.startsWith("http://") || artworkUri.startsWith("https://")) ->
        ArtworkSource.Url(nowPlayingArtworkUrl(artworkUri))
    artworkData != null && artworkData.isNotEmpty() ->
        ArtworkSource.ByteArray(fullResBytes ?: artworkData)
    else -> null
}
