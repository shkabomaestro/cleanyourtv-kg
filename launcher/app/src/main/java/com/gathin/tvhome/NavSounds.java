package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Local addition: Home's own two navigation sounds (res/raw/nav_move.wav and nav_select.wav, made by
 * work/make_nav_sounds.py) in place of Android's click. The TV's "System sounds" setting is switched off for
 * this, or both would play. shortcut: Home's own window only, menus and the App library stay silent; hook
 * their windows the same way if that is missed.
 */
final class NavSounds {
    private NavSounds() { }

    private static SoundPool pool;
    private static int move, select;
    private static long lastArrow;

    static void load(Context ctx) {
        if (pool != null) return;
        // Sonification, not media: a tick must not look like music to HomeMusic.resume().
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        move = pool.load(ctx.getApplicationContext(), R.raw.nav_move, 1);
        select = pool.load(ctx.getApplicationContext(), R.raw.nav_select, 1);
    }

    /** Every key Home sees: OK sounds at once, an arrow only if the focus then moves (focusMoved). */
    static void key(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return;
        int code = event.getKeyCode();
        if (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT) lastArrow = SystemClock.uptimeMillis();
        else if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && event.getRepeatCount() == 0) play(select);
    }

    /** The focus changed: a sound if an arrow key did it, none when Home moves the focus itself. */
    static void focusMoved() {
        if (SystemClock.uptimeMillis() - lastArrow < 400) move();
    }

    static void move() {
        play(move);
    }

    private static void play(int sound) {
        if (pool != null) pool.play(sound, 0.6f, 0.6f, 1, 0, 1f);
    }
}
