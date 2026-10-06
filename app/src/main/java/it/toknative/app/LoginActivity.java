package it.toknative.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tiktok.open.sdk.auth.AuthApi;
import com.tiktok.open.sdk.auth.AuthRequest;
import com.tiktok.open.sdk.auth.AuthResponse;
import com.tiktok.open.sdk.auth.utils.PKCEUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends Activity {
    public static final String REDIRECT_URI = "https://hgwells2001.github.io/TokNative---Native-Android-TikTok-Client/callback/";
    private static final String SCOPES = "user.info.basic,video.list";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AuthApi authApi;
    private TextView status;
    private TextView profileName;
    private ImageView avatar;
    private Button loginButton;
    private Button logoutButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authApi = new AuthApi(this);
        buildUi();
        refreshProfileUi();
        handleAuthResponse(getIntent());
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthResponse(intent);
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Profilo TikTok", 28f, Color.WHITE);
        root.addView(title);

        TextView subtitle = text("Login Kit ufficiale • nessuna WebView • Google resta dentro il flusso TikTok", 15f, 0xFFBBBBBB);
        subtitle.setPadding(0, dp(8), 0, dp(22));
        root.addView(subtitle);

        avatar = new ImageView(this);
        avatar.setBackgroundColor(0xFF1C1C1C);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(96), dp(96));
        avatarLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(avatar, avatarLp);

        profileName = text("Non collegato", 20f, Color.WHITE);
        profileName.setGravity(Gravity.CENTER_HORIZONTAL);
        profileName.setPadding(0, dp(12), 0, dp(16));
        root.addView(profileName);

        loginButton = button("Accedi con TikTok / Google");
        root.addView(loginButton, wideButtonParams());
        loginButton.setOnClickListener(v -> beginLogin());

        Button config = button("Configura Login Kit");
        root.addView(config, wideButtonParams());
        config.setOnClickListener(v -> showConfigDialog());

        logoutButton = button("Disconnetti profilo locale");
        root.addView(logoutButton, wideButtonParams());
        logoutButton.setOnClickListener(v -> {
            LoginPrefs.clearProfile(this);
            refreshProfileUi();
        });

        status = text("", 14f, 0xFFDDDDDD);
        status.setPadding(0, dp(20), 0, dp(12));
        root.addView(status);

        TextView help = text(
                "Redirect registrato da usare nel TikTok Developer Portal:\n" + REDIRECT_URI +
                "\n\nIl client secret NON viene salvato nell'APK. Per completare l'accesso, TokNative invia il codice OAuth al tuo backend configurato, che deve effettuare lo scambio token con TikTok.",
                13f, 0xFF999999);
        root.addView(help);

        setContentView(scroll);
    }

    private void beginLogin() {
        String clientKey = LoginPrefs.clientKey(this);
        if (clientKey.isEmpty()) {
            showConfigDialog();
            return;
        }

        String verifier = PKCEUtils.INSTANCE.generateCodeVerifier();
        String state = randomState();
        LoginPrefs.savePending(this, verifier, state);

        AuthRequest request = new AuthRequest(
                clientKey,
                SCOPES,
                REDIRECT_URI,
                verifier,
                false,
                state,
                "it"
        );

        boolean launched = authApi.authorize(request, AuthApi.AuthMethod.ChromeTab);
        setStatus(launched
                ? "Apertura autenticazione TikTok… Se disponibile, puoi scegliere Continua con Google."
                : "Impossibile avviare Login Kit. Controlla client key e configurazione TikTok.");
    }

    private void handleAuthResponse(android.content.Intent intent) {
        if (intent == null) return;
        AuthResponse response = authApi.getAuthResponseFromIntent(intent, REDIRECT_URI);
        if (response == null) return;

        String expectedState = LoginPrefs.state(this);
        String returnedState = response.getState();
        if (!expectedState.isEmpty() && returnedState != null && !expectedState.equals(returnedState)) {
            setStatus("Callback rifiutato: state OAuth non corrisponde.");
            return;
        }

        String code = response.getAuthCode();
        if (code != null && !code.isEmpty()) {
            String granted = response.getGrantedPermissions();
            String backend = LoginPrefs.backendUrl(this);
            if (backend.isEmpty()) {
                LoginPrefs.saveProfile(this, "Autorizzazione TikTok ricevuta", "", "", granted);
                refreshProfileUi();
                setStatus("TikTok ha autorizzato TokNative. Per trasformare il codice in una sessione persistente serve il backend token configurato.");
                return;
            }
            exchangeWithBackend(backend, code, LoginPrefs.codeVerifier(this), granted);
            return;
        }

        String detail = response.getAuthErrorDescription();
        if (detail == null || detail.isEmpty()) detail = response.getErrorMsg();
        if (detail == null || detail.isEmpty()) detail = "Autorizzazione annullata o non riuscita";
        setStatus("Login TikTok: " + detail);
    }

    private void exchangeWithBackend(String backend, String code, String verifier, String granted) {
        setStatus("TikTok autorizzato • completamento sessione…");
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                URL url = new URL(backend + "/tiktok/exchange");
                c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(12000);
                c.setReadTimeout(15000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                JSONObject body = new JSONObject();
                body.put("code", code);
                body.put("code_verifier", verifier);
                body.put("redirect_uri", REDIRECT_URI);
                body.put("granted_permissions", granted == null ? "" : granted);
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = c.getOutputStream()) { out.write(data); }

                int statusCode = c.getResponseCode();
                InputStream in = statusCode >= 200 && statusCode < 300 ? c.getInputStream() : c.getErrorStream();
                String payload = readAll(in);
                if (statusCode < 200 || statusCode >= 300) throw new IllegalStateException("Backend HTTP " + statusCode + ": " + payload);

                JSONObject result = new JSONObject(payload);
                String displayName = result.optString("display_name", "Profilo TikTok");
                String avatarUrl = result.optString("avatar_url", "");
                String sessionId = result.optString("session_id", "");
                String permissions = result.optString("granted_permissions", granted == null ? "" : granted);
                LoginPrefs.saveProfile(this, displayName, avatarUrl, sessionId, permissions);
                runOnUiThread(() -> {
                    refreshProfileUi();
                    setStatus("Accesso completato come " + displayName + ".");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("TikTok autorizzato, ma il backend non ha completato la sessione: " + safeMessage(e)));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void showConfigDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, 0, pad, 0);

        EditText client = new EditText(this);
        client.setHint("TikTok client_key");
        client.setSingleLine(true);
        client.setText(LoginPrefs.clientKey(this));
        box.addView(client);

        EditText backend = new EditText(this);
        backend.setHint("https://tuo-backend.example (opzionale per ora)");
        backend.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        backend.setSingleLine(true);
        backend.setText(LoginPrefs.backendUrl(this));
        box.addView(backend);

        new AlertDialog.Builder(this)
                .setTitle("Configura TikTok Login Kit")
                .setMessage("Inserisci solo la client key pubblica e, quando disponibile, l'URL del backend. Non inserire mai il client secret nell'app.")
                .setView(box)
                .setPositiveButton("Salva", (d, w) -> {
                    LoginPrefs.saveConfig(this, client.getText().toString(), backend.getText().toString());
                    setStatus("Configurazione salvata. Redirect: " + REDIRECT_URI);
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void refreshProfileUi() {
        String name = LoginPrefs.displayName(this);
        profileName.setText(name.isEmpty() ? "Non collegato" : name);
        boolean connected = !LoginPrefs.sessionId(this).isEmpty() || !name.isEmpty();
        logoutButton.setEnabled(connected);
        String avatarUrl = LoginPrefs.avatarUrl(this);
        if (avatarUrl.isEmpty()) {
            avatar.setImageDrawable(null);
            avatar.setBackgroundColor(0xFF1C1C1C);
        } else {
            loadAvatar(avatarUrl);
        }
    }

    private void loadAvatar(String url) {
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                try (InputStream in = c.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(in);
                    if (bitmap != null) runOnUiThread(() -> avatar.setImageBitmap(bitmap));
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {}
        });
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams wideButtonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private void setStatus(String value) {
        status.setText(value == null ? "" : value);
    }

    private String randomState() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
}