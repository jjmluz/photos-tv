package com.jjmluz.photostv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "PhotosTV"

/**
 * Signs the TV in with an Android-style Google account token, so it can make its own web session
 * whenever the old one ends, with no browser keeping it alive.
 *
 * The phone app signs in at `accounts.google.com/EmbeddedSetup` (the page Android's setup wizard
 * shows, which Google allows in a WebView) and sends the one-time `oauth_token` it sets. The TV
 * trades it for a long-lived master token (the gpsoauth flow), kept encrypted with a Keystore key.
 * Web cookies then come from master token → OAuthLogin access token → uberauth → MergeSession, as
 * Chrome on Android does.
 *
 * [web] must store cookies in the WebView cookie store, where the Photos client reads them.
 */
class GoogleAccount(context: Context, private val web: OkHttpClient) {

    private val prefs = context.getSharedPreferences("google_account", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var lastRenewal = 0L

    /**
     * Talks to the Android auth endpoint, which wants its own user agent and no cookies. Shares the
     * web client's connections and timeouts, so it also drops idle connections before they go stale.
     */
    private val auth = web.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .apply { interceptors().clear() }
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "GoogleAuth/1.4").build()) }
        .build()

    /** Random, made once per install; the auth endpoint ties tokens to it. */
    private val androidId: String =
        prefs.getString("androidId", null) ?: ByteArray(8).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString("androidId", it).apply() }

    val email: String? get() = prefs.getString("email", null)

    val hasToken: Boolean get() = prefs.contains("masterToken")

    /** Trades the phone's [oauthToken] for a master token, then makes a web session from it. */
    suspend fun signIn(email: String, oauthToken: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val reply = authRequest(
                "Email" to email,
                "Token" to oauthToken,
                "add_account" to "1",
                "ACCESS_TOKEN" to "1",
                "service" to "ac2dm",
                "callerSig" to CLIENT_SIG,
                "droidguard_results" to "dummy123",
            )
            val master = reply["Token"] ?: throw IOException("A Google não devolveu um token (${reply["Error"] ?: reply.keys})")
            prefs.edit()
                .putString("email", reply["Email"] ?: email)
                .putString("masterToken", seal(master))
                .apply()
            Log.i(TAG, "Account: master token stored for ${reply["Email"] ?: email}")
        }
        makeWebSession()
    }

    /**
     * Replaces an ended web session with a fresh one. Returns false without a master token, or if
     * another renewal happened in the last minute (so a broken session doesn't loop).
     */
    suspend fun renewSession(): Boolean = mutex.withLock {
        if (!hasToken || System.currentTimeMillis() - lastRenewal < 60_000) return@withLock false
        runCatching { makeWebSession() }
            .onFailure { Log.w(TAG, "Account: renewal failed", it) }
            .isSuccess
    }

    /** The account's name and picture as last fetched, so the avatar shows before [profile] answers. */
    val savedProfile: Profile?
        get() = email?.let { Profile(prefs.getString("name", null), it, prefs.getString("picture", null)) }

    /** Fetches the account's name and picture; null when signed in without a master token. */
    suspend fun profile(): Profile? = withContext(Dispatchers.IO) {
        val email = email ?: return@withContext null
        val master = unseal(prefs.getString("masterToken", null) ?: return@withContext null)
        val reply = authRequest(
            "Email" to email,
            "EncryptedPasswd" to master,
            "service" to "oauth2:https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/userinfo.email",
            "app" to "com.android.chrome",
        )
        val access = reply["Auth"] ?: throw IOException("A Google recusou o pedido do perfil (${reply["Error"] ?: reply.keys})")
        val request = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v3/userinfo")
            .header("Authorization", "Bearer $access")
            .build()
        val info = auth.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("userinfo: HTTP ${response.code}")
            JSONObject(response.body!!.string())
        }
        Profile(info.optString("name").ifEmpty { null }, email, info.optString("picture").ifEmpty { null }).also {
            prefs.edit().putString("name", it.name).putString("picture", it.pictureUrl).apply()
        }
    }

    fun forget() {
        prefs.edit().remove("email").remove("masterToken").remove("name").remove("picture").apply()
    }

    private suspend fun makeWebSession() {
        lastRenewal = System.currentTimeMillis()
        val uberauth = withContext(Dispatchers.IO) { uberauth() }
        // The old session's cookies would otherwise sit next to the new ones.
        withContext(Dispatchers.Main) {
            suspendCoroutine { cont -> CookieManager.getInstance().removeAllCookies { cont.resume(Unit) } }
        }
        withContext(Dispatchers.IO) { mergeSession(uberauth) }
    }

    /** A one-time token that MergeSession turns into web cookies. */
    private fun uberauth(): String {
        val email = email ?: throw IOException("Sem conta")
        val master = unseal(prefs.getString("masterToken", null) ?: throw IOException("Sem token"))

        val reply = authRequest(
            "Email" to email,
            "EncryptedPasswd" to master,
            "service" to "oauth2:https://www.google.com/accounts/OAuthLogin",
            "app" to "com.android.chrome",
        )
        val access = reply["Auth"] ?: run {
            if (reply["Error"] == "BadAuthentication") forget()
            throw IOException("A Google recusou o token da conta (${reply["Error"] ?: reply.keys})")
        }

        return web.newCall(
            Request.Builder()
                .url("https://accounts.google.com/OAuthLogin?source=ChromiumBrowser&issueuberauth=1")
                .header("Authorization", "Bearer $access")
                .build()
        ).execute().use { response ->
            val body = response.body!!.string().trim()
            if (!response.isSuccessful) throw IOException("OAuthLogin: HTTP ${response.code}: ${body.take(200)}")
            body
        }
    }

    private fun mergeSession(uberauth: String) {
        val merge = "https://accounts.google.com/MergeSession".toHttpUrl().newBuilder()
            .addQueryParameter("uberauth", uberauth)
            .addQueryParameter("continue", "https://photos.google.com/")
            .addQueryParameter("source", "ChromiumBrowser")
            .build()
        // The cookies are set on accounts.google.com; the last hop, to photos.google.com, can stall
        // for over 30 s, and the Photos client loads that page itself afterwards.
        val noRedirects = web.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var url = merge
        for (hop in 1..MAX_HOPS) {
            val next = noRedirects.newCall(Request.Builder().url(url).build()).execute().use { response ->
                Log.i(TAG, "Account: MergeSession hop $hop: HTTP ${response.code} at ${url.host}${url.encodedPath}")
                response.header("Location")?.takeIf { response.isRedirect }?.let { url.resolve(it) }
            }
            if (next == null || next.host != "accounts.google.com") break
            url = next
        }
        CookieManager.getInstance().flush()
        val cookies = CookieManager.getInstance().getCookie("https://photos.google.com/").orEmpty()
        if ("SID=" !in cookies) throw IOException("MergeSession não criou a sessão")
        Log.i(TAG, "Account: web session made (${cookies.count { it == '=' }} cookies)")
    }

    /** POST to the Android auth endpoint; its reply is `key=value` lines. */
    private fun authRequest(vararg fields: Pair<String, String>): Map<String, String> {
        val form = FormBody.Builder().apply {
            add("accountType", "HOSTED_OR_GOOGLE")
            add("has_permission", "1")
            add("source", "android")
            add("androidId", androidId)
            add("client_sig", CLIENT_SIG)
            add("device_country", "pt")
            add("operatorCountry", "pt")
            add("lang", "pt_PT")
            add("sdk_version", "34")
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder().url("https://android.clients.google.com/auth").post(form).build()
        return auth.newCall(request).execute().use { response ->
            val reply = response.body!!.string().lines()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
            if (!response.isSuccessful) Log.w(TAG, "Account: auth HTTP ${response.code}: ${reply["Error"]}")
            reply
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun seal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv.toBase64() + ":" + cipher.doFinal(text.toByteArray()).toBase64()
    }

    private fun unseal(sealed: String): String {
        val (iv, data) = sealed.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }

    private fun ByteArray.toBase64() = Base64.encodeToString(this, Base64.NO_WRAP)

    private companion object {
        const val KEY_ALIAS = "google_account"
        const val MAX_HOPS = 10
        // Signature of Google's own apps (GMS, Chrome); the auth endpoint only serves those.
        const val CLIENT_SIG = "38918a453d07199354f8b19af05ec6562ced5788"
    }
}
