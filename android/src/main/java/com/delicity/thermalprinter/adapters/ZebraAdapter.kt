package com.delicity.thermalprinter.adapters

import android.content.Context
import android.graphics.Bitmap
import com.delicity.thermalprinter.Logger
import kotlinx.coroutines.delay
import com.delicity.thermalprinter.model.AdapterId
import com.delicity.thermalprinter.model.DiscoveredPrinter
import com.delicity.thermalprinter.model.ErrorCode
import com.delicity.thermalprinter.model.PrinterException
import com.delicity.thermalprinter.model.PrinterProfile
import com.delicity.thermalprinter.model.PrinterStatus
import com.delicity.thermalprinter.model.RenderOptions
import com.delicity.thermalprinter.model.Transport
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter Zebra basé sur le SDK Link-OS (`com.zebra.sdk`), piloté par RÉFLEXION.
 *
 * ⚠️ Zebra N'EST PAS de l'ESC/POS : le langage est ZPL/CPCL. Le SDK convertit le
 * Bitmap en ZPL et l'imprime via GraphicsUtil.printImage(...). On ne route JAMAIS
 * une Zebra vers EscPosAdapter (priority.ts attribue un score négatif).
 *
 * Le SDK Link-OS n'est pas redistribuable (licence Zebra) : déposer
 * `ZSDK_ANDROID_API.jar` (portail Zebra) — ou activer le dépôt Maven privé Zebra
 * (credentials). Voir docs/SDK_INTEGRATION.md (§ Zebra).
 */
class ZebraAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.ZEBRA

    private val cache = ConcurrentHashMap<String, Any>() // printerId -> com.zebra.sdk.comm.Connection

    /**
     * Langage résolu par imprimante ("zpl" | "cpcl"), décidé à la connexion via SGD.
     * Forcer du ZPL sur une imprimante CPCL (mobiles iMZ/ZQ1xx…) part en silence :
     * l'imprimante bipe et n'imprime rien, alors que l'écriture Bluetooth réussit.
     */
    private val languages = ConcurrentHashMap<String, String>()

    override fun isAvailable(): Boolean = EpsonAdapter.classExists(CONNECTION)

    // -------------------------------------------------------------------------
    // Découverte (NetworkDiscoverer / BluetoothDiscoverer + DiscoveryHandler proxy)
    // -------------------------------------------------------------------------

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        if (!isAvailable()) return
        val handler = SdkReflect.proxy(DISCOVERY_HANDLER, mapOf(
            "foundPrinter" to { args ->
                val dp = args.getOrNull(0)
                if (dp != null) {
                    // DiscoveredPrinter expose l'adresse en CHAMP public `address` (pas de
                    // getter getAddress()). callOrNull tente quand même un getter (autres
                    // versions de SDK), sinon on lit le champ — sans lever.
                    val address = SdkReflect.callOrNull(dp, "getAddress") as? String
                        ?: SdkReflect.field(dp, "address") as? String ?: ""
                    if (address.isNotEmpty()) {
                        onFound(
                            DiscoveredPrinter(
                                id = "zebra:$address",
                                name = "Zebra $address",
                                brand = "Zebra",
                                transport = if (address.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) Transport.BLUETOOTH else Transport.WIFI,
                                adapter = AdapterId.ZEBRA,
                                address = address,
                                discoveredBy = mutableSetOf(AdapterId.ZEBRA),
                            ),
                        )
                    }
                }
                null
            },
            "discoveryFinished" to { null },
            "discoveryError" to { null },
        ))
        // NetworkDiscoverer.findPrinters(DiscoveryHandler) — bloquant jusqu'à fin.
        runCatching {
            SdkReflect.callStatic(
                NETWORK_DISCOVERER, "findPrinters",
                arrayOf(SdkReflect.classOrNull(DISCOVERY_HANDLER)!!),
                arrayOf(handler),
            )
        }
        // BluetoothDiscoverer.findPrinters(Context, DiscoveryHandler)
        runCatching {
            SdkReflect.callStatic(
                BLUETOOTH_DISCOVERER, "findPrinters",
                arrayOf(Context::class.java, SdkReflect.classOrNull(DISCOVERY_HANDLER)!!),
                arrayOf(context, handler),
            )
        }
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        isAvailable() && profile.adapter == AdapterId.ZEBRA

    // -------------------------------------------------------------------------
    // Connexion
    // -------------------------------------------------------------------------

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        ensureSdk()
        if (isConnected(profile.id)) return
        val connection = buildConnection(profile)
        try {
            SdkReflect.call(connection, "open")
        } catch (e: Throwable) {
            throw PrinterException(ErrorCode.CONNECTION_FAILED, "Connexion Zebra échouée: ${profile.address}", e.message, retryable = true)
        }
        // Résolution du langage RÉEL de l'imprimante, en TOUT PREMIER octet sur une
        // connexion propre (les commandes SGD arrivant en milieu de flux peuvent être
        // imprimées littéralement par les imprimantes en `line_print`, cf. fix 8.1.0) :
        //   - `zpl` / `hybrid_xml_zpl`  -> rien à changer, instance ZPL ;
        //   - `line_print`              -> on force `hybrid_xml_zpl` (comportement 8.1.0),
        //                                  et on retombe en CPCL si la bascule est refusée ;
        //   - `cpcl`                    -> instance CPCL (ne PAS forcer du ZPL : les
        //                                  modèles CPCL l'ignorent -> bip sans impression) ;
        //   - pas de réponse            -> comportement historique (force ZPL, best-effort).
        languages[profile.id] = resolveLanguage(connection)
        cache[profile.id] = connection
    }

    /** Lit puis résout le langage d'impression à utiliser ("zpl" | "cpcl"). */
    private suspend fun resolveLanguage(connection: Any): String {
        val current = sgdGet(connection, "device.languages")?.lowercase()
        Logger.log("zebra", "device.languages", mapOf("value" to (current ?: "n/a")))
        return when {
            current == null -> { forceZplLanguage(connection); "zpl" }
            "zpl" in current -> "zpl"
            "line_print" in current -> {
                forceZplLanguage(connection)
                val after = sgdGet(connection, "device.languages")?.lowercase() ?: ""
                Logger.log("zebra", "device.languages après bascule", mapOf("value" to after))
                if ("zpl" in after) "zpl" else "cpcl"
            }
            "cpcl" in current -> "cpcl"
            else -> { forceZplLanguage(connection); "zpl" }
        }
    }

    /** SGD GET best-effort (null si le SDK/l'imprimante ne répond pas). */
    private fun sgdGet(connection: Any, key: String): String? = runCatching {
        SdkReflect.callStatic(
            SGD, "GET",
            arrayOf(String::class.java, SdkReflect.classOrNull(CONNECTION)!!),
            arrayOf(key, connection),
        ) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Sort l'imprimante du mode line_print en forçant un langage ZPL-compatible. */
    private suspend fun forceZplLanguage(connection: Any) {
        val ok = runCatching {
            val cmd = "! U1 setvar \"device.languages\" \"hybrid_xml_zpl\"\r\n".toByteArray(Charsets.US_ASCII)
            SdkReflect.call(connection, "write", arrayOf(ByteArray::class.java), arrayOf<Any?>(cmd))
        }.isSuccess
        // Le changement de langage n'est pas instantané : laisser l'imprimante l'appliquer
        // avant le premier `getInstance`/impression.
        if (ok) delay(LANGUAGE_SWITCH_DELAY_MS)
    }

    /**
     * Récupère le `ZebraPrinter` en FORÇANT le langage résolu à la connexion (ZPL ou CPCL,
     * cf. resolveLanguage) : évite l'overload auto-détection `getInstance(Connection)` qui
     * envoie la sonde `! U1 getvar "appl.name"` (imprimée littéralement si l'imprimante
     * n'interprète pas le SGD). Fallback auto-détection si l'enum `PrinterLanguage` est
     * introuvable (vieille version de SDK).
     */
    private fun zebraPrinter(connection: Any, printerId: String): Any {
        val langName = if (languages[printerId] == "cpcl") "CPCL" else "ZPL"
        val lang = SdkReflect.enumValue(PRINTER_LANGUAGE, langName)
        val langClass = SdkReflect.classOrNull(PRINTER_LANGUAGE)
        val connClass = SdkReflect.classOrNull(CONNECTION)!!
        if (lang != null && langClass != null) {
            SdkReflect.callStatic(
                PRINTER_FACTORY, "getInstance",
                arrayOf(langClass, connClass), arrayOf(lang, connection),
            )?.let { return it }
        }
        return SdkReflect.callStatic(
            PRINTER_FACTORY, "getInstance",
            arrayOf(connClass), arrayOf(connection),
        ) ?: error("ZebraPrinterFactory.getInstance null")
    }

    override fun isConnected(printerId: String): Boolean {
        val c = cache[printerId] ?: return false
        return (SdkReflect.call(c, "isConnected") as? Boolean) ?: true
    }

    override suspend fun disconnect(printerId: String) {
        languages.remove(printerId)
        cache.remove(printerId)?.let { runCatching { SdkReflect.call(it, "close") } }
    }

    // -------------------------------------------------------------------------
    // Impression image (-> ZPL via GraphicsUtil)
    // -------------------------------------------------------------------------

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        val connection = cache[profile.id]
            ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Zebra non connecté: ${profile.id}")
        try {
            val printer = zebraPrinter(connection, profile.id)
            val graphics = SdkReflect.call(printer, "getGraphicsUtil") ?: error("getGraphicsUtil null")
            val zebraImage = SdkReflect.callStatic(
                IMAGE_FACTORY, "getImage",
                arrayOf(Bitmap::class.java), arrayOf(bitmap),
            ) ?: error("ZebraImageFactory.getImage null")
            val intT = Int::class.javaPrimitiveType!!
            val boolT = Boolean::class.javaPrimitiveType!!
            repeat(options.copies.coerceAtLeast(1)) {
                SdkReflect.call(
                    graphics, "printImage",
                    arrayOf(SdkReflect.classOrNull(IMAGE_I)!!, intT, intT, intT, intT, boolT),
                    arrayOf(zebraImage, 0, 0, bitmap.width, bitmap.height, false),
                )
            }
        } catch (e: Throwable) {
            throw PrinterException(ErrorCode.PRINT_FAILED, "Impression Zebra échouée", e.message, retryable = true)
        }
        return bitmap.width * bitmap.height / 8
    }

    // -------------------------------------------------------------------------
    // Statut
    // -------------------------------------------------------------------------

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        val connection = cache[profile.id]
            ?: return PrinterStatus(profile.id, "disconnected", online = false, paper = "unknown")
        return try {
            val printer = zebraPrinter(connection, profile.id)
            val status = SdkReflect.call(printer, "getCurrentStatus") ?: error("getCurrentStatus null")
            val ready = (SdkReflect.field(status, "isReadyToPrint") as? Boolean) ?: false
            val paperOut = (SdkReflect.field(status, "isPaperOut") as? Boolean) ?: false
            val headOpen = (SdkReflect.field(status, "isHeadOpen") as? Boolean) ?: false
            PrinterStatus(
                id = profile.id,
                connection = "connected",
                online = ready,
                paper = if (paperOut) "empty" else "ok",
                coverOpen = headOpen,
                errorCode = if (paperOut) ErrorCode.PAPER_EMPTY else if (headOpen) ErrorCode.COVER_OPEN else null,
            )
        } catch (e: Throwable) {
            PrinterStatus(profile.id, "error", online = false, paper = "unknown", rawStatus = e.message)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildConnection(profile: PrinterProfile): Any = when (profile.transport) {
        Transport.WIFI, Transport.ETHERNET -> {
            val host = profile.address.substringBefore(":")
            val port = profile.address.substringAfter(":", "9100").toIntOrNull() ?: 9100
            SdkReflect.newInstance(
                TCP_CONNECTION,
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
                arrayOf(host, port),
            )
        }
        Transport.BLUETOOTH -> SdkReflect.newInstance(
            BT_CONNECTION, arrayOf(String::class.java), arrayOf(profile.address),
        )
        else -> throw PrinterException(ErrorCode.UNSUPPORTED_TRANSPORT, "Transport Zebra non supporté: ${profile.transport.value}")
    }

    private fun ensureSdk() {
        if (!isAvailable()) throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "SDK Zebra Link-OS absent")
    }

    companion object {
        private const val CONNECTION = "com.zebra.sdk.comm.Connection"
        private const val TCP_CONNECTION = "com.zebra.sdk.comm.TcpConnection"
        private const val BT_CONNECTION = "com.zebra.sdk.comm.BluetoothConnection"
        private const val PRINTER_FACTORY = "com.zebra.sdk.printer.ZebraPrinterFactory"
        private const val PRINTER_LANGUAGE = "com.zebra.sdk.printer.PrinterLanguage"
        private const val SGD = "com.zebra.sdk.printer.SGD"
        private const val LANGUAGE_SWITCH_DELAY_MS = 400L
        private const val IMAGE_FACTORY = "com.zebra.sdk.graphics.ZebraImageFactory"
        private const val IMAGE_I = "com.zebra.sdk.graphics.ZebraImageI"
        private const val NETWORK_DISCOVERER = "com.zebra.sdk.printer.discovery.NetworkDiscoverer"
        private const val BLUETOOTH_DISCOVERER = "com.zebra.sdk.printer.discovery.BluetoothDiscoverer"
        private const val DISCOVERY_HANDLER = "com.zebra.sdk.printer.discovery.DiscoveryHandler"
    }
}
