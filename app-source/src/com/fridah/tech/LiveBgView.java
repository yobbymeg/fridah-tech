package com.fridah.tech;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/** Animated "live" background: drifting neon orbs + rising particles on deep space gradient. */
public class LiveBgView extends View {

    private static final int[] ORB_COLORS = {
            0x2A00E5FF, 0x2A7C4DFF, 0x24FF4D9E, 0x222979FF, 0x2000E5FF, 0x1E7C4DFF
    };

    private final Paint orb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[][] orbs = new float[ORB_COLORS.length][5]; // fx, fy, fr, speed, phase
    private final float[][] dots = new float[42][4];                // x, y, r, speed
    private final Random rnd = new Random(2026);
    private final long t0 = System.currentTimeMillis();
    private boolean running = true;

    public LiveBgView(Context c) {
        super(c);
        for (int i = 0; i < orbs.length; i++) {
            orbs[i][0] = rnd.nextFloat();
            orbs[i][1] = rnd.nextFloat();
            orbs[i][2] = 0.22f + rnd.nextFloat() * 0.20f;
            orbs[i][3] = 0.10f + rnd.nextFloat() * 0.16f;
            orbs[i][4] = rnd.nextFloat() * 6.28f;
        }
        for (int i = 0; i < dots.length; i++) {
            dots[i][0] = rnd.nextFloat();
            dots[i][1] = rnd.nextFloat();
            dots[i][2] = 1f + rnd.nextFloat() * 2.2f;
            dots[i][3] = 0.02f + rnd.nextFloat() * 0.05f;
        }
        dot.setColor(0x58A9D6FF);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        cv.drawColor(Color.rgb(6, 9, 20));
        float t = (System.currentTimeMillis() - t0) / 1000f;

        // drifting glow orbs
        for (int i = 0; i < orbs.length; i++) {
            float x = w * (orbs[i][0] + 0.18f * (float) Math.sin(t * orbs[i][3] + orbs[i][4]));
            float y = h * (orbs[i][1] + 0.22f * (float) Math.cos(t * orbs[i][3] * 1.3f + orbs[i][4]));
            float r = Math.min(w, h) * orbs[i][2] * (1f + 0.15f * (float) Math.sin(t * 0.5f + orbs[i][4]));
            orb.setShader(new RadialGradient(x, y, r, ORB_COLORS[i % ORB_COLORS.length], 0x00000000, Shader.TileMode.CLAMP));
            cv.drawCircle(x, y, r, orb);
        }

        // subtle vignette
        Paint vig = new Paint(Paint.ANTI_ALIAS_FLAG);
        vig.setShader(new RadialGradient(w * 0.5f, h * 0.45f, h * 0.85f,
                0x00000000, 0x5E02060D, Shader.TileMode.CLAMP));
        cv.drawPaint(vig);

        // rising particles
        float dt = 0.033f;
        for (int i = 0; i < dots.length; i++) {
            float x = dots[i][0] * w + (float) Math.sin(t * 0.6f + i) * 8f;
            float y = dots[i][1] * h - ((t * dots[i][3] * h) % (h + 40f));
            if (y < -10) y += h + 40f;
            cv.drawCircle(x, y, dots[i][2], dot);
        }

        if (running) postInvalidateDelayed(33);
    }

    public void stop() { running = false; }
    public void start() { running = true; postInvalidate(); }
}
