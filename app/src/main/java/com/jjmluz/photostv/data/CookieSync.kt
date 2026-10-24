package com.jjmluz.photostv.data

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "PhotosTV"

/**
 * Receives the Google session from the "Fotos TV" Chrome extension over the local network, so the
 * TV keeps the browser's current cookies instead of a one-off export that Google soon retires.
 *
 * The extension POSTs `{"iv", "data"}` to `http://<tv>:[PORT]/cookies`: AES-GCM over
 * `{"sentAt", "cookies"}` (cookies in `cookies.txt` format), keyed from the pairing [code] shown on
 * the sign-in screen. Anything that doesn't decrypt with it, or is stale or replayed, is refused.
 */
class CookieSync(context: Context, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("cookie_sync", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Pairing code, e.g. "K7QM-2XPA-9RTD"; made once per install. */
    val code: String = prefs.getString("code", null) ?: newCode().also { prefs.edit().putString("code", it).apply() }

    private val key by lazy { deriveKey(code) }
    private var lastSentAt = 0L

    private val _received = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** Emits the number of cookies applied after each accepted push. */
    val received: SharedFlow<Int> = _received.asSharedFlow()

    /** This TV's address on the local network, for the extension's settings. */
    fun address(): String? {
        val props = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
        val ip = props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        return ip?.hostAddress?.let { "$it:$PORT" }
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
            } catch (e: IOException) {
                Log.w(TAG, "Cookie sync: can't listen on $PORT", e)
                return@launch
            }
            Log.i(TAG, "Cookie sync: listening on $PORT")
            server.use {
                while (isActive) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: continue
                    launch { socket.use { handle(it) } }
                }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val (status, body) = try {
            val request = readRequest(BufferedInputStream(socket.getInputStream()))
            when {
                request.method == "OPTIONS" -> 204 to ""
                request.method == "GET" && request.path == "/ping" -> 200 to """{"app":"photostv"}"""
                request.method == "POST" && request.path == "/cookies" -> accept(request.body)
                else -> 404 to ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cookie sync: bad request: ${e.message}")
            400 to ""
        }
        val reason = mapOf(200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 409 to "Conflict")
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status ${reason[status]}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Access-Control-Allow-Private-Network: true\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().run {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private suspend fun accept(body: ByteArray): Pair<Int, String> {
        val envelope = JSONObject(String(body))
        val payload = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.getString("iv").fromBase64()))
            JSONObject(String(cipher.doFinal(envelope.getString("data").fromBase64())))
        } catch (e: Exception) {
            Log.w(TAG, "Cookie sync: refused a push that doesn't match the pairing code")
            return 401 to """{"error":"pairing"}"""
        }
        val sentAt = payload.getLong("sentAt")
        if (sentAt <= lastSentAt || kotlin.math.abs(System.currentTimeMillis() - sentAt) > MAX_AGE_MS) {
            return 409 to """{"error":"stale"}"""
        }
        lastSentAt = sentAt
        val count = withContext(Dispatchers.Main) { CookieImport.apply(payload.getString("cookies")) }
        Log.i(TAG, "Cookie sync: applied $count cookies")
        _received.tryEmit(count)
        return 200 to """{"cookies":$count}"""
    }

    private class Request(val method: String, val path: String, val body: ByteArray)

    private fun readRequest(input: BufferedInputStream): Request {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || head.length > 16_384) throw IOException("incomplete headers")
            head.append(b.toChar())
        }
        val lines = head.lines()
        val (method, path) = lines.first().split(" ").let { it[0] to it[1] }
        val length = lines.firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toInt()
        } ?: 0
        if (length > MAX_BODY) throw IOException("body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("body cut short")
            read += n
        }
        return Request(method, path, body)
    }

    companion object {
        const val PORT = 8765
        private const val MAX_BODY = 2 * 1024 * 1024
        private const val MAX_AGE_MS = 10 * 60 * 1000L
        // No 0/O or 1/I, so the code is easy to copy from the TV.
        private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

        private fun newCode(): String {
            val random = SecureRandom()
            return List(12) { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("").chunked(4).joinToString("-")
        }

        /** PBKDF2-SHA256 over the code without dashes; the extension derives the same key. */
        private fun deriveKey(code: String): SecretKeySpec {
            val clean = code.uppercase().filter { it.isLetterOrDigit() }
            val spec = PBEKeySpec(clean.toCharArray(), "photos-tv-cookie-sync".toByteArray(), 100_000, 256)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        }

        private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.DEFAULT)
    }
}
