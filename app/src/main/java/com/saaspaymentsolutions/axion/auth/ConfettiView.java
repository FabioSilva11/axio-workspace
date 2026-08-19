package com.saaspaymentsolutions.axion.auth;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ConfettiView extends View {
    private static final int[] COLORS = {
            0xFF6B5CE7, 0xFFFFC857, 0xFF22C55E, 0xFFFF5D8F, 0xFF38BDF8, 0xFFFF8A4C
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private float progress;

    public ConfettiView(Context context) {
        super(context);
        initialize();
    }

    public ConfettiView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    private void initialize() {
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < 110; i++) {
            Particle particle = new Particle();
            particle.x = random.nextFloat();
            particle.startY = -random.nextFloat() * 0.65f;
            particle.speed = 0.75f + random.nextFloat() * 0.65f;
            particle.drift = (random.nextFloat() - 0.5f) * 0.20f;
            particle.size = 7f + random.nextFloat() * 11f;
            particle.rotation = random.nextFloat() * 360f;
            particle.rotationSpeed = 180f + random.nextFloat() * 540f;
            particle.color = COLORS[random.nextInt(COLORS.length)];
            particle.circle = random.nextBoolean();
            particles.add(particle);
        }
    }

    public void start() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(3000L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        for (Particle particle : particles) {
            float y = (particle.startY + progress * particle.speed * 1.7f) * height;
            if (y < -particle.size || y > height + particle.size) {
                continue;
            }
            float x = (particle.x + particle.drift * progress) * width;
            int alpha = progress < 0.82f ? 255 : Math.max(0, (int) ((1f - progress) / 0.18f * 255));
            paint.setColor(particle.color);
            paint.setAlpha(alpha);
            canvas.save();
            canvas.rotate(particle.rotation + particle.rotationSpeed * progress, x, y);
            if (particle.circle) {
                canvas.drawCircle(x, y, particle.size * 0.45f, paint);
            } else {
                RectF rect = new RectF(
                        x - particle.size * 0.55f,
                        y - particle.size * 0.28f,
                        x + particle.size * 0.55f,
                        y + particle.size * 0.28f);
                canvas.drawRoundRect(rect, 3f, 3f, paint);
            }
            canvas.restore();
        }
    }

    private static final class Particle {
        float x;
        float startY;
        float speed;
        float drift;
        float size;
        float rotation;
        float rotationSpeed;
        int color;
        boolean circle;
    }
}
