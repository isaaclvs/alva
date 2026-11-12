package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.data.list.ConflictResolver
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme

/**
 * An explicit review of local refusals and of changes whose result can no longer be confirmed, outside the add form
 * and without a modal. [onResolved] runs after a group left the queue (e.g. to ask for a sync: an uncertain group
 * was what held the server's list back).
 */
@Composable
fun ConflictPanel(resolver: ConflictResolver, onResolved: () -> Unit = {}) {
    var groups by remember(resolver) { mutableStateOf<List<ConflictResolver.Preview>>(emptyList()) }
    var selected by remember(resolver) { mutableStateOf<ConflictResolver.Preview?>(null) }
    var expanded by remember(resolver) { mutableStateOf(false) }
    var saving by remember(resolver) { mutableStateOf(false) }
    var message by remember(resolver) { mutableStateOf<String?>(null) }
    var readFailed by remember(resolver) { mutableStateOf(false) }
    var retry by remember(resolver) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(resolver, retry) {
        readFailed = false
        resolver.observe().catch { error ->
            if (error is CancellationException) throw error
            readFailed = true
        }.collect { groups = it }
    }
    if (groups.isEmpty() && selected == null && message == null && !readFailed) return

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("conflicts-panel")) {
        TextButton(onClick = {
            expanded = !expanded
            if (!expanded && !saving) { selected = null; message = null }
        }, enabled = !saving, modifier = Modifier.testTag("conflicts-expand")) {
            Text("Revisar alterações" + if (groups.isNotEmpty()) " (${groups.sumOf { it.commandIds.size }})" else "")
        }
        if (!expanded) return@Column
        message?.let {
            Text(it, modifier = Modifier.testTag("conflict-feedback").semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (readFailed) {
            Text("Não foi possível ler as alterações. Nada foi descartado.")
            TextButton(onClick = { retry++ }) { Text("Tentar novamente") }
        }
        // This value is the object selected by the person, never a replacement
        // from a subsequent Flow emission. discard revalidates its token.
        val review = selected
        if (review != null) {
            Column(Modifier.testTag("conflict-details")) {
                val uncertain = review.kind == ConflictResolver.Kind.UNCERTAIN
                review.intentions.forEach { intention -> Text(intention.description, modifier = Modifier.padding(vertical = 4.dp)) }
                Text(
                    if (uncertain) {
                        "Não dá para saber se estas alterações chegaram ao servidor. " +
                            "Usar a lista do servidor retira estas alterações deste aparelho e traz a lista da casa. " +
                            "Depois, confira a lista e adicione de novo o que faltar."
                    } else {
                        "Descartar retira estas alterações locais. A lista mantém o último estado recebido e as outras alterações locais."
                    },
                    style = AlvaTheme.type.caption, color = AlvaTheme.colors.inkSoft,
                )
                val count = review.commandIds.size
                Button(onClick = {
                    saving = true
                    scope.launch {
                        try {
                            when (resolver.discard(review)) {
                                is ConflictResolver.Result.Discarded -> {
                                    selected = null
                                    message = if (uncertain) "Pronto. Confira a lista e adicione de novo o que faltar." else "Alterações locais descartadas."
                                    onResolved()
                                }
                                ConflictResolver.Result.Stale -> {
                                    selected = null
                                    message = "As alterações mudaram. Revise novamente."
                                }
                                ConflictResolver.Result.NotDiscardable -> {
                                    selected = null
                                    message = "Não é possível descartar estas alterações agora. Nada foi alterado."
                                }
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            message = "Não foi possível descartar. Nada foi alterado; tente novamente."
                        } finally { saving = false }
                    }
                }, enabled = !saving && !readFailed, modifier = Modifier.testTag("conflict-discard")) {
                    Text(
                        when {
                            saving -> if (uncertain) "Atualizando…" else "Descartando…"
                            uncertain -> "Usar a lista do servidor"
                            count == 1 -> "Descartar 1 alteração"
                            else -> "Descartar $count alterações"
                        },
                    )
                }
                TextButton(onClick = { selected = null; message = null }, enabled = !saving, modifier = Modifier.testTag("conflict-cancel")) {
                    Text("Cancelar")
                }
            }
        } else {
            groups.forEach { group ->
                Text(group.intentions.first().description)
                if (group.kind == ConflictResolver.Kind.UNCERTAIN) {
                    Text("Sem confirmação do servidor", style = AlvaTheme.type.caption, color = AlvaTheme.colors.warn)
                }
                TextButton(onClick = { selected = group; message = null }, modifier = Modifier.testTag("conflict-review-${group.rootId}")) {
                    Text(if (group.commandIds.size == 1) "Revisar alteração" else "Revisar ${group.commandIds.size} alterações")
                }
            }
        }
    }
}
