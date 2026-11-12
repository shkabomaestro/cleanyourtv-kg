package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Local addition for this TV: background sound on Home, the idle clock and the screensaver, chosen with the
 * sound button under the top bar. Either the audio files in the theme folder's music/ (in name order, round
 * and round), or a radio station from the theme folder's stations.txt, or nothing. The launcher stays offline:
 * a station is played by the radio add-on (addons/radio), which this only tells what to play.
 */
final class HomeMusic {
    private HomeMusic() { }

    /** The two sources that are not a station's name. MUSIC is the default; with an empty music/ it is silent. */
    static final String MUSIC = "music", OFF = "off";

    private static final String RADIO = "local.cleanyourtv.radio";
    /** Home pauses a moment before the clock resumes, and after the screensaver starts: wait before going quiet. */
    private static final long PAUSE_DELAY_MS = 1500;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable PAUSE = HomeMusic::pauseNow;

    private static MediaPlayer player;
    private static boolean prepared;
    private static boolean wanted;
    private static int next;
    /** How many of Home, the clock and the screensaver are on screen: the screensaver starts before Home pauses. */
    private static int holders;
    /** The application context of the last start(), for the pause that runs after its screen is gone. */
    private static Context last;

    static String source(Context ctx) {
        return prefs(ctx).getString("source", MUSIC);
    }

    /** The sound button's choice: MUSIC, OFF or a station's name. */
    static void select(Context ctx, String source) {
        Context app = ctx.getApplicationContext();
        prefs(app).edit().putString("source", source).apply();
        radio(app, source);
        if (!MUSIC.equals(source)) {
            wanted = false;
            if (player != null && prepared && player.isPlaying()) player.pause();
        } else if (holders > 0) {
            wanted = true;
            resume(app, true);
        }
    }

    /** {name, url} for each station in stations.txt; empty without the file or without the radio add-on. */
    static List<String[]> stations(Context ctx) {
        List<String[]> stations = new ArrayList<>();
        try {
            ctx.getPackageManager().getPackageInfo(RADIO, 0);
            for (String line : Files.readAllLines(new File(ctx.getExternalFilesDir(null), "stations.txt").toPath(),
                    StandardCharsets.UTF_8)) {
                String[] station = Station.parse(line);
                if (station != null) stations.add(station);
            }
        } catch (Exception e) {
            stations.clear();   // no add-on, no file, or not text: no stations
        }
        return stations;
    }

    /** Its own class so a unit test can load it: HomeMusic itself needs Android's main looper. */
    static final class Station {
        private Station() { }

        /** One line of stations.txt, "Name|http...", as {name, url}; null for anything else. */
        static String[] parse(String line) {
            int bar = line.indexOf('|');
            if (bar < 1) return null;
            String name = line.substring(0, bar).replace("﻿", "").trim(), url = line.substring(bar + 1).trim();
            boolean web = url.startsWith("http://") || url.startsWith("https://");
            return name.isEmpty() || !web || MUSIC.equals(name) || OFF.equals(name) ? null : new String[]{name, url};
        }
    }

    /** Home, the clock or the screensaver is on screen. */
    static void start(Context ctx) {
        holders++;
        MAIN.removeCallbacks(PAUSE);
        Context app = last = ctx.getApplicationContext();
        String source = source(app);
        if (MUSIC.equals(source)) {
            wanted = true;
            resume(app, true);
        } else if (!OFF.equals(source)) {
            radio(app, source);   // again after a film took the sound: the add-on ignores what is already on air
        }
    }

    /** That screen went away: the files go quiet shortly after, unless another of the three is still up. */
    static void stop() {
        holders = Math.max(0, holders - 1);
        if (holders == 0) MAIN.postDelayed(PAUSE, PAUSE_DELAY_MS);
    }

    /** Tells the add-on to play that station, or to stop when the source is not one. */
    private static void radio(Context app, String source) {
        Intent service = new Intent().setClassName(RADIO, RADIO + ".RadioService");
        try {
            for (String[] station : stations(app)) {
                if (station[0].equals(source)) {
                    app.startForegroundService(service.putExtra("name", station[0]).putExtra("url", station[1]));
                    return;
                }
            }
            app.stopService(service);
        } catch (RuntimeException e) {
            // the add-on is gone or signed with another key: nothing to play, nothing to stop
        }
    }

    private static void resume(Context app, boolean retry) {
        if (player != null && prepared && player.isPlaying()) return;
        AudioManager audio = app.getSystemService(AudioManager.class);
        if (audio != null && audio.isMusicActive()) {
            // Something else is playing: a cast keeps the floor, the tail of the app or station just left does not.
            if (retry) MAIN.postDelayed(() -> { if (holders > 0 && wanted) resume(app, false); }, 2500);
            return;
        }
        if (player == null) play(app, 0);
        else if (prepared) player.start();
    }

    /** An app is in front now: the files pause where they are, a station stops until Home is back. */
    private static void pauseNow() {
        if (holders > 0) return;
        wanted = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
        if (last != null && !MUSIC.equals(source(last))) radio(last, OFF);
    }

    /** Starts the next file; failures counts the files in a row that would not play, so a folder of bad files ends. */
    private static void play(Context app, int failures) {
        if (player != null) player.release();
        player = null;
        prepared = false;
        File[] files = new File(app.getExternalFilesDir(null), "music").listFiles(File::isFile);   // listed each time: new songs need no restart
        if (files == null || failures >= files.length) return;
        Arrays.sort(files);
        File file = files[next % files.length];
        next = (next + 1) % files.length;
        // shortcut: no audio focus request, resume() only checks that nothing else plays; add one if the assistant talks over it
        MediaPlayer p = new MediaPlayer();
        player = p;
        p.setOnPreparedListener(mp -> {
            prepared = true;
            if (wanted) mp.start();
        });
        p.setOnCompletionListener(mp -> play(app, 0));
        p.setOnErrorListener((mp, what, extra) -> {
            play(app, failures + 1);
            return true;
        });
        try {
            p.setDataSource(file.getPath());
            p.prepareAsync();
        } catch (Exception e) {   // not an audio file, or unreadable
            play(app, failures + 1);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("home_music", Context.MODE_PRIVATE);
    }
}
