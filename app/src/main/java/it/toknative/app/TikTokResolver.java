package it.toknative.app;

import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TikTokResolver {
    private static final CookieManager COOKIE_MANAGER = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private static final int MAX_HTML = 4 * 1024 * 1024;
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36";

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s<>\\\"]+");
    private static final Pattern[] MEDIA_PATTERNS = new Pattern[] {
            Pattern.compile("\\\"downloadAddr\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\""),
            Pattern.compile("\\\"playAddr\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\""),
            Pattern.compile("\\\"download_addr\\\"\\s*:\\s*\\{[^}]*?\\\"url_list\\\"\\s*:\\s*\\[\\s*\\\"((?:\\\\.|[^\\\"])*)\\\""),
            Pattern.compile("\\\"play_addr\\\"\\s*:\\s*\\{[^}]*?\\\"url_list\\\"\\s*:\\s*\\[\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
    };
    private static final Pattern DESC = Pattern.compile("\\\"desc\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
    private static final Pattern HTML_TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static {
        try {
            CookieHandler.setDefault(COOKIE_MANAGER);
        } catch (Exception ignored) {}
    }

    private TikTokResolver() {}

    public static final class Result {
        public final String sourceUrl;
        public final String mediaUrl;
        public final String title;
        public Result(String sourceUrl, String mediaUrl, String title) {
            this.sourceUrl = sourceUrl;
            this.mediaUrl = mediaUrl;
            this.title = title;
        }
    }

    public static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_IN_TEXT.matcher(text.trim());
        if (!m.find()) return null;
        String u = m.group();
        while (u.endsWith(".") || u.endsWith(",") || u.endsWith(")") || u.endsWith("]")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public static Result resolve(String textOrUrl) throws Exception {
        String source = extractUrl(textOrUrl);
        if (source == null) throw new IllegalArgumentException("Nessun link https trovato");

        if (looksLikeDirectMedia(source)) {
            return new Result(source, source, friendlyTitle(source));
        }

        URL current = new URL(source);
        HttpURLConnection c = null;
        for (int redirects = 0; redirects < 6; redirects++) {
            c = (HttpURLConnection) current.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en;q=0.7");
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc == null) break;
                current = current.toURI().resolve(loc).toURL();
                c.disconnect();
                c = null;
                continue;
            }
            break;
        }
        if (c == null) throw new IllegalStateException("Redirect TikTok non risolto");
        int status = c.getResponseCode();
        if (status < 200 || status >= 300) {
            c.disconnect();
            throw new IllegalStateException("TikTok ha risposto HTTP " + status);
        }

        String contentType = c.getContentType();
        if (contentType != null && (contentType.contains("video/") || contentType.contains("application/vnd.apple.mpegurl"))) {
            String finalUrl = c.getURL().toString();
            c.disconnect();
            return new Result(source, finalUrl, friendlyTitle(finalUrl));
        }

        String html;
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
            html = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }

        String media = null;
        for (Pattern p : MEDIA_PATTERNS) {
            Matcher m = p.matcher(html);
            if (m.find()) {
                media = decodeJsonString(m.group(1));
                if (media != null && media.startsWith("http")) break;
            }
        }
        if (media == null) {
            throw new IllegalStateException("Il video pubblico non espone un flusso multimediale diretto in questa risposta");
        }

        String title = "Video TikTok";
        Matcher d = DESC.matcher(html);
        if (d.find()) {
            String decoded = decodeJsonString(d.group(1));
            if (decoded != null && !decoded.trim().isEmpty()) title = decoded;
        } else {
            Matcher t = HTML_TITLE.matcher(html);
            if (t.find()) {
                String x = t.group(1).replaceAll("<[^>]+>", "").trim();
                if (!x.trim().isEmpty()) title = x;
            }
        }

        return new Result(source, media, title);
    }

    private static String decodeJsonString(String raw) {
        if (raw == null) return null;
        try {
            String decoded = new JSONArray("[\\\"" + raw + "\\"]").getString(0);
            return decoded.replace("&amp;", "&").replace("\\u0026", "&");
        } catch (Exception ignored) {
            return raw.replace("\\u002F", "/").replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
        }
    }


    public static String cookieHeader(String url) {
        try {
            URI uri = new URI(url);
            StringBuilder b = new StringBuilder();
            for (HttpCookie c : COOKIE_MANAGER.getCookieStore().get(uri)) {
                if (b.length() > 0) b.append("; ");
                b.append(c.getName()).append("=").append(c.getValue());
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean looksLikeDirectMedia(String u) {
        String p;
        try { p = new URI(u).getPath().toLowerCase(Locale.ROOT); }
        catch (Exception e) { p = u.toLowerCase(Locale.ROOT); }
        return p.endsWith(".mp4") || p.endsWith(".m4v") || p.endsWith(".mov") || p.endsWith(".m3u8");
    }

    private static String friendlyTitle(String u) {
        try {
            String path = new URI(u).getPath();
            int slash = path.lastIndexOf('/');
            String f = slash >= 0 ? path.substring(slash + 1) : path;
            return f.trim().isEmpty() ? "Video" : f;
        } catch (Exception e) {
            return "Video";
        }
    }
}
