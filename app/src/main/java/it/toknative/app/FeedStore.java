package it.toknative.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class FeedStore {
    private static final String PREFS = "toknative_feed";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_INDEX = "index";

    private FeedStore() {}

    public static List<VideoItem> load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString(KEY_ITEMS, "[]");
        List<VideoItem> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                VideoItem item = VideoItem.fromJson(o);
                if (!item.sourceUrl.trim().isEmpty()) out.add(item);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(Context context, List<VideoItem> items, int index) {
        JSONArray a = new JSONArray();
        for (VideoItem item : items) {
            try { a.put(item.toJson()); } catch (Exception ignored) {}
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, a.toString())
                .putInt(KEY_INDEX, Math.max(0, index))
                .apply();
    }

    public static int loadIndex(Context context) {
        return Math.max(0, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_INDEX, 0));
    }
}
