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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.jjmluz.photostv.R
import com.jjmluz.photostv.data.Profile
import coil3.compose.AsyncImage

/** Account pictures are fetched once at this size, for both the button and the dialog. */
private const val PICTURE_PX = 160

/**
 * The account's picture at the end of the top bar; clicking it offers to switch to another signed-in
 * account, add one, or sign out.
 */
@Composable
fun AccountButton(
    profile: Profile?,
    otherAccounts: List<Profile>,
    onSwitch: (email: String) -> Unit,
    onAdd: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    takeFocus: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (takeFocus) focus.requestFocusSafely() }
    Surface(
        onClick = { open = true },
        modifier = modifier.size(44.dp).focusRequester(focus),
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
        AccountDialog(
            profile,
            otherAccounts,
            onSwitch = { open = false; onSwitch(it) },
            onAdd = { open = false; onAdd() },
            onSignOut = { open = false; onSignOut() },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun AccountDialog(
    profile: Profile?,
    otherAccounts: List<Profile>,
    onSwitch: (email: String) -> Unit,
    onAdd: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // Back dismisses it, like any dialog.
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(420.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AccountPicture(profile, 72.dp)
            Spacer(Modifier.height(8.dp))
            profile?.name?.let { Text(it, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) }
            profile?.email?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            // Focus starts on the first row, so a stray OK switches or adds rather than signs out.
            otherAccounts.forEachIndexed { i, other ->
                ListItem(
                    selected = false,
                    onClick = { other.email?.let(onSwitch) },
                    headlineContent = { Text(other.name ?: other.email.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = other.name?.let { { Text(other.email.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = { AccountPicture(other, 40.dp) },
                    modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                )
            }
            ListItem(
                selected = false,
                onClick = onAdd,
                headlineContent = { Text(stringResource(R.string.add_account)) },
                leadingContent = {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                    }
                },
                modifier = if (otherAccounts.isEmpty()) Modifier.focusRequester(focus) else Modifier,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocusSafely() }
}

/** [profile]'s picture in a disc of [size]. */
@Composable
private fun AccountPicture(profile: Profile?, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) {
        Avatar(profile, size)
    }
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
                contentDescription = stringResource(R.string.google_account),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
