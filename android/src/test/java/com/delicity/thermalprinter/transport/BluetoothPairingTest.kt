package com.delicity.thermalprinter.transport

import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Attente de l'issue de la pop-up d'appairage (sans Bluetooth réel ni vraie attente). */
class BluetoothPairingTest {

    private fun await(vararg states: Int, timeoutMs: Long = 1_000): Boolean {
        val it = states.iterator()
        var last = states.last()
        return BluetoothSppTransport.awaitBonded(
            bondState = { if (it.hasNext()) it.next().also { s -> last = s } else last },
            timeoutMs = timeoutMs,
            sleep = {},
        )
    }

    @Test
    fun `appairage accepte`() = assertTrue(await(BOND_NONE, BOND_BONDING, BOND_BONDING, BOND_BONDED))

    @Test
    fun `pop-up annulee ou mauvais PIN`() = assertFalse(await(BOND_BONDING, BOND_BONDING, BOND_NONE))

    @Test
    fun `NONE avant le debut de l'appairage n'est pas un echec`() =
        assertTrue(await(BOND_NONE, BOND_NONE, BOND_BONDING, BOND_BONDED))

    @Test
    fun `personne ne repond a la pop-up`() = assertFalse(await(BOND_BONDING, timeoutMs = 5_000))
}
