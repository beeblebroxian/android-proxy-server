package internal.lab.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

data class ProxySettings(
    val httpEnabled: Boolean = false,
    val httpPort: Int = 8080,
    val socksEnabled: Boolean = false,
    val socksPort: Int = 1080,
    val bindAllInterfaces: Boolean = false,
)

data class RuntimeSnapshot(
    val running: Boolean = false,
    val httpRunning: Boolean = false,
    val socksRunning: Boolean = false,
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
    val receiveBytesPerSecond: Long = 0,
    val sendBytesPerSecond: Long = 0,
    val activeConnections: Int = 0,
    val totalConnections: Long = 0,
    val lastError: String? = null,
    val log: List<String> = emptyList(),
)

class ProxyPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): ProxySettings = ProxySettings(
        httpEnabled = preferences.getBoolean(KEY_HTTP_ENABLED, false),
        httpPort = preferences.getInt(KEY_HTTP_PORT, 8080),
        socksEnabled = preferences.getBoolean(KEY_SOCKS_ENABLED, false),
        socksPort = preferences.getInt(KEY_SOCKS_PORT, 1080),
        bindAllInterfaces = preferences.getBoolean(KEY_BIND_ALL_INTERFACES, false),
    )

    fun write(settings: ProxySettings) {
        preferences.edit()
            .putBoolean(KEY_HTTP_ENABLED, settings.httpEnabled)
            .putInt(KEY_HTTP_PORT, settings.httpPort)
            .putBoolean(KEY_SOCKS_ENABLED, settings.socksEnabled)
            .putInt(KEY_SOCKS_PORT, settings.socksPort)
            .putBoolean(KEY_BIND_ALL_INTERFACES, settings.bindAllInterfaces)
            .apply()
    }

    private companion object {
        const val FILE_NAME = "proxy_settings"
        const val KEY_HTTP_ENABLED = "http_enabled"
        const val KEY_HTTP_PORT = "http_port"
        const val KEY_SOCKS_ENABLED = "socks_enabled"
        const val KEY_SOCKS_PORT = "socks_port"
        const val KEY_BIND_ALL_INTERFACES = "bind_all_interfaces"
    }
}

fun lanBindAddresses(context: Context): List<String> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val addresses = mutableSetOf<String>()
    for (network in connectivity.allNetworks) {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
        val isLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!isLan) continue
        val properties = connectivity.getLinkProperties(network) ?: continue
        for (linkAddress in properties.linkAddresses) {
            val inet = linkAddress.address
            if (inet is Inet4Address && !inet.isLoopbackAddress && !inet.isLinkLocalAddress && !inet.isAnyLocalAddress) {
                inet.hostAddress?.let(addresses::add)
            }
        }
    }
    return addresses.sorted()
}
