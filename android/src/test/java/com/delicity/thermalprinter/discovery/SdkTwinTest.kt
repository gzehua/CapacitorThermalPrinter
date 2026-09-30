package com.delicity.thermalprinter.discovery

import com.delicity.thermalprinter.model.AdapterId
import com.delicity.thermalprinter.model.DiscoveredPrinter
import com.delicity.thermalprinter.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdkTwinTest {

    private fun sdk(adapter: AdapterId, address: String, name: String) = DiscoveredPrinter(
        id = "${adapter.value}:$address", name = name, transport = Transport.BLUETOOTH,
        adapter = adapter, address = address,
    )

    @Test
    fun `star appairee retrouvee par son adresse MAC`() {
        val star = sdk(AdapterId.STAR, "00:11:62:AA:BB:CC", "TSP100IV")
        assertEquals(star, DiscoveryManager.sdkTwinOf("00:11:62:aa:bb:cc", "TSP143IV-BT", listOf(star)))
    }

    @Test
    fun `MAC Star sans separateur rapprochee de la MAC native`() {
        val star = sdk(AdapterId.STAR, "0011622E0816", "TSP100IIIBI")
        assertEquals(star, DiscoveryManager.sdkTwinOf("00:11:62:2E:08:16", "TSP100-K8672", listOf(star)))
    }

    @Test
    fun `prefixe de cible ePOS2 ignore`() {
        val epson = sdk(AdapterId.EPSON, "BT:00:01:90:11:22:33", "TM-m30II")
        assertEquals(epson, DiscoveryManager.sdkTwinOf("00:01:90:11:22:33", "TM-m30II_000001", listOf(epson)))
    }

    @Test
    fun `nom ambigu ou Zebra ne declenchent pas de bascule`() {
        val a = sdk(AdapterId.STAR, "00:11:62:00:00:01", "TSP100IV")
        val b = sdk(AdapterId.STAR, "00:11:62:00:00:02", "TSP100IV")
        assertNull(DiscoveryManager.sdkTwinOf("66:55:44:33:22:11", "TSP100IV", listOf(a, b)))
        val zebra = sdk(AdapterId.ZEBRA, "66:55:44:33:22:11", "Zebra")
        assertNull(DiscoveryManager.sdkTwinOf("66:55:44:33:22:11", "Zebra", listOf(zebra)))
        assertEquals(a, DiscoveryManager.sdkTwinOf("66:55:44:33:22:11", "TSP100IV", listOf(a)))
    }
}
