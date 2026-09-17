package com.streamezy.capture.bonding

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

class AndroidNetworkManager(private val context: Context) {
    companion object {
        private const val TAG = "BondNetworkManager"
        const val PATH_ID_WIFI: Byte = 1
        const val PATH_ID_CELLULAR_1: Byte = 2
        const val PATH_ID_CELLULAR_2: Byte = 3
        const val PATH_ID_ETHERNET: Byte = 4
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val paths = mutableMapOf<Byte, NetworkPath>()
    var onPathsChanged: ((List<NetworkPath>) -> Unit)? = null

    private var cellularNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        paths[PATH_ID_WIFI] = NetworkPath(PATH_ID_WIFI, "Wi-Fi", "WIFI")
        paths[PATH_ID_CELLULAR_1] = NetworkPath(PATH_ID_CELLULAR_1, getSimCarrierName(0) ?: "Cellular SIM 1", "CELLULAR")
        paths[PATH_ID_CELLULAR_2] = NetworkPath(PATH_ID_CELLULAR_2, getSimCarrierName(1) ?: "Cellular SIM 2", "CELLULAR")
    }

    fun startDiscovery() {
        Log.i(TAG, "Starting Android multi-network discovery...")

        // 1. Request Cellular network to stay active even when Wi-Fi is connected
        try {
            val cellRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            cellularNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleCellularAvailable(network)
                }

                override fun onLost(network: Network) {
                    handleNetworkLost(network)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    handleCapabilitiesChanged(network, caps)
                }
            }
            connectivityManager.requestNetwork(cellRequest, cellularNetworkCallback!!)
            Log.i(TAG, "Requested concurrent cellular network transport")
        } catch (e: Exception) {
            Log.w(TAG, "Could not request concurrent cellular network: ${e.message}")
        }

        // 2. Monitor all default and general network changes
        try {
            val defaultRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = connectivityManager.getNetworkCapabilities(network) ?: return
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        handleWifiAvailable(network)
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        handleEthernetAvailable(network)
                    }
                }

                override fun onLost(network: Network) {
                    handleNetworkLost(network)
                }
            }
            connectivityManager.registerNetworkCallback(defaultRequest, defaultNetworkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register default network callback", e)
        }

        refreshCurrentNetworks()
    }

    private fun handleWifiAvailable(network: Network) {
        val path = paths[PATH_ID_WIFI] ?: return
        path.network = network
        path.status = PathStatus.ONLINE
        Log.i(TAG, "Wi-Fi path connected ($network)")
        notifyPathsChanged()
    }

    private fun handleCellularAvailable(network: Network) {
        // Check if SIM 1 already has this network
        val p1 = paths[PATH_ID_CELLULAR_1]
        val p2 = paths[PATH_ID_CELLULAR_2]

        if (p1?.network == null) {
            p1?.network = network
            p1?.status = PathStatus.ONLINE
            Log.i(TAG, "Assigned cellular network to SIM 1 ($network)")
        } else if (p1.network != network && p2?.network == null) {
            // Independent secondary cellular network detected (DSDA or dual-active)
            p2?.network = network
            p2?.status = PathStatus.ONLINE
            Log.i(TAG, "Assigned secondary cellular network to SIM 2 ($network)")
        }
        notifyPathsChanged()
    }

    private fun handleEthernetAvailable(network: Network) {
        var eth = paths[PATH_ID_ETHERNET]
        if (eth == null) {
            eth = NetworkPath(PATH_ID_ETHERNET, "Ethernet/USB", "ETHERNET")
            paths[PATH_ID_ETHERNET] = eth
        }
        eth.network = network
        eth.status = PathStatus.ONLINE
        Log.i(TAG, "Ethernet/USB path connected ($network)")
        notifyPathsChanged()
    }

    private fun handleCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        for (path in paths.values) {
            if (path.network == network) {
                if (!hasInternet && path.status == PathStatus.ONLINE) {
                    path.status = PathStatus.FAILING
                } else if (hasInternet && path.status == PathStatus.FAILING) {
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
                Log.w(TAG, "Path ${path.name} lost connectivity")
                break
            }
        }
        notifyPathsChanged()
    }

    fun refreshCurrentNetworks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val all = connectivityManager.allNetworks
            for (net in all) {
                val caps = connectivityManager.getNetworkCapabilities(net) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    handleWifiAvailable(net)
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    handleCellularAvailable(net)
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    handleEthernetAvailable(net)
                }
            }
        }
    }

    fun getUsablePaths(): List<NetworkPath> {
        return paths.values.filter { it.isUsable }
    }

    fun getDualSimSupportNotice(): String? {
        val usableCellularCount = paths.values.count { it.transportType == "CELLULAR" && it.isUsable }
        val detectedSimCount = getDetectedSimCount()

        if (detectedSimCount >= 2 && usableCellularCount <= 1) {
            return "Multiple networks detected, but independent simultaneous transport is not available on this device."
        }
        return null
    }

    private fun getDetectedSimCount(): Int {
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            sm?.activeSubscriptionInfoCount ?: 1
        } catch (e: Exception) {
            1
        }
    }

    private fun getSimCarrierName(simSlot: Int): String? {
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val subInfo = sm?.getActiveSubscriptionInfoForSimSlotIndex(simSlot)
            subInfo?.displayName?.toString() ?: subInfo?.carrierName?.toString()
        } catch (e: Exception) {
            null
        }
    }

    fun stopDiscovery() {
        try {
            cellularNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            defaultNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callbacks", e)
        }
    }

    private fun notifyPathsChanged() {
        onPathsChanged?.invoke(paths.values.toList())
    }
}
