package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Local addition (zvezda_card): Zvezda's next football and basketball games on a small card right above the news
 * ticker's red label, as wide as the label, with the club's crest faint behind the text. The radio add-on fetches
 * them (its games provider, the same rows as game_day plus the pieces this card lays out) and the crest; Home only
 * reads. Football and basketball take turns every twenty seconds.
 *
 *   ЧЕТ 3.12. 18:45 · ЕВРОЛИГА
 *   Фенербахче – Звезда
 *   🏀 за 7д 20ч
 */
final class ZvezdaCard extends View {
    private static final Uri TURNS = Uri.parse("content://local.cleanyourtv.radio.games/turns");
    private static final Uri CREST = Uri.parse("content://local.cleanyourtv.radio.games/crest");
    private static final long TURN_MS = 20_000, READ_MS = 60_000, FADE_MS = 400;
    private static final int RED = 0xFFE30613, AMBER = 0xFFFFC107, LIVE_RED = 0xFFFF5252;

    private final float dp, sp;
    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(), crestPaint = new Paint(Paint.FILTER_BITMAP_FLAG),
            top = new Paint(Paint.ANTI_ALIAS_FLAG), teams = new Paint(Paint.ANTI_ALIAS_FLAG), bottom = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private final Rect crestFrom = new Rect();
    private final RectF crestTo = new RectF();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Game> games = Collections.emptyList();
    private Bitmap crest;
    private int on;
    private long turnStart;
    private boolean running;

    private static final class Game {
        final String sport, when, teams, competition;
        final long start;
        final int state;

        Game(String sport, String when, String teams, String competition, long start, int state) {
            this.sport = sport;
            this.when = when;
            this.teams = teams;
            this.competition = competition;
            this.start = start;
            this.state = state;
        }
    }

