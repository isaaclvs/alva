package net.ts.taild80ca0.atlas.twa

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.toArgb
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColors
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaPalettes
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaRadius
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaSpacing
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import net.ts.taild80ca0.atlas.twa.access.AccessGraph

/** Native online implementation under the existing component name: installed widgets keep their PendingIntent. */
class QuickAddActivity : ComponentActivity() {
    companion object {
        /**
         * Set by the "Adicionar item" launcher shortcut (#158): a device that is not connected to the house goes
         * to the entry screen instead of showing a panel that could not send the name.
         */
        const val EXTRA_OPEN_APP_WHEN_UNPAIRED = "open_app_when_unpaired"
    }

    private val model: QuickPanelModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, intent.extras) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                QuickPanelModel(handle, AccessGraph.houseAccess(applicationContext), AccessGraph.onlineRepository(applicationContext)) as T
        }
    }
    private var colors = AlvaPalettes.TealDark

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep component and view IDs so installed widgets and panel lifecycle remain compatible.
        setContentView(R.layout.quick_add_sheet)
        applyPalette(colors)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DevicePreferencesStore(applicationContext).values.collect { preferences ->
                    colors = AlvaPalettes.of(preferences.dark, preferences.color)
                    applyPalette(colors)
                }
            }
        }
        val input = findViewById<EditText>(R.id.quick_add_product)
        val submit = findViewById<Button>(R.id.quick_add_submit)
        val status = findViewById<TextView>(R.id.quick_add_status)
        // The launcher shortcut wants the panel only when there is a house to send to; otherwise the app's own
        // entry screen is the useful place, and it asks for the password once (#158).
        val openAppWhenUnpaired = intent.getBooleanExtra(EXTRA_OPEN_APP_WHEN_UNPAIRED, false)
        findViewById<View>(R.id.quick_add_password).visibility = View.GONE
        findViewById<View>(R.id.quick_add_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.quick_add_success_undo).setOnClickListener { model.undo() }
        input.doAfterTextChanged { model.edit(it?.toString().orEmpty()) }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { model.submit(); true } else false
        }
        submit.setOnClickListener {
            if (model.state.value.connected) model.submit()
            else startActivity(Intent(this, MainActivity::class.java))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var wasConfirmed = false
                model.state.collect { current ->
                    if (input.text.toString() != current.draft) { input.setText(current.draft); input.setSelection(input.length()) }
                    if (openAppWhenUnpaired && !current.connected && !current.loading) {
                        // The shortcut cannot do its job without the house: hand the person to the entry screen.
                        startActivity(Intent(this@QuickAddActivity, MainActivity::class.java))
                        finish()
                        return@collect
                    }
                    input.visibility = if (current.connected) View.VISIBLE else View.GONE
                    input.isEnabled = !current.busy && current.success == null
                    submit.isEnabled = !current.busy && !current.loading
                    submit.alpha = if (current.busy) .65f else 1f
                    submit.text = when {
                        current.loading -> "Abrindo…"
                        !current.connected -> "Abrir app"
                        current.busy -> "Enviando…"
                        else -> getString(R.string.quick_add_submit_label)
                    }
                    status.text = current.error ?: if (!current.connected && !current.loading) "Conecte este aparelho à casa pelo app principal." else ""
                    status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
                    status.setTextColor(if (current.error != null) colors.danger.toArgb() else colors.inkSoft.toArgb())
                    findViewById<View>(R.id.quick_add_form).visibility = if (current.success == null) View.VISIBLE else View.GONE
                    findViewById<View>(R.id.quick_add_success).visibility = if (current.success != null) View.VISIBLE else View.GONE
                    findViewById<TextView>(R.id.quick_add_success_message).text = current.success.orEmpty()
                    // Only a name this add created can be taken back while the panel is open.
                    val undo = findViewById<Button>(R.id.quick_add_success_undo)
                    undo.visibility = if (current.undo != null) View.VISIBLE else View.GONE
                    undo.isEnabled = !current.undoing
                    undo.alpha = if (current.undoing) .65f else 1f
                    // Taking the name back returns to the field, empty, for the one that was meant.
                    if (wasConfirmed && current.success == null && current.connected) focusInput()
                    wasConfirmed = current.success != null
                    if (current.success != null || !current.connected) keyboard().hideSoftInputFromWindow(input.windowToken, 0)
                    if (current.close) finish()
                }
            }
        }
    }
    /**
     * The sheet is drawn entirely from the app's design system: the layout has no color of its own, so
     * every piece below names a token of [palette] and a radius of [AlvaRadius]. Teal, pink, light and
     * dark therefore reach the panel the same way they reach the screens.
     */
    private fun applyPalette(palette: AlvaColors) {
        fun shape(fill: Int, radius: Float, border: Boolean = false) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * resources.displayMetrics.density
            if (border) setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), palette.line.toArgb())
        }
        val root = findViewById<View>(R.id.quick_add_panel)
        root.background = shape(palette.surface.toArgb(), AlvaRadius.sheet.value)
        val regular = ResourcesCompat.getFont(this, R.font.nunito_sans_regular)
        val bold = ResourcesCompat.getFont(this, R.font.nunito_sans_bold)
        fun textStyles(view: View) {
            if (view is TextView) {
                view.typeface = if (view.typeface?.isBold == true) bold else regular
                view.setTextColor(palette.inkSoft.toArgb())
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) textStyles(view.getChildAt(i))
        }
        textStyles(root)
        listOf(R.id.quick_add_title, R.id.quick_add_success_message).forEach {
            findViewById<TextView>(it).setTextColor(palette.ink.toArgb())
        }
        findViewById<TextView>(R.id.quick_add_brand).setTextColor(palette.accent.toArgb())
        findViewById<View>(R.id.quick_add_handle).setBackgroundColor(palette.line.toArgb())
        findViewById<EditText>(R.id.quick_add_product).apply {
            background = shape(palette.canvas.toArgb(), AlvaRadius.control.value, border = true)
            setTextColor(palette.ink.toArgb())
            setHintTextColor(palette.inkFaint.toArgb())
            // Selection and cursor follow the accent, not the dialog theme's own hue (the style names none).
            highlightColor = palette.accent.copy(alpha = 0.35f).toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) textCursorDrawable = cursor(palette.accent.toArgb())
        }
        // The password field is hidden since the panel sends through the paired house, but it keeps the
        // same look if a caller ever shows it (textStyles above already gave it the body colors).
        findViewById<EditText>(R.id.quick_add_password).apply {
            background = shape(palette.canvas.toArgb(), AlvaRadius.control.value, border = true)
            setTextColor(palette.ink.toArgb())
            setHintTextColor(palette.inkFaint.toArgb())
        }
        findViewById<Button>(R.id.quick_add_submit).apply {
            background = shape(palette.accent.toArgb(), AlvaRadius.control.value)
            setTextColor(palette.accentInk.toArgb())
        }
        findViewById<TextView>(R.id.quick_add_success_icon).apply {
            background = shape(palette.accentSoft.toArgb(), AlvaRadius.sheet.value)
            setTextColor(palette.accent.toArgb())
        }
        findViewById<Button>(R.id.quick_add_success_undo).apply {
            setTextColor(palette.accent.toArgb())
            background = ripple(palette.accent.toArgb(), AlvaRadius.control.value)
        }
        // The close button is borderless, so its ripple is a circle rather than a rounded rectangle.
        findViewById<Button>(R.id.quick_add_close).background = ripple(palette.accent.toArgb())
        findViewById<TextView>(R.id.quick_add_status).setTextColor(
            if (model.state.value.error != null) palette.danger.toArgb() else palette.inkSoft.toArgb())
    }

    /** The text cursor of the field, in the palette's accent; the dialog theme names no color of its own. */
    private fun cursor(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        setSize((2 * resources.displayMetrics.density).toInt(), 0)
    }

    /**
     * The press feedback of a button, in the palette's accent instead of the dialog theme's own
     * `colorControlHighlight`. Without [radius] the ripple is clipped to a circle (a borderless button).
     */
    private fun ripple(color: Int, radius: Float? = null): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = if (radius == null) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            if (radius != null) cornerRadius = radius * resources.displayMetrics.density
        }
        val pressed = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x29))
        return RippleDrawable(pressed, null, mask)
    }

    override fun onStart() {
        super.onStart()
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        val density = resources.displayMetrics.density
        window.setLayout(resources.displayMetrics.widthPixels - (AlvaSpacing.xxl.value * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply { y = (AlvaSpacing.md.value * density).toInt() }
    }
    override fun onResume() {
        super.onResume()
        model.resume()
        lifecycleScope.launch {
            // Focus before any network request; a slow or unavailable server must not delay typing.
            val ready = model.state.first { !it.loading }
            if (ready.connected && ready.success == null && !ready.busy) focusInput()
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusInput()
    }
    private fun focusInput() {
        val input = findViewById<EditText>(R.id.quick_add_product) ?: return
        input.post {
            val current = model.state.value
            if (!isFinishing && hasWindowFocus() && current.connected && !current.busy && current.success == null && input.visibility == View.VISIBLE) {
                input.requestFocus()
                keyboard().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    private fun keyboard() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}
