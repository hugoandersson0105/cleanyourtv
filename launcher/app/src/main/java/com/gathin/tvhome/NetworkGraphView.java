package com.gathin.tvhome;

// Ported from the "Clean TV for TCL G10" fork, github.com/itsmevizz/cleanyourtv (branch clean-tv-launcher, MIT),
// for the Control Center panel (control_center). Unchanged apart from this note.

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;

/**
 * An area chart of the last minute of network speed: download and upload, each a smooth filled curve
 * with a fading gradient under it and a line on top. Fed one sample a second by SettingsOverlay.
 */
final class NetworkGraphView extends View {

    static final int SAMPLES = 60;
    /** Never scale the chart below this, so a quiet network does not look busy. */
    private static final float MIN_PEAK = 48f * 1024f;
    private static final int DOWN_COLOR = 0xFF7CC4FF;
    private static final int UP_COLOR = 0xFFFFB86B;

    private final float[] down = new float[SAMPLES];
    private final float[] up = new float[SAMPLES];
    private final float[] xs = new float[SAMPLES];
    private final float[] ys = new float[SAMPLES];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path areaPath = new Path();
    private final float density;

    /** Used by the layout inflater (item_network_card.xml): the XML attributes, id and size, go to View. */
    NetworkGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    NetworkGraphView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Adds one sample (bytes per second) at the right edge; the oldest falls off the left. */
    void push(float downBytesPerSecond, float upBytesPerSecond) {
        System.arraycopy(down, 1, down, 0, SAMPLES - 1);
        System.arraycopy(up, 1, up, 0, SAMPLES - 1);
        down[SAMPLES - 1] = downBytesPerSecond;
        up[SAMPLES - 1] = upBytesPerSecond;
        invalidate();
    }

    void clear() {
        Arrays.fill(down, 0f);
        Arrays.fill(up, 0f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float peak = MIN_PEAK;
        for (int i = 0; i < SAMPLES; i++) {
            peak = Math.max(peak, Math.max(down[i], up[i]));
        }
        peak *= 1.15f;   // headroom above the highest sample
        drawArea(c, up, UP_COLOR, w, h, peak);
        drawArea(c, down, DOWN_COLOR, w, h, peak);
    }

    private void drawArea(Canvas c, float[] values, int color, float w, float h, float peak) {
        float top = h * 0.12f;
        float base = h;
        float span = base - top;
        for (int i = 0; i < SAMPLES; i++) {
            xs[i] = i * w / (SAMPLES - 1);
            ys[i] = base - Math.min(1f, values[i] / peak) * span;
        }
        // Smooth curve through the samples: quadratic segments between the midpoints.
        linePath.reset();
        linePath.moveTo(xs[0], ys[0]);
        for (int i = 1; i < SAMPLES; i++) {
            float midX = (xs[i - 1] + xs[i]) / 2f, midY = (ys[i - 1] + ys[i]) / 2f;
            linePath.quadTo(xs[i - 1], ys[i - 1], midX, midY);
        }
        linePath.lineTo(xs[SAMPLES - 1], ys[SAMPLES - 1]);

        areaPath.reset();
        areaPath.addPath(linePath);
        areaPath.lineTo(w, base);
        areaPath.lineTo(0, base);
        areaPath.close();

        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        fill.setShader(new LinearGradient(0, top, 0, base,
                Color.argb(120, r, g, b), Color.argb(0, r, g, b), Shader.TileMode.CLAMP));
        c.drawPath(areaPath, fill);
        line.setColor(color);
        c.drawPath(linePath, line);
    }
}
