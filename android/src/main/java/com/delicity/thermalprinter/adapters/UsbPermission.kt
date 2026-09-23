package com.delicity.thermalprinter.adapters

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Permission USB runtime (dialogue système « Autoriser l'accès à … »), partagée par
 * l'adapter USB générique et l'adapter Epson.
 *
 * Android ne réaffiche JAMAIS ce dialogue de lui-même : s'il est fermé (clic à côté,
 * retour), la permission reste refusée jusqu'au prochain `requestPermission`. On la
 * redemande donc à chaque connexion tant qu'elle n'est pas accordée.
 */
object UsbPermission {

    private const val ACTION_USB_PERMISSION = "com.delicity.thermalprinter.USB_PERMISSION"

    /** true si déjà accordée, sinon affiche le dialogue et suspend jusqu'à la réponse. */
    suspend fun ensure(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) return true
        return request(context, usbManager, device)
    }

    private suspend fun request(context: Context, usbManager: UsbManager, device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val action = "$ACTION_USB_PERMISSION.${device.deviceId}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            usbManager.requestPermission(device, pi)
        }
}
