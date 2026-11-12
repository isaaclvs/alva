package net.ts.taild80ca0.atlas.twa.access

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entry screen says what the PWA's login page says: this reads `sessions/new.html.erb` and
 * `SessionsController` from the repository, so changing a word there without copying it here fails.
 */
class LoginParityTest {
    private fun repoFile(path: String): File =
        listOf("../../$path", "../$path", path).map(::File).firstOrNull { it.isFile }
            ?: error("$path not found from ${File(".").absoluteFile}; run the tests from the repository checkout")

    private val view by lazy { repoFile("app/views/sessions/new.html.erb").readText() }
    private val controller by lazy { repoFile("app/controllers/sessions_controller.rb").readText() }

    @Test
    fun theLoginPageTextsAreTheOnesOfTheView() {
        assertTrue(view.contains("<span class=\"text-2xl font-bold\">${AccessMessages.BRAND}</span>"))
        assertTrue(view.contains(">${AccessMessages.TITLE}</h1>"))
        assertTrue(view.contains(">${AccessMessages.HELP}</p>"))
        assertTrue(view.contains("\"${AccessMessages.PASSWORD_LABEL}\""))
        assertTrue(view.contains("form.submit \"${AccessMessages.LOGIN_BUTTON}\""))
    }

    @Test
    fun wrongPasswordAndTooManyAttemptsSayWhatTheServerSays() {
        val wrong = Regex("""render_login_error\("([^"]+)", :unprocessable_entity\)""").find(controller)!!.groupValues[1]
        val limited = Regex("""render_login_error\("([^"]+)", :too_many_requests\)""").find(controller)!!.groupValues[1]

        assertEquals(wrong, AccessMessages.forError(ConnectError.Server(AccessError.WrongPassword)))
        assertEquals(limited, AccessMessages.forError(ConnectError.Server(AccessError.RateLimited(null))))
        assertEquals(limited, AccessMessages.forError(ConnectError.Server(AccessError.RateLimited(60))))
    }

    @Test
    fun noNetworkAndServerTroubleAreShownInPlainPortuguese() {
        val network = AccessMessages.forError(ConnectError.Server(AccessError.Network))
        assertEquals("Sem conexão com a casa. Confira a internet e tente de novo.", network)
        assertTrue(AccessMessages.forError(ConnectError.Server(AccessError.ServerUnavailable)).isNotBlank())
    }
}
