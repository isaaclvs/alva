package net.ts.taild80ca0.atlas.twa.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import net.ts.taild80ca0.atlas.twa.R
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore
import net.ts.taild80ca0.atlas.twa.ui.theme.*
import androidx.compose.ui.graphics.toArgb
import net.ts.taild80ca0.atlas.twa.MainActivity
import net.ts.taild80ca0.atlas.twa.access.AccessGraph
import net.ts.taild80ca0.atlas.twa.online.OnlineState
import net.ts.taild80ca0.atlas.twa.sync.SnapshotHabitual

/** Configuration is local to this widget instance and never changes the household's habituals. */
class HabitualWidgetConfigureActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val selected = mutableListOf<String>()
    private var habits: List<SnapshotHabitual> = emptyList()
    private lateinit var title: EditText
    private lateinit var choices: LinearLayout
    private lateinit var message: TextView
    private lateinit var preview: TextView
    private var ink = android.graphics.Color.WHITE
    private var accent = android.graphics.Color.WHITE
    private var accentInk = android.graphics.Color.BLACK
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        lifecycleScope.launch {
            val prefs = DevicePreferencesStore(this@HabitualWidgetConfigureActivity).values.first()
            val colors = AlvaPalettes.of(prefs.dark, prefs.color)
            applyAlvaSystemBars(colors)
            ink = colors.ink.toArgb()
            accent = colors.accent.toArgb()
            accentInk = colors.accentInk.toArgb()
            build(savedInstanceState, colors.canvas.toArgb(), colors.inkSoft.toArgb())
        }
    }
    private fun build(savedInstanceState: Bundle?, background: Int, soft: Int) {
        val settings = HabitualWidgetSettings.read(this, widgetId)
        selected.addAll(savedInstanceState?.getStringArrayList("selected") ?: settings.ids)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); setBackgroundColor(background)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 16f; setTextColor(ink); setPadding(0, dp(8), 0, dp(8)) }
        content.addView(label("Atalhos de casa").apply { textSize = 24f })
        content.addView(label("Escolha seus habituais e a ordem dos botões. Esta seleção vale só para este widget."))
        title = EditText(this).apply {
            hint = "Nome do widget"; setSingleLine(); setTextColor(ink); setHintTextColor(soft)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            setText(savedInstanceState?.getString("title") ?: settings.title)
        }
        content.addView(title)
        message = label("Carregando habituais…")
        content.addView(message)
        preview = label("").apply { contentDescription = "Prévia dos atalhos" }
        content.addView(preview)
        choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(this).apply { addView(choices) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(Button(this).apply {
            text = "Gerenciar habituais no Alva"
            setOnClickListener { startActivity(Intent(this@HabitualWidgetConfigureActivity, MainActivity::class.java)) }
        })
        content.addView(Button(this).apply {
            text = "Salvar widget"
            setOnClickListener {
                if (selected.isEmpty()) { message.text = "Escolha pelo menos um habitual."; return@setOnClickListener }
                HabitualWidgetSettings.save(this@HabitualWidgetConfigureActivity, widgetId, HabitualWidgetSettings(title.text.toString(), selected.toList()))
                HabitualWidgetProvider.update(this@HabitualWidgetConfigureActivity)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            }
        })
        fun applyFont(view: android.view.View) {
            if (view is TextView) view.typeface = ResourcesCompat.getFont(this, R.font.nunito_sans_regular)
            if (view is Button) styleButton(view)
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) applyFont(view.getChildAt(index))
        }
        applyFont(content)
        setContentView(content)
        habits = WidgetRuntime.snapshot(this)?.habitualItems.orEmpty()
        render()
        val repository = AccessGraph.onlineRepository(this)
        if (repository == null) message.text = "Abra o Alva para conectar sua casa." else lifecycleScope.launch {
            launch { repository.refresh() }
            repository.state.collect { state ->
                when (state) {
                    is OnlineState.Ready -> {
                        habits = state.snapshot.habitualItems
                        selected.retainAll(habits.map { it.publicId }.toSet())
                        render()
                        state.problem?.let { message.text = it.message }
                    }
                    is OnlineState.Failed -> { message.text = state.problem.message }
                    else -> Unit
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        WidgetRuntime.refresh(this)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selected", ArrayList(selected))
        if (::title.isInitialized) outState.putString("title", title.text.toString())
        super.onSaveInstanceState(outState)
    }
    private fun render() {
        choices.removeAllViews()
        message.text = if (habits.isEmpty()) "Ainda não há habituais. Salve seus itens frequentes no Alva." else "${selected.size} selecionados · use ↑ e ↓ para ordenar"
        preview.text = "Prévia · " + selected.mapNotNull { id -> habits.find { it.publicId == id }?.name }.joinToString("  + ").ifBlank { "Nenhum atalho selecionado" }
        val ordered = selected.mapNotNull { id -> habits.find { it.publicId == id } } + habits.filter { it.publicId !in selected }.sortedBy { it.normalizedName }
        ordered.forEach { habit ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(CheckBox(this).apply {
                text = habit.name; typeface = ResourcesCompat.getFont(this@HabitualWidgetConfigureActivity, R.font.nunito_sans_regular); setTextColor(ink); isChecked = habit.publicId in selected; minHeight = dp(48)
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(habit.publicId) else selected.remove(habit.publicId)
                    render()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (habit.publicId in selected) {
                val index = selected.indexOf(habit.publicId)
                listOf(-1 to "↑", 1 to "↓").forEach { (offset, symbol) ->
                    row.addView(Button(this).apply {
                        styleButton(this)
                        text = symbol; contentDescription = "Mover ${habit.name} ${if (offset < 0) "para cima" else "para baixo"}"
                        isEnabled = index + offset in selected.indices
                        setOnClickListener { selected.removeAt(index); selected.add(index + offset, habit.publicId); render() }
                    }, LinearLayout.LayoutParams(dp(52), dp(48)))
                }
            }
            choices.addView(row)
        }
    }
    private fun styleButton(button: Button) {
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        button.setTextColor(accentInk)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
