package it.toknative.app;

import org.json.JSONException;
import org.json.JSONObject;

public final class VideoItem {
    public final String sourceUrl;
    public String mediaUrl;
    public String title;
    public long resolvedAtMs;

    public VideoItem(String sourceUrl, String mediaUrl, String title, long resolvedAtMs) {
        this.sourceUrl = sourceUrl;
        this.mediaUrl = mediaUrl;
        this.title = title == null || title.trim().isEmpty() ? "Video TikTok" : title;
        this.resolvedAtMs = resolvedAtMs;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("sourceUrl", sourceUrl);
        o.put("mediaUrl", mediaUrl);
        o.put("title", title);
        o.put("resolvedAtMs", resolvedAtMs);
        return o;
    }

    public static VideoItem fromJson(JSONObject o) {
        return new VideoItem(
                o.optString("sourceUrl", ""),
                o.optString("mediaUrl", ""),
                o.optString("title", "Video TikTok"),
                o.optLong("resolvedAtMs", 0L)
        );
    }
}
