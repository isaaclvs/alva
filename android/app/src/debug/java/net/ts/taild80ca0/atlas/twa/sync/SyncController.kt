package net.ts.taild80ca0.atlas.twa.sync

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

/** Process-scoped sync. Resume is throttled; committed edits and restored connectivity are never dropped. */
class SyncController(
    private val cycle: SyncRunner,
    private val scope: CoroutineScope,
    private val autoIntervalMillis: Long = 15_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private var busy = false
    private var pending = false
    private var lastStart: Long? = null
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle(null))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    fun request(manual: Boolean): Boolean = enqueue(throttled = !manual, followUp = false)

    /** Called only after a local commit, or when connectivity returns. Coalesce events during a cycle. */
    fun changed(): Boolean = enqueue(throttled = false, followUp = true)

    private fun enqueue(throttled: Boolean, followUp: Boolean): Boolean = synchronized(lock) {
        if (busy) {
            if (followUp) pending = true
            return@synchronized false
        }
        val started = lastStart
        if (throttled && started != null && clock() - started < autoIntervalMillis) return@synchronized false
        busy = true
        lastStart = clock()
        val previous = (_status.value as SyncStatus.Idle).last
        _status.value = SyncStatus.Running(previous)
        scope.launch {
            var report = previous
            var completed = false
            try {
                do {
                    report = cycle.run().takeIf { it.outcome != SyncOutcome.AlreadyRunning } ?: report
                    val again = synchronized(lock) {
                        if (pending) {
                            pending = false
                            lastStart = clock()
                            _status.value = SyncStatus.Running(report)
                            true
                        } else {
                            _status.value = SyncStatus.Idle(report)
                            busy = false
                            false
                        }
                    }
                } while (again)
                completed = true
            } finally {
                synchronized(lock) {
                    // A normal completion already released the lock to a possible new request.
                    if (!completed) {
                        _status.value = SyncStatus.Idle(report)
                        busy = false
                        pending = false
                    }
                }
            }
        }
        true
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
