package com.jjmluz.photostv.ui

import android.graphics.Bitmap
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jjmluz.photostv.R
import com.jjmluz.photostv.app
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Sign-in from the phone. Google blocks every way for a third-party TV app to sign in to Photos on
 * the TV itself, so the account comes from the Photos TV phone app, which pairs by scanning the QR
 * code (see [com.jjmluz.photostv.data.PairingServer] and [com.jjmluz.photostv.data.GoogleAccount]).
 *
 * When [adding], an account is already signed in and this adds another; Back returns to it.
 */
@Composable
fun SignInScreen(adding: Boolean = false) {
    val context = LocalContext.current
    val pairing = context.app.pairing

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
                Image(qr, contentDescription = stringResource(R.string.sign_in_qr), filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            } else {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
            Text(stringResource(if (adding) R.string.add_account else R.string.sign_in), style = MaterialTheme.typography.displaySmall)
            Step(1, stringResource(R.string.sign_in_step_phone))
            Step(2, stringResource(R.string.sign_in_step_account))
            Text(
                when {
                    qr == null -> stringResource(R.string.sign_in_no_network)
                    adding -> stringResource(R.string.sign_in_waiting_cancel)
                    else -> stringResource(R.string.sign_in_waiting)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
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
