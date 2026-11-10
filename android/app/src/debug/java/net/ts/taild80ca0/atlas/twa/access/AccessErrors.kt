package net.ts.taild80ca0.atlas.twa.access

/**
 * Why talking to the server failed, in terms the app can act on. Carries no
 * HTTP detail, no response body and no secret, so any of them is safe to log or
 * show.
 */
sealed interface AccessError {
    /** 401 on pairing: the shared password is wrong. */
    data object WrongPassword : AccessError

    /** 403 `sync_unavailable`: the server has no shared password configured. */
    data object SyncUnavailable : AccessError

    /** 429 `rate_limited`. */
    data class RateLimited(val retryAfterSeconds: Int?) : AccessError

    /** 422 `invalid_previous_token`: the old credential is not from this household. */
    data object InvalidPreviousToken : AccessError

    /** 401/403 on an authenticated call: revoked, rotated or not a `sync` credential. */
    data object Unauthorized : AccessError

    /** 5xx or 503-style trouble: retry later. */
    data object ServerUnavailable : AccessError

    /** No connectivity, DNS, timeout or connection reset. */
    data object Network : AccessError

    /** The TLS handshake or certificate was refused. Never retried insecurely. */
    data object Tls : AccessError

    /** The response did not match the contract (shape, status). */
    data object UnexpectedResponse : AccessError
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    data class Failed(val error: AccessError) : ApiResult<Nothing>
}