    ZvezdaCard(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        sp = context.getResources().getDisplayMetrics().scaledDensity;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
        back.setColor(0xD90B0F18);
        bar.setColor(RED);
        crestPaint.setAlpha(70);
        // The system sans-serif, as the ticker below: the theme's Outfit has no Cyrillic letters.
        top.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.setColor(0xB3FFFFFF);
        top.setLetterSpacing(0.08f);
        teams.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        teams.setColor(0xFFFFFFFF);
        bottom.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    private final Runnable read = new Runnable() {
        @Override public void run() {
            Context ctx = getContext().getApplicationContext();
            new Thread(() -> {
                List<Game> found = new ArrayList<>();
                try (Cursor c = ctx.getContentResolver().query(TURNS, null, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        int when = c.getColumnIndex("when_text");
                        if (when < 0) break;   // an older add-on without the card's columns
                        found.add(new Game(c.getString(c.getColumnIndex("sport")), c.getString(when),
                                c.getString(c.getColumnIndex("teams")), c.getString(c.getColumnIndex("competition")),
                                c.getLong(c.getColumnIndex("start_ms")), c.getInt(c.getColumnIndex("state"))));
                    }
                } catch (RuntimeException e) {
                    return;   // no add-on, or it crashed mid-query: keep what is shown
                }
                Bitmap picture = crest;
                if (picture == null && !found.isEmpty()) {
                    try (InputStream in = ctx.getContentResolver().openInputStream(CREST)) {
                        picture = in == null ? null : BitmapFactory.decodeStream(in);
                    } catch (Exception e) {
                        picture = null;   // the card works without it
                    }
                }
                Bitmap got = picture;
                main.post(() -> {
                    crest = got;
                    if (found.size() != games.size()) on = 0;
                    games = found;
                    setVisibility(games.isEmpty() ? GONE : VISIBLE);
                    invalidate();
                });
            }, "zvezda").start();
            main.postDelayed(this, READ_MS);
        }
    };

    void start() {
        if (running) return;
        running = true;
        turnStart = System.currentTimeMillis();
        main.removeCallbacks(read);
        read.run();
        invalidate();
    }

    void stop() {
        running = false;
        main.removeCallbacks(read);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (games.isEmpty()) return;
        long now = System.currentTimeMillis();
        long since = now - turnStart;
        if (games.size() > 1 && since >= TURN_MS) {
            on = (on + 1) % games.size();
            turnStart = now;
            since = 0;
        }
        Game g = games.get(Math.min(on, games.size() - 1));
        float w = getWidth(), h = getHeight(), r = 5 * dp, pad = 9 * dp;
        card.set(0, 0, w, h);
        canvas.drawRoundRect(card, r, r, back);
        if (crest != null) {   // the crest, faint, at the right, a little bigger than the card so it reads as a background
            float size = h * 1.15f, ratio = crest.getWidth() / (float) Math.max(1, crest.getHeight());
            float cw = size * ratio;
            crestFrom.set(0, 0, crest.getWidth(), crest.getHeight());
            crestTo.set(w - cw * 0.85f, (h - size) / 2f, w + cw * 0.15f, (h + size) / 2f);
            canvas.save();
            canvas.clipRect(card);
            canvas.drawBitmap(crest, crestFrom, crestTo, crestPaint);
            canvas.restore();
        }
        canvas.drawRect(0, r, 3 * dp, h - r, bar);

        // A short fade in at each turn, then still.
        int alpha = games.size() > 1 ? (int) (255 * Math.min(1f, since / (float) FADE_MS)) : 255;
        float room = w - pad - 6 * dp;
        String head = (g.when + (g.competition == null || g.competition.isEmpty() ? "" : " · " + g.competition))
                .toUpperCase(new Locale("sr"));
        fit(top, head, room, 9.5f, 7.5f);
        fit(teams, g.teams, room, 13f, 9.5f);
        boolean live = g.state == 2;
        bottom.setColor(live ? LIVE_RED : g.state == 1 ? AMBER : 0xE6FFFFFF);
        String icon = "football".equals(g.sport) ? "⚽" : "🏀";
        String tail;
        if (live) {
            tail = icon + "  ● " + getContext().getString(R.string.game_live);
        } else {
            long left = Math.max(0, g.start - now);
            String in = RaceWeekend.countdown(getResources(), Duration.ofMillis(left));
            tail = icon + "  " + getContext().getString(R.string.race_next, "", in).trim();
        }
        fit(bottom, tail, room, 12f, 9f);

        top.setAlpha(alpha * 0xB3 / 255);
        teams.setAlpha(alpha);
        bottom.setAlpha(alpha * android.graphics.Color.alpha(bottom.getColor()) / 255);
        float y1 = pad - top.ascent() - 2 * dp;
        canvas.drawText(cut(top, head, room), pad, y1, top);
        float y2 = h / 2f - (teams.descent() + teams.ascent()) / 2f;
        canvas.drawText(cut(teams, g.teams, room), pad, y2, teams);
        float y3 = h - pad + 2 * dp - bottom.descent();
        canvas.drawText(cut(bottom, tail, room), pad, y3, bottom);

        if (running) {
            if (games.size() > 1 && since < FADE_MS) postInvalidateOnAnimation();
            else postInvalidateDelayed(Math.max(1000, Math.min(30_000, TURN_MS - since)));
        }
    }

    /** The largest size from max down to min (in sp) at which the text fits the room. */
    private void fit(Paint p, String text, float room, float max, float min) {
        float size = max;
        p.setTextSize(size * sp);
        while (size > min && p.measureText(text) > room) {
            size -= 0.5f;
            p.setTextSize(size * sp);
        }
    }

    /** The text, cut with "…" if even the smallest size is too wide. */
    private static String cut(Paint p, String text, float room) {
        if (p.measureText(text) <= room) return text;
        int n = p.breakText(text, true, room - p.measureText("…"), null);
        return text.substring(0, Math.max(0, n)).trim() + "…";
    }
}
