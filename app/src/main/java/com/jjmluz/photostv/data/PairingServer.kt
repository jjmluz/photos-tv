package com.jjmluz.photostv.data

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "PhotosTV"

/**
 * Receives the account sign-in token from the Fotos TV phone app over the local network (see
 * [GoogleAccount]). The phone finds this server by scanning the QR code of [pairingUri].
 *
 * The phone POSTs `{"iv", "data"}` to `http://<tv>:[PORT]/token`: AES-GCM over
 * `{"sentAt", "email", "oauthToken"}`, keyed from the pairing [code] in the QR code. Anything that
 * doesn't decrypt with the code, or is stale or replayed, is refused.
 */
class PairingServer(context: Context, private val scope: CoroutineScope, private val account: GoogleAccount) {

    // Named after the Chrome extension sync this replaced, so existing installs keep their code.
    private val prefs = context.getSharedPreferences("cookie_sync", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Pairing code, e.g. "K7QM-2XPA-9RTD"; made once per install. */
    val code: String = prefs.getString("code", null) ?: newCode().also { prefs.edit().putString("code", it).apply() }

    private val key by lazy { deriveKey(code) }
    private var lastSentAt = 0L

    private val _signedIn = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits after the phone has signed the TV in. */
    val signedIn: SharedFlow<Unit> = _signedIn.asSharedFlow()

    /** What the phone app scans to pair: this TV's address and its code. */
    fun pairingUri(): String? = address()?.let { "photostv://pair?host=$it&code=${code.filter(Char::isLetterOrDigit)}" }

    /** This TV's address on the local network. */
    private fun address(): String? {
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
                Log.w(TAG, "Pairing: can't listen on $PORT", e)
                return@launch
            }
            Log.i(TAG, "Pairing: listening on $PORT")
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
                request.method == "GET" && request.path == "/ping" -> 200 to """{"app":"photostv"}"""
                request.method == "POST" && request.path == "/token" -> acceptToken(request.body)
                else -> 404 to ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pairing: bad request: ${e.message}")
            400 to ""
        }
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 409 to "Conflict", 502 to "Bad Gateway")
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status ${reason[status]}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().run {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    /** Signs in with the phone's token; answers only once the TV has a web session, or why not. */
    private suspend fun acceptToken(body: ByteArray): Pair<Int, String> {
        val payload = try { open(body) } catch (e: Refusal) { return e.response }
        return try {
            account.signIn(payload.getString("email"), payload.getString("oauthToken"))
            _signedIn.tryEmit(Unit)
            200 to """{"email":${JSONObject.quote(account.email)}}"""
        } catch (e: Exception) {
            Log.w(TAG, "Pairing: phone sign-in failed", e)
            502 to JSONObject().put("error", e.message ?: e.toString()).toString()
        }
    }

    private class Refusal(val response: Pair<Int, String>) : Exception()

    /** Decrypts a request's payload, refusing one sealed with another code, stale or replayed. */
    private fun open(body: ByteArray): JSONObject {
        val payload = try {
            unseal(key, body)
        } catch (e: Exception) {
            Log.w(TAG, "Pairing: refused a request that doesn't match the pairing code")
            throw Refusal(401 to """{"error":"pairing"}""")
        }
        val sentAt = payload.getLong("sentAt")
        if (sentAt <= lastSentAt || kotlin.math.abs(System.currentTimeMillis() - sentAt) > MAX_AGE_MS) {
            throw Refusal(409 to """{"error":"stale"}""")
        }
        lastSentAt = sentAt
        return payload
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
        private const val MAX_BODY = 64 * 1024
        private const val MAX_AGE_MS = 10 * 60 * 1000L
        // No 0/O or 1/I, so the code is easy to read off the TV.
        private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

        private fun newCode(): String {
            val random = SecureRandom()
            return List(12) { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("").chunked(4).joinToString("-")
        }

        /** PBKDF2-SHA256 over the code without dashes; the phone app derives the same key. */
        internal fun deriveKey(code: String): SecretKeySpec {
            val clean = code.uppercase().filter { it.isLetterOrDigit() }
            val spec = PBEKeySpec(clean.toCharArray(), "photos-tv-cookie-sync".toByteArray(), 100_000, 256)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        }

        /** Opens the phone's `{"iv", "data"}` envelope; throws if it wasn't sealed with [key]. */
        internal fun unseal(key: SecretKeySpec, body: ByteArray): JSONObject {
            val envelope = JSONObject(String(body))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.getString("iv").fromBase64()))
            return JSONObject(String(cipher.doFinal(envelope.getString("data").fromBase64())))
        }

        private fun String.fromBase64(): ByteArray = Base64.getMimeDecoder().decode(this)
    }
}
