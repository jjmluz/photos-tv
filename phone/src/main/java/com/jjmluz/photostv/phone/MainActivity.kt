package com.jjmluz.photostv.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "PhotosTvPhone"

/** The value of the sign-in page's email field, if it's showing one. */
private const val EMAIL_FIELD = "(document.querySelector('input[type=email], input[name=identifier]') || {}).value || null"

/**
 * Signs the Fotos TV app in to a Google account. Scan the TV's QR code, sign in on Google's
 * Android setup page, and the app sends the one-time token that page leaves in its cookies to the
 * TV, which turns it into a session of its own. Nothing is kept on the phone afterwards.
 */
class MainActivity : ComponentActivity() {

    private sealed interface Step {
        data object Start : Step
        data class SignIn(val tv: Tv) : Step
        data class NeedEmail(val tv: Tv, val token: String) : Step
        data class Sending(val tv: Tv) : Step
        data class Done(val email: String) : Step
        data class Failed(val message: String) : Step
    }

    private var step by mutableStateOf<Step>(Step.Start)

    /** What to do once the local network permission has been answered. */
    private var afterPermission: (() -> Unit)? = null

    private val localNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(TAG, "Local network permission: ${if (granted) "granted" else "denied"}")
        val next = afterPermission
        afterPermission = null
        if (granted) next?.invoke() else step = Step.Failed("Sem acesso à rede local, a app não consegue falar com a TV. Permita o acesso a dispositivos próximos.")
    }

    /** Runs [then] once the app may connect to the TV over the home network. */
    private fun withLocalNetwork(then: () -> Unit) {
        afterPermission = then
        localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Screen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Opened from the QR code by the camera app. */
    private fun handle(intent: Intent?) {
        val tv = intent?.dataString?.let(Tv::parse) ?: return
        Log.i(TAG, "Opened for TV at ${tv.host}")
        withLocalNetwork { step = Step.SignIn(tv) }
    }

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        fun send(tv: Tv, email: String, token: String) {
            step = Step.Sending(tv)
            scope.launch {
                Log.i(TAG, "Sending token to ${tv.host}")
                step = runCatching { Step.Done(tv.sendToken(email, token)) }
                    .onSuccess { Log.i(TAG, "TV signed in") }
                    .getOrElse {
                        Log.w(TAG, "Send failed", it)
                        Step.Failed(listOfNotNull(it.message, it.cause?.toString()).joinToString("\n\n"))
                    }
                clearCookies()
            }
        }

        when (val s = step) {
            Step.Start -> Message(
                "Iniciar sessão na TV",
                "Abra a app Fotos na TV e leia o código QR que aparece no ecrã.",
                "Ler código QR",
            ) { withLocalNetwork(::scan) }
            is Step.SignIn -> {
                SignInPage(onToken = { token, email ->
                    if (email != null) send(s.tv, email, token) else step = Step.NeedEmail(s.tv, token)
                })
                BackHandler { step = Step.Start }
            }
            is Step.NeedEmail -> EmailForm { send(s.tv, it, s.token) }
            is Step.Sending -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text("A iniciar sessão na TV…", style = MaterialTheme.typography.titleMedium)
            }
            is Step.Done -> Message("Sessão iniciada", "A TV tem sessão iniciada como ${s.email}. Já pode fechar esta app.", "Concluir") { finish() }
            is Step.Failed -> Message("Não foi possível iniciar sessão", s.message, "Tentar novamente") { step = Step.Start }
        }
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val tv = barcode.rawValue?.let(Tv::parse)
                Log.i(TAG, "Scanned ${if (tv != null) "TV at ${tv.host}" else "another QR code"}")
                step = if (tv != null) Step.SignIn(tv) else Step.Failed("Esse código QR não é o da app Fotos na TV.")
            }
            .addOnFailureListener { step = Step.Failed("Não foi possível ler o código: ${it.message}") }
    }

    /**
     * Google's Android setup sign-in, the one page Google allows inside a WebView. Once signed in it
     * leaves an `oauth_token` cookie; [onToken] gets it, with the account email when it can be found.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun SignInPage(onToken: (token: String, email: String?) -> Unit) {
        var ready by remember { mutableStateOf(false) }
        var webView by remember { mutableStateOf<WebView?>(null) }
        LaunchedEffect(Unit) {
            // A token left from an earlier attempt would be sent before signing in.
            clearCookies()
            ready = true
            // What was typed in the page's email field, in case Google's account list doesn't answer.
            var typed: String? = null
            while (true) {
                val token = cookie("https://accounts.google.com/", "oauth_token")
                if (token != null) {
                    val listed = withContext(Dispatchers.IO) { signedInEmail() }
                    val email = listed ?: typed?.let { if ('@' in it) it else "$it@gmail.com" }
                    Log.i(TAG, "Sign-in token found (${token.length} chars), account from ${if (listed != null) "account list" else if (email != null) "email field" else "nowhere"}")
                    onToken(token, email)
                    return@LaunchedEffect
                }
                webView?.evaluateJavascript(EMAIL_FIELD) { value ->
                    JSONArray("[$value]").optString(0).trim().takeIf { it.isNotEmpty() && it != "null" }?.let { typed = it }
                }
                delay(500)
            }
        }
        if (!ready) return
        AndroidView(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // What the setup page expects from Android's own sign-in WebView.
                    settings.userAgentString = settings.userAgentString + " MinuteMaid"
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w(TAG, "Sign-in page console: ${message.message()}")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        // Host and path only: the query can hold tokens.
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            Log.i(TAG, "Sign-in page: ${url.substringBefore('?').substringBefore('#')}")
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) Log.w(TAG, "Sign-in page failed: ${error.errorCode} ${error.description}")
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) Log.w(TAG, "Sign-in page: HTTP ${response.statusCode} at ${request.url.host}${request.url.path}")
                        }
                    }
                    loadUrl("https://accounts.google.com/EmbeddedSetup?hl=pt-PT")
                    webView = this
                }
            },
        )
    }

    /** The account the WebView just signed in to, from the same list Chrome uses for its account menu. */
    private fun signedInEmail(): String? = runCatching<String> {
        val request = Request.Builder()
            .url("https://accounts.google.com/ListAccounts?gpsia=1&source=ChromiumBrowser&json=standard")
            .header("Cookie", CookieManager.getInstance().getCookie("https://accounts.google.com/").orEmpty())
            .build()
        OkHttpClient().newCall(request).execute().use { response ->
            val body = response.body!!.string()
            Log.i(TAG, "ListAccounts: HTTP ${response.code}, ${body.length} chars")
            // ["gaia.l.a.r", [["gaia.l.a", 1, name, email, ...], ...]]
            JSONArray(body).getJSONArray(1).getJSONArray(0).getString(3)
        }
    }.onFailure { Log.w(TAG, "ListAccounts failed", it) }.getOrNull()?.takeIf { '@' in it }

    private fun cookie(url: String, name: String): String? =
        CookieManager.getInstance().getCookie(url)?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private suspend fun clearCookies() {
        suspendCoroutine { cont -> CookieManager.getInstance().removeAllCookies { cont.resume(Unit) } }
        CookieManager.getInstance().flush()
    }
}

@Composable
private fun Message(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
    }
}

/** Asked only when the signed-in account couldn't be read from Google's account list. */
@Composable
private fun EmailForm(onDone: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Qual é a conta?", style = MaterialTheme.typography.headlineSmall)
        Text("Introduza o email da conta Google com que acabou de iniciar sessão.", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onDone(email) }, enabled = '@' in email, modifier = Modifier.align(Alignment.End)) { Text("Continuar") }
    }
}
