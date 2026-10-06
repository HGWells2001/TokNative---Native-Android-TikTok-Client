package it.toknative.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class LoginPrefs {
    private static final String PREFS = "toknative_login";
    private static final String CLIENT_KEY = "client_key";
    private static final String BACKEND_URL = "backend_url";
    private static final String CODE_VERIFIER = "code_verifier";
    private static final String STATE = "state";
    private static final String DISPLAY_NAME = "display_name";
    private static final String AVATAR_URL = "avatar_url";
    private static final String SESSION_ID = "session_id";
    private static final String GRANTED = "granted_permissions";

    private LoginPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String clientKey(Context c) { return prefs(c).getString(CLIENT_KEY, ""); }
    public static String backendUrl(Context c) { return prefs(c).getString(BACKEND_URL, ""); }
    public static String codeVerifier(Context c) { return prefs(c).getString(CODE_VERIFIER, ""); }
    public static String state(Context c) { return prefs(c).getString(STATE, ""); }
    public static String displayName(Context c) { return prefs(c).getString(DISPLAY_NAME, ""); }
    public static String avatarUrl(Context c) { return prefs(c).getString(AVATAR_URL, ""); }
    public static String sessionId(Context c) { return prefs(c).getString(SESSION_ID, ""); }
    public static String grantedPermissions(Context c) { return prefs(c).getString(GRANTED, ""); }

    public static void saveConfig(Context c, String clientKey, String backendUrl) {
        prefs(c).edit().putString(CLIENT_KEY, clean(clientKey)).putString(BACKEND_URL, trimSlash(backendUrl)).apply();
    }

    public static void savePending(Context c, String verifier, String state) {
        prefs(c).edit().putString(CODE_VERIFIER, verifier).putString(STATE, state).apply();
    }

    public static void saveProfile(Context c, String displayName, String avatarUrl, String sessionId, String granted) {
        prefs(c).edit()
                .putString(DISPLAY_NAME, clean(displayName))
                .putString(AVATAR_URL, clean(avatarUrl))
                .putString(SESSION_ID, clean(sessionId))
                .putString(GRANTED, clean(granted))
                .apply();
    }

    public static void clearProfile(Context c) {
        prefs(c).edit().remove(DISPLAY_NAME).remove(AVATAR_URL).remove(SESSION_ID).remove(GRANTED).apply();
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
    private static String trimSlash(String s) {
        String x = clean(s);
        while (x.endsWith("/")) x = x.substring(0, x.length() - 1);
        return x;
    }
}