package com.delicity.thermalprinter.image

import android.content.Context
import android.graphics.BitmapFactory
import com.delicity.thermalprinter.Logger
import com.delicity.thermalprinter.model.ErrorCode
import com.delicity.thermalprinter.model.PrinterException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache local des images à imprimer.
 *
 * Objectif : éviter de re-télécharger une même URL et fournir un chemin fichier
 * stable au pipeline (le mode fichier local est le plus fiable/performant).
 *
 * Emplacement : context.cacheDir/thermal-images/
 * Clé de cache : SHA-1 de l'URL.
 * Politique : LRU best-effort par date de modification, plafonné à MAX_BYTES.
 *
 * Garanties d'intégrité (une entrée visible est TOUJOURS une image complète) :
 *   - téléchargement vers un fichier temporaire puis rename atomique : un download
 *     interrompu ou concurrent ne laisse jamais un fichier partiel sous la clé finale ;
 *   - validation du contenu (en-tête décodable + longueur annoncée) avant publication :
 *     une page d'erreur HTTP 200 (portail captif, erreur serveur) n'entre jamais au cache ;
 *   - verrou par clé : deux fetch simultanés de la même URL (ex. double impression d'une
 *     commande) sont sérialisés au lieu d'écrire le même fichier en même temps.
 * Sans ces garanties : fichier tronqué -> BitmapFactory renvoie null ("Décodage
 * impossible") ou décode partiellement -> impression illisible.
 */
class ImageCache(context: Context) {

    private val dir: File = File(context.cacheDir, "thermal-images").apply { mkdirs() }

    /** Verrous par clé de cache (jamais purgés : une entrée = un Any, négligeable). */
    private val keyLocks = ConcurrentHashMap<String, Any>()

    /**
     * Télécharge l'URL (si absente du cache) et renvoie le fichier local.
     * `forceFetch` ignore le cache : re-télécharge et écrase l'entrée existante.
     */
    fun fetch(url: String, timeoutMs: Int = 10000, forceFetch: Boolean = false): File {
        val key = sha1(url)
        synchronized(keyLocks.getOrPut(key) { Any() }) {
            val cached = File(dir, "$key.img")
            // isValidImage (et pas seulement length > 0) : purge les entrées corrompues
            // héritées d'anciennes versions qui écrivaient directement le fichier final.
            if (!forceFetch && isValidImage(cached)) {
                cached.setLastModified(System.currentTimeMillis())
                Logger.log("image", "cache hit", mapOf("url" to url, "bytes" to cached.length()))
                return cached
            }
            return download(url, cached, timeoutMs)
        }
    }

    private fun download(url: String, dest: File, timeoutMs: Int): File {
        var conn: HttpURLConnection? = null
        val tmp = File(dir, "${dest.name}.tmp")
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw PrinterException(ErrorCode.IMAGE_INVALID, "HTTP $code en téléchargeant $url")
            }
            val expected = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 8192) }
            }
            // Flux tronqué silencieusement (proxy, coupure réseau sans IOException).
            if (expected > 0 && tmp.length() != expected) {
                throw PrinterException(
                    ErrorCode.IMAGE_INVALID,
                    "Téléchargement tronqué: ${tmp.length()}/$expected octets ($url)",
                    retryable = true,
                )
            }
            // Contenu non-image renvoyé en 200 (portail captif, page d'erreur serveur).
            if (!isValidImage(tmp)) {
                throw PrinterException(ErrorCode.IMAGE_INVALID, "Contenu non décodable téléchargé: $url", retryable = true)
            }
            // Publication atomique : le fichier final est toujours complet et valide.
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    throw PrinterException(ErrorCode.IMAGE_INVALID, "Écriture cache impossible: ${dest.name}")
                }
            }
            Logger.log("image", "downloaded", mapOf("url" to url, "bytes" to dest.length()))
            enforceQuota()
            return dest
        } catch (e: PrinterException) {
            throw e
        } catch (e: Exception) {
            throw PrinterException(ErrorCode.IMAGE_INVALID, "Téléchargement image échoué", e.message, retryable = true)
        } finally {
            tmp.delete() // no-op si le rename a réussi
            conn?.disconnect()
        }
    }

    /**
     * Vrai si le fichier ressemble à une image décodable (bornes lisibles sans décoder
     * les pixels). Rejette fichiers vides, HTML, JSON d'erreur, en-têtes corrompus.
     */
    private fun isValidImage(f: File): Boolean {
        if (!f.exists() || f.length() == 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /** Supprime les fichiers les plus anciens si le cache dépasse le quota. */
    private fun enforceQuota() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var i = 0
        while (total > MAX_BYTES && i < files.size) {
            total -= files[i].length()
            files[i].delete()
            i++
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_BYTES = 32L * 1024 * 1024 // 32 Mo
    }
}
