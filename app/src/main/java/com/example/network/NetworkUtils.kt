package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            val ipCandidates = mutableListOf<String>()
            
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        // Prioritize Hotspot (ap0, wlan1) and standard AP IPs (192.168.43.x)
                        val name = intf.name.lowercase()
                        if (name.contains("ap") || hostAddress.startsWith("192.168.43.")) {
                            return hostAddress
                        }
                        if (name.contains("wlan") || name.contains("eth")) {
                            ipCandidates.add(0, hostAddress)
                        } else {
                            ipCandidates.add(hostAddress)
                        }
                    }
                }
            }
            if (ipCandidates.isNotEmpty()) {
                return ipCandidates.first()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "192.168.43.1"
    }

    fun getBroadcastAddress(): InetAddress {
        try {
            val localIp = getLocalIpAddress()
            val parts = localIp.split(".")
            if (parts.size == 4) {
                val broadcastStr = "${parts[0]}.${parts[1]}.${parts[2]}.255"
                return InetAddress.getByName(broadcastStr)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return InetAddress.getByName("255.255.255.255")
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
