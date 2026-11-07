package com.jjmluz.photostv

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.util.Log
import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.jjmluz.photostv.data.GoogleAccount
import com.jjmluz.photostv.data.Jamendo
import com.jjmluz.photostv.data.PairingServer
import com.jjmluz.photostv.data.PhotosApi
import com.jjmluz.photostv.data.SlideshowSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.jjmluz.photostv.data.WebViewCookieJar
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import android.content.res.Configuration
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** The languages the app is translated into (its `res/values-*` folders); English is the default. */
private val Translated = setOf("en", "pt", "es", "fr", "de")

/**
 * The app's language: the first of the TV's languages it's translated into, or English. Dates,
 * month names, the video player's controls and Google's place names all follow it.
 */
var AppLocale: Locale = Locale.ENGLISH
    private set

/** Picks [AppLocale] from [config]'s languages (the system's, or the one chosen for this app). */
fun pickAppLocale(config: Configuration): Locale {
    val locales = config.locales
    val locale = (0 until locales.size()).map(locales::get).firstOrNull { it.language in Translated } ?: Locale.ENGLISH
    AppLocale = locale
    Locale.setDefault(locale)
    return locale
}

class PhotosTvApp : Application(), SingletonImageLoader.Factory {

    /** Lives as long as the process: the pairing server keeps listening while the app is backgrounded. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val account by lazy { GoogleAccount(this, http) }

    val pairing by lazy { PairingServer(this, scope, account) }

    override fun onCreate() {
        super.onCreate()
        pickAppLocale(resources.configuration)
        pairing.start()
    }

    val http: OkHttpClient by lazy {
        val userAgent = WebSettings.getDefaultUserAgent(this)
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            // Google's frontends stall HTTP/2 requests from this client (seen on the Streamer with
            // a full session's cookies); HTTP/1.1 answers immediately.
            .protocols(listOf(Protocol.HTTP_1_1))
            // Connections left idle for a few minutes stop answering (the network drops them
            // without closing them), and a request on one hangs until it times out. Seen after
            // signing in from the phone, which leaves the photos.google.com connection idle.
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // A regular browser user agent, so Google serves the normal Photos web app.
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .apply { demoImages()?.let(::addInterceptor) }
            .build()
    }

    val api by lazy { demoApi() ?: PhotosApi(http, renewSession = account::renewSession) }

    val slideshowSettings by lazy { SlideshowSettings(this) }

    val jamendo by lazy { Jamendo(http, BuildConfig.JAMENDO_CLIENT_ID) }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = {
                    http.newBuilder()
                        .addNetworkInterceptor { chain ->
                            chain.proceed(chain.request()).also {
                                if (!it.isSuccessful) Log.w("PhotosTV", "Image: HTTP ${it.code} from ${it.request.url.host}")
                            }
                        }
                        .build()
                }))
            }
            .eventListener(object : EventListener() {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    Log.w("PhotosTV", "Image failed: ${result.throwable}")
                }
            })
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.3).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(768L * 1024 * 1024)
                    .build()
            }
            .build()

    /** Forgets the current account, its Google session and everything cached for it. */
    suspend fun signOut() {
        account.forget()
        clearWebSession()
        api.reset()
        SingletonImageLoader.get(this).run {
            memoryCache?.clear()
            diskCache?.clear()
        }
    }

    /** Removes all cookies and web storage, e.g. leftovers from an unfinished sign-in. */
    suspend fun clearWebSession() {
        suspendCoroutine { cont -> CookieManager.getInstance().removeAllCookies { cont.resume(Unit) } }
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}

val Context.app get() = applicationContext as PhotosTvApp
