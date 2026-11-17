package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Calendar;
import java.util.Random;

/**
 * Local addition: the weather outside, drawn live over the wallpaper and under everything else on Home and on the
 * screensaver. Sunshine warms and lights the picture from the top right with slow rays and drifting motes; clouds
 * drift; fog rolls; drizzle, rain and showers fall as streaks (more, longer and darker the harder it rains) with
 * drops landing and sliding on the "glass" and, in heavy rain, splashes along the bottom; snow falls as flakes that
 * sway; a thunderstorm adds heavy rain and an occasional soft flash. The sky comes from the radio add-on
 * (Weather.sky, Open-Meteo's current WMO code for Kragujevac), checked every few seconds; no sky, no effect.
 *
 * Kept light for a 1 GB stick: no blur, no video, a few hundred lines and circles at most, about 30 frames a second,
 * and nothing at all while it is stopped (an app in front, the screensaver gone). The lightning is soft and rare,
 * never more than two quick pulses, so it never strobes.
 */
final class WeatherFx extends View {
    static final int NONE = -1, CLEAR = 0, PARTLY = 1, OVERCAST = 2, FOG = 3, DRIZZLE = 4, RAIN = 5, SNOW = 6, SLEET = 7, STORM = 8;

    /** What to draw: one of the kinds above, how strong (0..1), by day or night, how far the wind slants it, hail. */
    static final class Scene {
        final int kind;
        final float intensity, slant;
        final boolean day, hail;

        Scene(int kind, float intensity, boolean day, float slant, boolean hail) {
            this.kind = kind;
            this.intensity = intensity;
            this.day = day;
            this.slant = slant;
            this.hail = hail;
        }

        boolean same(Scene o) {
            return o != null && o.kind == kind && o.day == day && o.hail == hail
                    && Math.abs(o.intensity - intensity) < 0.05f && Math.abs(o.slant - slant) < 0.05f;
        }
    }

    /**
     * The scene for a WMO weather code. day is 1 or 0, or -1 for "work it out from the hour"; precipitation in mm in the
     * last hour leans the strength of rain and snow toward what is really falling; wind (km/h) slants it.
     */
    static Scene scene(int code, int day, float precipitation, float wind, int hour) {
        boolean isDay = day >= 0 ? day == 1 : hour >= 7 && hour < 18;
        float slant = Math.max(0f, Math.min(0.5f, wind / 70f));
        int kind;
        float strength;
        boolean hail = false;
        switch (code) {
            case 0: kind = CLEAR; strength = 1f; break;
            case 1: kind = CLEAR; strength = 0.8f; break;
            case 2: kind = PARTLY; strength = 0.6f; break;
            case 3: kind = OVERCAST; strength = 1f; break;
            case 45: kind = FOG; strength = 0.8f; break;
            case 48: kind = FOG; strength = 1f; break;
            case 51: kind = DRIZZLE; strength = 0.25f; break;
            case 53: kind = DRIZZLE; strength = 0.4f; break;
            case 55: kind = DRIZZLE; strength = 0.55f; break;
            case 56: kind = SLEET; strength = 0.3f; break;
            case 57: kind = SLEET; strength = 0.5f; break;
            case 61: kind = RAIN; strength = 0.35f; break;
            case 63: kind = RAIN; strength = 0.65f; break;
            case 65: kind = RAIN; strength = 1f; break;
            case 66: kind = SLEET; strength = 0.5f; break;
            case 67: kind = SLEET; strength = 0.9f; break;
            case 71: kind = SNOW; strength = 0.35f; break;
            case 73: kind = SNOW; strength = 0.65f; break;
            case 75: kind = SNOW; strength = 1f; break;
            case 77: kind = SNOW; strength = 0.25f; break;
            case 80: kind = RAIN; strength = 0.5f; break;
            case 81: kind = RAIN; strength = 0.8f; break;
            case 82: kind = RAIN; strength = 1f; break;
            case 85: kind = SNOW; strength = 0.6f; break;
            case 86: kind = SNOW; strength = 1f; break;
            case 95: kind = STORM; strength = 0.85f; break;
            case 96: case 99: kind = STORM; strength = 1f; hail = true; break;
            default: return new Scene(NONE, 0f, isDay, slant, false);
        }
        if (precipitation > 0 && (kind == RAIN || kind == DRIZZLE || kind == SNOW || kind == SLEET)) {
            float measured = Math.min(1f, precipitation / 5f);   // 5 mm in an hour is a downpour
            strength = Math.max(0.15f, Math.min(1f, 0.6f * strength + 0.4f * measured));
        }
        return new Scene(kind, strength, isDay, slant, hail);
    }

