package net.ts.taild80ca0.atlas.twa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.access.AccessGraph
import net.ts.taild80ca0.atlas.twa.online.*
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore
import net.ts.taild80ca0.atlas.twa.ui.theme.*

class QuickPanelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val access = AccessGraph.houseAccess(this)
        val repo = AccessGraph.onlineRepository(this)
        lifecycleScope.launch {
            val prefs = DevicePreferencesStore(this@QuickPanelActivity).values.first()
            val connected = access.credentialForSync() != null
            setContent {
                AlvaTheme(prefs.dark, prefs.color) {
                    Surface(Modifier.fillMaxWidth(), color = AlvaTheme.colors.canvas, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                        if (!connected) {
                            QuickEntry("Conecte este aparelho à casa pelo app principal.") { finish() }
                        } else if (repo != null) QuickAdd(repo) { finish() }
                        else QuickEntry("Não foi possível abrir o painel. Tente novamente.") { finish() }
                    }
                }
            }
        }
    }
}

@Composable private fun QuickEntry(message: String, close: () -> Unit) {
    Column(Modifier.padding(24.dp)) { Text("Adicionar", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(12.dp)); Text(message); TextButton(onClick = close) { Text("Fechar") } }
}

@Composable private fun QuickAdd(repository: OnlineRepository, close: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repository.refresh(); focus.requestFocus(); keyboard?.show() }
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Adicionar", style = MaterialTheme.typography.titleLarge); TextButton(onClick = close) { Text("Fechar") } }
        Spacer(Modifier.height(12.dp))
        Surface(color = AlvaTheme.colors.surface2, shape = RoundedCornerShape(16.dp)) {
            BasicTextField(text, { text = it }, Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus), enabled = !busy,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AlvaTheme.colors.ink),
                decorationBox = { inner -> Box { if (text.isEmpty()) Text("O que acabou?", color = AlvaTheme.colors.inkSoft); inner() } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submitQuick(repository, text, { busy = it }, { message = it }, { text = it }, close, keyboard, scope) }))
        }
        if (message.isNotEmpty()) Text(message, color = AlvaTheme.colors.danger, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = { submitQuick(repository, text, { busy = it }, { message = it }, { text = it }, close, keyboard, scope) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(if (busy) "Enviando…" else "Adicionar à lista +") }
    }
}

private fun submitQuick(repo: OnlineRepository, text: String, busy: (Boolean) -> Unit, message: (String) -> Unit, clear: (String) -> Unit, close: () -> Unit, keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?, scope: kotlinx.coroutines.CoroutineScope) {
    if (text.isBlank()) { message("Digite pelo menos um item."); return }
    busy(true); message("")
    scope.launch {
        when (val result = repo.execute(AddItems.fromText(text))) {
            is ActionResult.Success -> { clear(""); message("Adicionado à lista"); keyboard?.hide(); kotlinx.coroutines.delay(1500); close() }
            is ActionResult.Invalid -> message("Digite até 30 itens separados por vírgula, ponto e vírgula ou linha.")
            else -> message(OnlineMessages.failure(result, keepsText = true))
        }
        busy(false)
    }
}
