package net.ts.taild80ca0.atlas.twa.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.ts.taild80ca0.atlas.twa.access.AccessGraph
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.data.list.ShoppingListStore

fun interface SyncScheduler {
    suspend fun request()
}

/**
 * One durable chain. Appending instead of KEEP on a save closes the race where a running worker has
 * already read its last snapshot: that save must have a successor, not be silently ignored.
 * A failed/cancelled chain must not poison future saves, hence APPEND_OR_REPLACE.
 * Work input contains no tokens, household data or command documents: Room and Keystore own them.
 */
class BackgroundSync(private val manager: WorkManager) : SyncScheduler {
    override suspend fun request() = enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** Opening a process repairs a commit interrupted before enqueue, without extending a running chain. */
    suspend fun recover() = enqueue(ExistingWorkPolicy.KEEP)

    private suspend fun enqueue(policy: ExistingWorkPolicy) {
        // Once the local commit succeeded, activity cancellation must not interrupt persisting its wake-up.
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                manager.enqueueUniqueWork(NAME, policy, requestBuilder().build()).result.get()
            } catch (_: Exception) {
                // The local action is already saved. Never report it as failed (which invites duplicate input).
                // The next app opening repairs scheduling from the durable Room queue.
            }
        }
    }

    companion object {
        const val NAME = "alva-household-sync-v1"

        fun requestBuilder() = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    }
}

/** Schedules only committed mutations, without introducing network decisions into screens or Room. */
class SyncingShoppingListStore(
    private val local: ShoppingListStore,
    private val scheduler: SyncScheduler,
    private val onSaved: () -> Unit = {},
) : ShoppingListStore {
    override fun observe() = local.observe()

    override suspend fun execute(intent: ListIntent): IntentResult {
        val result = local.execute(intent)
        if (result is IntentResult.Saved) {
            scheduler.request()
            onSaved()
        }
        return result
    }
}

/** Only transient failures retry automatically; business conflicts and invalid answers need attention. */
fun SyncOutcome.shouldRetryInBackground(): Boolean = when (this) {
    SyncOutcome.AlreadyRunning -> true
    is SyncOutcome.Offline -> cause != TransientCause.TLS
    else -> false
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val controller = AccessGraph.syncController(applicationContext) ?: return Result.success()
        val report = controller.runInBackground()
        return if (report.outcome.shouldRetryInBackground()) Result.retry() else Result.success()
    }
}
