package net.ts.taild80ca0.atlas.twa.sync

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SyncStatus {
    /** Nothing is running. [last] is the report of the latest cycle that did something, null before the first. */
    data class Idle(val last: SyncReport?) : SyncStatus

    data class Running(val last: SyncReport?) : SyncStatus
}

/**
 * The trigger of [SyncCycle] for the screens: opening or resuming the app asks for a cycle, and the user can ask
 * again with a button. The background worker shares this controller and its reports. It lives as long as the
 * process (it is not tied to a screen),
 * so rotating the phone does not cancel a cycle, and a cycle that the system kills is recovered by the next one
 * from what the database holds (attempts are committed before their request).
 *
 * An automatic request (resume) is ignored while a cycle runs or if the previous one started less than
 * [autoIntervalMillis] ago, so coming back to the app does not hammer the server; a manual one only waits for
 * the running cycle.
 */
class SyncController(
    private val cycle: SyncRunner,
    private val scope: CoroutineScope,
    private val autoIntervalMillis: Long = 15_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val busy = AtomicBoolean(false)
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle(null))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    @Volatile
    private var lastStart: Long? = null

    /** Returns true when a cycle was started. */
    fun request(manual: Boolean): Boolean {
        if (!manual) {
            val started = lastStart
            if (started != null && clock() - started < autoIntervalMillis) return false
        }
        if (!busy.compareAndSet(false, true)) return false
        lastStart = clock()
        val previous = startReport()
        scope.launch { finishRun(previous) }
        return true
    }

    /** A WorkManager invocation must await completion, rather than launching an unowned coroutine. */
    suspend fun runInBackground(): SyncReport {
        if (!busy.compareAndSet(false, true)) return SyncReport(SyncOutcome.AlreadyRunning)
        lastStart = clock()
        return finishRun(startReport())
    }

    private fun startReport(): SyncReport? {
        val previous = _status.value.let { (it as? SyncStatus.Idle)?.last ?: (it as? SyncStatus.Running)?.last }
        _status.value = SyncStatus.Running(previous)
        return previous
    }

    private suspend fun finishRun(previous: SyncReport?): SyncReport {
        var report = previous
        try {
            val result = cycle.run()
            report = result.takeIf { it.outcome != SyncOutcome.AlreadyRunning } ?: previous
            return result
        } finally {
            _status.value = SyncStatus.Idle(report)
            busy.set(false)
        }
    }
}

/** What to tell the person, in Portuguese and without codes, ids or server text. */
object SyncMessages {
    fun describe(report: SyncReport): String {
        val base = when (val outcome = report.outcome) {
            is SyncOutcome.Synced -> "Sincronizado."
            SyncOutcome.AlreadyRunning -> "Sincronizando…"
            SyncOutcome.NotConnected -> "Sem acesso à casa: reconecte pelo menu ⋯. O que você adicionou continua salvo neste aparelho."
            is SyncOutcome.Offline -> "Sem conexão com o servidor. O que você adicionou continua salvo neste aparelho e será enviado depois."
            is SyncOutcome.SnapshotHeld ->
                "Aguardando o servidor confirmar ${plural(outcome.commandIds.size, "alteração enviada", "alterações enviadas")}. " +
                    "A lista não foi atualizada para não perder nada; tente de novo."
            is SyncOutcome.SnapshotNotInstalled -> "A lista recebida do servidor não foi aplicada. Nada foi apagado; tente de novo."
            is SyncOutcome.SnapshotUnusable -> "O servidor respondeu algo inesperado. Nada foi alterado neste aparelho."
            is SyncOutcome.StorageFailed -> "Não foi possível gravar a atualização neste aparelho. Nada foi perdido; tente de novo."
        }
        val extras = buildList {
            if (report.needsAttention > 0) {
                add("${plural(report.needsAttention, "alteração precisa", "alterações precisam")} de atenção (guardada${if (report.needsAttention > 1) "s" else ""} neste aparelho)")
            }
            if (report.stalled > 0) add("${plural(report.stalled, "alteração ainda não pôde", "alterações ainda não puderam")} ser enviada${if (report.stalled > 1) "s" else ""}")
        }
        return if (extras.isEmpty()) base else base + " " + extras.joinToString(". ", postfix = ".")
    }

    /** True when the label of the button should say "Tentar novamente" rather than "Sincronizar agora". */
    fun isTrouble(report: SyncReport?): Boolean = report != null && report.outcome !is SyncOutcome.Synced

    private fun plural(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "$count $many"
}