    private static final int FRAME_MS = 33;
    private static final int MAX_STREAKS = 360, MAX_FLAKES = 320, MAX_DROPS = 70, MAX_SPLASHES = 24, MAX_MOTES = 26,
            MAX_STARS = 36, MAX_CLOUDS = 6;

    private final float dp;
    private final Random random = new Random();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path ray = new Path();
    private final RectF box = new RectF();

    private Scene scene;
    private boolean running;
    private long lastFrame;
    private float time;
    private long lastAsk;

    // falling things: x, y, speed (dp/s), size (length or radius in dp), sway phase
    private final float[] fx = new float[MAX_FLAKES], fy = new float[MAX_FLAKES], fs = new float[MAX_FLAKES],
            fr = new float[MAX_FLAKES], fp = new float[MAX_FLAKES];
    private int fallers;
    private final float[] lines = new float[MAX_STREAKS * 4];
    // drops on the glass: x, y, radius, sliding speed, age, life, trail top
    private final float[] dx = new float[MAX_DROPS], dy = new float[MAX_DROPS], dr = new float[MAX_DROPS],
            dv = new float[MAX_DROPS], da = new float[MAX_DROPS], dl = new float[MAX_DROPS], dt = new float[MAX_DROPS];
    private int drops;
    // splashes: x, y, age
    private final float[] sx = new float[MAX_SPLASHES], sy = new float[MAX_SPLASHES], sa = new float[MAX_SPLASHES];
    private float splashDue;
    // sun motes and night stars: x, y, phase, size
    private final float[] mx = new float[MAX_STARS], my = new float[MAX_STARS], mp = new float[MAX_STARS], ms = new float[MAX_STARS];
    // clouds: x, y, scale, speed, alpha
    private final float[] cx = new float[MAX_CLOUDS], cy = new float[MAX_CLOUDS], cs = new float[MAX_CLOUDS],
            cv = new float[MAX_CLOUDS], ca = new float[MAX_CLOUDS];
    private int clouds;
    // lightning: seconds until the next one, and the time into the current flash (-1: none)
    private float flashDue = 8f, flashAt = -1f;

    private Bitmap cloudBitmap, dropBitmap;
    private android.graphics.ColorFilter cloudFilter;
    private int cloudTint;
    private RadialGradient sunShader;
    private int shaderW, shaderH;

