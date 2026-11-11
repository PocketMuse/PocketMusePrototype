package com.example.pocketmuseprototype.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pocketmuseprototype.R
import com.example.pocketmuseprototype.auth.AuthOperation
import com.example.pocketmuseprototype.auth.AuthState

@Composable
internal fun CivitaiAccountHeader(
    state: AuthState,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit
) {
    val message = when (state) {
        is AuthState.Connected -> {
            ConnectedHeader(state.profile.username, onDisconnect)
            return
        }
        AuthState.Initializing -> R.string.market_civitai_restoring
        AuthState.Connecting -> R.string.market_civitai_connecting
        AuthState.Disconnecting -> R.string.market_civitai_disconnecting
        AuthState.ReauthenticationRequired -> R.string.market_civitai_reauth
        is AuthState.Error -> when (state.operation) {
            AuthOperation.Login -> R.string.market_civitai_login_error
            AuthOperation.Restore, AuthOperation.Session -> R.string.market_civitai_session_error
            AuthOperation.Disconnect -> R.string.market_civitai_disconnect_error
        }
        is AuthState.Disconnected -> if (state.revocationUnconfirmed) R.string.market_civitai_revoke_unconfirmed
            else R.string.market_civitai_description
    }
    val retryingSession = state is AuthState.Error && state.operation != AuthOperation.Login
    val action = when {
        state == AuthState.ReauthenticationRequired -> R.string.market_civitai_reconnect
        retryingSession -> R.string.market_retry
        state is AuthState.Disconnected || state is AuthState.Error -> R.string.market_civitai_connect
        else -> null
    }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.market_civitai_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(message),
            modifier = Modifier.widthIn(max = 340.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state is AuthState.Error) colors.error else colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action == null) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Button(onClick = if (retryingSession) onRetry else onConnect) { Text(stringResource(action)) }
            if (state is AuthState.Error && state.hasSession && state.operation != AuthOperation.Disconnect) {
                TextButton(onClick = onDisconnect) { Text(stringResource(R.string.market_civitai_disconnect)) }
            }
        }
    }
}

@Composable
private fun ConnectedHeader(username: String, onDisconnect: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val handle = stringResource(R.string.market_civitai_username, username)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.market_catalog),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = { expanded = true }) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_person), null, Modifier.size(18.dp))
                    Text(handle, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp))
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Column(Modifier.widthIn(max = 240.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.market_civitai_account), style = MaterialTheme.typography.labelMedium)
                    Text(handle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.market_civitai_disconnect)) },
                    onClick = {
                        expanded = false
                        onDisconnect()
                    }
                )
            }
        }
    }
}
