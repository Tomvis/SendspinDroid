package com.sendspindroid.discovery

import com.sendspindroid.coordinator.ConnectionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What keeps every mDNS browse in the app off while the app advertises
 * itself: "Clients MUST use exactly one of the two methods at a time,
 * advertising or discovering accordingly" (`connection.md`).
 *
 * Browses are started in several places (the server list, the add-server
 * wizard, Android Auto, the reconnect loop), each with its own
 * [NsdDiscoveryManager]. They all pass through here: a manager asks before
 * it starts, and [forbid] stops the ones that are running. [ConnectionMode]
 * is the only caller of [forbid] and [allow].
 */
object DiscoveryGate : ConnectionMode.Discovery {

    private val _allowed = MutableStateFlow(true)

    /** Whether a browse may be started. For a screen that wants to start one when it can. */
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    /** Each browse under way, with how to stop it. */
    private val browsing = mutableMapOf<Any, () -> Unit>()

    /**
     * [owner] wants to browse. False means it must not; true, that [stop]
     * will be called if browsing is forbidden before [leave].
     */
    @Synchronized
    fun enter(owner: Any, stop: () -> Unit): Boolean {
        if (!_allowed.value) return false
        browsing[owner] = stop
        return true
    }

    /** [owner] has stopped browsing. */
    @Synchronized
    fun leave(owner: Any) {
        browsing -= owner
    }

    override fun forbid() {
        val stops = synchronized(this) {
            _allowed.value = false
            browsing.values.toList().also { browsing.clear() }
        }
        stops.forEach { it() }
    }

    override fun allow() {
        _allowed.value = true
    }
}
