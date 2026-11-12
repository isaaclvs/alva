package net.ts.taild80ca0.atlas.twa

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
    private val model: QuickPanelModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, intent.extras) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                QuickPanelModel(handle, AccessGraph.houseAccess(applicationContext), AccessGraph.onlineRepository(applicationContext)) as T
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Share the existing pink 1.3 panel resources; only its transport and lifecycle change.
        setContentView(R.layout.quick_add_sheet)
        val input = findViewById<EditText>(R.id.quick_add_product)
        val submit = findViewById<Button>(R.id.quick_add_submit)
        val status = findViewById<TextView>(R.id.quick_add_status)
        findViewById<View>(R.id.quick_add_password).visibility = View.GONE
        findViewById<View>(R.id.quick_add_close).setOnClickListener { finish() }
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
                model.state.collect { current ->
                    if (input.text.toString() != current.draft) { input.setText(current.draft); input.setSelection(input.length()) }
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
                    status.setTextColor(if (current.error != null) 0xffa32636.toInt() else 0xff846674.toInt())
                    findViewById<View>(R.id.quick_add_form).visibility = if (current.success == null) View.VISIBLE else View.GONE
                    findViewById<View>(R.id.quick_add_success).visibility = if (current.success != null) View.VISIBLE else View.GONE
                    findViewById<TextView>(R.id.quick_add_success_message).text = current.success.orEmpty()
                    if (current.success != null || !current.connected) keyboard().hideSoftInputFromWindow(input.windowToken, 0)
                    if (current.close) finish()
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        val density = resources.displayMetrics.density
        window.setLayout(resources.displayMetrics.widthPixels - (24 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply { y = (12 * density).toInt() }
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
