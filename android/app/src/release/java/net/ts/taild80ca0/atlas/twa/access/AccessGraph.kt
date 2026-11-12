package net.ts.taild80ca0.atlas.twa.access

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.ts.taild80ca0.atlas.twa.BuildConfig
import net.ts.taild80ca0.atlas.twa.online.*
import net.ts.taild80ca0.atlas.twa.sync.*

/** Production graph: API v1 and an encrypted credential. No database, outbox or background worker. */
object AccessGraph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var access: HouseAccess? = null
    private var repository: OnlineRepository? = null
    private var invalidation: ForegroundInvalidation? = null
    private fun endpoint() = ServerEndpoint.parse(BuildConfig.SERVER_URL)
    @Synchronized fun houseAccess(context: Context): HouseAccess = access ?: HouseAccess(
        KeystoreCredentialVault(KeystoreCredentialVault.defaultFile(context)), HttpHouseAccessApi(endpoint()),
        DeviceHouseholdBinding(context.applicationContext), onSignedOut = { repository?.forget(); net.ts.taild80ca0.atlas.twa.widgets.WidgetRuntime.clear(context); net.ts.taild80ca0.atlas.twa.widgets.WidgetRuntime.updateAll(context) },
    ).also { access = it }
    @Synchronized fun onlineRepository(context: Context): OnlineRepository = repository ?: OnlineRepository(
        OnlineAccess.of(houseAccess(context)), HttpSnapshotTransport(endpoint()), HttpCommandTransport(endpoint()), scope,
    ).also { repository = it; net.ts.taild80ca0.atlas.twa.widgets.WidgetRuntime.bind(context, it) }
    @Synchronized fun foregroundInvalidation(context: Context): ForegroundInvalidation = invalidation ?: ForegroundInvalidation(
        houseAccess(context), endpoint(), onlineRepository(context), scope,
    ).also { invalidation = it }
}

/** Only the house identity is retained, to prevent silently switching households on reauthorization. */
internal class DeviceHouseholdBinding(context: Context) : HouseholdBinding {
    private val prefs = context.getSharedPreferences("household_identity", Context.MODE_PRIVATE)
    private val lock = Mutex()
    override suspend fun current(): String? = lock.withLock { prefs.getString("public_id", null) }
    override suspend fun bind(householdPublicId: String): BindResult = lock.withLock {
        val existing = prefs.getString("public_id", null)
        if (existing != null && existing != householdPublicId) return@withLock BindResult.OtherHousehold
        if (existing == null) withContext(Dispatchers.IO) {
            if (!prefs.edit().putString("public_id", householdPublicId).commit()) throw java.io.IOException("Household identity could not be stored")
        }
        BindResult.Bound
    }
}
