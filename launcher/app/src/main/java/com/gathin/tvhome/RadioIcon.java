package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * Local addition: the sound line's icon, a small portable radio drawn here: a white body with a black contour,
 * an antenna, a speaker grille, a dial window and two knobs in black. Black on white reads the same on a photo
 * and on the white pill the line becomes while it is focused. Coordinates are dp on a 28x26 box.
 */
final class RadioIcon extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    RadioIcon(float dp) {
        this.dp = dp;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(dp, dp);
        // antenna: black with a white core
        stroke(0xFF000000, 2.8f);
        canvas.drawLine(8, 8, 21, 1.8f, line);
        stroke(0xFFFFFFFF, 1f);
        canvas.drawLine(8, 8, 21, 1.8f, line);
        // body
        fill.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(2, 7.5f, 26, 24.5f, 3.2f, 3.2f, fill);
        stroke(0xFF000000, 1.5f);
        canvas.drawRoundRect(2, 7.5f, 26, 24.5f, 3.2f, 3.2f, line);
        // speaker: a ring and three grille lines cut to it
        stroke(0xFF000000, 1.3f);
        canvas.drawCircle(9.4f, 15.8f, 4.8f, line);
        stroke(0xFF000000, 1.1f);
        for (float y : new float[]{13.6f, 15.8f, 18f}) {
            float half = (float) Math.sqrt(4.8f * 4.8f - (y - 15.8f) * (y - 15.8f)) - 1.3f;
            canvas.drawLine(9.4f - half, y, 9.4f + half, y, line);
        }
        // dial window with its needle, and two knobs
        stroke(0xFF000000, 1.2f);
        canvas.drawRoundRect(16.2f, 10.6f, 23.6f, 14.6f, 1, 1, line);
        canvas.drawLine(20.6f, 11.6f, 20.6f, 13.6f, line);
        canvas.drawCircle(18f, 18.8f, 1.6f, line);
        fill.setColor(0xFF000000);
        canvas.drawCircle(22.2f, 18.8f, 1.7f, fill);
        canvas.restore();
    }

    private void stroke(int colour, float width) {
        line.setColor(colour);
        line.setStrokeWidth(width);
    }

    @Override public int getIntrinsicWidth() { return Math.round(28 * dp); }
    @Override public int getIntrinsicHeight() { return Math.round(26 * dp); }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
