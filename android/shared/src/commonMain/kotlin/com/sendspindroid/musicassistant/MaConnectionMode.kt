package com.sendspindroid.musicassistant

/**
 * Connection modes for the Music Assistant API.
 *
 * REMOTE and PROXY have no app-side producer since the WebRTC and reverse-proxy
 * transports were removed; [MaApiEndpoint] and [MaCommandClient] still branch on
 * all three values. See docs/superpowers/sdd/2026-09-01-cut-remote-access/ for
 * why this enum was left as-is rather than collapsed to LOCAL.
 */
enum class MaConnectionMode {
    LOCAL,
    REMOTE,
    PROXY
}
