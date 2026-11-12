package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.access.*

sealed interface PdfResult {
    class Ready(val bytes: ByteArray) : PdfResult
    class Failed(val message: String) : PdfResult
}

/** Same authenticated household export as the PWA, fetched once, with no token in the URL or redirects. */
class PdfClient(endpoint: ServerEndpoint, private val access: OnlineAccess) {
    private val exchange = HttpExchange(endpoint, 10_000, 60_000, MAX_BYTES)
    suspend fun download(): PdfResult {
        val credential = access.credential() ?: return PdfResult.Failed(OnlineProblem.NO_ACCESS.message)
        return when (val result = exchange.execute("GET", PATH, credential.token, null, accept = "application/pdf")) {
            is HttpResult.Failed -> PdfResult.Failed(if (result.failure == HttpFailure.NETWORK || result.failure == HttpFailure.TIMEOUT)
                "Sem conexão com o servidor. Tente baixar o PDF novamente." else "Não foi possível baixar o PDF agora. Tente de novo.")
            is HttpResult.Received -> when (result.status) {
                401, 403 -> { access.reportUnauthorized(credential); PdfResult.Failed(OnlineProblem.NO_ACCESS.message) }
                200 -> if (result.body.size >= 5 && result.body.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)))
                    PdfResult.Ready(result.body) else PdfResult.Failed("O servidor não retornou um PDF válido. Tente de novo.")
                else -> PdfResult.Failed("Não foi possível baixar o PDF agora. Tente de novo.")
            }
        }
    }
    companion object {
        const val PATH = "/api/v1/list.pdf"
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
