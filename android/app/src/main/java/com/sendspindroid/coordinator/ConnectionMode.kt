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
 * Every outbound connection is opened through [dial], which opens it only
 * once the advertisement and the listener are gone, and nothing else turns
 * [Advertising] on or off. Browsing for servers is the other method, so it
 * is stopped, and refused, for as long as anything is advertised
 * ([Discovery]). Not thread-safe: call from one thread.
 */
class ConnectionMode(
    private val advertising: Advertising,
    private val discovery: Discovery,
) {

    /** The listener and its mDNS announcement, up or down together. */
    interface Advertising {
        fun start()

        /**
         * Take both down. [onStopped] is called, on the thread this was
         * called on, once the listener's port is released; that may be
         * before this returns or some time after it. Nothing here waits.
         */
        fun stop(onStopped: () -> Unit)
    }

    /** Every mDNS browse for servers the app makes, wherever it is made. */
    interface Discovery {
        /** Stop every browse under way and refuse new ones. */
        fun forbid()

        /** Browsing may start again. */
        fun allow()
    }

    private var running = false
    private var searching = false
    private var dialledOut = false

    /** A stop has been asked for and [Advertising.stop] has not reported back. */
    private var stopping = false

    /** The connection [dial] was asked to open, until it has been opened. */
    private var pendingDial: (() -> Unit)? = null

    /** Whether the app is meant to be advertising. While a stop completes, the listener outlives this. */
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
        pendingDial = null
        apply()
    }

    /** The user chose to search for servers (true) or to advertise (false). */
    fun setSearching(search: Boolean) {
        searching = search
        apply()
    }

    /**
     * Open an outbound connection with [connect], once nothing is advertised
     * and the listener is closed: at once if that is already so, otherwise
     * when [Advertising.stop] reports back. The app stays dialled out until
     * [dialEnded]. A second call before the first has connected replaces it.
     */
    fun dial(connect: () -> Unit) {
        dialledOut = true
        pendingDial = connect
        apply()
    }

    /**
     * The outbound connection is gone and nothing is trying to bring it
     * back: return to whichever mode is selected.
     *
     * Ignored while [dial] has not opened its connection yet. Taking the
     * advertisement down ends the connection a server had opened to us, and
     * that ending must not be taken for the end of the dial.
     */
    fun dialEnded() {
        if (pendingDial != null) return
        dialledOut = false
        apply()
    }

    private fun apply() {
        // One thing at a time. Whatever was asked for meanwhile is applied
        // when the stop in flight reports back.
        if (stopping) return
        val advertise = running && !searching && !dialledOut
        if (advertise != isAdvertising) {
            isAdvertising = advertise
            if (advertise) {
                discovery.forbid()
                advertising.start()
            } else {
                stopping = true
                advertising.stop {
                    stopping = false
                    apply()
                    // Unless that put the advertisement straight back up.
                    if (!isAdvertising) discovery.allow()
                }
                return
            }
        }
        val connect = pendingDial ?: return
        try {
            connect()
        } finally {
            pendingDial = null
        }
    }
}
