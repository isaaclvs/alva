package net.ts.taild80ca0.atlas.twa.access

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The entry screen's states, without a server: what it shows and what it hands to its caller. */
class EntryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var working by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private val sent = mutableListOf<String>()
    private var dark by mutableStateOf(true)
    private var color by mutableStateOf(AlvaColor.Teal)

    private fun show() {
        compose.setContent {
            AlvaTheme(dark, color) { EntryScreen(working, message, onSubmit = { sent += it }) }
        }
    }

    private val passwordText: String
        get() = compose.onNodeWithTag("password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun showsTheMarkTheTitleTheExplanationAndTheLabelledField() {
        show()

        compose.onNodeWithText(AccessMessages.BRAND).assertIsDisplayed()
        compose.onNodeWithText("Entre na lista de casa").assertIsDisplayed()
        compose.onNodeWithText("Use a senha da casa. Este aparelho ficará conectado por 30 dias.").assertIsDisplayed()
        compose.onNodeWithText("Senha da casa").assertIsDisplayed()
        compose.onNodeWithTag("password").assertIsDisplayed().assertContentDescriptionEquals("Senha da casa")
        compose.onNodeWithText("Entrar").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onAllNodesWithTagCount("message", 0)
    }

    @Test
    fun theFieldIsFocusedFromTheStartLikeTheAutofocusOfThePage() {
        show()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("password") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aWrongPasswordIsShownAboveTheFieldAndTheFieldIsEmpty() {
        show()
        compose.onNodeWithTag("password").performTextInput("errada")
        compose.onNodeWithTag("connect").performScrollTo().performClick()
        message = "Senha incorreta. Tente novamente."
        compose.waitForIdle()

        compose.onNodeWithTag("message").assertIsDisplayed().assertTextEquals("Senha incorreta. Tente novamente.")
        assertEquals("", passwordText)
        compose.onNodeWithTag("connect").assertIsEnabled()
    }

    @Test
    fun tooManyAttemptsAndNoNetworkUseThePwaWordsOrPlainEquivalents() {
        show()
        for (error in listOf(AccessError.RateLimited(120), AccessError.Network)) {
            message = AccessMessages.forError(ConnectError.Server(error))
            compose.waitForIdle()
            compose.onNodeWithTag("message").assertIsDisplayed().assertTextEquals(message!!)
        }
        compose.onNodeWithTag("message").assertTextContains("Sem conexão com a casa", substring = true)
    }

    @Test
    fun whileConnectingTheFieldAndTheButtonAreDisabled() {
        working = true
        show()

        compose.onNodeWithText(AccessMessages.LOGGING_IN).assertIsDisplayed()
        compose.onNodeWithTag("connect").assertIsNotEnabled()
        compose.onNodeWithTag("password").assertIsNotEnabled()
    }

    @Test
    fun theButtonSendsThePasswordOnceAndClearsTheField() {
        show()
        compose.onNodeWithTag("password").performTextInput("senha-da-casa")

        compose.onNodeWithTag("connect").performScrollTo().performClick()

        assertEquals(listOf("senha-da-casa"), sent)
        assertEquals("", passwordText)
    }

    @Test
    fun theKeyboardsDoneKeySendsItToo() {
        show()
        compose.onNodeWithTag("password").performTextInput("outra-senha")

        compose.onNodeWithTag("password").performImeAction()

        assertEquals(listOf("outra-senha"), sent)
    }

    @Test
    fun thePasswordIsMaskedOnScreen() {
        show()
        compose.onNodeWithTag("password").performTextInput("segredo")

        assertTrue(SemanticsProperties.Password in compose.onNodeWithTag("password").fetchSemanticsNode().config)
    }

    @Test
    fun rendersInEveryThemeAndColor() {
        message = "Senha incorreta. Tente novamente."
        show()
        for (isDark in listOf(true, false)) for (accent in AlvaColor.entries) {
            dark = isDark
            color = accent
            compose.waitForIdle()
            compose.onNodeWithText("Entre na lista de casa").assertIsDisplayed()
            compose.onNodeWithTag("message").assertIsDisplayed()
            compose.onNodeWithText("Entrar").performScrollTo().assertIsDisplayed()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, expected: Int) {
        assertEquals(expected, onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size)
    }
}
