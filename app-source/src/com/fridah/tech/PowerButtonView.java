package com.fridah.tech;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.View;

/** Big circular connect / disconnect power button with neon glow + spinner. */
public class PowerButtonView extends View {

    public static final int OFF = 0, CONNECTING = 1, ON = 2;

    private int state = OFF;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private long t0 = System.currentTimeMillis();
    private OnClickListener tap;

    public PowerButtonView(Context c) {
        super(c);
        ring.setStrokeWidth(dp(7));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeWidth(dp(6));
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setColor(0xFFF3F7FF);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xF20B1428);
        setOnClickListener(v -> { if (tap != null) tap.onClick(v); });
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setState(int s) { state = s; t0 = System.currentTimeMillis(); postInvalidate(); }

    public int getState() { return state; }

    @Override
    protected void onDraw(Canvas cv) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = Math.min(cx, cy) * 0.62f;
        float t = (System.currentTimeMillis() - t0) / 1000f;

        // outer glow
        float pulse = state == ON ? 0.5f + 0.5f * (float) Math.sin(t * 2.2f) : 0f;
        int glowColor = state == ON ? 0x8800E676 : (state == CONNECTING ? 0x5500E5FF : 0x33104D8F);
        int glowR = (int) (R * (1.9f + pulse * 0.25f));
        glow.setShader(new RadialGradient(cx, cy, glowR, glowColor, 0x00000000, RadialGradient.TileMode.CLAMP));
        cv.drawCircle(cx, cy, glowR, glow);

        // base disc
        cv.drawCircle(cx, cy, R, fill);

        // ring
        if (state == CONNECTING) {
            ring.setColor(0xFF00E5FF);
            float sweep = 90f, rot = (t * 320f) % 360f;
            rf.set(cx - R, cy - R, cx + R, cy + R);
            cv.drawArc(rf, rot, sweep, false, ring);
            ring.setColor(0x33FFFFFF);
            cv.drawArc(rf, rot + sweep + 20, 360 - sweep - 20, false, ring);
        } else {
            int[] cols = state == ON
                    ? new int[]{0xFF00E676, 0xFF00E5FF, 0xFF7C4DFF, 0xFF00E676}
                    : new int[]{0xFF2A3F6E, 0xFF4A6AD4, 0xFF00E5FF, 0xFF2A3F6E};
            ring.setShader(new SweepGradient(cx, cy, cols, null));
            rf.set(cx - R, cy - R, cx + R, cy + R);
            cv.drawArc(rf, -90, 360, false, ring);
        }

        // power glyph
        float gr = R * 0.42f;
        rf.set(cx - gr, cy - gr, cx + gr, cy + gr);
        cv.drawArc(rf, -55, 290, false, icon);
        cv.drawLine(cx, cy - gr - dp(7), cx, cy - gr + dp(9), icon);

        if (state != OFF) postInvalidateDelayed(16);
    }
}
