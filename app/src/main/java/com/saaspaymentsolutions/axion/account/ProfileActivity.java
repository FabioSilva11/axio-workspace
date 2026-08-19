package com.saaspaymentsolutions.axion.account;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.saaspaymentsolutions.axion.BaseAppCompatActivity;
import com.saaspaymentsolutions.axion.R;
import com.saaspaymentsolutions.axion.analytics.AxionAnalytics;
import com.saaspaymentsolutions.axion.auth.AuthActivity;

import java.text.NumberFormat;
import java.util.Locale;

public final class ProfileActivity extends BaseAppCompatActivity {
    private FirebaseAccountStore accountStore;
    private FirebaseUser currentUser;
    private TextView initial;
    private TextView name;
    private TextView email;
    private TextView accountEmail;
    private TextView planName;
    private TextView planStatus;
    private TextView balance;
    private TextView spent;
    private TextView usageDetail;
    private TextView error;
    private LinearProgressIndicator usageProgress;
    private boolean accountLoadLogged;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            openAuthentication();
            return;
        }

        setContentView(R.layout.activity_profile);
        accountStore = new FirebaseAccountStore(this);
        initial = findViewById(R.id.profile_initial);
        name = findViewById(R.id.profile_name);
        email = findViewById(R.id.profile_email);
        accountEmail = findViewById(R.id.profile_account_email);
        planName = findViewById(R.id.profile_plan_name);
        planStatus = findViewById(R.id.profile_plan_status);
        balance = findViewById(R.id.profile_balance);
        spent = findViewById(R.id.profile_spent);
        usageDetail = findViewById(R.id.profile_usage_detail);
        usageProgress = findViewById(R.id.profile_usage_progress);
        error = findViewById(R.id.profile_error);

        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.profile_toolbar))
                .setNavigationOnClickListener(v -> finish());
        findViewById(R.id.profile_plan_card).setOnClickListener(v -> {
            AxionAnalytics.logEvent(
                    this,
                    AxionAnalytics.Events.PLANS_OPENED,
                    AxionAnalytics.params(AxionAnalytics.Params.SOURCE, "profile")
            );
            startActivity(new Intent(this, PlansActivity.class));
        });
        AxionAnalytics.logEvent(this, AxionAnalytics.Events.PROFILE_OPENED);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (accountStore == null || currentUser == null) {
            return;
        }
        accountStore.start(currentUser, false, new FirebaseAccountStore.Listener() {
            @Override
            public void onAccountChanged(AxionAccount account) {
                render(account);
            }

            @Override
            public void onError(Exception exception) {
                error.setVisibility(View.VISIBLE);
                if (!accountLoadLogged) {
                    accountLoadLogged = true;
                    AxionAnalytics.logResult(
                            ProfileActivity.this,
                            AxionAnalytics.Events.ACCOUNT_LOAD_RESULT,
                            false,
                            exception
                    );
                }
            }
        });
    }

    @Override
    protected void onStop() {
        if (accountStore != null) {
            accountStore.stop();
        }
        super.onStop();
    }

    private void render(AxionAccount account) {
        error.setVisibility(View.GONE);
        name.setText(account.name);
        email.setText(account.email);
        accountEmail.setText(account.email);
        initial.setText(account.name.isEmpty()
                ? "A"
                : account.name.substring(0, 1).toUpperCase(new Locale("pt", "BR")));
        planName.setText(account.isPaid()
                ? R.string.account_plan_paid
                : R.string.account_plan_free);
        planStatus.setText(AxionAccount.STATUS_ACTIVE.equals(account.status)
                ? R.string.account_status_active
                : R.string.account_status_blocked);
        balance.setText(format(account.tokensRemaining));
        spent.setText(format(account.tokensUsed));
        usageDetail.setText(getString(
                R.string.profile_usage_detail,
                format(account.tokensUsed),
                format(account.tokenLimit)
        ));
        usageProgress.setProgress(progress(account.tokensUsed, account.tokenLimit), true);
        AxionAnalytics.setUser(this, account.uid, account.planId);
        if (!accountLoadLogged) {
            accountLoadLogged = true;
            AxionAnalytics.logResult(
                    this,
                    AxionAnalytics.Events.ACCOUNT_LOAD_RESULT,
                    true,
                    null
            );
        }
    }

    private int progress(long used, long limit) {
        if (limit <= 0L) {
            return 0;
        }
        return (int) Math.min(1000L, Math.max(0L, used * 1000L / limit));
    }

    private String format(long value) {
        return NumberFormat.getIntegerInstance(new Locale("pt", "BR")).format(value);
    }

    private void openAuthentication() {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
