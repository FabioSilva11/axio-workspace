package com.saaspaymentsolutions.axion.account;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.saaspaymentsolutions.axion.BaseAppCompatActivity;
import com.saaspaymentsolutions.axion.R;
import com.saaspaymentsolutions.axion.analytics.AxionAnalytics;

import java.util.Locale;

public final class ProfileActivity extends BaseAppCompatActivity {
    private TextView initial;
    private TextView name;
    private TextView email;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        initial = findViewById(R.id.profile_initial);
        name = findViewById(R.id.profile_name);
        email = findViewById(R.id.profile_email);
        TextView accountEmail = findViewById(R.id.profile_account_email);
        TextView planName = findViewById(R.id.profile_plan_name);
        TextView planStatus = findViewById(R.id.profile_plan_status);
        TextView balance = findViewById(R.id.profile_balance);
        TextView spent = findViewById(R.id.profile_spent);

        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.profile_toolbar))
                .setNavigationOnClickListener(v -> finish());

        // Show local-only profile info (no Firebase)
        String localName = "Usuário";
        String localEmail = "";

        initial.setText(localName.isEmpty()
                ? "U"
                : localName.substring(0, 1).toUpperCase(new Locale("pt", "BR")));
        name.setText(localName);
        email.setText(localEmail);
        if (accountEmail != null) accountEmail.setText(localEmail);
        if (planName != null) planName.setText(R.string.account_plan_free);
        if (planStatus != null) planStatus.setText(R.string.account_status_active);
        if (balance != null) balance.setText("—");
        if (spent != null) spent.setText("—");

        // Hide plan card click listener (no PlansActivity)
        findViewById(R.id.profile_plan_card).setOnClickListener(null);

        AxionAnalytics.logEvent(this, AxionAnalytics.Events.PROFILE_OPENED);
    }
}
