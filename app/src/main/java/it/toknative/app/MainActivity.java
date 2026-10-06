package it.toknative.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final long RESOLVE_TTL_MS = 20L * 60L * 1000L;
    private static final long DISCOVERY_COOLDOWN_MS = 90L * 1000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private List<VideoItem> feed;
    private int index;
    private PlayerService playerService;
    private boolean bound;
    private Surface videoSurface;
    private VideoItem pendingPlayItem;
    private long pendingPlayPosition;
    private boolean discoverLoading;
    private long lastDiscoveryAttempt;
    private boolean autoPlayAfterDiscovery;

    private TextureView textureView;
    private TextView titleView;
    private TextView statusView;
    private TextView emptyView;
    private Button playButton;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            PlayerService.LocalBinder b = (PlayerService.LocalBinder) service;
            playerService = b.getService();
            bound = true;
            if (videoSurface != null) playerService.attachSurface(videoSurface);
            if (pendingPlayItem != null) {
                VideoItem item = pendingPlayItem;
                long pos = pendingPlayPosition;
                pendingPlayItem = null;
                pendingPlayPosition = 0L;
                startResolvedPlayback(item, pos);
            }
            refreshUi();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            playerService = null;
            refreshUi();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        feed = FeedStore.load(this);
        index = Math.min(Math.max(0, FeedStore.loadIndex(this)), Math.max(0, feed.size() - 1));

        buildUi();
        requestNotificationPermissionIfNeeded();
        handleShareIntent(getIntent());
        if (feed.isEmpty()) {
            feed.addAll(DiscoveryFeed.fallback());
            FeedStore.save(this, feed, 0);
            index = 0;
            autoPlayAfterDiscovery = true;
        }
        refreshUi();
        refreshDiscover(false);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (bound) {
            if (playerService != null && videoSurface != null) playerService.detachSurface(videoSurface);
            unbindService(connection);
            bound = false;
        }
        FeedStore.save(this, feed, index);
        super.onStop();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        super.onDestroy();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        root.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(this);
        emptyView.setTextColor(Color.WHITE);
        emptyView.setTextSize(18f);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(28), dp(28), dp(28), dp(28));
        emptyView.setText("TokNative\n\nCondividi un link TikTok con questa app oppure premi + per aggiungerlo.\n\nNessuna WebView: il video viene riprodotto dal player Android nativo.");
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(emptyView, emptyLp);

        statusView = new TextView(this);
        statusView.setTextColor(0xFFDDDDDD);
        statusView.setTextSize(13f);
        statusView.setPadding(dp(14), dp(8), dp(14), dp(8));
        statusView.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        statusLp.topMargin = dp(8);
        statusLp.leftMargin = dp(8);
        statusLp.rightMargin = dp(8);
        root.addView(statusView, statusLp);

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16f);
        titleView.setMaxLines(3);
        titleView.setPadding(dp(14), dp(8), dp(14), dp(8));
        titleView.setBackgroundColor(0x77000000);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        titleLp.bottomMargin = dp(76);
        titleLp.leftMargin = dp(8);
        titleLp.rightMargin = dp(8);
        root.addView(titleView, titleLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(4), dp(6), dp(4), dp(6));
        controls.setBackgroundColor(0xCC101010);

        Button profile = makeButton("👤");
        Button discover = makeButton("⟳");
        Button add = makeButton("+");
        Button prev = makeButton("◀");
        playButton = makeButton("▶");
        Button next = makeButton("▶▶");
        Button download = makeButton("↓");

        controls.addView(profile, weighted());
        controls.addView(discover, weighted());
        controls.addView(add, weighted());
        controls.addView(prev, weighted());
        controls.addView(playButton, weighted());
        controls.addView(next, weighted());
        controls.addView(download, weighted());

        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68), Gravity.BOTTOM);
        root.addView(controls, controlsLp);

        profile.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
        discover.setOnClickListener(v -> refreshDiscover(true));
        add.setOnClickListener(v -> showAddDialog());
        prev.setOnClickListener(v -> goRelative(-1));
        next.setOnClickListener(v -> goRelative(1));
        playButton.setOnClickListener(v -> togglePlayback());
        download.setOnClickListener(v -> downloadCurrent());

        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                togglePlayback();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > dp(90) && Math.abs(velocityY) > 500) {
                    goRelative(dy < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        textureView.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18f);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String type = intent.getType();
        if (type == null || !type.startsWith("text/")) return;
        String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (shared != null && TikTokResolver.extractUrl(shared) != null) {
            resolveAndAdd(shared, true);
        }
    }

    private void showAddDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://www.tiktok.com/... oppure URL MP4/M3U8");
        input.setSingleLine(false);
        input.setMinLines(2);
        int pad = dp(18);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Aggiungi video")
                .setMessage("Incolla un link pubblico TikTok o un URL video diretto. TokNative non usa WebView.")
                .setView(box)
                .setPositiveButton("Aggiungi", (d, which) -> resolveAndAdd(input.getText().toString(), true))
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void resolveAndAdd(String textOrUrl, boolean playAfter) {
        String extracted = TikTokResolver.extractUrl(textOrUrl);
        if (extracted == null) {
            toast("Non trovo un link valido");
            return;
        }
        setStatus("Risoluzione del link…");
        io.execute(() -> {
            try {
                TikTokResolver.Result r = TikTokResolver.resolve(textOrUrl);
                VideoItem item = new VideoItem(r.sourceUrl, r.mediaUrl, r.title, System.currentTimeMillis());
                main.post(() -> {
                    for (int i = 0; i < feed.size(); i++) {
                        if (feed.get(i).sourceUrl.equals(item.sourceUrl)) {
                            feed.set(i, item);
                            index = i;
                            FeedStore.save(this, feed, index);
                            refreshUi();
                            if (playAfter) playCurrent(true);
                            return;
                        }
                    }
                    feed.add(item);
                    index = feed.size() - 1;
                    FeedStore.save(this, feed, index);
                    refreshUi();
                    if (playAfter) playCurrent(true);
                });
            } catch (Exception e) {
                main.post(() -> {
                    setStatus("Link non risolto");
                    new AlertDialog.Builder(this)
                            .setTitle("Video non disponibile")
                            .setMessage("TokNative non è riuscita a ottenere un flusso video diretto da questo link. TikTok può cambiare il formato delle pagine o richiedere controlli aggiuntivi.\n\nDettaglio: " + safeMessage(e))
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void goRelative(int delta) {
        if (feed.isEmpty()) return;
        int next = index + delta;
        if (next < 0) next = feed.size() - 1;
        if (next >= feed.size()) next = 0;
        index = next;
        FeedStore.save(this, feed, index);
        refreshUi();
        playCurrent(true);
        if (delta > 0 && index >= Math.max(0, feed.size() - 4)) refreshDiscover(false);
    }

    private void togglePlayback() {
        if (feed.isEmpty()) {
            refreshDiscover(true);
            return;
        }
        if (bound && playerService != null && playerService.isPlaying()) {
            playerService.pause();
            refreshUi();
        } else {
            playCurrent(false);
        }
    }

    private void playCurrent(boolean fromStart) {
        if (feed.isEmpty()) return;
        VideoItem item = feed.get(index);
        long savedPos = 0L;
        if (!fromStart && item.mediaUrl.equals(PlayerService.savedUrl(this))) {
            savedPos = PlayerService.savedPosition(this);
        }

        if (needsRefresh(item)) {
            final long pos = savedPos;
            setStatus("Aggiornamento del link video…");
            io.execute(() -> {
                try {
                    TikTokResolver.Result r = TikTokResolver.resolve(item.sourceUrl);
                    item.mediaUrl = r.mediaUrl;
                    item.title = r.title;
                    item.resolvedAtMs = System.currentTimeMillis();
                    main.post(() -> {
                        FeedStore.save(this, feed, index);
                        refreshUi();
                        startResolvedPlayback(item, pos);
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        setStatus("Impossibile aggiornare il flusso");
                        toast(safeMessage(e));
                    });
                }
            });
        } else {
            startResolvedPlayback(item, savedPos);
        }
    }

    private boolean needsRefresh(VideoItem item) {
        if (item.mediaUrl == null || item.mediaUrl.trim().isEmpty()) return true;
        if (item.sourceUrl.equals(item.mediaUrl)) return false;
        return System.currentTimeMillis() - item.resolvedAtMs > RESOLVE_TTL_MS;
    }

    private void startResolvedPlayback(VideoItem item, long pos) {
        startPlayerService();
        if (!bound || playerService == null) {
            pendingPlayItem = item;
            pendingPlayPosition = pos;
            bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE);
            return;
        }
        if (videoSurface != null) playerService.attachSurface(videoSurface);
        playerService.play(item.mediaUrl, item.title, pos);
        setStatus("Riproduzione nativa • audio continua a schermo bloccato");
        main.postDelayed(this::refreshUi, 350L);
    }

    private void startPlayerService() {
        Intent serviceIntent = new Intent(this, PlayerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent);
        else startService(serviceIntent);
    }

    private void downloadCurrent() {
        if (feed.isEmpty()) return;
        VideoItem item = feed.get(index);
        if (needsRefresh(item)) {
            setStatus("Aggiornamento link prima del download…");
            io.execute(() -> {
                try {
                    TikTokResolver.Result r = TikTokResolver.resolve(item.sourceUrl);
                    item.mediaUrl = r.mediaUrl;
                    item.title = r.title;
                    item.resolvedAtMs = System.currentTimeMillis();
                    main.post(() -> {
                        FeedStore.save(this, feed, index);
                        enqueueDownload(item);
                    });
                } catch (Exception e) {
                    main.post(() -> toast("Download non disponibile: " + safeMessage(e)));
                }
            });
        } else {
            enqueueDownload(item);
        }
    }

    private void enqueueDownload(VideoItem item) {
        try {
            Uri uri = Uri.parse(item.mediaUrl);
            DownloadManager.Request req = new DownloadManager.Request(uri);
            req.setTitle(item.title);
            req.setDescription("Download da TokNative");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36");
            req.addRequestHeader("Referer", "https://www.tiktok.com/");
            String cookie = TikTokResolver.cookieHeader(item.mediaUrl);
            if (!cookie.isEmpty()) req.addRequestHeader("Cookie", cookie);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(false);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(new Date());
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "TokNative/TikTok_" + stamp + ".mp4");
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("DownloadManager non disponibile");
            dm.enqueue(req);
            toast("Download avviato in Movies/TokNative");
        } catch (Exception e) {
            toast("Download non avviato: " + safeMessage(e));
        }
    }

    private void refreshUi() {
        boolean has = !feed.isEmpty();
        emptyView.setVisibility(has ? View.GONE : View.VISIBLE);
        titleView.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) {
            VideoItem item = feed.get(index);
            titleView.setText(item.title + "\n" + (index + 1) + " / " + feed.size() + "  •  swipe ↑↓");
            if (statusView.getText().length() == 0) {
                setStatus("Scorri su/giù • tocca il video per Play/Pausa");
            }
        } else {
            titleView.setText("");
            setStatus(discoverLoading ? "Caricamento Discover…" : "Discover pronto • nessuna WebView");
        }
        boolean playing = bound && playerService != null && playerService.isPlaying();
        playButton.setText(playing ? "Ⅱ" : "▶");
    }


    private void refreshDiscover(boolean userRequested) {
        long now = System.currentTimeMillis();
        if (discoverLoading) return;
        if (!userRequested && now - lastDiscoveryAttempt < DISCOVERY_COOLDOWN_MS) return;
        lastDiscoveryAttempt = now;
        discoverLoading = true;
        setStatus("Aggiornamento Discover…");
        io.execute(() -> {
            List<VideoItem> discovered = DiscoveryFeed.discover();
            main.post(() -> {
                discoverLoading = false;
                int added = mergeDiscovered(discovered);
                FeedStore.save(this, feed, index);
                refreshUi();
                setStatus(added > 0 ? ("Discover aggiornato • +" + added + " video") : "Discover già aggiornato");
                if (autoPlayAfterDiscovery && !feed.isEmpty()) {
                    autoPlayAfterDiscovery = false;
                    playCurrent(false);
                }
            });
        });
    }

    private int mergeDiscovered(List<VideoItem> discovered) {
        if (discovered == null || discovered.isEmpty()) return 0;
        int added = 0;
        for (VideoItem candidate : discovered) {
            boolean exists = false;
            for (VideoItem current : feed) {
                if (current.sourceUrl.equals(candidate.sourceUrl)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                feed.add(candidate);
                added++;
            }
        }
        return added;
    }

    private void setStatus(String text) {
        statusView.setText(text == null ? "" : text);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9001);
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        if (videoSurface != null) videoSurface.release();
        videoSurface = new Surface(surfaceTexture);
        if (bound && playerService != null) playerService.attachSurface(videoSurface);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        if (bound && playerService != null && videoSurface != null) playerService.detachSurface(videoSurface);
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}