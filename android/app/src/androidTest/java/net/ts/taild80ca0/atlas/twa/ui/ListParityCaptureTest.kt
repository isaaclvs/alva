package net.ts.taild80ca0.atlas.twa.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import net.ts.taild80ca0.atlas.twa.MainActivity
import net.ts.taild80ca0.atlas.twa.access.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.online.FakeHouse
import net.ts.taild80ca0.atlas.twa.online.OnlineAccess
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Not a regression test: it renders the Lista screen with the same sample list as the PWA captures, at the
 * PWA's 412×915 dp, in the four theme/color combinations, and writes PNGs for the side-by-side comparison
 * (`/home/isaac/alva-captures/98`). Runs only with `-Pandroid.testInstrumentationRunnerArguments.captureDir=…`
 * (a directory on the device that the shell can write, e.g. /sdcard/Download/alva98); otherwise it is skipped.
 */
class ListParityCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val args = InstrumentationRegistry.getArguments()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dark by mutableStateOf(true)
    private var color by mutableStateOf(AlvaColor.Teal)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun stop() {
        scenario?.close()
        AccessGraph.install(null)
        scope.cancel()
    }

    private fun shell(command: String, input: ByteArray? = null) {
        val (out, stdin) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw(command)
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { if (input != null) it.write(input) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    private fun save(dir: String, name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("parity-root").captureToImage().asAndroidBitmap()
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        shell("dd of=$dir/$name", png)
    }

    @Test fun capturesTheListInTheFourThemes() {
        val dir = args.getString("captureDir")
        assumeTrue("needs -Pandroid.testInstrumentationRunnerArguments.captureDir", dir != null)
        shell("mkdir -p $dir")

        val house = FakeHouse()
        val id = { n: Int -> "c0000000-0000-4000-8000-%012d".format(n) }
        house.categories = listOf(
            "Hortifruti", "Laticínios", "Mercearia", "Açougue", "Padaria", "Bebidas", "Limpeza", "Higiene", "Outros",
        ).mapIndexed { index, name -> SnapshotFixtures.category(id(index + 1), name, index + 1) }
        fun seed(name: String, category: Int, bought: Boolean = false) =
            house.seed(name, status = if (bought) "purchased" else "pending", category = id(category))
        seed("Banana", 1); seed("Maçã", 1, true)
        seed("Iogurte", 2); seed("Queijo", 2); seed("Leite", 2, true)
        seed("Arroz", 3); seed("Café", 3); seed("Ovos", 3)
        seed("Pão", 5)
        seed("Água", 6, true)
        seed("Shampoo", 8)
        seed("Pilha", 9); seed("Sabão", 9)

        val credential = AccessCredential(1, "capture-only")
        val repository = OnlineRepository(
            object : OnlineAccess {
                override suspend fun credential() = credential
                override suspend fun reportUnauthorized(credential: AccessCredential) = Unit
            },
            house, house, scope, pause = {},
        )
        // MainActivity only hosts the content; what is captured is ShoppingScreens over the in-memory server.
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(credential))
            override fun write(access: StoredAccess) = error("capturing must not write credentials")
        }
        val offlineApi = object : HouseAccessApi {
            override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("no HTTP")
            override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("no HTTP")
        }
        AccessGraph.install(HouseAccess(vault, offlineApi, InMemoryHouseholdBinding()), online = repository)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity -> activity.setContent {
            AlvaTheme(dark, color) {
                Box(Modifier.size(412.dp, 915.dp).consumeWindowInsets(WindowInsets.safeDrawing).testTag("parity-root")) {
                    Surface(color = MaterialTheme.colorScheme.background) { ShoppingScreens(repository) }
                }
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-list").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("list-summary").fetchSemanticsNodes().isNotEmpty() }

        for (isDark in listOf(true, false)) for (accent in AlvaColor.entries) {
            dark = isDark; color = accent
            save(dir!!, "${if (isDark) "dark" else "light"}-${accent.name.lowercase()}-list.png")
        }

        // The undo toast, in the default theme.
        dark = true; color = AlvaColor.Teal
        val queijo = house.named("Queijo").single()
        compose.onNodeWithTag("item-${queijo.id}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("toast-undo").fetchSemanticsNodes().isNotEmpty() }
        save(dir!!, "dark-teal-list-toast.png")
    }
}
