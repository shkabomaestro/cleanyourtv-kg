package local.cleanyourtv.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

import java.io.IOException;
import java.net.InetAddress;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Plays the station the launcher's sound line names (extras "name" and "url"), until another app takes the
 * sound (YouTube, Netflix) or the launcher stops the service. The launcher asks again each time Home comes up.
 * ExoPlayer, not the system MediaPlayer: this stick's own player refuses AAC+ streams (Radio Kragujevac).
 */
public class RadioService extends Service implements AudioManager.OnAudioFocusChangeListener, Player.Listener {
    static final String NAME = "name", URL = "url";
    private static final long RETRY_MS = 5000;
    private static final long LINK_CHECK_MS = 5_000, AFTER_RENEW_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread linkThread = new HandlerThread("link");
    private Handler link;
    private ExoPlayer player;
    private WifiManager.WifiLock wifiLock;
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
        if (wifiLock == null) {
            // What a streaming app is expected to hold: keeps the Wi-Fi chip out of power save while a station plays.
            wifiLock = getApplicationContext().getSystemService(WifiManager.class)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "radio");
            wifiLock.acquire();
            linkThread.start();
            link = new Handler(linkThread.getLooper());
            link.postDelayed(this::checkLink, LINK_CHECK_MS);
        }
        connect();
        return START_NOT_STICKY;
    }

    /** (Re)opens the stream. */
    private void connect() {
        main.removeCallbacksAndMessages(null);
        if (player == null) {
            // Start after 4 s of sound in hand and resume after 8 s, not the default 2.5 and 5: a mobile link loses
            // packets in short bursts, and a radio stream has no buffer to fall back on otherwise.
            player = new ExoPlayer.Builder(this).setLoadControl(new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, 4000, 8000)
                    .build()).build();
            player.addListener(this);
        }
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
    }

    /** The stream dropped or would not open: try again shortly. */
    private void retry() {
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::connect, RETRY_MS);
    }

    /**
     * Every five seconds, on its own thread: does the router still answer? On this stick (MiTV-AESP0 on a phone
     * hotspot) the link goes dead for minutes while Android still shows it connected: no disconnect event and no
     * packets either way, so nothing ever reconnects. When the router stays silent, the stick leaves the Wi-Fi
     * network and joins again, which is what switching Wi-Fi off and on by hand does; Android then picks the best
     * saved network in range. Only the router is asked, so a station or an internet outage never bounces Wi-Fi.
     */
    @SuppressWarnings("deprecation")   // getDhcpInfo, disconnect and reconnect: the last two only work below targetSdk 29
    private void checkLink() {
        WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
        int gateway = wifi.getDhcpInfo().gateway;   // 0 while not connected: Android is already reconnecting
        boolean dead = gateway != 0 && !answers(gateway);
        if (dead) {
            wifi.disconnect();
            wifi.reconnect();
        }
        link.postDelayed(this::checkLink, dead ? AFTER_RENEW_MS : LINK_CHECK_MS);
    }

    /** Three tries of 1.5 s: a live link on a busy hotspot loses the odd ping, a dead one loses them all. */
    private static boolean answers(int address) {
        byte[] ip = {(byte) address, (byte) (address >> 8), (byte) (address >> 16), (byte) (address >> 24)};
        for (int i = 0; i < 3; i++) {
            try {
                if (InetAddress.getByAddress(ip).isReachable(1500)) return true;
            } catch (IOException e) {
                // counts as a silent try
            }
        }
        return false;
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        retry();
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_ENDED) retry();   // the station closed the stream
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) stopSelf();   // another app plays now
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        if (wifiLock != null) {
            wifiLock.release();
            link.removeCallbacksAndMessages(null);
            linkThread.quit();
        }
        getSystemService(AudioManager.class).abandonAudioFocus(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
