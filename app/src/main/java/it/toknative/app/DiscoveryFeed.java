package it.toknative.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native public-feed discovery. This class never embeds TikTok web pages.
 * It performs plain HTTP requests, extracts public canonical video links and lets
 * TikTokResolver resolve the actual media lazily only when an item is played.
 */
public final class DiscoveryFeed {
    private static final int MAX_HTML = 5 * 1024 * 1024;
    private static final int MAX_ITEMS = 48;
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36";

    // Mix of public creators/categories. If one endpoint is challenged or unavailable,
    // the others can still populate the feed.
    private static final String[] SOURCES = new String[] {
            "https://www.tiktok.com/@redbull",
            "https://www.tiktok.com/@natgeo",
            "https://www.tiktok.com/@newscientist",
            "https://www.tiktok.com/@italiateam",
            "https://www.tiktok.com/@olympics",
            "https://www.tiktok.com/channel/racing"
    };

    private static final String[] FALLBACK_URLS = new String[] {
            "https://www.tiktok.com/@redbull/video/7607401470505405718",
            "https://www.tiktok.com/@redbull/video/7405617359127465249",
            "https://www.tiktok.com/@redbullbike/video/7451789875319278870",
            "https://www.tiktok.com/@redbullbike/video/7449981439627595030",
            "https://www.tiktok.com/@redbullbike/video/7449632690854202646",
            "https://www.tiktok.com/@redbullbike/video/7448499914805398806",
            "https://www.tiktok.com/@redbullbike/video/7447766704953904406",
            "https://www.tiktok.com/@redbullbike/video/7404196650891169057",
            "https://www.tiktok.com/@natgeo/video/7159645889147981098",
            "https://www.tiktok.com/@redbull/video/7210374948911615238"
    };

    private static final Pattern CANONICAL = Pattern.compile(
            "https?://(?:www\\.)?tiktok\\.com/@([A-Za-z0-9._-]+)/video/(\\d{15,22})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIVE = Pattern.compile(
            "/@([A-Za-z0-9._-]+)/video/(\\d{15,22})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_DESC = Pattern.compile(
            "\\\"id\\\"\\s*:\\s*\\\"(\\d{15,22})\\\"\\s*,\\s*\\\"desc\\\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_THEN_AUTHOR = Pattern.compile(
            "\\\"id\\\"\\s*:\\s*\\\"(\\d{15,22})\\\".{0,7000}?\\\"uniqueId\\\"\\s*:\\s*\\\"([A-Za-z0-9._-]+)\\\"",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AUTHOR_THEN_ID = Pattern.compile(
            "\\\"uniqueId\\\"\\s*:\\s*\\\"([A-Za-z0-9._-]+)\\\".{0,7000}?\\\"id\\\"\\s*:\\s*\\\"(\\d{15,22})\\\"",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private DiscoveryFeed() {}

    public static List<VideoItem> discover() {
        LinkedHashMap<String, VideoItem> items = new LinkedHashMap<>();
        for (String source : SOURCES) {
            if (items.size() >= MAX_ITEMS) break;
            try {
                String html = fetch(source);
                extract(html, source, items);
            } catch (Exception ignored) {
                // Discovery is best-effort; another source or the fallback still works.
            }
        }
        if (items.size() < 8) addFallback(items);
        List<VideoItem> out = new ArrayList<>(items.values());
        Collections.shuffle(out);
        if (out.size() > MAX_ITEMS) return new ArrayList<>(out.subList(0, MAX_ITEMS));
        return out;
    }

    public static List<VideoItem> fallback() {
        LinkedHashMap<String, VideoItem> items = new LinkedHashMap<>();
        addFallback(items);
        return new ArrayList<>(items.values());
    }

    private static void extract(String rawHtml, String source, Map<String, VideoItem> out) {
        if (rawHtml == null || rawHtml.isEmpty()) return;
        String html = rawHtml
                .replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&");

        Matcher full = CANONICAL.matcher(html);
        while (full.find() && out.size() < MAX_ITEMS) {
            put(out, full.group(1), full.group(2));
        }
        Matcher rel = RELATIVE.matcher(html);
        while (rel.find() && out.size() < MAX_ITEMS) {
            put(out, rel.group(1), rel.group(2));
        }

        Matcher paired = ID_THEN_AUTHOR.matcher(html);
        while (paired.find() && out.size() < MAX_ITEMS) {
            put(out, paired.group(2), paired.group(1));
        }
        Matcher reverse = AUTHOR_THEN_ID.matcher(html);
        while (reverse.find() && out.size() < MAX_ITEMS) {
            put(out, reverse.group(1), reverse.group(2));
        }

        String knownHandle = handleFromProfile(source);
        if (knownHandle != null) {
            Matcher ids = ID_DESC.matcher(html);
            while (ids.find() && out.size() < MAX_ITEMS) {
                put(out, knownHandle, ids.group(1));
            }
        }
    }

    private static void put(Map<String, VideoItem> out, String handle, String id) {
        if (handle == null || id == null) return;
        handle = handle.trim();
        id = id.trim();
        if (handle.isEmpty() || !id.matches("\\d{15,22}")) return;
        String url = "https://www.tiktok.com/@" + handle + "/video/" + id;
        out.putIfAbsent(url, new VideoItem(url, "", "@" + handle + " • Discover", 0L));
    }

    private static void addFallback(Map<String, VideoItem> out) {
        for (String url : FALLBACK_URLS) {
            String handle = "TikTok";
            try {
                String path = new URI(url).getPath();
                int at = path.indexOf("/@");
                int slash = path.indexOf('/', at + 2);
                if (at >= 0 && slash > at) handle = path.substring(at + 2, slash);
            } catch (Exception ignored) {}
            out.putIfAbsent(url, new VideoItem(url, "", "@" + handle + " • Discover", 0L));
        }
    }

    private static String fetch(String address) throws Exception {
        URL current = new URL(address);
        HttpURLConnection c = null;
        for (int redirects = 0; redirects < 5; redirects++) {
            c = (HttpURLConnection) current.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(12_000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en;q=0.7");
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = c.getHeaderField("Location");
                if (location == null) break;
                current = current.toURI().resolve(location).toURL();
                c.disconnect();
                c = null;
                continue;
            }
            break;
        }
        if (c == null) throw new IllegalStateException("Sorgente feed non raggiungibile");
        int status = c.getResponseCode();
        if (status < 200 || status >= 300) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + status);
        }
        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buf)) >= 0 && total < MAX_HTML) {
                int take = Math.min(n, MAX_HTML - total);
                out.write(buf, 0, take);
                total += take;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static String handleFromProfile(String source) {
        try {
            String path = new URI(source).getPath();
            if (path == null || !path.startsWith("/@")) return null;
            String h = path.substring(2);
            int slash = h.indexOf('/');
            if (slash >= 0) h = h.substring(0, slash);
            return h.trim().isEmpty() ? null : h;
        } catch (Exception e) {
            return null;
        }
    }
}