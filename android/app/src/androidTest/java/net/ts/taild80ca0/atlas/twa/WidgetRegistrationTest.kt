package net.ts.taild80ca0.atlas.twa

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Verifies the actual installed manifest and resources as a launcher sees them. */
class WidgetRegistrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everyInitialLayoutCanBeInflatedByARemoteViewsHost() {
        val providers = AppWidgetManager.getInstance(context).installedProviders
            .filter { it.provider.packageName == context.packageName }
        assertEquals(3, providers.size)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            providers.forEach { provider ->
                val root = RemoteViews(context.packageName, provider.initialLayout).apply(context, FrameLayout(context))
                assertNotNull(provider.provider.className, root)
            }
        }
    }

    @Test fun launcherOffersThreeDistinctResizableWidgetsAndHabitualConfiguration() {
        val providers = AppWidgetManager.getInstance(context).installedProviders
            .filter { it.provider.packageName == context.packageName }
        assertEquals("The launcher must offer all three widgets", 3, providers.size)
        assertEquals(3, providers.map { it.loadLabel(context.packageManager) }.distinct().size)
        val quick = providers.single { it.provider.className.endsWith(".QuickAddWidgetProvider") }
        assertTrue(quick.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0)
        assertNull("Adding text needs no setup", quick.configure)
        val shortcuts = providers.single { it.configure != null }
        val configure = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(shortcuts.configure)
        val activity = context.packageManager.resolveActivity(configure, 0)
        assertNotNull("The launcher must be able to open configuration", activity)
        assertTrue(activity!!.activityInfo.exported)
        providers.filter { it != quick }.forEach {
            assertTrue(it.minWidth > 0 && it.minHeight > 0)
            assertEquals(AppWidgetProviderInfo.RESIZE_BOTH, it.resizeMode)
            assertTrue("Periodic updates must respect Android's minimum", it.updatePeriodMillis >= 1_800_000)
        }
    }
}
