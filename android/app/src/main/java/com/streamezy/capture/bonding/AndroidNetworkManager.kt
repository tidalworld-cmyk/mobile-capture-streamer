package com.streamezy.capture.bonding

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Android Multi-Network Manager for LiveU LRT-style bonding.
 *
 * KEY FIX: Android OS hides cellular from allNetworks when Wi-Fi is active.
 * We use requestNetwork() with TRANSPORT_CELLULAR to force Android to
 * expose the cellular network handle SIMULTANEOUSLY with Wi-Fi.
 * This enables true multi-path bonding (Wi-Fi + Cellular simultaneously).
 */
class AndroidNetworkManager(private val context: Context) {
    companion object {
        private const val TAG = "BondNetworkManager"
        const val PATH_ID_WIFI: Byte = 1
        const val PATH_ID_SIM1: Byte = 2
        const val PATH_ID_SIM2: Byte = 3
        const val PATH_ID_ETHERNET: Byte = 4
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val paths = mutableMapOf<Byte, NetworkPath>()
    var onPathsChanged: ((List<NetworkPath>) -> Unit)? = null

    // Persistent callbacks to keep network handles alive for bonding
    private var cellularNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var ethernetNetworkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        paths[PATH_ID_WIFI] = NetworkPath(PATH_ID_WIFI, "Wi-Fi", "WIFI", carrierName = getWifiSSID())
        paths[PATH_ID_SIM1] = NetworkPath(PATH_ID_SIM1, "SIM 1 — " + getSimCarrierName(0), "CELLULAR", carrierName = getSimCarrierName(0))
        paths[PATH_ID_SIM2] = NetworkPath(PATH_ID_SIM2, "SIM 2 — " + getSimCarrierName(1), "CELLULAR", carrierName = getSimCarrierName(1))
        paths[PATH_ID_ETHERNET] = NetworkPath(PATH_ID_ETHERNET, "USB / Ethernet", "ETHERNET", carrierName = "Ethernet")
    }

