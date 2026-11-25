package local.fonoteka;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.IBinder;
import java.util.ArrayList;

public final class Playback extends Service {
    // Activity lifecycle and service callbacks run on the main thread.
    static final java.util.Set<Runnable> listeners = new java.util.LinkedHashSet<>();
    static String currentTitle;
    static boolean playing;
    private MediaPlayer player;
    private MediaSession session;
    private ArrayList<String> uris;
    private ArrayList<String> titles;
    private int index;
    private boolean ready;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("playback", "Воспроизведение", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "Фонотека");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { pause(false); }
            @Override public void onPause() { pause(true); }
            @Override public void onSkipToNext() { skip(1); }
            @Override public void onSkipToPrevious() { skip(-1); }
            @Override public void onSeekTo(long position) { if (ready) player.seekTo((int) position); }
            @Override public void onStop() { stopSelf(); }
        });
        session.setActive(true);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        switch (intent.getAction()) {
            case "play":
                uris = intent.getStringArrayListExtra("uris");
                titles = intent.getStringArrayListExtra("titles");
                index = intent.getIntExtra("index", 0);
                open();
                break;
            case "toggle": if (ready) pause(player.isPlaying()); break;
            case "next": skip(1); break;
            case "previous": skip(-1); break;
            case "stop": stopSelf(); break;
        }
        return START_NOT_STICKY;
    }

    private void skip(int step) {
        if (uris != null && index + step >= 0 && index + step < uris.size()) {
            index += step;
            open();
        }
    }

    private void open() {
        if (player != null) player.release();
        ready = false;
        update(PlaybackState.STATE_BUFFERING);
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        player.setOnPreparedListener(mp -> {
            ready = true;
            session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titles.get(index))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, mp.getDuration()).build());
            pause(false);
        });
        player.setOnCompletionListener(mp -> {
            if (index + 1 < uris.size()) skip(1);
            else update(PlaybackState.STATE_PAUSED);
        });
        player.setOnErrorListener((mp, what, extra) -> { fail(); return true; });
        try {
            player.setDataSource(this, Uri.parse(uris.get(index)));
            player.prepareAsync();
        } catch (Exception error) { fail(); }
    }

    private void fail() {
        android.widget.Toast.makeText(this, "Не удалось воспроизвести файл", android.widget.Toast.LENGTH_LONG).show();
        stopSelf();
    }

    private void pause(boolean paused) {
        if (!ready) return;
        if (paused) player.pause(); else player.start();
        update(paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING);
    }

    private PendingIntent action(String name, int code) {
        return PendingIntent.getService(this, code, new Intent(this, Playback.class).setAction(name),
            PendingIntent.FLAG_IMMUTABLE);
    }

    private void update(int state) {
        playing = state == PlaybackState.STATE_PLAYING;
        currentTitle = titles.get(index);
        for (Runnable listener : listeners) listener.run();
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO)
            .setState(state, ready ? player.getCurrentPosition() : 0, playing ? 1 : 0).build());
        startForeground(1, new Notification.Builder(this, "playback")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(titles.get(index))
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Назад", action("previous", 1)).build())
            .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Пауза" : "Играть", action("toggle", 2)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Далее", action("next", 3)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
            .build());
    }

    @Override public void onDestroy() {
        if (player != null) player.release();
        session.release();
        currentTitle = null;
        playing = false;
        for (Runnable listener : listeners) listener.run();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

