package net.ts.taild80ca0.atlas.twa.access

/**
 * The per-device sync credential issued by `POST /api/v1/devices`.
 *
 * It is a secret: [toString] never prints the token, so a credential cannot
 * leak through a log line, an exception message or a debugger string.
 */
class AccessCredential(val deviceId: Long, val token: String) {
    override fun equals(other: Any?): Boolean =
        other is AccessCredential && other.deviceId == deviceId && other.token == token

    override fun hashCode(): Int = 31 * deviceId.hashCode() + token.hashCode()

    override fun toString(): String = "AccessCredential(deviceId=$deviceId, token=<redacted>)"
}

/**
 * Everything the device keeps about its access, as one record so a single
 * atomic write moves it from one state to the next.
 *
 * - [active]: the credential sync uses. After a revocation it is **kept**: it is
 *   the `previous_token` that lets the server continue this device's lineage, so
 *   commands the old credential already sent still resolve instead of being
 *   duplicated.
 * - [pending]: a credential the server issued that has not yet been tied to the
 *   household. It is stored before the household is identified, so a network
 *   failure in between never throws away a credential (or its lineage).
 * - [needsReconnect]: the server refused [active] (revoked, or the shared
 *   password was rotated). The local database and queue stay as they are.
 */
data class StoredAccess(
    val active: AccessCredential?,
    val pending: AccessCredential? = null,
    val needsReconnect: Boolean = false,
)