    /**
     * Start persistent network discovery.
     * Uses requestNetwork() for cellular so it remains active even when Wi-Fi is on.
     * This is required for LiveU LRT-style bonding over Wi-Fi + cellular simultaneously.
     */
    fun startDiscovery() {
        Log.i(TAG, "Starting BondStream multi-path network discovery (LiveU LRT mode)...")
        stopDiscovery() // clean up any previous callbacks

        // ── 1. REQUEST CELLULAR ── MUST use requestNetwork, not registerNetworkCallback.
        //    requestNetwork forces Android to keep the cellular data path alive
        //    even while Wi-Fi is connected. Without this, cellular disappears when Wi-Fi active.
        try {
            val cellRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cellularNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Cellular available for bonding: $network")
                    handleCellularAvailable(network)
                }
                override fun onLost(network: Network) {
                    Log.i(TAG, "Cellular lost: $network")
                    handleNetworkLost(network)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    handleCapabilitiesChanged(network, caps)
                }
                override fun onUnavailable() {
                    Log.i(TAG, "Cellular network unavailable")
                }
            }
            connectivityManager.requestNetwork(cellRequest, cellularNetworkCallback!!)
            Log.i(TAG, "Cellular requestNetwork registered")
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork cellular failed: ${e.message}")
        }

        // ── 2. REGISTER Wi-Fi callback
        try {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Wi-Fi available: $network")
                    handleWifiAvailable(network)
                }
                override fun onLost(network: Network) {
                    Log.i(TAG, "Wi-Fi lost: $network")
                    handleNetworkLost(network)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    handleCapabilitiesChanged(network, caps)
                }
            }
            connectivityManager.registerNetworkCallback(wifiRequest, wifiNetworkCallback!!)
            Log.i(TAG, "Wi-Fi callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback wifi failed: ${e.message}")
        }

        // ── 3. REGISTER Ethernet callback
        try {
            val ethRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            ethernetNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleEthernetAvailable(network)
                }
                override fun onLost(network: Network) {
                    handleNetworkLost(network)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    handleCapabilitiesChanged(network, caps)
                }
            }
            connectivityManager.registerNetworkCallback(ethRequest, ethernetNetworkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback ethernet failed: ${e.message}")
        }

        // Also do an immediate scan
        refreshCurrentNetworks()
    }

    private fun handleWifiAvailable(network: Network) {
        val path = paths[PATH_ID_WIFI] ?: return
        path.network = network
        path.status = PathStatus.ONLINE
        path.carrierName = getWifiSSID()
        path.name = "Wi-Fi (${path.carrierName})"
        path.isInternetAvailable = true
        path.isVpsReachable = true
        path.statusDetail = "✓ Bond ready"

        val caps = connectivityManager.getNetworkCapabilities(network)
        val upstream = caps?.linkUpstreamBandwidthKbps ?: 0
        path.availableBandwidthMbps = if (upstream > 0) upstream / 1000.0 else 25.0
        Log.i(TAG, "Wi-Fi path updated: ${path.name} @ ${path.availableBandwidthMbps} Mbps")
        notifyPathsChanged()
    }

    private fun handleCellularAvailable(network: Network) {
        val sim1 = paths[PATH_ID_SIM1]
        val sim2 = paths[PATH_ID_SIM2]
        val caps = connectivityManager.getNetworkCapabilities(network)
        val upstream = caps?.linkUpstreamBandwidthKbps ?: 0
        val mbps = if (upstream > 0) upstream / 1000.0 else 15.0

        // Detect network type (5G, 4G, etc.)
        val networkTypeName = getNetworkTypeName()
        val carrier0 = getSimCarrierName(0)
        val carrier1 = getSimCarrierName(1)

        if (sim1?.network == null) {
            sim1?.network = network
            sim1?.status = PathStatus.ONLINE
            sim1?.carrierName = carrier0
            sim1?.name = if (carrier0 != "Carrier unavailable") "$carrier0 ($networkTypeName)" else "SIM 1 ($networkTypeName)"
            sim1?.isInternetAvailable = true
            sim1?.isVpsReachable = true
            sim1?.statusDetail = "✓ Bond ready"
            sim1?.availableBandwidthMbps = mbps
            Log.i(TAG, "SIM1 path updated: ${sim1?.name} @ $mbps Mbps")
        } else if (sim1.network != network && sim2?.network == null) {
            sim2?.network = network
            sim2?.status = PathStatus.ONLINE
            sim2?.carrierName = carrier1
            sim2?.name = if (carrier1 != "Carrier unavailable") "$carrier1 ($networkTypeName)" else "SIM 2 ($networkTypeName)"
            sim2?.isInternetAvailable = true
            sim2?.isVpsReachable = true
            sim2?.statusDetail = "✓ Bond ready"
            sim2?.availableBandwidthMbps = mbps
            Log.i(TAG, "SIM2 path updated: ${sim2?.name} @ $mbps Mbps")
        }
        notifyPathsChanged()
    }

    private fun handleEthernetAvailable(network: Network) {
        val eth = paths[PATH_ID_ETHERNET] ?: return
        eth.network = network
        eth.status = PathStatus.ONLINE
        eth.isInternetAvailable = true
        eth.isVpsReachable = true
        eth.statusDetail = "✓ Bond ready"
        val caps = connectivityManager.getNetworkCapabilities(network)
        val upstream = caps?.linkUpstreamBandwidthKbps ?: 0
        eth.availableBandwidthMbps = if (upstream > 0) upstream / 1000.0 else 50.0
        notifyPathsChanged()
    }

    private fun handleCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        for (path in paths.values) {
            if (path.network == network) {
                path.isInternetAvailable = hasInternet
                // Update bandwidth
                val upstream = caps.linkUpstreamBandwidthKbps
                if (upstream > 0) path.availableBandwidthMbps = upstream / 1000.0

                if (!hasInternet) {
                    path.statusDetail = "✕ No internet"
                    path.status = PathStatus.FAILING
                } else if (path.status == PathStatus.FAILING) {
                    path.statusDetail = "✓ Bond ready"
                    path.status = PathStatus.ONLINE
                }
                break
            }
        }
        notifyPathsChanged()
    }

    private fun handleNetworkLost(network: Network) {
        for (path in paths.values) {
            if (path.network == network) {
                path.network = null
                path.status = PathStatus.OFFLINE
                path.isInternetAvailable = false
                path.isVpsReachable = false
                path.statusDetail = "Disconnected"
                Log.i(TAG, "Path lost: ${path.name}")
                break
            }
        }
        notifyPathsChanged()
    }

    /**
     * Scan all currently active networks.
     * NOTE: allNetworks does NOT return cellular when Wi-Fi is active unless requestNetwork was called first.
     * This is why startDiscovery() + requestNetwork(CELLULAR) is required for bonding.
     */
    fun refreshCurrentNetworks() {
        val all = connectivityManager.allNetworks
        Log.i(TAG, "refreshCurrentNetworks: found ${all.size} system networks")
        for (net in all) {
            val caps = connectivityManager.getNetworkCapabilities(net) ?: continue
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> handleWifiAvailable(net)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> handleCellularAvailable(net)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> handleEthernetAvailable(net)
            }
        }
    }

    fun getUsablePaths(): List<NetworkPath> {
        return paths.values.filter { it.isUsable }
    }

    /**
     * Get actual carrier name from SIM slot.
     */
    fun getSimCarrierName(simSlot: Int): String {
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val subInfo = sm?.getActiveSubscriptionInfoForSimSlotIndex(simSlot)
            val carrier = subInfo?.displayName?.toString() ?: subInfo?.carrierName?.toString()
            if (!carrier.isNullOrBlank() && carrier.lowercase() != "unknown") {
                carrier
            } else {
                // Fallback: try TelephonyManager for active carrier
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val tmCarrier = tm?.networkOperatorName
                if (!tmCarrier.isNullOrBlank() && tmCarrier.lowercase() != "unknown") tmCarrier
                else "Carrier unavailable"
            }
        } catch (e: Exception) {
            Log.w(TAG, "getSimCarrierName($simSlot): ${e.message}")
            "Carrier unavailable"
        }
    }

    /**
     * Get current mobile network technology: 5G, 4G/LTE, 3G, etc.
     */
    fun getNetworkTypeName(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                when (tm?.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G/LTE"
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_HSDPA,
                    TelephonyManager.NETWORK_TYPE_HSUPA,
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "3G/H+"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                    else -> "Cellular"
                }
            } else {
                "Cellular"
            }
        } catch (e: Exception) {
            "Cellular"
        }
    }

    private fun getWifiSSID(): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            val ssid = info?.ssid?.replace("\"", "")?.trim()
            if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") ssid else "Wi-Fi"
        } catch (e: Exception) {
            "Wi-Fi"
        }
    }

    fun stopDiscovery() {
        try {
            cellularNetworkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                cellularNetworkCallback = null
            }
            wifiNetworkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                wifiNetworkCallback = null
            }
            ethernetNetworkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                ethernetNetworkCallback = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopDiscovery: ${e.message}")
        }
    }

    private fun notifyPathsChanged() {
        onPathsChanged?.invoke(paths.values.toList())
    }
}
