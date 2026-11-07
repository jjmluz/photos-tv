package com.jjmluz.photostv.phone

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A TV paired from its QR code: `photostv://pair?host=<ip:port>&code=<pairing code>`. */
data class Tv(val host: String, val code: String) {

    companion object {
        fun parse(text: String): Tv? {
            val uri = Uri.parse(text)
            if (uri.scheme != "photostv" || uri.host != "pair") return null
            val host = uri.getQueryParameter("host") ?: return null
            val code = uri.getQueryParameter("code") ?: return null
            return Tv(host, code)
        }

        private val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // The TV answers once it has turned the token into a session, which takes a few requests.
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Sends the sign-in token; returns the account the TV signed in with. */
    suspend fun sendToken(email: String, oauthToken: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("sentAt", System.currentTimeMillis()).put("email", email).put("oauthToken", oauthToken)
        val request = Request.Builder()
            .url("http://$host/token")
            .post(seal(payload.toString()).toRequestBody("application/json".toMediaType()))
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw TvException(R.string.tv_no_answer, cause = e)
        }
        response.use {
            val body = it.body?.string().orEmpty()
            when (it.code) {
                200 -> JSONObject(body).optString("email", email)
                401 -> throw TvException(R.string.tv_wrong_code)
                409 -> throw TvException(R.string.tv_stale)
                else -> throw runCatching { IOException(JSONObject(body).getString("error")) }.getOrNull() ?: TvException(R.string.tv_http, it.code)
            }
        }
    }

    // The envelope data/PairingServer.kt in the TV app opens: AES-GCM with a key from the pairing
    // code by PBKDF2-SHA256. Keep the salt and iteration count in step.
    private fun seal(plain: String): String {
        val spec = PBEKeySpec(code.uppercase().filter { it.isLetterOrDigit() }.toCharArray(), "photos-tv-cookie-sync".toByteArray(), 100_000, 256)
        val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        val data = cipher.doFinal(plain.toByteArray())
        val b64 = Base64.getEncoder()
        return JSONObject().put("iv", b64.encodeToString(iv)).put("data", b64.encodeToString(data)).toString()
    }
}

/** A failure talking to the TV, explained in the app's language by [message]. */
class TvException(@StringRes private val res: Int, private vararg val args: Any, cause: Throwable? = null) : IOException(cause) {
    fun message(context: Context): String = context.getString(res, *args)
}
