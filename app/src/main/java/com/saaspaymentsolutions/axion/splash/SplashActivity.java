package com.saaspaymentsolutions.axion.splash;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.TextView;

import com.saaspaymentsolutions.axion.MainActivity;
import com.saaspaymentsolutions.axion.R;

/** Splash baseada na implementação do Sketchware-IA, com identidade visual do Axion. */
public final class SplashActivity extends Activity {
    private static final int SPLASH_DELAY_MS = 1000;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { if (getActionBar() != null) getActionBar().hide(); } catch (Exception ignored) { }
        setContentView(R.layout.activity_splash);
        ImageView logo = findViewById(R.id.app_logo);
        TextView title = findViewById(R.id.app_title);
        TextView subtitle = findViewById(R.id.app_subtitle);
        Animation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setInterpolator(new android.view.animation.DecelerateInterpolator());
        fadeIn.setDuration(800);
        logo.startAnimation(fadeIn); title.startAnimation(fadeIn); subtitle.startAnimation(fadeIn);
        new Handler().postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, SPLASH_DELAY_MS);
    }
}
