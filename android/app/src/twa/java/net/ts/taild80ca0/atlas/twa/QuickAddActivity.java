package net.ts.taild80ca0.atlas.twa;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QuickAddActivity extends Activity {
  private static final String BASE = "https://atlas.taild80ca0.ts.net";
  private static final String PREFS = "alva_quick_add";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private EditText input;
  private EditText password;
  private TextView status;
  private Button submit;
  private boolean enrolling;
  private boolean busy;
  private final Handler handler = new Handler(Looper.getMainLooper());

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    buildForm();
    showForm();
  }

  private void buildForm() {
    setContentView(R.layout.quick_add_sheet);
    input = findViewById(R.id.quick_add_product);
    password = findViewById(R.id.quick_add_password);
    status = findViewById(R.id.quick_add_status);
    submit = findViewById(R.id.quick_add_submit);
    findViewById(R.id.quick_add_close).setOnClickListener(v -> finish());
    submit.setOnClickListener(v -> send());
    TextView.OnEditorActionListener done = (v, action, event) -> {
      if (action == EditorInfo.IME_ACTION_DONE) { send(); return true; }
      return false;
    };
    input.setOnEditorActionListener(done);
    password.setOnEditorActionListener(done);
  }

  @Override protected void onStart() {
    super.onStart();
    getWindow().setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(24),
        WindowManager.LayoutParams.WRAP_CONTENT);
    WindowManager.LayoutParams attributes = getWindow().getAttributes();
    attributes.y = dp(12);
    getWindow().setAttributes(attributes);
  }

  private void showForm() {
    enrolling = token().isEmpty();
    password.setVisibility(enrolling ? View.VISIBLE : View.GONE);
    setStatus(enrolling ? "Conecte este aparelho à casa uma vez." : "", false);
    setBusy(false);
    input.requestFocus();
  }

  private void setStatus(String message, boolean error) {
    status.setText(message);
    status.setTextColor(error ? 0xFFA32636 : 0xFF846674);
    status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private void send() {
    if (busy) return;
    String name = input.getText().toString().trim();
    if (name.isEmpty()) { input.setError("Digite um produto"); return; }
    if (enrolling && password.getText().toString().isEmpty()) {
      password.setError("Digite a senha da casa"); password.requestFocus(); return;
    }
    final String housePassword = password.getText().toString();
    setBusy(true);
    worker.execute(() -> {
      try {
        if (token().isEmpty()) enroll(housePassword);
        JSONObject body = new JSONObject().put("name", name);
        JSONObject result = request("/api/quick_add", body, token());
        org.json.JSONArray results = result.optJSONArray("results");
        boolean added = false;
        if (results != null) for (int i = 0; i < results.length(); i++) {
          String outcome = results.getJSONObject(i).optString("outcome");
          added |= "created".equals(outcome) || "revived".equals(outcome);
        }
        final boolean anyAdded = added;
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          InputMethodManager keyboard = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
          keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
          findViewById(R.id.quick_add_form).setVisibility(View.GONE);
          TextView message = findViewById(R.id.quick_add_success_message);
          message.setText(anyAdded ? "Adicionado à lista" : "Já estava na lista");
          findViewById(R.id.quick_add_success).setVisibility(View.VISIBLE);
          message.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT);
          handler.postDelayed(this::finish, 1500);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          setStatus("Não foi possível enviar. Confira a conexão e tente de novo.", true);
          setBusy(false);
        });
      }
    });
  }

  private void enroll(String housePassword) throws Exception {
    JSONObject response = request("/api/android/devices", new JSONObject()
        .put("password", housePassword).put("name", "Android widget"), null);
    saveToken(response.getString("token"));
    runOnUiThread(() -> {
      if (isFinishing() || isDestroyed()) return;
      enrolling = false;
      password.setText("");
      password.setVisibility(View.GONE);
    });
  }

  private JSONObject request(String path, JSONObject body, String bearer) throws Exception {
    HttpURLConnection connection = (HttpURLConnection)new URL(BASE + path).openConnection();
    connection.setRequestMethod("POST"); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
    connection.setDoOutput(true);
    try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
    int code = connection.getResponseCode();
    InputStream stream = code < 400 ? connection.getInputStream() : connection.getErrorStream();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[2048];
    int count;
    while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
    connection.disconnect();
    if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
    return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
  }

  private String token() {
    try { return prefs().getString("token", ""); } catch (Exception e) { return ""; }
  }
  private void saveToken(String value) throws Exception { prefs().edit().putString("token", value).apply(); }
  private android.content.SharedPreferences prefs() throws Exception {
    MasterKey key = new MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
    return EncryptedSharedPreferences.create(this, PREFS, key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
  }
  private void setBusy(boolean busy) {
    this.busy = busy;
    submit.setEnabled(!busy);
    input.setEnabled(!busy);
    password.setEnabled(!busy);
    submit.setAlpha(busy ? .65f : 1f);
    submit.setText(busy ? "Enviando…" : enrolling ? "Conectar e adicionar" : "Adicionar à lista +");
  }
  private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
  @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy(); }
}
