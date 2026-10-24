package com.jjmluz.photostv.data

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Shares the WebView cookie store with OkHttp, so the Google session created during
 * sign-in is used by API, image and video requests, and rotated cookies flow back.
 */
class WebViewCookieJar(private val cookies: CookieManager = CookieManager.getInstance()) : CookieJar {

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = cookies.getCookie(url.toString()) ?: return emptyList()
        return header.split(";").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            Cookie.Builder()
                .name(part.substring(0, eq).trim())
                .value(part.substring(eq + 1).trim())
                .domain(url.host)
                .build()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies.setCookie(url.toString(), it.toString()) }
        this.cookies.flush()
    }
}
