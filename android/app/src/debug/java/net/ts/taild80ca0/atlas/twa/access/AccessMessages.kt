package net.ts.taild80ca0.atlas.twa.access

/**
 * The only texts access shows. Portuguese, short, and with no internal detail:
 * no status code, no field name, no exception, nothing about tokens or keys.
 */
object AccessMessages {
    const val SETUP_TITLE = "Conectar à casa"
    const val SETUP_HELP = "Digite a senha da casa uma vez. Depois o Alva abre direto."
    const val RECONNECT_TITLE = "Reconectar à casa"
    const val RECONNECT_HELP = "Este aparelho perdeu o acesso à casa. O que você adicionou aqui foi mantido."
    const val PASSWORD_LABEL = "Senha da casa"
    const val CONNECT_BUTTON = "Conectar"
    const val RECONNECT_BUTTON = "Reconectar"
    const val WORKING = "Conectando…"
    const val CONNECTED_TITLE = "Conectado à casa"
    const val CONNECTED_HELP = "Este aparelho já está conectado."
    const val CHECK_BUTTON = "Verificar conexão"
    const val CHECK_OK = "A conexão com a casa está funcionando."
    const val STORAGE_UNAVAILABLE = "Não foi possível acessar o armazenamento seguro agora. Tente de novo."

    fun forError(error: ConnectError): String = when (error) {
        ConnectError.EmptyPassword -> "Digite a senha da casa."
        ConnectError.OtherHousehold ->
            "Este aparelho guarda dados de outra casa. Nada foi misturado nem apagado."
        ConnectError.StorageFailed ->
            "Não foi possível guardar o acesso com segurança neste aparelho. Tente de novo."
        is ConnectError.Server -> forServer(error.error)
    }

    private fun forServer(error: AccessError): String = when (error) {
        AccessError.WrongPassword -> "Senha incorreta. Confira e tente de novo."
        AccessError.SyncUnavailable ->
            "A casa ainda não está pronta para conectar aparelhos. Fale com quem cuida do servidor."
        is AccessError.RateLimited -> "Muitas tentativas. Aguarde alguns minutos e tente de novo."
        AccessError.InvalidPreviousToken ->
            "Este aparelho não pôde ser reconectado a esta casa. O que você adicionou aqui foi mantido."
        AccessError.Unauthorized -> "Este aparelho perdeu o acesso à casa. Reconecte para continuar."
        AccessError.ServerUnavailable -> "A casa não respondeu agora. Tente de novo em instantes."
        AccessError.Network -> "Sem conexão com a casa. Confira a internet e tente de novo."
        AccessError.Tls -> "Não foi possível confirmar a segurança da conexão. Tente de novo mais tarde."
        AccessError.UnexpectedResponse -> "Algo deu errado ao conectar. Tente de novo."
    }
}
