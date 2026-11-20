package local.cleanyourtv.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
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
 *
 * Local: it never stays silent. A dropped or stalled stream is opened again at once (then after 1, 2 and up to 4
 * seconds while it keeps failing); a stream that hangs without an error (buffering, or playing with the position
 * standing still) counts as dropped after ten seconds; the network coming back reconnects at once; and if Android
 * stops the add-on, it starts it again with the last station (START_STICKY).
 */
public class RadioService extends Service implements AudioManager.OnAudioFocusChangeListener, Player.Listener {
    static final String NAME = "name", URL = "url";
    /** "Artist - Title" as the station sends it (ICY metadata), for the launcher's sound line; null when unknown. */
    static volatile String nowPlaying;
    /** Waits between attempts while a station keeps failing: the first at once, the last repeated. */
    private static final long[] RETRY_MS = {300, 1000, 2000, 4000};
    /** How long a stream may hang (buffering, or the position standing still) before it is opened again. */
    private static final long STALL_MS = 10_000, WATCH_MS = 1000;
    private static final long LINK_CHECK_MS = 5_000;
    /** After each renew in a row, a longer wait before the next: a network that stays bad is not bounced every 30 s. */
    private static final long[] AFTER_RENEW_MS = {30_000, 60_000, 120_000, 300_000};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread linkThread = new HandlerThread("link");
    private Handler link;
    private ExoPlayer player;
    private WifiManager.WifiLock wifiLock;
    private String url;
    private int failures;
    private int renews;   // Wi-Fi renews in a row, on the link thread
    private int offline;  // checks in a row where the router answers but the internet does not
    private final WifiChooser chooser = new WifiChooser();
    private String lastUrl;
    private long lastPosition = -1, progressAt;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences last = getSharedPreferences("radio", MODE_PRIVATE);
        // intent null: Android restarted the add-on after stopping it; play the last station again
        String wanted = intent == null ? last.getString(URL, null) : intent.getStringExtra(URL);
        String name = intent == null ? last.getString(NAME, null) : intent.getStringExtra(NAME);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("radio", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "radio").setSmallIcon(R.drawable.icon)
                .setContentTitle(name).build());
        // Restarted by Android while something else plays (YouTube, Netflix): leave the sound to it.
        boolean othersPlaying = intent == null && player == null && getSystemService(AudioManager.class).isMusicActive();
        if (othersPlaying || wanted == null || !(wanted.startsWith("http://") || wanted.startsWith("https://"))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wanted.equals(url) && player != null) {   // Home asking again for what is on air
            if (!player.isPlaying() && player.getPlaybackState() != Player.STATE_BUFFERING) reconnectNow();
            return START_STICKY;
        }
        url = wanted;
        last.edit().putString(URL, wanted).putString(NAME, name).apply();
        failures = 0;
        getSystemService(AudioManager.class).requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (wifiLock == null) {
            // What a streaming app is expected to hold: keeps the Wi-Fi chip out of power save while a station plays.
            wifiLock = getApplicationContext().getSystemService(WifiManager.class)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "radio");
            wifiLock.acquire();
            linkThread.start();
            link = new Handler(linkThread.getLooper());
            link.postDelayed(this::checkLink, LINK_CHECK_MS);
            watchNetwork();
        }
        connect();
        return START_STICKY;
    }

    /** The network back (Wi-Fi rejoined, router restarted): reconnect at once instead of waiting for an error. */
    private void watchNetwork() {
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                main.post(() -> {
                    if (player != null && !player.isPlaying()) reconnectNow();
                });
            }
        };
        try {
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;   // the stall watch still catches a dead stream
        }
    }

    /** Once a second: a stream that hangs with no error is opened again after STALL_MS. */
    private void watch() {
        if (player == null || url == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        long position = player.getCurrentPosition();
        if (player.isPlaying() && position != lastPosition) {
            lastPosition = position;
            progressAt = now;
            failures = 0;   // sound is coming: the next drop retries at once again
        } else if (now - progressAt > STALL_MS) {
            reconnectNow();
            return;
        }
        main.postDelayed(this::watch, WATCH_MS);
    }

    private void reconnectNow() {
        main.removeCallbacksAndMessages(null);
        connect();
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
        player.stop();   // a fresh connection, not a resume of the broken one
        if (!url.equals(lastUrl)) nowPlaying = null;   // another station: its own song comes with its stream
        lastUrl = url;
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        lastPosition = -1;
        progressAt = android.os.SystemClock.elapsedRealtime();
        main.postDelayed(this::watch, WATCH_MS);
    }

    /** The stream dropped or would not open: at once, then a little later each time it keeps failing. */
    private void retry() {
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::connect, RETRY_MS[Math.min(failures, RETRY_MS.length - 1)]);
        failures++;
    }

    /**
     * Every five seconds, on its own thread: is the link alive? On this stick (MiTV-AESP0 on a phone hotspot) the link
     * goes dead for minutes while Android still shows it connected: no disconnect event and no packets either way, so
     * nothing ever reconnects. When neither the router nor the internet answers, the stick leaves the Wi-Fi network and
     * joins again, which is what switching Wi-Fi off and on by hand does; Android then picks the best saved network in
     * range.
     *
     * The router alone is not enough: a guest network, or a router that ignores pings, never answers one while the
     * internet works fine, and asking only the router bounced such a network every 40 seconds (a stick at an office,
     * November 2026: it hopped between the guest network and a phone hotspot all evening). And a network that stays
     * dead after a renew is renewed again later and later, not every 30 seconds.
     */
    @SuppressWarnings("deprecation")   // getDhcpInfo, disconnect and reconnect: the last two only work below targetSdk 29
    private void checkLink() {
        WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
        int gateway = wifi.getDhcpInfo().gateway;   // 0 while not connected: Android is already reconnecting
        long wait = LINK_CHECK_MS;
        boolean router = gateway == 0 || answers(gateway);
        // Silent router: ask the internet itself. Router fine: Android's own check of the internet is enough.
        boolean online = gateway != 0 && (router ? validated() : internet());
        if (gateway != 0 && !router && !online) {
            android.util.Log.i("radio", "Wi-Fi link dead (router and internet silent): renewing, " + (renews + 1) + " in a row");
            chooser.avoidCurrent(wifi);   // and if another saved network is in range, it is the one to join
            if (!chooser.switchToBest(wifi, true)) {
                wifi.disconnect();
                wifi.reconnect();
            }
            wait = AFTER_RENEW_MS[Math.min(renews, AFTER_RENEW_MS.length - 1)];
            renews++;
            offline = 0;
        } else if (gateway != 0) {
            renews = 0;
            // Joined but no internet (a guest network's sign-in page, a hotspot out of data): after 20 s, move on.
            offline = online ? 0 : offline + 1;
            if (offline >= 4) {
                chooser.avoidCurrent(wifi);
                if (chooser.switchToBest(wifi, true)) offline = 0;
            } else {
                chooser.switchToBest(wifi, false);   // the strongest saved network in range, when clearly stronger
            }
        }
        link.postDelayed(this::checkLink, wait);
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

    /** Android found the internet through the network it uses now (its own check, redone when that network changes). */
    private boolean validated() {
        try {
            ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
            android.net.NetworkCapabilities caps = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            return caps == null || caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return true;   // cannot tell: never a reason to move
        }
    }

    /** A TCP connection to a public DNS server, two of them, 2 s each: the internet through this link answers. */
    private static boolean internet() {
        for (String host : new String[] {"1.1.1.1", "8.8.8.8"}) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, 53), 2000);
                return true;
            } catch (IOException e) {
                // the next one
            }
        }
        return false;
    }

    @Override
    public void onMetadata(androidx.media3.common.Metadata metadata) {
        for (int i = 0; i < metadata.length(); i++) {
            androidx.media3.common.Metadata.Entry entry = metadata.get(i);
            if (entry instanceof androidx.media3.extractor.metadata.icy.IcyInfo) {
                String title = ((androidx.media3.extractor.metadata.icy.IcyInfo) entry).title;
                nowPlaying = title == null ? null : title.trim();
            }
        }
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
        nowPlaying = null;
        if (networkCallback != null) {
            try {
                getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
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
