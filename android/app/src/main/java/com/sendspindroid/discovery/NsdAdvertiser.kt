package com.sendspindroid.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Announces this client on mDNS so servers can connect to it
 * (`connection.md`, "Server Initiated Connections"):
 *
 * - Service type: `_sendspin._tcp.local.`
 * - Port: the port the client is listening on
 * - TXT `path`: the WebSocket endpoint, REQUIRED
 * - TXT `name`: the friendly name, which "SHOULD match the `name` the client
 *   sends in `client/hello`"
 *
 * The counterpart of [NsdDiscoveryManager], which browses for servers. A
 * client does one or the other, never both: "Clients MUST NOT manually
 * connect to servers while advertising `_sendspin._tcp`."
 *
 * Call from the main thread.
 */
class NsdAdvertiser(
    private val context: Context,
    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager,
) {
    companion object {
        private const val TAG = "NsdAdvertiser"
        const val SERVICE_TYPE = "_sendspin._tcp."
        const val TXT_PATH = "path"
        const val TXT_NAME = "name"

        /** A DNS label is at most 63 bytes. */
        private const val MAX_LABEL_BYTES = 63

        fun serviceInfo(name: String, port: Int, path: String): NsdServiceInfo =
            NsdServiceInfo().apply {
                serviceName = instanceName(name)
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute(TXT_PATH, path)
                setAttribute(TXT_NAME, name)
            }

        /** [name] cut to what fits in a DNS label, on a character boundary. */
        internal fun instanceName(name: String): String {
            var end = name.length
            while (name.substring(0, end).toByteArray(Charsets.UTF_8).size > MAX_LABEL_BYTES) end--
            return name.substring(0, end)
        }
    }

    private var registration: NsdManager.RegistrationListener? = null
    private var advertised: NsdServiceInfo? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    val isAdvertising: Boolean get() = registration != null

    /**
     * Advertise the listener on [port]. Replaces whatever was advertised.
     *
     * @param name the player name sent in `client/hello`.
     */
    fun start(name: String, port: Int, path: String) {
        stop()
        advertised = serviceInfo(name, port, path)
        register()
    }

    /**
     * Announce again after the network changed: the registration made on the
     * old network is not carried to the new one on every Android version.
     */
    fun refresh() {
        if (registration == null) return
        Log.i(TAG, "Network changed - advertising again")
        unregister()
        register()
    }

    fun stop() {
        unregister()
        advertised = null
    }

    private fun register() {
        val info = advertised ?: return
        // Answering a server's query means hearing it, and Android filters
        // multicast while no lock is held.
        acquireMulticastLock()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                Log.i(TAG, "Advertising ${registered.serviceName} ($SERVICE_TYPE) on port ${info.port}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Advertising failed: error $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "No longer advertising ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Withdrawing the advertisement failed: error $errorCode")
            }
        }
        registration = listener
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Advertising failed", e)
            registration = null
            releaseMulticastLock()
        }
    }

    private fun unregister() {
        val listener = registration ?: return
        registration = null
        try {
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            Log.d(TAG, "unregisterService ignored: ${e.message}")
        }
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("SendSpinDroid_Advertise").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
}
