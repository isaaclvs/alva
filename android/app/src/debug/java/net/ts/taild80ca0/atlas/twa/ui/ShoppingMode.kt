package net.ts.taild80ca0.atlas.twa.ui

import android.content.SharedPreferences
import androidx.compose.runtime.*

internal const val SHOPPING_DURATION_MILLIS = 86_400_000L
internal const val SHOPPING_PREFERENCES = "shopping-preferences"
internal fun shoppingModeActive(until: Long, now: Long) = until > now && until - now <= SHOPPING_DURATION_MILLIS

/** A device preference, read before rendering either screen, never derived from a server snapshot. */
@Stable
internal class ShoppingMode(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    var until by mutableLongStateOf(prefs.getLong("until", 0L))
        private set
    var active by mutableStateOf(shoppingModeActive(until, now()))
        private set
    fun start() { until = now() + SHOPPING_DURATION_MILLIS; prefs.edit().putLong("until", until).apply(); active = true }
    fun exit() { until = 0; prefs.edit().remove("until").apply(); active = false }
    fun refresh() { if (!shoppingModeActive(until, now())) exit() }
}
