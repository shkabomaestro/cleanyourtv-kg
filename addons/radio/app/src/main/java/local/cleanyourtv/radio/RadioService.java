package local.cleanyourtv.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Plays the station the launcher's sound line names (extras "name" and "url"), until another app takes the
 * sound (YouTube, Netflix) or the launcher stops the service. The launcher asks again each time Home comes up.
 * ExoPlayer, not the system MediaPlayer: this stick's own player refuses AAC+ streams (Radio Kragujevac).
 */
public class RadioService extends Service implements AudioManager.OnAudioFocusChangeListener, Player.Listener {
    static final String NAME = "name", URL = "url";
    private static final long RETRY_MS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private String url;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String wanted = intent == null ? null : intent.getStringExtra(URL);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("radio", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "radio").setSmallIcon(R.drawable.icon)
                .setContentTitle(intent == null ? null : intent.getStringExtra(NAME)).build());
        if (wanted == null || !(wanted.startsWith("http://") || wanted.startsWith("https://"))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wanted.equals(url) && player != null) return START_NOT_STICKY;   // Home asking again for what is on air
        url = wanted;
        getSystemService(AudioManager.class).requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        connect();
        return START_NOT_STICKY;
    }

    /** (Re)opens the stream. A stream that drops or will not open is tried again: Wi-Fi on a stick comes and goes. */
    private void connect() {
        main.removeCallbacksAndMessages(null);
        if (player == null) {
            player = new ExoPlayer.Builder(this).build();
            player.addListener(this);
        }
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        main.postDelayed(this::connect, RETRY_MS);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_ENDED) main.postDelayed(this::connect, RETRY_MS);   // the station closed the stream
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) stopSelf();   // another app plays now
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        getSystemService(AudioManager.class).abandonAudioFocus(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
