package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local addition: the news ticker (kajron) along the bottom of Home. A red "ВЕСТИ" label stays at the left and the
 * headlines run under it from right to left at reading speed, sport marked in amber and the rest in blue, each
 * followed by its source. The launcher has no network: the radio add-on reads RTS, Mondo and Mozzart Sport, leaves
 * out crime stories and betting tips, puts the sport first and writes everything in Cyrillic (addons/radio, News);
 * this only asks it, when Home comes up and every ten minutes while it stays up. A new list takes over when the
 * running one has gone round, so a headline is never cut off mid-way. No add-on or no headlines: no ticker.
 */
final class NewsTicker extends View {
    private static final String PROVIDER = "content://local.cleanyourtv.radio.news/now";
    private static final long ASK_EVERY_MS = 10 * 60_000;
    private static final int SPORT = 0xFFFFC21A, NEWS = 0xFF64B5F6, BADGE = 0xFFD32F2F, BACK_TOP = 0xE6111622, BACK_BOTTOM = 0xF2070A11;

    /** One headline as the add-on sends it. */
    static final class Line {
        final String title, source;
        final boolean sport;

        Line(String title, String source, boolean sport) {
            this.title = title;
            this.source = source;
            this.sport = sport;
        }
    }

    /** The last list, kept across a rebuilt Home so the ticker comes back at once. */
    private static List<Line> kept = Collections.emptyList();
    private static long asked;

    private final float dp;
    private final Paint back = new Paint(), fade = new Paint(), badge = new Paint(Paint.ANTI_ALIAS_FLAG),
            label = new Paint(Paint.ANTI_ALIAS_FLAG), title = new Paint(Paint.ANTI_ALIAS_FLAG),
            source = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), rule = new Paint();
    private final RectF box = new RectF();
    private final String badgeText;
    /** The label's width, and where the running text starts (under the label's right edge). */
    private final float badgeWidth, left;

    private List<Line> lines = Collections.emptyList(), next;
    private float[] titleWidths = new float[0], sourceWidths = new float[0];
    private float loop, offset;
    private boolean running;
    private long lastFrame;

    NewsTicker(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        float sp = context.getResources().getDisplayMetrics().scaledDensity;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        badgeText = context.getString(R.string.news_ticker_label);
        // The system sans-serif: the theme's Outfit has no Cyrillic letters, so a mix of two typefaces would show.
        Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        label.setTextSize(13.5f * sp);
        label.setColor(0xFFFFFFFF);
        label.setLetterSpacing(0.1f);
        title.setTypeface(medium);
        title.setTextSize(16f * sp);
        title.setColor(0xFFF4F6FA);
        source.setTypeface(regular);
        source.setTextSize(12.5f * sp);
        source.setColor(0x99FFFFFF);
        badge.setColor(BADGE);
        rule.setColor(0x33FFFFFF);
        badgeWidth = label.measureText(badgeText) + 30 * dp;
        left = 56 * dp + badgeWidth;   // in line with Home's own left margin
        setLines(kept);
    }

    /** Asks the add-on in the background (at most every ten minutes, or at once with force) and shows what it gives. */
    void refresh(Activity activity, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - asked < ASK_EVERY_MS) return;
        asked = now;
        new Thread(() -> {
            List<Line> read = new ArrayList<>();
            try (Cursor row = activity.getContentResolver().query(Uri.parse(PROVIDER), null, null, null, null)) {
                while (row != null && row.moveToNext() && read.size() < 40) {
                    String t = row.getString(0);
                    if (t != null && !t.trim().isEmpty()) read.add(new Line(t.trim(), row.getString(1), row.getInt(2) == 1));
                }
            } catch (RuntimeException e) {
                return;   // no add-on, an older one without the news, or another signing key: keep what is shown
            }
            if (read.isEmpty()) return;
            activity.runOnUiThread(() -> {
                kept = read;
                if (lines.isEmpty()) setLines(read);
                else next = read;   // after the running list has gone round
            });
        }, "news").start();
    }

    private void setLines(List<Line> list) {
        lines = list;
        next = null;
        titleWidths = new float[list.size()];
        sourceWidths = new float[list.size()];
        loop = 0;
        for (int i = 0; i < list.size(); i++) {
            titleWidths[i] = title.measureText(list.get(i).title);
            sourceWidths[i] = list.get(i).source == null ? 0 : source.measureText(list.get(i).source);
            loop += segment(i);
        }
        offset = 0;
        setVisibility(list.isEmpty() ? GONE : VISIBLE);
        invalidate();
    }

    /** Width of one headline with its dot, its source and the gap after it. */
    private float segment(int i) {
        return 7 * dp + 9 * dp + titleWidths[i] + 12 * dp + sourceWidths[i] + 34 * dp;
    }

    void start() {
        if (running) return;
        running = true;
        lastFrame = 0;
        invalidate();
    }

    void stop() {
        running = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        back.setShader(new LinearGradient(0, 0, 0, h, BACK_TOP, BACK_BOTTOM, Shader.TileMode.CLAMP));
        fade.setShader(new LinearGradient(left, 0, left + 18 * dp, 0, BACK_BOTTOM, 0x00070A11, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, back);
        canvas.drawRect(0, 0, w, Math.max(1, dp), rule);
        long now = System.nanoTime();
        float step = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;

        if (!lines.isEmpty() && loop > 0) {
            offset += 78 * dp * step;   // reading speed
            if (offset >= loop) {
                offset -= loop;
                if (next != null) {
                    setLines(next);
                    offset = 0;
                }
            }
            float baseline = h / 2f - (title.descent() + title.ascent()) / 2f;
            float sourceBaseline = h / 2f - (source.descent() + source.ascent()) / 2f;
            canvas.save();
            canvas.clipRect(left, 0, w, h);
            float x = left + 18 * dp - offset;
            int i = 0;
            for (int guard = 0; x < w && guard < 400; guard++) {
                float width = segment(i);
                if (x + width > left) {
                    Line line = lines.get(i);
                    dot.setColor(line.sport ? SPORT : NEWS);
                    canvas.drawCircle(x + 3.5f * dp, h / 2f, 3.5f * dp, dot);
                    float tx = x + 7 * dp + 9 * dp;
                    canvas.drawText(line.title, tx, baseline, title);
                    if (line.source != null) canvas.drawText(line.source, tx + titleWidths[i] + 12 * dp, sourceBaseline, source);
                }
                x += width;
                i = (i + 1) % lines.size();
            }
            canvas.restore();
        }
        // the label last, over the text that slides under it, with a short fade on its right
        float top = h * 0.2f, bottom = h * 0.8f;
        canvas.drawRect(left, 0, left + 18 * dp, h, fade);
        box.set(56 * dp, top, 56 * dp + badgeWidth - 12 * dp, bottom);
        canvas.drawRoundRect(box, 4 * dp, 4 * dp, badge);
        canvas.drawText(badgeText, box.left + (box.width() - label.measureText(badgeText)) / 2f,
                h / 2f - (label.descent() + label.ascent()) / 2f, label);
        if (running && !lines.isEmpty()) postInvalidateOnAnimation();
    }
}
