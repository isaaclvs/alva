package net.ts.taild80ca0.atlas.twa.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the app knows about its access to the household, with no network involved. */
sealed interface AccessState {
    /** This device was never connected: ask for the shared password once. */
    data object NotConfigured : AccessState

    /** A credential is stored: open straight into the app. The password is not involved. */
    data object Connected : AccessState

    /**
     * The credential was refused or is gone. The local database and queue are
     * untouched; connecting again (same household only) keeps them.
     */
    data object NeedsReconnect : AccessState

    /** The secure storage could not be read right now. Nothing is assumed. */
    data object StorageUnavailable : AccessState
}

sealed interface ConnectResult {
    data class Connected(val householdPublicId: String) : ConnectResult

    /** Nothing changed on this device; [error] says why. */
    data class Failed(val error: ConnectError) : ConnectResult
}

sealed interface ConnectError {
    data object EmptyPassword : ConnectError

    data class Server(val error: AccessError) : ConnectError

    /** The server belongs to a household other than the one this device's data belongs to. */
    data object OtherHousehold : ConnectError

    /** The credential could not be stored safely, so it was not accepted. */
    data object StorageFailed : ConnectError
}

/**
 * Connects the device to the household once, and keeps the access afterwards.
 *
 * - **First setup / reconnect** is the only time the shared password is used. It
 *   goes to `POST /api/v1/devices` and is not kept: not in the vault, not in the
 *   database, not in this object's state.
 * - **Everyday opening** ([state]) reads only the vault. No network, no password.
 * - **Reconnecting** sends the old secret as `previous_token`, so the server keeps
 *   the device's lineage and commands it already sent are not repeated. The old
 *   secret is only replaced after the new credential is safely stored and the
 *   household is confirmed, so a lost response never abandons the identity.
 * - The local database is never touched except for `sync_state.household_public_id`
 *   and only through [HouseholdBinding].
 *
 * Calls are serialized: two taps never pair twice.
 */
