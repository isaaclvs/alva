package net.ts.taild80ca0.atlas.twa.widgets

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.app.PendingIntent
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ts.taild80ca0.atlas.twa.*
import net.ts.taild80ca0.atlas.twa.access.*
import net.ts.taild80ca0.atlas.twa.online.*
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaPalettes
import net.ts.taild80ca0.atlas.twa.sync.ServerSnapshot

/** Widget state is presentation only. Commands and undo receipts never survive process death. */
data class WidgetPalette(val background: Int, val surface: Int, val ink: Int, val soft: Int,
    val accent: Int, val accentInk: Int, val line: Int)

object WidgetRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actionLock = Mutex()
    private var observing: OnlineRepository? = null
    private val jobs = mutableListOf<Job>()
    @Volatile private var data: ServerSnapshot? = null
    @Volatile private var message: String? = null
    private val busy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var colors = AlvaPalettes.TealDark.let { WidgetPalette(it.canvas.toArgb(), it.surface.toArgb(),
        it.ink.toArgb(), it.inkSoft.toArgb(), it.accent.toArgb(), it.accentInk.toArgb(), it.line.toArgb()) }
    @Volatile var undo: WidgetUndo? = null
        private set

    @JvmStatic fun palette(context: Context): WidgetPalette = colors
    fun snapshot(context: Context): ServerSnapshot? = data
    fun status(context: Context): String? = message
    fun busyHabitual(context: Context, id: String) = "habitual:$id" in busy
    fun isBusy(id: String) = id in busy
    fun launch(context: Context, block: suspend () -> Unit) { scope.launch { block() } }

    /** Called once by the graph, so changes made in any app screen also redraw every widget. */
    @Synchronized fun bind(context: Context, repository: OnlineRepository) {
        if (observing === repository) return
        jobs.forEach { it.cancel() }; jobs.clear()
        if (observing != null) { clear(context); message = null }
        observing = repository
        val app = context.applicationContext
        jobs += scope.launch {
            DevicePreferencesStore(app).values.collect { saved ->
                val p = AlvaPalettes.of(saved.dark, saved.color)
                colors = WidgetPalette(p.canvas.toArgb(), p.surface.toArgb(), p.ink.toArgb(), p.inkSoft.toArgb(),
                    p.accent.toArgb(), p.accentInk.toArgb(), p.line.toArgb())
                updateAll(app)
            }
        }
        jobs += scope.launch {
            repository.state.collect { state ->
                if (observing !== repository) return@collect
                when (state) {
                    is OnlineState.Ready -> {
                        if (state.problem == OnlineProblem.NO_ACCESS || AccessGraph.houseAccess(app).state.value != AccessState.Connected) clear(app) else {
                            data = state.snapshot
                            session(app)?.let { WidgetDisplayCache.write(app, state.snapshot, it) }
                            if (busy.isEmpty() && (message == null || message == "Atualizando…" || message?.startsWith("Lista pode") == true))
                                message = state.problem?.let { "Lista pode estar desatualizada · Atualizar" }
                        }
                    }
                    is OnlineState.Failed -> {
                        if (state.problem == OnlineProblem.NO_ACCESS) clear(app)
                        message = state.problem.message
                    }
                    OnlineState.Loading -> Unit
                }
                updateAll(app)
            }
        }
        jobs += scope.launch {
            AccessGraph.houseAccess(app).state.collect { state ->
                if (state != null && state != AccessState.Connected) {
                    clear(app); message = "Conecte a casa pelo app"; updateAll(app)
                }
            }
        }
    }

    @JvmStatic fun refresh(context: Context) { scope.launch { refreshNow(context) } }
    @JvmStatic fun refresh(context: Context, pending: BroadcastReceiver.PendingResult) {
        scope.launch { try { withTimeout(8_000) { refreshNow(context) } }
            finally { pending.finish() } }
    }
    suspend fun refreshNow(context: Context) {
        val app = context.applicationContext
        val repository = AccessGraph.onlineRepository(app) ?: return
        bind(app, repository)
        if (AccessGraph.houseAccess(app).refresh() == AccessState.Connected) {
            if (data == null) { data = session(app)?.let { WidgetDisplayCache.read(app, it) }; if (data != null) message = "Atualizando…" }
            message = "Atualizando…"
            updateAll(app)
            try {
                repository.refresh()
                // StateFlow does not emit when the server returns the same snapshot.
                // Finish a manual refresh even when there was nothing to change.
                when (val current = repository.state.value) {
                    is OnlineState.Ready -> {
                        if (current.problem == OnlineProblem.NO_ACCESS || AccessGraph.houseAccess(app).state.value != AccessState.Connected) {
                            clear(app); message = OnlineProblem.NO_ACCESS.message
                        } else {
                            data = current.snapshot
                            if (busy.isEmpty()) message = current.problem?.let { "Lista pode estar desatualizada · Atualizar" }
                        }
                    }
                    is OnlineState.Failed -> message = current.problem.message
                    OnlineState.Loading -> message = "Não foi possível atualizar. Tente de novo."
                }
            } catch (cancelled: CancellationException) {
                if (message == "Atualizando…") message = "Lista pode estar desatualizada · Atualizar"
                throw cancelled
            } finally { updateAll(app) }
        } else { clear(app); message = "Conecte a casa pelo app"; updateAll(app) }
    }

    private suspend fun session(context: Context): String? = AccessGraph.houseAccess(context).credentialForSync()?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it.token.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }

    fun clear(context: Context) { data = null; undo = null; message = null; WidgetDisplayCache.clear(context) }
    /** Detach graph observers when instrumentation replaces the dependency graph. */
    @Synchronized fun resetForTests(context: Context) {
        jobs.forEach { it.cancel() }; jobs.clear(); observing = null
        clear(context); busy.clear()
    }
    fun updateAll(context: Context) {
        QuickAddWidgetProvider.refresh(context)
        ShoppingListWidgetProvider.update(context)
        HabitualWidgetProvider.update(context)
    }

    suspend fun addHabitual(context: Context, id: String) = action(context, "habitual:$id") {
        val repo = AccessGraph.onlineRepository(context) ?: return@action "Abra o app para conectar a casa"
        repo.refresh()
        val result = repo.execute(AddHabituals(listOf(id)))
        if (result is ActionResult.Success) OnlineMessages.added(result.receipt.addedItems()) else OnlineMessages.failure(result)
    }

    suspend fun mark(context: Context, id: String, version: Int, household: String) = action(context, id) {
        val repo = AccessGraph.onlineRepository(context) ?: return@action "Abra o app para conectar a casa"
        repo.refresh()
        val current = repo.state.value as? OnlineState.Ready
        if (current?.snapshot?.householdPublicId != household) return@action "A casa mudou. Atualize o widget."
        val result = repo.execute(SetItemState(id, net.ts.taild80ca0.atlas.twa.data.list.ItemStatus.PURCHASED, version))
        if (result is ActionResult.Success) {
            result.receipt.markedItem()?.let { marked ->
                undo = WidgetUndo(marked.publicId, marked.lockVersion, System.currentTimeMillis() + 5_000)
                scope.launch { delay(5_100); if (undo?.expiresAt?.let { it <= System.currentTimeMillis() } == true) {
                    undo = null; if (busy.isEmpty()) message = null; updateAll(context)
                } }
            }
            "Item marcado"
        } else OnlineMessages.failure(result)
    }

    suspend fun undoMark(context: Context, token: String) = action(context, "undo") {
        val saved = undo?.takeIf { it.token == token && it.expiresAt > System.currentTimeMillis() }
            ?: return@action "O prazo para desfazer terminou"
        undo = null
        val repo = AccessGraph.onlineRepository(context) ?: return@action "Abra o app para conectar a casa"
        val result = repo.execute(UndoItemState(saved.id, net.ts.taild80ca0.atlas.twa.data.list.ItemStatus.PENDING, null, saved.version))
        if (result is ActionResult.Success) "Marcação desfeita" else OnlineMessages.failure(result)
    }

    private suspend fun action(context: Context, key: String, block: suspend () -> String) {
        if (!busy.add(key)) return
        message = "Enviando…"; updateAll(context)
        try {
            actionLock.withLock { message = block() }
        } catch (e: CancellationException) { message = "Não foi possível confirmar. Atualize a lista antes de tentar de novo."; throw e }
        catch (_: Exception) { message = "Não foi possível atualizar. Tente de novo." }
        finally { busy.remove(key); updateAll(context) }
    }

    fun openList(context: Context): PendingIntent = PendingIntent.getActivity(context, 600,
        Intent(context, MainActivity::class.java).putExtra("widget_screen", "list")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

data class WidgetUndo(val id: String, val version: Int, val expiresAt: Long,
    val token: String = java.util.UUID.randomUUID().toString())
