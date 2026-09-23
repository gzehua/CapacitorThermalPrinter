package com.delicity.thermalprinter.adapters

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.epson.epos2.printer.Printer
import com.delicity.thermalprinter.model.AdapterId
import com.delicity.thermalprinter.model.Capabilities
import com.delicity.thermalprinter.model.ErrorCode
import com.delicity.thermalprinter.model.PrintItem
import com.delicity.thermalprinter.model.PrinterProfile
import com.delicity.thermalprinter.model.RenderOptions
import com.delicity.thermalprinter.model.TextStyle
import com.delicity.thermalprinter.model.Transport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage de la CONNEXION + impression via SDK, sur l'adapter Epson (réflexion),
 * grâce au FAUX SDK Epson présent sur le classpath de test (com.epson.epos2.*).
 *
 * Robolectric fournit Context/Bitmap sur la JVM (aucun émulateur requis).
 * `./gradlew testDebugUnitTest jacocoTestReport`
 */
@RunWith(RobolectricTestRunner::class)
class EpsonAdapterTest {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        Printer.reset()
    }

    private fun profile() = PrinterProfile(
        id = "epson:TCP:192.168.1.50",
        adapter = AdapterId.EPSON,
        transport = Transport.WIFI,
        address = "TCP:192.168.1.50",
        brand = "Epson",
        model = "TM-m30",
        name = "Epson TM-m30",
        capabilities = Capabilities(supportsCut = true, supportsCashDrawer = true),
    )

    @Test
    fun `isAvailable est true quand le SDK (faux) est sur le classpath`() {
        assertTrue(EpsonAdapter(ctx).isAvailable())
    }

    @Test
    fun `connect ouvre une session ePOS2 et marque connecte`() = runTest {
        val adapter = EpsonAdapter(ctx)
        val p = profile()
        assertFalse(adapter.isConnected(p.id))
        adapter.connect(p, 1000)
        assertTrue(adapter.isConnected(p.id))
        val printer = Printer.instances.last()
        assertTrue(printer.calls.any { it.startsWith("connect:TCP:192.168.1.50") })
    }

    @Test
    fun `printBitmap appelle addImage + addCut + sendData`() = runTest {
        val adapter = EpsonAdapter(ctx)
        val p = profile()
        adapter.connect(p, 1000)
        val bmp = Bitmap.createBitmap(384, 120, Bitmap.Config.ARGB_8888)
        adapter.printBitmap(p, bmp, RenderOptions(widthDots = 384, cut = true, openCashDrawer = true))
        val calls = Printer.instances.last().calls
        assertTrue("addImage manquant: $calls", calls.any { it.startsWith("addImage:384x120") })
        assertTrue("addCut manquant", calls.any { it.startsWith("addCut") })
        assertTrue("addPulse (tiroir) manquant", calls.any { it.startsWith("addPulse") })
        assertTrue("sendData manquant", calls.contains("sendData"))
    }

    @Test
    fun `printItems mappe le texte vers le builder ePOS2`() = runTest {
        val adapter = EpsonAdapter(ctx)
        val p = profile()
        adapter.connect(p, 1000)
        val items = listOf(
            PrintItem.Text("Bonjour", TextStyle(bold = true, align = "center")),
            PrintItem.Feed(2),
        )
        adapter.printItems(p, items, "WPC1252", cut = true, feedLines = 3)
        val calls = Printer.instances.last().calls
        assertTrue("addText manquant: $calls", calls.any { it == "addText:Bonjour\n" })
        assertTrue("align center manquant", calls.contains("align:1"))
        assertTrue("sendData manquant", calls.contains("sendData"))
        assertTrue(adapter.supportsTextItems())
    }

    @Test
    fun `getStatus mappe papier vide et capot`() = runTest {
        val adapter = EpsonAdapter(ctx)
        val p = profile()
        adapter.connect(p, 1000)
        Printer.instances.last().statusInfo.apply { paper = Printer.PAPER_EMPTY; coverOpen = Printer.TRUE }
        val st = adapter.getStatus(p)
        assertEquals("empty", st.paper)
        assertEquals(ErrorCode.PAPER_EMPTY, st.errorCode)
        assertEquals(true, st.coverOpen)
    }

    @Test
    fun `discover remonte les imprimantes avec leur IP reelle en adresse`() = runTest {
        val adapter = EpsonAdapter(ctx)
        val found = mutableListOf<com.delicity.thermalprinter.model.DiscoveredPrinter>()
        adapter.discover(50) { found.add(it) }
        assertEquals(3, found.size)
        assertTrue(found.all { it.adapter == AdapterId.EPSON })
        // Firmware classique : adresse = IP (dédoublonnable avec le scan TCP natif),
        // id inchangé par rapport à l'ancien schéma "epson:<target>".
        assertEquals("192.168.1.50", found[0].address)
        assertEquals("epson:TCP:192.168.1.50", found[0].id)
        // Firmware TLS (target TCPS basé MAC) : l'IP prime sur le target brut.
        assertEquals("192.168.1.60", found[1].address)
        assertEquals("epson:TCP:192.168.1.60", found[1].id)
        // Pas d'IP exposée : on retombe sur le target brut (ancien comportement).
        assertEquals("TCP:192.168.1.70", found[2].address)
        assertEquals("epson:TCP:192.168.1.70", found[2].id)
    }

    @Test
    fun `connect reecrit un target TCPS en TCP (TLS non provisionne)`() = runTest {
        val adapter = EpsonAdapter(ctx)
        val p = profile().copy(
            id = "epson:TCPS:4C:D5:77:51:E3:B1",
            address = "TCPS:4C:D5:77:51:E3:B1",
        )
        adapter.connect(p, 1000)
        assertTrue(adapter.isConnected(p.id))
        val printer = Printer.instances.last()
        assertTrue(
            "connexion TCPS non réécrite: ${printer.calls}",
            printer.calls.any { it.startsWith("connect:TCP:4C:D5:77:51:E3:B1") },
        )
    }

    @Test
    fun `connect construit un target TCP depuis une adresse IP nue`() = runTest {
        val adapter = EpsonAdapter(ctx)
        val p = profile().copy(id = "epson:TCP:192.168.1.60", address = "192.168.1.60")
        adapter.connect(p, 1000)
        val printer = Printer.instances.last()
        assertTrue(
            "target TCP:<ip> attendu: ${printer.calls}",
            printer.calls.any { it.startsWith("connect:TCP:192.168.1.60") },
        )
    }

    @Test
    fun `seriesCandidates retire les lettres de revision finales`() {
        assertEquals(listOf("TM_T20II", "TM_T20I", "TM_T20"), EpsonAdapter.seriesCandidates("TM-T20II"))
        assertEquals(listOf("TM_M30III", "TM_M30II", "TM_M30I", "TM_M30"), EpsonAdapter.seriesCandidates("Epson TM-m30III"))
        assertEquals(emptyList<String>(), EpsonAdapter.seriesCandidates("TM Printer"))
    }

    @Test
    fun `connect choisit la serie TM_T20 pour une TM-T20II`() = runTest {
        val adapter = EpsonAdapter(ctx)
        adapter.connect(profile().copy(model = "TM-T20II", name = "TM-T20II"), 1000)
        assertEquals(Printer.TM_T20, Printer.instances.last().series)
    }

    @Test
    fun `printBitmap remonte le code d'erreur renvoye par l'imprimante`() = runTest {
        val adapter = EpsonAdapter(ctx)
        val p = profile()
        adapter.connect(p, 1000)
        Printer.instances.last().receiveCode = Printer.CODE_ERR_TIMEOUT
        val bmp = Bitmap.createBitmap(384, 120, Bitmap.Config.ARGB_8888)
        val e = runCatching { adapter.printBitmap(p, bmp, RenderOptions(widthDots = 384)) }.exceptionOrNull()
        assertTrue("PrinterException attendue: $e", e is com.delicity.thermalprinter.model.PrinterException)
        e as com.delicity.thermalprinter.model.PrinterException
        assertEquals(ErrorCode.TIMEOUT, e.code)
        assertEquals("CODE_ERR_TIMEOUT", e.detail)
    }

    @Test
    fun `printBitmap sans reponse de l'imprimante est interrompu par le timeout`() = runTest {
        val adapter = EpsonAdapter(ctx)
        val p = profile()
        adapter.connect(p, 1000)
        Printer.instances.last().respond = false
        val bmp = Bitmap.createBitmap(384, 120, Bitmap.Config.ARGB_8888)
        val r = kotlinx.coroutines.withTimeoutOrNull(200) { adapter.printBitmap(p, bmp, RenderOptions(widthDots = 384)) }
        assertEquals(null, r)
    }

    @Test
    fun `disconnect ferme la session`() = runTest {
        val adapter = EpsonAdapter(ctx)
        val p = profile()
        adapter.connect(p, 1000)
        adapter.disconnect(p.id)
        assertFalse(adapter.isConnected(p.id))
        assertTrue(Printer.instances.last().calls.contains("disconnect"))
    }
}
