package net.ts.taild80ca0.atlas.twa.access

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaBrandMark
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaShapes
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme

/**
 * The entry gate: the house password once, then straight into the app. [connected] shows the app (and gets
 * the actions of the house menu); every other state is the entry screen, the same one for a first opening,
 * a revoked device and "Sair" ([HouseAccess.signOut]), as the PWA has one login page for all of them.
 * The password lives only in the field of [EntryScreen] and is cleared as soon as it is sent.
 */
@Composable
fun AccessScreen(access: HouseAccess, connected: @Composable (@Composable () -> Unit) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val state by access.state.collectAsState()
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { access.refresh() }

    when (state) {
        AccessState.Connected -> connected {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        working = true
                        try {
                            message = when (val result = access.checkConnection()) {
                                is ConnectResult.Connected -> AccessMessages.CHECK_OK
                                is ConnectResult.Failed -> AccessMessages.forError(result.error)
                            }
                        } finally { working = false }
                    }
                },
                enabled = !working,
                modifier = Modifier.testTag("check"),
            ) { Text(AccessMessages.CHECK_BUTTON) }
            message?.let { Text(it, modifier = Modifier.testTag("message")) }
        }
        null -> Box(modifier.fillMaxSize().background(AlvaTheme.colors.canvas)) // the vault is being read: nothing to ask yet
        AccessState.StorageUnavailable -> Column(
            modifier = modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(AccessMessages.STORAGE_UNAVAILABLE, style = AlvaTheme.type.body, color = AlvaTheme.colors.ink)
            Button(onClick = { scope.launch { access.refresh() } }) { Text("Tentar de novo") }
        }
        AccessState.NotConfigured, AccessState.NeedsReconnect -> EntryScreen(
            working = working,
            message = message,
            onSubmit = { typed ->
                scope.launch {
                    working = true
                    message = null
                    val result = access.connect(typed)
                    message = (result as? ConnectResult.Failed)?.let { AccessMessages.forError(it.error) }
                    working = false
                }
            },
            modifier = modifier,
        )
    }
}

/** CSS gives every line its full `line-height`; Compose trims the first ascent and last descent by default. */
private fun TextStyle.fullLine(): TextStyle =
    copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))

/** Tailwind's `text-2xl font-bold` of the login page: no tracking, unlike the `heading` token. */
private fun loginHeading(): TextStyle = AlvaTheme.type.heading.copy(fontWeight = FontWeight.Bold, letterSpacing = TextUnit.Unspecified).fullLine()

/**
 * `sessions/new.html.erb` in Compose: the mark and "Alva", the title, the explanation, the error in
 * `text-danger` (a live region, like `role="alert"`), then the labelled password field and "Entrar".
 * Spacing, sizes and colors are the PWA's (`max-w-md px-6 py-16`, `mb-8`, `mt-8 space-y-4`, `min-h-12 rounded-xl`).
 * [onSubmit] gets the typed password; the field is emptied as it is sent.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EntryScreen(working: Boolean, message: String?, onSubmit: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = AlvaTheme.colors
    var password by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val submit = {
        if (!working) {
            val typed = password
            password = ""
            onSubmit(typed)
        }
    }
    // `autofocus` on the field, and again after each attempt (a disabled field drops focus, a new page would regain it).
    LaunchedEffect(working) { if (!working) focus.requestFocus() }

    Box(modifier.fillMaxSize().background(colors.canvas).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 448.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 64.dp)) {
            Row(Modifier.padding(bottom = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AlvaBrandMark(size = 48.dp)
                Text(AccessMessages.BRAND, style = loginHeading(), color = colors.ink, modifier = Modifier.height(32.dp))
            }
            Text(AccessMessages.TITLE, style = loginHeading(), color = colors.ink, modifier = Modifier.height(32.dp).semantics { heading() }.testTag("title"))
            Text(AccessMessages.HELP, style = AlvaTheme.type.bodySmall.fullLine(), color = colors.inkSoft, modifier = Modifier.padding(top = 8.dp).testTag("help"))
            if (message != null) {
                Text(
                    message,
                    style = AlvaTheme.type.bodySmall.fullLine(),
                    color = colors.danger,
                    modifier = Modifier.padding(top = 24.dp).semantics { liveRegion = LiveRegionMode.Assertive }.testTag("message"),
                )
            }
            Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(AccessMessages.PASSWORD_LABEL, style = AlvaTheme.type.bodySmall.copy(fontWeight = FontWeight.Medium).fullLine(), color = colors.ink, modifier = Modifier.height(20.dp))
                BasicTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !working,
                    singleLine = true,
                    textStyle = AlvaTheme.type.body.copy(color = colors.ink).fullLine(),
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .background(colors.surface, AlvaShapes.Control)
                        .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.line, AlvaShapes.Control)
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics {
                            contentType = ContentType.Password
                            contentDescription = AccessMessages.PASSWORD_LABEL
                        }
                        .testTag("password"),
                    decorationBox = { field ->
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) { field() }
                    },
                )
                Button(
                    onClick = { submit() },
                    enabled = !working,
                    shape = AlvaShapes.Control,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent, contentColor = colors.accentInk,
                        disabledContainerColor = colors.accent.copy(alpha = 0.6f), disabledContentColor = colors.accentInk,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connect"),
                ) {
                    Text(if (working) AccessMessages.LOGGING_IN else AccessMessages.LOGIN_BUTTON, style = AlvaTheme.type.body.copy(fontWeight = FontWeight.SemiBold).fullLine())
                }
            }
        }
    }
}
