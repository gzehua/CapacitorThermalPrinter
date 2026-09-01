package com.epson.epos2.discovery

import android.content.Context

/**
 * Faux Discovery ePOS2 (test classpath). Simule la découverte d'une imprimante
 * en appelant immédiatement le listener fourni par l'adapter (via proxy réflexif).
 */
object Discovery {
    @JvmField val TYPE_PRINTER = 0
    var started = false

    @JvmStatic
    fun start(context: Context, filter: FilterOption, listener: DiscoveryListener) {
        started = true
        // Firmware classique : target TCP + IP renseignée.
        listener.onDiscovery(DeviceInfo("TCP:192.168.1.50", "TM-m30", "192.168.1.50"))
        // Firmware TLS (ex. TM-m30II-NT) : target TCPS basé MAC + IP renseignée.
        listener.onDiscovery(DeviceInfo("TCPS:4C:D5:77:51:E3:B1", "TM-m30II-NT", "192.168.1.60"))
        // SDK ancien / cas dégradé : pas d'IP exposée.
        listener.onDiscovery(DeviceInfo("TCP:192.168.1.70", "TM-T88", null))
    }

    @JvmStatic
    fun stop() { started = false }
}

class FilterOption {
    @JvmField var deviceType = 0
    fun setDeviceType(type: Int) { deviceType = type }
}

interface DiscoveryListener {
    fun onDiscovery(deviceInfo: DeviceInfo)
}

class DeviceInfo(private val target: String, private val name: String, private val ipAddress: String? = null) {
    fun getTarget(): String = target
    fun getDeviceName(): String = name
    fun getIpAddress(): String? = ipAddress
}
