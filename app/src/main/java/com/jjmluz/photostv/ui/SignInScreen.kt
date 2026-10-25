package com.jjmluz.photostv.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jjmluz.photostv.R
import com.jjmluz.photostv.app
import com.jjmluz.photostv.data.CookieImport
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "PhotosTV"
private const val FILE_NAME = "cookies.txt"

/**
 * Sign-in from the phone. Google blocks every way for a third-party TV app to sign in to Photos on
 * the TV itself, so the account comes from the Fotos TV phone app, which pairs by scanning the QR
 * code (see [com.jjmluz.photostv.data.PairingServer] and [com.jjmluz.photostv.data.GoogleAccount]).
 * A cookies.txt pushed with adb also works, as a one-off session.
 *
 * When [adding], an account is already signed in and this adds another; Back returns to it.
 */
@Composable
fun SignInScreen(onImported: () -> Unit, adding: Boolean = false) {
    val context = LocalContext.current
    val dir = remember { context.getExternalFilesDir(null)!! }
    var error by remember { mutableStateOf<String?>(null) }
    val pairing = context.app.pairing

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
        Modifier.fillMaxSize().padding(horizontal = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        val qr = remember { pairing.pairingUri()?.let(::qrCode) }
        Box(
            // QR codes need a light quiet zone around them to scan, whatever the theme.
            Modifier.size(232.dp).background(Color.White, MaterialTheme.shapes.large).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(qr, contentDescription = "Código QR para emparelhar", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            } else {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
            Text(if (adding) "Adicionar outra conta" else "Iniciar sessão", style = MaterialTheme.typography.displaySmall)
            Step(1, "No telemóvel, abra a app Fotos TV e leia este código.")
            Step(2, "Inicie sessão na sua conta Google. A TV fica com sessão própria e renova-a sozinha.")
            Text(
                "Em alternativa, exporte os cookies como $FILE_NAME e envie-os com " +
                    "adb push <ficheiro> ${dir.absolutePath.replace("/storage/emulated/0", "/sdcard")}/$FILE_NAME",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                error ?: when {
                    qr == null -> "A TV não está ligada à rede."
                    adding -> "À espera do telemóvel… Prima Voltar para cancelar."
                    else -> "À espera do telemóvel…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** [text] as a QR code, one pixel per module, to be scaled up without smoothing. */
private fun qrCode(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
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
