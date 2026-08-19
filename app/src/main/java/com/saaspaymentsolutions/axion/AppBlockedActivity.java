package com.saaspaymentsolutions.axion;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Non-dismissible full-screen message shown while remote access is blocked. */
public final class AppBlockedActivity extends AppCompatActivity {
    private TextView titleView;
    private TextView bodyView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8, 14, 29));
        getWindow().setNavigationBarColor(Color.rgb(8, 14, 29));
        setContentView(createContent());
        AxionAppBlockManager.State state = AxionAppBlockManager.currentState();
        if (!state.enabled) {
            finish();
            return;
        }
        render(state);
    }

    @NonNull
    private ScrollView createContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 14, 29));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int horizontal = dp(28);
        content.setPadding(horizontal, dp(48), horizontal, dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.app_icon_new);
        content.addView(icon, new LinearLayout.LayoutParams(dp(88), dp(88)));

        titleView = new TextView(this);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(26f);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(28);
        content.addView(titleView, titleParams);

        bodyView = new TextView(this);
        bodyView.setGravity(Gravity.CENTER);
        bodyView.setTextColor(Color.rgb(190, 201, 222));
        bodyView.setTextSize(16f);
        bodyView.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(16);
        content.addView(bodyView, bodyParams);

        TextView footer = new TextView(this);
        footer.setGravity(Gravity.CENTER);
        footer.setText(R.string.app_blocked_footer);
        footer.setTextColor(Color.rgb(112, 128, 158));
        footer.setTextSize(12f);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(32);
        content.addView(footer, footerParams);
        return scroll;
    }

    void render(@NonNull AxionAppBlockManager.State state) {
        if (titleView == null || bodyView == null) return;
        titleView.setText(state.title.isEmpty()
                ? getString(R.string.app_blocked_default_title) : state.title);
        bodyView.setText(state.body.isEmpty()
                ? getString(R.string.app_blocked_default_body) : state.body);
    }

    void releaseBlock() {
        finish();
    }

    @Override
    public void onBackPressed() {
        // The remote block is intentionally non-dismissible.
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