class HouseAccess(
    private val vault: CredentialVault,
    private val api: HouseAccessApi,
    private val binding: HouseholdBinding,
    private val deviceName: String = DEFAULT_DEVICE_NAME,
    private val onConnected: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AccessState?>(null)

    /** `null` until [refresh] has read the vault the first time. */
    val state: StateFlow<AccessState?> = _state.asStateFlow()

    /** Reads the vault (local only) and publishes the resulting state. */
    suspend fun refresh(): AccessState = mutex.withLock { readState() }

    /**
     * The credential sync may use, or `null` when it must not (not connected,
     * revoked, storage unavailable). The sender (#45) asks this, never the vault.
     */
    suspend fun credentialForSync(): AccessCredential? = mutex.withLock {
        val read = vault.read()
        (read as? VaultRead.Found)?.access?.takeIf { !it.needsReconnect }?.active
    }

    /**
     * Records that the server refused [credential] (HTTP 401/403 on a sync call).
     * Only the credential currently in use is flagged: a late answer for an older
     * one cannot knock out a freshly reconnected device. The secret is kept for
     * `previous_token`.
     */
    suspend fun reportUnauthorized(credential: AccessCredential) {
        mutex.withLock {
            val found = vault.read() as? VaultRead.Found ?: return
            if (found.access.active != credential || found.access.needsReconnect) return
            flagReconnect(found.access)
            readState()
        }
    }

    /**
     * Asks the server whether the stored credential still works. A refusal flags
     * the device for reconnection; a network failure changes nothing.
     */
    suspend fun checkConnection(): ConnectResult = mutex.withLock {
        val access = (vault.read() as? VaultRead.Found)?.access
        val credential = access?.active?.takeIf { !access.needsReconnect }
        if (access == null || credential == null) {
            readState()
            return@withLock failed(AccessError.Unauthorized)
        }
        when (val identity = api.identifyHousehold(credential)) {
            is ApiResult.Ok -> when (binding.bind(identity.value.publicId)) {
                BindResult.Bound -> ConnectResult.Connected(identity.value.publicId)
                BindResult.OtherHousehold -> ConnectResult.Failed(ConnectError.OtherHousehold)
            }
            is ApiResult.Failed -> {
                if (identity.error == AccessError.Unauthorized) {
                    flagReconnect(access)
                    readState()
                }
                failed(identity.error)
            }
        }
    }

    /**
     * First setup and reconnection alike. [password] is used for this call only.
     */
    suspend fun connect(password: String): ConnectResult {
        val result = mutex.withLock {
            val connected = doConnect(password)
            readState()
            connected
        }
        if (result is ConnectResult.Connected) onConnected()
        return result
    }

    private suspend fun doConnect(password: String): ConnectResult {
        if (password.isBlank()) return ConnectResult.Failed(ConnectError.EmptyPassword)

        val stored = when (val read = vault.read()) {
            is VaultRead.Found -> read.access
            VaultRead.Empty, VaultRead.Lost -> null // Lost: the old secret is unrecoverable
            VaultRead.Unavailable -> return ConnectResult.Failed(ConnectError.StorageFailed)
        }

        // Already connected (for example a second tap queued behind the first): nothing to do,
        // and no second device is minted.
        if (stored != null && stored.active != null && !stored.needsReconnect && stored.pending == null) {
            binding.current()?.let { return ConnectResult.Connected(it) }
        }

        // A credential from an interrupted attempt is finished first: it is the newest
        // member of this device's lineage and spares another pairing (rate limited).
        var previous = stored?.active
        stored?.pending?.let { pending ->
            when (val identity = api.identifyHousehold(pending)) {
                is ApiResult.Ok -> return finish(pending, identity.value, stored)
                is ApiResult.Failed -> {
                    if (identity.error != AccessError.Unauthorized) return failed(identity.error)
                    previous = pending // refused, but still proof of the lineage
                }
            }
        }

        val paired = when (val result = api.pair(password, deviceName, previous?.token)) {
            is ApiResult.Ok -> result.value
            is ApiResult.Failed -> return ConnectResult.Failed(ConnectError.Server(result.error))
        }

        // Safe before anything else can fail: the server will not show this secret again.
        val withPending = StoredAccess(active = previous, pending = paired, needsReconnect = stored?.needsReconnect ?: false)
        try {
            vault.write(withPending)
        } catch (_: VaultWriteException) {
            return ConnectResult.Failed(ConnectError.StorageFailed)
        }

        return when (val identity = api.identifyHousehold(paired)) {
            is ApiResult.Ok -> finish(paired, identity.value, withPending)
            is ApiResult.Failed -> failed(identity.error) // `pending` stays for the next attempt
        }
    }

    private suspend fun finish(
        credential: AccessCredential,
        identity: HouseholdIdentity,
        stored: StoredAccess,
    ): ConnectResult {
        return when (binding.bind(identity.publicId)) {
            BindResult.OtherHousehold -> {
                // Never keep another household's credential: drop it, keep what the device had.
                try {
                    vault.write(stored.copy(pending = null))
                } catch (_: VaultWriteException) {
                }
                ConnectResult.Failed(ConnectError.OtherHousehold)
            }
            BindResult.Bound -> try {
                vault.write(StoredAccess(active = credential, pending = null, needsReconnect = false))
                ConnectResult.Connected(identity.publicId)
            } catch (_: VaultWriteException) {
                ConnectResult.Failed(ConnectError.StorageFailed)
            }
        }
    }

    /** Advisory: if it cannot be written, the next refused call reports it again. */
    private fun flagReconnect(access: StoredAccess) {
        try {
            vault.write(access.copy(needsReconnect = true))
        } catch (_: VaultWriteException) {
        }
    }

    private suspend fun readState(): AccessState {
        val state = when (val read = vault.read()) {
            VaultRead.Empty -> if (binding.current() != null) AccessState.NeedsReconnect else AccessState.NotConfigured
            VaultRead.Lost -> if (binding.current() != null) AccessState.NeedsReconnect else AccessState.NotConfigured
            VaultRead.Unavailable -> AccessState.StorageUnavailable
            is VaultRead.Found -> when {
                read.access.needsReconnect -> AccessState.NeedsReconnect
                read.access.active != null -> AccessState.Connected
                else -> if (binding.current() != null) AccessState.NeedsReconnect else AccessState.NotConfigured
            }
        }
        _state.value = state
        return state
    }

    private fun failed(error: AccessError) = ConnectResult.Failed(ConnectError.Server(error))

    companion object {
        const val DEFAULT_DEVICE_NAME = "Alva Android"
    }
}
