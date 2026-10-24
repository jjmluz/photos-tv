package com.jjmluz.photostv.ui

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jjmluz.photostv.R
import com.jjmluz.photostv.app
import com.jjmluz.photostv.data.CookieImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "PhotosTV"
private const val FILE_NAME = "cookies.txt"

/**
 * Sign-in by importing a Google session from a desktop browser. Google blocks every way for a
 * third-party TV app to sign in to Photos directly, so the session comes from the Fotos TV Chrome
 * extension (see [com.jjmluz.photostv.data.CookieSync]) or a cookies.txt pushed with adb.
 */
@Composable
fun SignInScreen(onImported: () -> Unit) {
    val context = LocalContext.current
    val dir = remember { context.getExternalFilesDir(null)!! }
    var error by remember { mutableStateOf<String?>(null) }
    val sync = context.app.cookieSync

    LaunchedEffect(Unit) {
        val file = dir.resolve(FILE_NAME)
        while (true) {
            if (file.exists()) {
                val text = withContext(Dispatchers.IO) { file.readText().also { file.delete() } }
                Log.i(TAG, "Sign-in: found $FILE_NAME (${text.length} chars)")
                context.app.clearWebSession()
                val result = runCatching { CookieImport.apply(text) }
                result.onSuccess {
                    Log.i(TAG, "Sign-in: imported $it cookies")
                    onImported()
                    return@LaunchedEffect
                }
                error = result.exceptionOrNull()?.message ?: "Não foi possível ler esse ficheiro."
            }
            delay(1_000)
        }
    }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 96.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(64.dp),
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(200.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.width(860.dp)) {
            Text("Iniciar sessão com o navegador", style = MaterialTheme.typography.displaySmall)
            Step(1, "No computador, instale a extensão Fotos TV no Chrome e abra photos.google.com com sessão iniciada.")
            Step(2, "Na extensão, introduza o endereço e o código desta TV:")
            Column(
                Modifier
                    .padding(start = 48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PairingField("Endereço", sync.address() ?: "sem rede")
                PairingField("Código", sync.code)
            }
            Step(3, "A TV inicia sessão sozinha e mantém-se sincronizada enquanto o Chrome estiver aberto.")
            Text(
                "Em alternativa, exporte os cookies como $FILE_NAME e envie-os com " +
                    "adb push <ficheiro> ${dir.absolutePath.replace("/storage/emulated/0", "/sdcard")}/$FILE_NAME",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                error ?: "À espera da extensão…",
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PairingField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace), softWrap = false)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        // Numbered tonal discs for the steps.
        Box(
            Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