    WeatherFx(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        light.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));   // light adds to the picture, as sunlight does
    }

    void start() {
        if (running) return;
        running = true;
        lastFrame = 0;
        lastAsk = 0;
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

    /** Picks up a new sky from Weather.sky, rebuilding the particles only when the scene really changed. */
    private void follow() {
        Weather.Sky sky = Weather.sky;
        Scene next = sky == null ? null
                : scene(sky.code, sky.day, sky.precipitation, sky.wind, Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
        if (next != null && next.kind == NONE) next = null;
        if (next == null ? scene == null : next.same(scene)) return;
        scene = next;
        build();
    }

    private void build() {
        int w = getWidth(), h = getHeight();
        fallers = drops = clouds = 0;
        for (int i = 0; i < MAX_SPLASHES; i++) sa[i] = -1;
        if (scene == null || w == 0 || h == 0) return;
        float i = scene.intensity;
        switch (scene.kind) {
            case DRIZZLE: fallers = (int) (60 + 120 * i); break;
            case RAIN: fallers = (int) (80 + 270 * i); break;
            case STORM: fallers = (int) (220 + 140 * i); break;
            case SLEET: fallers = (int) (70 + 160 * i); break;
            case SNOW: fallers = (int) (50 + 270 * i); break;
            default: fallers = 0;
        }
        fallers = Math.min(fallers, scene.kind == SNOW ? MAX_FLAKES : MAX_STREAKS);
        for (int k = 0; k < fallers; k++) spawnFaller(k, w, h, true);
        if (scene.kind == RAIN || scene.kind == STORM || scene.kind == DRIZZLE || scene.kind == SLEET) {
            drops = Math.min(MAX_DROPS, (int) ((scene.kind == DRIZZLE ? 8 : 14) + 56 * i));
            for (int k = 0; k < drops; k++) spawnDrop(k, w, h, true);
        }
        int cloudCount = scene.kind == PARTLY ? 3 : scene.kind == OVERCAST || scene.kind == STORM ? 6
                : scene.kind == FOG ? 5 : scene.kind == RAIN || scene.kind == DRIZZLE || scene.kind == SNOW || scene.kind == SLEET ? 4 : 0;
        clouds = Math.min(MAX_CLOUDS, cloudCount);
        for (int k = 0; k < clouds; k++) {
            cs[k] = (scene.kind == FOG ? 3.2f : 1.6f) + random.nextFloat() * 1.4f;
            cx[k] = random.nextFloat() * w;
            cy[k] = scene.kind == FOG ? h * (0.35f + 0.5f * random.nextFloat()) : h * (0.02f + 0.3f * random.nextFloat());
            cv[k] = (6 + random.nextFloat() * 10) * dp * (scene.kind == FOG ? 0.6f : 1f);
            ca[k] = scene.kind == FOG ? 0.32f + 0.2f * random.nextFloat() : 0.35f + 0.35f * random.nextFloat();
        }
        int stars = scene.kind == CLEAR || scene.kind == PARTLY ? (scene.day ? MAX_MOTES : MAX_STARS) : 0;
        for (int k = 0; k < MAX_STARS; k++) {
            mx[k] = scene.day ? w * (0.35f + 0.65f * random.nextFloat()) : random.nextFloat() * w;
            my[k] = scene.day ? h * random.nextFloat() * 0.8f : h * random.nextFloat() * 0.32f;
            mp[k] = random.nextFloat() * 6.28f;
            ms[k] = (scene.day ? 1.2f + 1.8f * random.nextFloat() : 0.6f + 1.1f * random.nextFloat()) * dp;
            if (k >= stars) ms[k] = 0;
        }
        flashDue = 4 + random.nextFloat() * 8;
        flashAt = -1;
    }

    private void spawnFaller(int k, int w, int h, boolean anywhere) {
        float depth = 0.45f + 0.55f * random.nextFloat();   // far ones are smaller, dimmer and slower
        fx[k] = random.nextFloat() * (w + h * scene.slant) - h * scene.slant;
        fy[k] = anywhere ? random.nextFloat() * h : -random.nextFloat() * h * 0.2f - 30 * dp;
        fp[k] = random.nextFloat() * 6.28f;
        if (scene.kind == SNOW || (scene.kind == SLEET && k % 3 == 0) || (scene.hail && k % 5 == 0)) {
            fr[k] = -(1.0f + 2.6f * depth * (0.7f + 0.5f * scene.intensity)) * dp;   // negative: a flake or pellet, not a streak
            fs[k] = (scene.hail || scene.kind == SLEET ? 420 + 300 * depth : 35 + 80 * depth) * dp;
        } else {
            boolean drizzle = scene.kind == DRIZZLE;
            fr[k] = (drizzle ? 5 + 7 * depth : 12 + 24 * depth * (0.6f + 0.4f * scene.intensity)) * dp;
            fs[k] = (drizzle ? 420 + 260 * depth : 850 + 750 * depth) * dp;
        }
    }

    private void spawnDrop(int k, int w, int h, boolean old) {
        dx[k] = random.nextFloat() * w;
        dy[k] = random.nextFloat() * h * 0.92f;
        dr[k] = (scene.kind == DRIZZLE ? 1.5f + 2.5f * random.nextFloat() : 2f + 6f * random.nextFloat() * random.nextFloat() + 1.5f) * dp;
        dv[k] = 0;
        dl[k] = 6 + random.nextFloat() * 18;
        da[k] = old ? random.nextFloat() * dl[k] : 0;
        dt[k] = dy[k];
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        build();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float step = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        time += step;
        if (now - lastAsk > 3_000_000_000L) {   // Weather.sky is a field read: cheap, but no need every frame
            lastAsk = now;
            follow();
        }
        if (scene != null) {
            int w = getWidth(), h = getHeight();
            switch (scene.kind) {
                case CLEAR: case PARTLY:
                    if (scene.day) sun(canvas, w, h, scene.kind == CLEAR ? scene.intensity : 0.55f, step);
                    else stars(canvas, scene.kind == CLEAR ? 1f : 0.5f, w, h);
                    if (scene.kind == PARTLY) clouds(canvas, w, h, step, scene.day ? 0xFFFFFFFF : 0xFF9AA4B4);
                    break;
                case OVERCAST:
                    wash(canvas, w, h, scene.day ? 0x2E3A4250 : 0x30101820);
                    clouds(canvas, w, h, step, scene.day ? 0xFFC8CED6 : 0xFF6A7380);
                    break;
                case FOG:
                    glow(canvas, w, h, scene.day ? 0x2EB8C0C8 : 0x1E8090A0);
                    clouds(canvas, w, h, step, scene.day ? 0xFFE6EAEE : 0xFFA0A8B4);
                    break;
                default:
                    wet(canvas, w, h, step);
            }
        }
        // Nothing to draw: look again in a few seconds instead of drawing an empty frame 30 times a second.
        if (running) postInvalidateDelayed(scene == null ? 3000 : FRAME_MS);
    }

    // ---- sun, sky and cloud -----------------------------------------------------------------------------------

    /** A warm light from the top right corner, slowly breathing, with turning rays and motes floating in them. */
    private void sun(Canvas canvas, int w, int h, float strength, float step) {
        float sunX = w * 0.88f, sunY = -h * 0.10f, radius = h * 1.15f;
        if (sunShader == null || shaderW != w || shaderH != h) {
            sunShader = new RadialGradient(sunX, sunY, radius, new int[]{0xFFFFF1C8, 0xB3FFD27A, 0x40FFB347, 0x00000000},
                    new float[]{0f, 0.22f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            shaderW = w;
            shaderH = h;
        }
        float breathe = 0.86f + 0.14f * (float) Math.sin(time * 2 * Math.PI / 9.0);
        light.setShader(sunShader);
        light.setAlpha((int) (255 * 0.42f * strength * breathe));
        canvas.drawRect(0, 0, w, h, light);
        // the rays: thin wedges from the sun, the same fading light, turning a few degrees back and forth
        light.setAlpha((int) (255 * 0.22f * strength));
        float base = 118f + 4f * (float) Math.sin(time * 2 * Math.PI / 40.0);
        for (int k = 0; k < 7; k++) {
            float angle = (float) Math.toRadians(base + k * 9.5f + 2.5f * Math.sin(time * 0.6f + k * 1.7f));
            float spread = (float) Math.toRadians(1.6f + (k % 3) * 1.2f);
            float far = radius * 1.25f;
            ray.rewind();
            ray.moveTo(sunX, sunY);
            ray.lineTo(sunX + far * (float) Math.cos(angle - spread), sunY + far * (float) Math.sin(angle - spread));
            ray.lineTo(sunX + far * (float) Math.cos(angle + spread), sunY + far * (float) Math.sin(angle + spread));
            ray.close();
            canvas.drawPath(ray, light);
        }
        light.setShader(null);
        // dust in the light: slow, rising a little, twinkling
        fill.setShader(null);
        for (int k = 0; k < MAX_MOTES; k++) {
            if (ms[k] <= 0) continue;
            my[k] -= 6 * dp * step;
            mx[k] -= 3 * dp * step;
            if (my[k] < -10 || mx[k] < w * 0.3f) {
                mx[k] = w * (0.45f + 0.55f * random.nextFloat());
                my[k] = h * (0.3f + 0.6f * random.nextFloat());
            }
            float twinkle = 0.5f + 0.5f * (float) Math.sin(time * 1.3f + mp[k]);
            fill.setColor(0xFFFFF4D6);
            fill.setAlpha((int) (150 * strength * twinkle));
            canvas.drawCircle(mx[k], my[k], ms[k], fill);
        }
    }

    /** A clear night: a faint blue and a few twinkling stars high up. */
    private void stars(Canvas canvas, float strength, int w, int h) {
        wash(canvas, w, h, 0x1A08102A);
        fill.setShader(null);
        for (int k = 0; k < MAX_STARS; k++) {
            if (ms[k] <= 0) continue;
            float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(time * (0.8f + (k % 5) * 0.3f) + mp[k]));
            fill.setColor(0xFFFFFFFF);
            fill.setAlpha((int) (170 * strength * twinkle));
            canvas.drawCircle(mx[k], my[k], ms[k], fill);
        }
    }

    private void clouds(Canvas canvas, int w, int h, float step, int tint) {
        if (clouds == 0) return;
        if (cloudBitmap == null) cloudBitmap = makeCloud();
        if (tint != cloudTint || cloudFilter == null) {
            cloudTint = tint;
            cloudFilter = new android.graphics.PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY);
        }
        image.setColorFilter(cloudFilter);
        for (int k = 0; k < clouds; k++) {
            float cw = cloudBitmap.getWidth() * cs[k] * dp, ch = cloudBitmap.getHeight() * cs[k] * dp;
            cx[k] += cv[k] * step;
            if (cx[k] - cw / 2 > w) {
                cx[k] = -cw / 2;
                cy[k] = scene.kind == FOG ? h * (0.35f + 0.5f * random.nextFloat()) : h * (0.02f + 0.3f * random.nextFloat());
            }
            image.setAlpha((int) (255 * ca[k]));
            box.set(cx[k] - cw / 2, cy[k] - ch / 2, cx[k] + cw / 2, cy[k] + ch / 2);
            canvas.drawBitmap(cloudBitmap, null, box, image);
        }
        image.setColorFilter(null);
        image.setAlpha(255);
    }

    // ---- rain, snow, storm ------------------------------------------------------------------------------------

    private void wet(Canvas canvas, int w, int h, float step) {
        float i = scene.intensity;
        boolean storm = scene.kind == STORM;
        // the light under rain: darker and bluer the harder it falls; snow lightens it instead
        if (scene.kind == SNOW) glow(canvas, w, h, (int) (0x10 + 0x24 * i) << 24 | 0xE8F0FF);
        else wash(canvas, w, h, (int) ((storm ? 0x40 : 0x14) + 0x2C * i) << 24 | (storm ? 0x060A14 : 0x0E1622));
        clouds(canvas, w, h, step, scene.kind == SNOW ? 0xFFE0E6EE : 0xFF5A6472);
        if (storm) lightning(canvas, w, h, step);

        // streaks in one batch, flakes and pellets as circles
        int n = 0;
        float slantX = scene.slant;
        for (int k = 0; k < fallers; k++) {
            boolean round = fr[k] < 0;
            float sway = round && scene.kind == SNOW ? (float) Math.sin(time * 0.9f + fp[k]) * 22 * dp * step : 0;
            fy[k] += fs[k] * step;
            fx[k] += fs[k] * step * slantX * (round && scene.kind == SNOW ? 0.4f : 1f) + sway;
            if (fy[k] > h + 40 * dp) {
                spawnFaller(k, w, h, false);
                if ((scene.kind == RAIN || storm) && i > 0.45f) splash(w, h);
                continue;
            }
            if (round) {
                float r = -fr[k];
                fill.setShader(null);
                fill.setColor(0xFFFFFFFF);
                fill.setAlpha((int) (255 * Math.min(0.95f, 0.45f + r / (5 * dp))));
                canvas.drawCircle(fx[k], fy[k], r, fill);
            } else if (n < MAX_STREAKS) {
                float len = fr[k];
                lines[n * 4] = fx[k];
                lines[n * 4 + 1] = fy[k];
                lines[n * 4 + 2] = fx[k] - len * slantX;
                lines[n * 4 + 3] = fy[k] - len;
                n++;
            }
        }
        if (n > 0) {
            line.setColor(scene.kind == DRIZZLE ? 0x66D8E4EE : 0x8CCFDCEA);
            line.setStrokeWidth((scene.kind == DRIZZLE ? 1f : 1.4f + 0.6f * i) * dp);
            canvas.drawLines(lines, 0, n * 4, line);
        }
        splashes(canvas, step);
        glass(canvas, w, h, step);
    }

    private void splash(int w, int h) {
        for (int k = 0; k < MAX_SPLASHES; k++) {
            if (sa[k] >= 0) continue;
            sx[k] = random.nextFloat() * w;
            sy[k] = h * (0.86f + 0.13f * random.nextFloat());
            sa[k] = 0;
            return;
        }
    }

    /** Small rings opening where heavy rain lands, along the bottom of the screen. */
    private void splashes(Canvas canvas, float step) {
        line.setStrokeWidth(1.2f * dp);
        for (int k = 0; k < MAX_SPLASHES; k++) {
            if (sa[k] < 0) continue;
            sa[k] += step;
            float t = sa[k] / 0.35f;
            if (t >= 1) {
                sa[k] = -1;
                continue;
            }
            float rx = (4 + 12 * t) * dp, ry = rx * 0.32f;
            line.setColor(0xFFDDE8F2);
            line.setAlpha((int) (150 * (1 - t)));
            box.set(sx[k] - rx, sy[k] - ry, sx[k] + rx, sy[k] + ry);
            canvas.drawOval(box, line);
        }
    }

    /** Drops on the glass: they appear, sit, some grow heavy and run down leaving a wet trail, then dry away. */
    private void glass(Canvas canvas, int w, int h, float step) {
        if (drops == 0) return;
        if (dropBitmap == null) dropBitmap = makeDrop();
        for (int k = 0; k < drops; k++) {
            da[k] += step;
            if (da[k] > dl[k] || dy[k] > h) {
                spawnDrop(k, w, h, false);
                continue;
            }
            if (dv[k] == 0 && dr[k] > 5.5f * dp && random.nextFloat() < step * 0.05f) dv[k] = (40 + 120 * random.nextFloat()) * dp;
            if (dv[k] > 0) {
                dy[k] += dv[k] * step;
                dx[k] += (random.nextFloat() - 0.5f) * 8 * dp * step;
                line.setColor(0x40D8E6F0);
                line.setStrokeWidth(dr[k] * 0.5f);
                canvas.drawLine(dx[k], dt[k], dx[k], dy[k] - dr[k], line);
            }
            float fade = Math.min(1f, Math.min(da[k] / 0.6f, (dl[k] - da[k]) / 1.5f));
            image.setAlpha((int) (235 * Math.max(0f, fade)));
            box.set(dx[k] - dr[k], dy[k] - dr[k] * 1.15f, dx[k] + dr[k], dy[k] + dr[k] * 1.15f);
            canvas.drawBitmap(dropBitmap, null, box, image);
        }
        image.setAlpha(255);
    }

    /** Now and then a soft flash, one or two quick pulses, never brighter than about a third. */
    private void lightning(Canvas canvas, int w, int h, float step) {
        if (flashAt < 0) {
            flashDue -= step;
            if (flashDue <= 0) flashAt = 0;
            return;
        }
        flashAt += step;
        float t = flashAt, a;
        if (t < 0.06f) a = t / 0.06f;
        else if (t < 0.18f) a = 1 - (t - 0.06f) / 0.12f * 0.7f;
        else if (t < 0.24f) a = 0.3f + (t - 0.18f) / 0.06f * 0.5f;   // the second, weaker pulse
        else a = Math.max(0f, 0.8f - (t - 0.24f) / 0.5f);
        if (t > 0.8f) {
            flashAt = -1;
            flashDue = 7 + random.nextFloat() * 12;
            return;
        }
        light.setShader(null);
        light.setColor(0xFFDCE4FF);
        light.setAlpha((int) (255 * 0.34f * a * scene.intensity));
        canvas.drawRect(0, 0, w, h, light);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** A plain darkening or tint over the whole picture. */
    private void wash(Canvas canvas, int w, int h, int argb) {
        fill.setShader(null);
        fill.setColor(argb);
        canvas.drawRect(0, 0, w, h, fill);
    }

    /** A lightening over the whole picture (fog, snow light). */
    private void glow(Canvas canvas, int w, int h, int argb) {
        light.setShader(null);
        light.setColor(argb);
        canvas.drawRect(0, 0, w, h, light);
    }

    /** One soft white cloud, made once: overlapping round glows. */
    private static Bitmap makeCloud() {
        Bitmap bitmap = Bitmap.createBitmap(256, 112, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[][] puffs = {{70, 66, 44}, {118, 50, 54}, {170, 60, 46}, {205, 72, 32}, {44, 78, 30}, {140, 78, 40}};
        for (float[] puff : puffs) {
            p.setShader(new RadialGradient(puff[0], puff[1], puff[2], new int[]{0xCCFFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(puff[0], puff[1], puff[2], p);
        }
        return bitmap;
    }

    /** One rain drop on glass, made once: clear in the middle, a darker rim, a bright highlight. */
    private static Bitmap makeDrop() {
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(24, 26, 22, new int[]{0x18FFFFFF, 0x10FFFFFF, 0x55203040, 0x00000000},
                new float[]{0f, 0.62f, 0.9f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(24, 26, 22, p);
        p.setShader(new RadialGradient(17, 16, 7, new int[]{0xE6FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawCircle(17, 16, 7, p);
        p.setShader(new RadialGradient(30, 36, 5, new int[]{0x59FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawCircle(30, 36, 5, p);
        return bitmap;
    }
}
