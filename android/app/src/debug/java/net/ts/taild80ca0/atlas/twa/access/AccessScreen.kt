package net.ts.taild80ca0.atlas.twa.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Setup, reconnect and "already connected" in one small screen. The password
 * lives only in this composable's field and is cleared as soon as it is sent.
 */
@Composable
fun AccessScreen(access: HouseAccess, connected: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val state by access.state.collectAsState()
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { access.refresh() }

    Column(
        modifier = modifier.fillMaxSize().padding(if (state == AccessState.Connected) 0.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            null -> Text(AccessMessages.WORKING)
            AccessState.StorageUnavailable -> {
                Text(AccessMessages.STORAGE_UNAVAILABLE, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { scope.launch { access.refresh() } }) { Text("Tentar de novo") }
            }
            AccessState.Connected -> {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { connected() }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            working = true
                            message = when (val result = access.checkConnection()) {
                                is ConnectResult.Connected -> AccessMessages.CHECK_OK
                                is ConnectResult.Failed -> AccessMessages.forError(result.error)
                            }
                            working = false
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.testTag("check"),
                ) { Text(AccessMessages.CHECK_BUTTON) }
                message?.let { Text(it, modifier = Modifier.testTag("message")) }
            }
            AccessState.NotConfigured, AccessState.NeedsReconnect -> {
                val reconnect = state == AccessState.NeedsReconnect
                Text(
                    if (reconnect) AccessMessages.RECONNECT_TITLE else AccessMessages.SETUP_TITLE,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (reconnect) AccessMessages.RECONNECT_HELP else AccessMessages.SETUP_HELP,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(AccessMessages.PASSWORD_LABEL) },
                    singleLine = true,
                    enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("password"),
                )
                Button(
                    onClick = {
                        val typed = password
                        password = ""
                        scope.launch {
                            working = true
                            message = null
                            val result = access.connect(typed)
                            message = (result as? ConnectResult.Failed)?.let { AccessMessages.forError(it.error) }
                            working = false
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.testTag("connect"),
                ) {
                    Text(
                        when {
                            working -> AccessMessages.WORKING
                            reconnect -> AccessMessages.RECONNECT_BUTTON
                            else -> AccessMessages.CONNECT_BUTTON
                        },
                    )
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("message")) }
            }
        }
    }
}
