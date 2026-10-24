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
import com.jjmluz.photostv.data.CookieSync
import com.jjmluz.photostv.data.PhotosApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.jjmluz.photostv.data.WebViewCookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** The app is Portuguese-only (European Portuguese), regardless of the TV's system language. */
val AppLocale: Locale = Locale.forLanguageTag("pt-PT")

class PhotosTvApp : Application(), SingletonImageLoader.Factory {

    /** Lives as long as the process: the cookie receiver keeps listening while the app is backgrounded. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val cookieSync by lazy { CookieSync(this, scope) }

    override fun onCreate() {
        super.onCreate()
        Locale.setDefault(AppLocale)
        cookieSync.start()
    }

    val http: OkHttpClient by lazy {
        val userAgent = WebSettings.getDefaultUserAgent(this)
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            // Google's frontends stall HTTP/2 requests from this client (seen on the Streamer with
            // a full session's cookies); HTTP/1.1 answers immediately.
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // A regular browser user agent, so Google serves the normal Photos web app.
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .apply { demoImages()?.let(::addInterceptor) }
            .build()
    }

    val api by lazy { demoApi() ?: PhotosApi(http) }

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

    /** Forgets the Google session and everything cached for it. */
    suspend fun signOut() {
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
