package net.ts.taild80ca0.atlas.twa;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    buildForm();
    showForm();
  }

  private void buildForm() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(24), dp(20), dp(24), dp(18));
    root.setBackgroundColor(0xFFFFF7FA);
    TextView title = new TextView(this);
    title.setText("Alva"); title.setTextSize(19); title.setTextColor(0xFF34212B);
    root.addView(title);
    status = new TextView(this); status.setTextColor(0xFF604653);
    root.addView(status);
    input = new EditText(this);
    input.setSingleLine(true); input.setHint("O que acabou?");
    input.setImeOptions(EditorInfo.IME_ACTION_DONE);
    root.addView(input);
    password = new EditText(this);
    password.setSingleLine(true); password.setHint("Senha da casa");
    password.setInputType(129);
    root.addView(password);
    submit = new Button(this); submit.setTextColor(0xFFFFFFFF);
    submit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFD9468D));
    root.addView(submit);
    setContentView(root);
    submit.setOnClickListener(v -> send());
    getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * .92), -2);
    input.setOnEditorActionListener((v, action, event) -> {
      if (action == EditorInfo.IME_ACTION_DONE) { send(); return true; }
      return false;
    });
  }

  private void showForm() {
    enrolling = token().isEmpty();
    password.setVisibility(enrolling ? android.view.View.VISIBLE : android.view.View.GONE);
    status.setText(enrolling ? "Conecte este aparelho à casa uma vez." : "");
    submit.setText(enrolling ? "Conectar e adicionar" : "Adicionar");
    input.setHint(enrolling ? "Produto para adicionar" : "O que acabou?");
    input.requestFocus();
  }

  private void send() {
    String name = input.getText().toString().trim();
    if (name.isEmpty()) { input.setError("Digite um produto"); return; }
    setBusy(true);
    worker.execute(() -> {
      try {
        if (token().isEmpty()) enroll();
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
          status.setText(anyAdded ? "Adicionado à lista" : "Já estava na lista");
          input.setText("");
          setBusy(false);
          input.requestFocus();
        });
      } catch (Exception error) {
        runOnUiThread(() -> { status.setText("Não foi possível enviar. Confira a conexão e tente de novo."); setBusy(false); });
      }
    });
  }

  private void enroll() throws Exception {
    JSONObject response = request("/api/android/devices", new JSONObject()
        .put("password", password.getText().toString()).put("name", "Android widget"), null);
    saveToken(response.getString("token"));
    runOnUiThread(() -> password.setVisibility(android.view.View.GONE));
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
    return new JSONObject(bytes.toString(StandardCharsets.UTF_8));
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
  private void setBusy(boolean busy) { submit.setEnabled(!busy); submit.setText(busy ? "Enviando…" : enrolling ? "Conectar e adicionar" : "Adicionar"); }
  private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
  @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
