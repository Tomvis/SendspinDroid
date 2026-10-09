package com.sendspindroid.coordinator

/**
 * The one place that decides how the client and a server find each other.
 *
 * `connection.md`, "Establishing a Connection": "Clients MUST use exactly one
 * of the two methods at a time, advertising or discovering accordingly", and
 * "Clients MUST NOT manually connect to servers while advertising
 * `_sendspin._tcp`". So there are three states and the app is in one of them:
 *
 * - advertising: the listener is up and announced, and servers connect to it;
 * - searching: nothing is announced, and the app looks for servers to dial;
 * - dialled out: the app opened a connection itself, from either of the
 *   other two, and stays here for as long as that connection or its
 *   reconnect loop is alive.
 *
 * Every outbound connection is opened through [dial], which takes the
 * advertisement down before the connection is attempted, and nothing else
 * turns [Advertising] on or off. Not thread-safe: call from one thread.
 */
class ConnectionMode(private val advertising: Advertising) {

    /** The listener and its mDNS announcement, up or down together. */
    interface Advertising {
        fun start()
        fun stop()
    }

    private var running = false
    private var searching = false
    private var dialledOut = false

    /** Inside [dial], before the connection it opens exists. */
    private var opening = false

    var isAdvertising = false
        private set

    /** Start in the mode the user last chose. */
    fun start(searchForServers: Boolean) {
        running = true
        searching = searchForServers
        apply()
    }

    /** Everything down, for good. */
    fun stop() {
        running = false
        apply()
    }

    /** The user chose to search for servers (true) or to advertise (false). */
    fun setSearching(search: Boolean) {
        searching = search
        apply()
    }

    /**
     * Run [connect], which opens an outbound connection, with nothing
     * advertised. The app stays dialled out until [dialEnded].
     */
    fun <T> dial(connect: () -> T): T {
        dialledOut = true
        opening = true
        try {
            apply()
            return connect()
        } finally {
            opening = false
        }
    }

    /**
     * The outbound connection is gone and nothing is trying to bring it
     * back: return to whichever mode is selected.
     *
     * Ignored while [dial] is still on its way to opening the connection.
     * Taking the advertisement down ends the connection a server had opened
     * to us, and that ending must not be taken for the end of the dial.
     */
    fun dialEnded() {
        if (opening) return
        dialledOut = false
        apply()
    }

    private fun apply() {
        val advertise = running && !searching && !dialledOut
        if (advertise == isAdvertising) return
        isAdvertising = advertise
        if (advertise) advertising.start() else advertising.stop()
    }
}
