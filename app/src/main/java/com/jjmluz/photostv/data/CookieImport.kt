package com.jjmluz.photostv.data

import android.webkit.CookieManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Loads a Google web session exported from a desktop browser into the app's cookie store.
 *
 * Accepts the Netscape `cookies.txt` format written by browser export extensions, or a plain
 * `name=value; name2=value2` Cookie header copied from developer tools.
 */
object CookieImport {

    private data class ImportedCookie(
        val domain: String,
        val path: String,
        val secure: Boolean,
        val expiresEpochSeconds: Long?,
        val name: String,
        val value: String,
    )

    /** The file has no Google session cookies, e.g. it was exported while signed out. */
    class NoSessionCookies : IllegalArgumentException("No Google session cookies")

    private val PhotosDomains = setOf("google.com", "photos.google.com", "accounts.google.com")

    private val ExpiresFormat = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

    /** Applies the Google cookies found in [text]; returns how many were imported. */
    fun apply(text: String): Int {
        // Only what Photos needs; a full browser export holds hundreds more. Image requests bounce
        // through accounts.google.com, which needs its own host cookies to vouch for the session.
        val cookies = parse(text).filter { c ->
            val d = c.domain.trimStart('.')
            d in PhotosDomains || d.endsWith("usercontent.google.com")
        }
        if (cookies.none { it.name == "SID" || it.name.endsWith("PSID") }) throw NoSessionCookies()
        val manager = CookieManager.getInstance()
        cookies.forEach { c ->
            val host = c.domain.trimStart('.')
            manager.setCookie("https://$host${c.path}", header(c))
        }
        manager.flush()
        return cookies.size
    }

    private fun parse(text: String): List<ImportedCookie> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val netscape = lines.filter { '\t' in it }
        if (netscape.isNotEmpty()) return netscape.mapNotNull(::parseNetscapeLine)

        // Fallback: a single Cookie header, e.g. "Cookie: SID=...; HSID=..."
        return text.removePrefix("Cookie:").trim().split(";").mapNotNull { part ->
            val eq = part.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
            ImportedCookie(".google.com", "/", true, null, part.substring(0, eq).trim(), part.substring(eq + 1).trim())
        }
    }

    private fun parseNetscapeLine(line: String): ImportedCookie? {
        // HttpOnly cookies are exported with a "#HttpOnly_" prefix on the domain.
        val clean = line.removePrefix("#HttpOnly_")
        if (clean.startsWith("#")) return null
        val f = clean.split('\t')
        if (f.size < 7) return null
        return ImportedCookie(
            domain = f[0],
            path = f[2].ifEmpty { "/" },
            secure = f[3].equals("TRUE", ignoreCase = true),
            expiresEpochSeconds = f[4].toDoubleOrNull()?.toLong()?.takeIf { it > 0 },
            name = f[5],
            value = f[6],
        )
    }

    private fun header(c: ImportedCookie) = buildString {
        append(c.name).append('=').append(c.value)
        // __Host- cookies must be host-only; everything else keeps its (sub)domain scope.
        if (!c.name.startsWith("__Host-") && c.domain.startsWith(".")) append("; Domain=").append(c.domain)
        append("; Path=").append(c.path)
        if (c.secure || c.name.startsWith("__Secure-") || c.name.startsWith("__Host-")) append("; Secure")
        c.expiresEpochSeconds?.let { append("; Expires=").append(ExpiresFormat.format(Instant.ofEpochSecond(it))) }
    }
}
