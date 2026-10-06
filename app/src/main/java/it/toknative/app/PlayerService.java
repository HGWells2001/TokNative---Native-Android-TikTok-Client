package it.toknative.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Surface;

import java.util.HashMap;
import java.util.Map;

public class PlayerService extends Service implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener {

    public static final String ACTION_TOGGLE = "it.toknative.app.action.TOGGLE";
    public static final String ACTION_STOP = "it.toknative.app.action.STOP";
    private static final String CHANNEL_ID = "toknative_playback";
    private static final int NOTIFICATION_ID = 1042;
    private static final String PREFS = "toknative_player";
    private static final String KEY_URL = "url";
    private static final String KEY_TITLE = "title";
    private static final String KEY_POS = "position";

    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaPlayer player;
    private Surface surface;
    private String currentUrl;
    private String currentTitle = "TokNative";
    private long pendingSeekMs = 0L;
    private boolean prepared = false;
    private boolean foreground = false;
    private MediaSession mediaSession;
    private AudioManager audioManager;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (player != null && prepared && !player.isPlaying()) {
                player.start();
                updatePlaybackState();
                updateNotification();
            }
        }
    };

    private final Runnable checkpoint = new Runnable() {
        @Override public void run() {
            savePosition();
            handler.postDelayed(this, 2000L);
        }
    };

    public final class LocalBinder extends Binder {
        public PlayerService getService() { return PlayerService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mediaSession = new MediaSession(this, "TokNativeSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stopEverything(); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
        });
        mediaSession.setActive(true);
        handler.post(checkpoint);
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            toggle();
        } else if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        } else {
            ensureForeground();
        }
        return START_NOT_STICKY;
    }

    public void attachSurface(Surface newSurface) {
        surface = newSurface;
        if (player != null) {
            try { player.setSurface(surface); } catch (Exception ignored) {}
        }
    }

    public void detachSurface(Surface oldSurface) {
        if (surface == oldSurface) surface = null;
        if (player != null) {
            try { player.setSurface(null); } catch (Exception ignored) {}
        }
    }

    public void play(String url, String title, long startPositionMs) {
        if (url == null || url.trim().isEmpty()) return;
        ensureForeground();

        if (url.equals(currentUrl) && player != null && prepared) {
            if (startPositionMs > 0 && Math.abs(getPosition() - startPositionMs) > 3000) {
                seekTo(startPositionMs);
            }
            resume();
            return;
        }

        releasePlayer();
        currentUrl = url;
        currentTitle = (title == null || title.trim().isEmpty()) ? "Video TikTok" : title;
        pendingSeekMs = Math.max(0L, startPositionMs);
        prepared = false;

        player = new MediaPlayer();
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
        player.setLooping(true);
        player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
        if (surface != null) player.setSurface(surface);

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36");
            headers.put("Referer", "https://www.tiktok.com/");
            String cookie = TikTokResolver.cookieHeader(url);
            if (!cookie.isEmpty()) headers.put("Cookie", cookie);
            player.setDataSource(this, Uri.parse(url), headers);
            player.prepareAsync();
            persistSnapshot(0L);
            updatePlaybackState();
            updateNotification();
        } catch (Exception e) {
            releasePlayer();
            updateNotification("Errore durante l'apertura del video");
        }
    }

    @Override public void onPrepared(MediaPlayer mp) {
        prepared = true;
        if (pendingSeekMs > 0) {
            try { mp.seekTo((int)Math.min(Integer.MAX_VALUE, pendingSeekMs)); } catch (Exception ignored) {}
        }
        requestAudioFocus();
        mp.start();
        updatePlaybackState();
        updateNotification();
    }

    @Override public void onCompletion(MediaPlayer mp) {
        try {
            mp.seekTo(0);
            mp.start();
        } catch (Exception ignored) {}
    }

    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
        prepared = false;
        updatePlaybackState();
        updateNotification("Flusso video non più valido. Riaprilo per aggiornarlo.");
        return true;
    }

    public void toggle() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) pause(); else resume();
    }

    public void pause() {
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
            savePosition();
            updatePlaybackState();
            updateNotification();
        }
    }

    public void resume() {
        if (player != null && prepared && !player.isPlaying()) {
            requestAudioFocus();
            player.start();
            updatePlaybackState();
            updateNotification();
        }
    }

    public void seekTo(long ms) {
        if (player != null && prepared) {
            try { player.seekTo((int)Math.min(Integer.MAX_VALUE, Math.max(0L, ms))); } catch (Exception ignored) {}
        }
    }

    public boolean isPlaying() {
        try { return player != null && prepared && player.isPlaying(); }
        catch (Exception e) { return false; }
    }

    public long getPosition() {
        try { return player != null && prepared ? player.getCurrentPosition() : 0L; }
        catch (Exception e) { return 0L; }
    }

    public String getCurrentUrl() { return currentUrl; }

    public static long savedPosition(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_POS, 0L);
    }

    public static String savedUrl(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "");
    }

    private void requestAudioFocus() {
        if (audioManager != null) {
            try {
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            } catch (Exception ignored) {}
        }
    }

    private void savePosition() {
        if (currentUrl == null || currentUrl.trim().isEmpty()) return;
        persistSnapshot(getPosition());
    }

    private void persistSnapshot(long position) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_URL, currentUrl == null ? "" : currentUrl)
                .putString(KEY_TITLE, currentTitle == null ? "" : currentTitle)
                .putLong(KEY_POS, Math.max(0L, position))
                .apply();
    }

    private void ensureForeground() {
        if (!foreground) {
            Notification notification = buildNotification(null);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
        } else {
            updateNotification();
        }
    }

    private void updateNotification() { updateNotification(null); }

    private void updateNotification(String message) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (foreground && nm != null) nm.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private Notification buildNotification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent toggle = new Intent(this, PlayerService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getService(this, 2, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, PlayerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean playing = isPlaying();
        int playIcon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playText = playing ? "Pausa" : "Riproduci";

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle == null ? "TokNative" : currentTitle)
                .setContentText(message == null ? (playing ? "Audio in background attivo" : "Riproduzione in pausa") : message)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(playIcon, playText, togglePi).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Chiudi", stopPi).build());

        if (mediaSession != null) {
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0));
        }
        return b.build();
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        int state = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, getPosition(), isPlaying() ? 1f : 0f)
                .build());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Riproduzione TokNative", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Mantiene l'audio dei video attivo in background e a schermo bloccato");
            c.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    private void stopEverything() {
        savePosition();
        releasePlayer();
        if (audioManager != null) {
            try { audioManager.abandonAudioFocus(focusListener); } catch (Exception ignored) {}
        }
        if (mediaSession != null) mediaSession.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        prepared = false;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(checkpoint);
        savePosition();
        releasePlayer();
        if (mediaSession != null) mediaSession.release();
        if (audioManager != null) {
            try { audioManager.abandonAudioFocus(focusListener); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}