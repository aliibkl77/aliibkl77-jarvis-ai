package com.jarvis.ali.ai;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class ArcReactorView extends View {

    public static final int STATE_IDLE = 0;
    public static final int STATE_LISTENING = 1;
    public static final int STATE_AUTONOMOUS = 2;
    public static final int STATE_SPEAKING = 3;
    public static final int STATE_ERROR = 4;

    private int currentState = STATE_IDLE;
    private float outerAngle = 0f;
    private float innerAngle = 0f;
    private float pulsePhase = 0f;

    private Paint glowPaint;
    private Paint outerRingPaint;
    private Paint tickPaint;
    private Paint innerDashedPaint;
    private Paint coreFramePaint;
    private Paint coreCenterPaint;
    private RectF arcRect;
    private Path trianglePath;
    private ValueAnimator animator;

    public ArcReactorView(Context context) {
        super(context);
        init();
    }

    public ArcReactorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.FILL);

        outerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outerRingPaint.setStyle(Paint.Style.STROKE);
        outerRingPaint.setStrokeCap(Paint.Cap.ROUND);

        tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tickPaint.setStyle(Paint.Style.STROKE);

        innerDashedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerDashedPaint.setStyle(Paint.Style.STROKE);
        innerDashedPaint.setPathEffect(new DashPathEffect(new float[]{18f, 12f}, 0f));

        coreFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        coreFramePaint.setStyle(Paint.Style.STROKE);
        coreFramePaint.setStrokeJoin(Paint.Join.ROUND);

        coreCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        coreCenterPaint.setStyle(Paint.Style.FILL);

        arcRect = new RectF();
        trianglePath = new Path();

        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(8000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float speedMult = (currentState == STATE_AUTONOMOUS) ? 3.2f :
                                  (currentState == STATE_LISTENING) ? 1.8f :
                                  (currentState == STATE_SPEAKING) ? 2.2f : 1.0f;
                outerAngle = (outerAngle + 0.9f * speedMult) % 360f;
                innerAngle = (innerAngle - 1.4f * speedMult) % 360f;
                pulsePhase += 0.06f * speedMult;
                invalidate();
            }
        });
    }

    public void setState(int state) {
        this.currentState = state;
        invalidate();
    }

    public int getState() {
        return currentState;
    }

    private int getPrimaryColor() {
        switch (currentState) {
            case STATE_LISTENING:
                return Color.parseColor("#00FF9D");
            case STATE_AUTONOMOUS:
                return Color.parseColor("#FF9500");
            case STATE_SPEAKING:
                return Color.parseColor("#38BDF8");
            case STATE_ERROR:
                return Color.parseColor("#FF2A55");
            case STATE_IDLE:
            default:
                return Color.parseColor("#00F0FF");
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator != null && !animator.isRunning()) {
            animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float maxR = Math.min(cx, cy) * 0.92f;
        if (maxR <= 10f) return;

        int color = getPrimaryColor();
        float pulse = (float) (0.5 + 0.5 * Math.sin(pulsePhase));

        // 1. Ambient Radial Glow
        int glowAlpha = (int) (55 + 45 * pulse);
        int glowColor = Color.argb(glowAlpha, Color.red(color), Color.green(color), Color.blue(color));
        RadialGradient gradient = new RadialGradient(
                cx, cy, maxR,
                new int[]{glowColor, Color.argb(15, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT},
                new float[]{0.15f, 0.65f, 1.0f},
                Shader.TileMode.CLAMP
        );
        glowPaint.setShader(gradient);
        canvas.drawCircle(cx, cy, maxR, glowPaint);

        // 2. Outer Segmented Energy Arcs
        float rOuter = maxR * 0.86f;
        outerRingPaint.setColor(color);
        outerRingPaint.setStrokeWidth(maxR * 0.045f);
        outerRingPaint.setShadowLayer(12f, 0f, 0f, color);
        arcRect.set(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter);

        canvas.save();
        canvas.rotate(outerAngle, cx, cy);
        for (int i = 0; i < 4; i++) {
            canvas.drawArc(arcRect, i * 90f + 12f, 66f, false, outerRingPaint);
        }
        canvas.restore();

        // 3. Telemetry Degree Ticks
        float rTickIn = maxR * 0.72f;
        float rTickOut = maxR * 0.79f;
        tickPaint.setColor(Color.argb(160, Color.red(color), Color.green(color), Color.blue(color)));
        tickPaint.setStrokeWidth(maxR * 0.015f);
        canvas.save();
        canvas.rotate(-outerAngle * 0.5f, cx, cy);
        for (int i = 0; i < 36; i++) {
            double rad = Math.toRadians(i * 10.0);
            float lenMult = (i % 3 == 0) ? 1.03f : 0.98f;
            float x1 = cx + (float) Math.cos(rad) * rTickIn;
            float y1 = cy + (float) Math.sin(rad) * rTickIn;
            float x2 = cx + (float) Math.cos(rad) * (rTickOut * lenMult);
            float y2 = cy + (float) Math.sin(rad) * (rTickOut * lenMult);
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }
        canvas.restore();

        // 4. Inner Dashed Plasma Ring
        float rInner = maxR * 0.58f;
        innerDashedPaint.setColor(color);
        innerDashedPaint.setStrokeWidth(maxR * 0.05f);
        innerDashedPaint.setShadowLayer(10f, 0f, 0f, color);
        arcRect.set(cx - rInner, cy - rInner, cx + rInner, cy + rInner);
        canvas.save();
        canvas.rotate(innerAngle, cx, cy);
        canvas.drawOval(arcRect, innerDashedPaint);
        canvas.restore();

        // 5. Stark Inverted Triangle Frame
        float rTri = maxR * 0.42f;
        trianglePath.reset();
        for (int i = 0; i < 3; i++) {
            double angle = Math.toRadians(90.0 + i * 120.0);
            float px = cx + (float) Math.cos(angle) * rTri;
            float py = cy + (float) Math.sin(angle) * rTri;
            if (i == 0) trianglePath.moveTo(px, py);
            else trianglePath.lineTo(px, py);
        }
        trianglePath.close();
        coreFramePaint.setColor(Color.WHITE);
        coreFramePaint.setStrokeWidth(maxR * 0.025f);
        coreFramePaint.setShadowLayer(14f, 0f, 0f, color);
        canvas.drawPath(trianglePath, coreFramePaint);

        // 6. Central Pulsing Plasma Core
        float coreRadius = maxR * (0.16f + 0.04f * pulse);
        coreCenterPaint.setColor(Color.WHITE);
        coreCenterPaint.setShadowLayer(22f, 0f, 0f, color);
        canvas.drawCircle(cx, cy, coreRadius, coreCenterPaint);
    }
}
