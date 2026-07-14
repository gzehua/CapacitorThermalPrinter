package com.delicity.thermalprinter.transport

import com.delicity.thermalprinter.model.ErrorCode
import com.delicity.thermalprinter.model.PrinterException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Transport TCP brut (RAW / port 9100). Utilisé par EscPosAdapter et RawTcpAdapter
 * en Wi-Fi/Ethernet.
 *
 * @param host adresse IP/hostname
 * @param port port (défaut 9100)
 */
class TcpTransport(
    private val host: String,
    private val port: Int = 9100,
) : ByteTransport {

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var input: InputStream? = null

    /** Dernier timeout utilisé par [open], réutilisé lors d'une reconnexion interne. */
    private var lastTimeoutMs: Long = 10_000

    override val isOpen: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    override fun open(timeoutMs: Long) {
        lastTimeoutMs = timeoutMs
        if (isOpen) return
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs.toInt())
            s.soTimeout = timeoutMs.toInt()
            socket = s
            out = s.getOutputStream()
            input = s.getInputStream()
        } catch (e: SocketTimeoutException) {
            throw PrinterException(ErrorCode.TIMEOUT, "Timeout connexion TCP $host:$port", e.message, retryable = true)
        } catch (e: Exception) {
            throw PrinterException(ErrorCode.CONNECTION_FAILED, "Connexion TCP échouée $host:$port", e.message, retryable = true)
        }
    }

    override fun write(bytes: ByteArray) {
        try {
            writeAll(bytes)
        } catch (first: Exception) {
            // Le socket est probablement mort côté distant : les Epson TM (et beaucoup
            // d'imprimantes RAW 9100) n'acceptent qu'UNE connexion et ferment les
            // connexions inactives. En Java, ni Socket.isConnected() ni isClosed() ne
            // détectent une fermeture côté pair, donc isOpen ment et aucune reconnexion
            // amont n'a lieu. On ré-ouvre un socket neuf et on réémet le job une fois
            // avant d'abandonner.
            try {
                close()
                open(lastTimeoutMs)
                writeAll(bytes)
            } catch (retry: Exception) {
                throw PrinterException(ErrorCode.PRINT_FAILED, "Écriture TCP échouée", retry.message, retryable = true)
            }
        }
    }

    /** Écrit tout [bytes] par chunks pour ménager les petits buffers d'imprimante. */
    private fun writeAll(bytes: ByteArray) {
        val o = out ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Socket TCP non ouvert")
        var offset = 0
        val chunk = 4096
        while (offset < bytes.size) {
            val len = minOf(chunk, bytes.size - offset)
            o.write(bytes, offset, len)
            o.flush()
            offset += len
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Long): Int {
        val i = input ?: return -1
        return try {
            socket?.soTimeout = timeoutMs.toInt()
            i.read(buffer)
        } catch (e: SocketTimeoutException) {
            -1
        } catch (e: Exception) {
            -1
        }
    }

    override fun close() {
        try { out?.flush() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null; input = null
    }
}
