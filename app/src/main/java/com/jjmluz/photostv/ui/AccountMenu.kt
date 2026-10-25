package com.jjmluz.photostv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.jjmluz.photostv.data.Profile

/** Account pictures are fetched once at this size, for both the button and the dialog. */
private const val PICTURE_PX = 160

/** The account's picture at the end of the top bar; clicking it offers to sign out. */
@Composable
fun AccountButton(profile: Profile?, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        modifier = modifier.size(44.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), shape = CircleShape),
        ),
    ) {
        Avatar(profile, 44.dp)
    }
    if (open) {
        AccountDialog(profile, onSignOut = { open = false; onSignOut() }, onDismiss = { open = false })
    }
}

@Composable
private fun AccountDialog(profile: Profile?, onSignOut: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    // Back dismisses it, like any dialog.
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(420.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) {
                Avatar(profile, 72.dp)
            }
            Spacer(Modifier.height(8.dp))
            profile?.name?.let { Text(it, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) }
            profile?.email?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Para voltar a entrar, leia o código QR da TV com a app Fotos TV no telemóvel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSignOut, modifier = Modifier.focusRequester(focus)) { Text("Terminar sessão") }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocusSafely() }
}

/** The account picture, over the initial of the name or email while it loads (or if there is none). */
@Composable
private fun Avatar(profile: Profile?, size: Dp) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val initial = (profile?.name ?: profile?.email)?.firstOrNull()?.uppercase()
        if (initial != null) {
            Text(
                initial,
                style = if (size > 48.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            Icon(Icons.Outlined.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size / 2))
        }
        profile?.pictureUrl(PICTURE_PX)?.let { url ->
            AsyncImage(
                model = rememberImageRequest(url),
                contentDescription = "Conta Google",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
