package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.HouseAccess

/**
 * The only part of [HouseAccess] the online client needs: the credential to use right now and a way to
 * say the server refused it. A narrow seam, so the repository is tested without a vault.
 */
interface OnlineAccess {
    /** The credential the server may be asked with, or null when there is none (not connected, revoked). */
    suspend fun credential(): AccessCredential?

    /** The server answered 401/403 to [credential]: the app must offer to reconnect. */
    suspend fun reportUnauthorized(credential: AccessCredential)

    companion object {
        fun of(access: HouseAccess): OnlineAccess = object : OnlineAccess {
            override suspend fun credential() = access.credentialForSync()
            override suspend fun reportUnauthorized(credential: AccessCredential) = access.reportUnauthorized(credential)
        }
    }
}
