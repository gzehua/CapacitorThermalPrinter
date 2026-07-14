package com.delicity.thermalprinter.transport

import com.delicity.thermalprinter.model.ErrorCode
import com.delicity.thermalprinter.model.PrinterException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Transport TCP RAW 9100 — pas d'API Android, testable sur JVM pure via un
 * ServerSocket loopback.
 *
 * Régression clé : les Epson TM ferment les connexions RAW inactives, et
 * `Socket.isConnected()/isClosed()` ne détectent pas une fermeture côté pair.
 * `write()` doit donc se ré-ouvrir + réémettre le job au lieu d'échouer avec
 * « Écriture TCP échouée ».
 */
class TcpTransportTest {

    /** Cas nominal : un job simple arrive intact côté imprimante. */
    @Test
    fun `write envoie les octets au socket ouvert`() {
        val received = CopyOnWriteArrayList<ByteArray>()
        val done = CountDownLatch(1)
        val server = ServerSocket(0)
        val acceptor = thread {
            server.accept().use { s ->
                received.add(s.getInputStream().readBytes())
                done.countDown()
            }
        }

        val t = TcpTransport("127.0.0.1", server.localPort)
        t.open(2000)
        t.write(byteArrayOf(1, 2, 3, 4, 5))
        t.close()

        assertTrue(done.await(2, TimeUnit.SECONDS))
        acceptor.join(2000)
        server.close()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), received.first())
    }

    /**
     * Le pair ferme la connexion inactive (comportement Epson TM). Le prochain
     * `write()` doit se reconnecter tout seul et livrer le job sur un socket neuf.
     */
    @Test
    fun `write se reconnecte quand le pair a ferme la connexion`() {
        val received = CopyOnWriteArrayList<ByteArray>()
        val firstAccepted = CountDownLatch(1)
        val secondReceived = CountDownLatch(1)
        val server = ServerSocket(0)

        val acceptor = thread {
            // 1re connexion : acceptée puis RST immédiat (SO_LINGER=0) pour reproduire une
            // fermeture brutale côté imprimante. Le socket client devient un fantôme :
            // isConnected() reste true mais le prochain write() lèvera.
            server.accept().use { s ->
                s.setSoLinger(true, 0)
            }
            firstAccepted.countDown()
            // 2e connexion : celle ouverte par la reconnexion interne de write().
            server.accept().use { s ->
                received.add(s.getInputStream().readBytes())
                secondReceived.countDown()
            }
        }

        val t = TcpTransport("127.0.0.1", server.localPort)
        t.open(2000)
        assertTrue(firstAccepted.await(2, TimeUnit.SECONDS))
        // isOpen ment encore (isConnected reste true) : write() doit gérer la reprise seul.
        t.write(byteArrayOf(9, 8, 7))
        t.close()

        assertTrue(secondReceived.await(2, TimeUnit.SECONDS))
        acceptor.join(2000)
        server.close()
        assertArrayEquals(byteArrayOf(9, 8, 7), received.first())
    }

    /** Cible injoignable : échec propre en PRINT_FAILED retryable après la tentative de reprise. */
    @Test
    fun `write echoue proprement si la reconnexion est impossible`() {
        val server = ServerSocket(0)
        val port = server.localPort
        val t = TcpTransport("127.0.0.1", port)
        t.open(1000)
        server.close() // plus rien n'écoute : la reconnexion échouera

        try {
            t.write(byteArrayOf(1, 2, 3))
            fail("attendu PrinterException")
        } catch (e: PrinterException) {
            assertEquals(ErrorCode.PRINT_FAILED, e.code)
            assertTrue(e.retryable)
        }
        t.close()
    }
}
