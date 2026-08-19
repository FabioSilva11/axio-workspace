package com.saaspaymentsolutions.axion.splash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/** Animação de chuva usada pela splash portada do Sketchware-IA. */
public final class MatrixRainView extends View {
    private static final int AXION_PURPLE = Color.rgb(107, 92, 231);
    private static final String[] SYMBOLS = {"ア", "イ", "ウ", "エ", "オ", "カ", "キ", "ク", "ケ", "コ", "サ", "シ", "ス", "セ", "ソ", "タ", "チ", "ツ", "テ", "ト", "ナ", "ニ", "ヌ", "ネ", "ノ", "日", "本", "光", "電", "空"};
    private static final int TRAIL_LENGTH = 9;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private float[] yPositions = new float[0];
    private float[] speeds = new float[0];
    private float columnWidth;
    private float textSize;
    private int frame;

    public MatrixRainView(Context context) { super(context); init(); }
    public MatrixRainView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public MatrixRainView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(); }

    private void init() {
        setWillNotDraw(false);
        textSize = sp(18);
        columnWidth = dp(24);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(textSize);
        paint.setTypeface(Typeface.MONOSPACE);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        int columns = Math.max(1, (int) Math.ceil(width / columnWidth) + 1);
        yPositions = new float[columns]; speeds = new float[columns];
        for (int i = 0; i < columns; i++) resetColumn(i, height, true);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0 || yPositions.length == 0) return;
        frame++;
        for (int i = 0; i < yPositions.length; i++) {
            float x = i * columnWidth + columnWidth / 2f;
            for (int trail = 0; trail < TRAIL_LENGTH; trail++) {
                float y = yPositions[i] - trail * textSize * 1.18f;
                if (y < -textSize || y > getHeight() + textSize) continue;
                int alpha = Math.max(20, 190 - trail * 19);
                paint.setColor(Color.argb(alpha, Color.red(AXION_PURPLE), Color.green(AXION_PURPLE), Color.blue(AXION_PURPLE)));
                canvas.drawText(SYMBOLS[(i + trail + frame / 5) % SYMBOLS.length], x, y, paint);
            }
            yPositions[i] += speeds[i];
            if (yPositions[i] - TRAIL_LENGTH * textSize > getHeight()) resetColumn(i, getHeight(), false);
        }
        postInvalidateOnAnimation();
    }

    private void resetColumn(int index, int height, boolean scatter) {
        yPositions[index] = scatter ? random.nextInt(Math.max(1, height + 1)) - height : -random.nextInt(Math.max(1, height / 2 + 1));
        speeds[index] = dp(2.2f + random.nextFloat() * 3.2f);
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
}
