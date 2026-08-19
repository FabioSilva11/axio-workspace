package com.saaspaymentsolutions.axion.account;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.saaspaymentsolutions.axion.BaseAppCompatActivity;
import com.saaspaymentsolutions.axion.R;
import com.saaspaymentsolutions.axion.analytics.AxionAnalytics;
import com.saaspaymentsolutions.axion.auth.AuthActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class PlansActivity extends BaseAppCompatActivity {
    private FirebaseAccountStore accountStore;
    private FirebaseUser currentUser;
    private AxionAccount currentAccount;
    private TextView currentName;
    private TextView balance;
    private TextView periodStatus;
    private TextView freeName;
    private TextView freePrice;
    private TextView freeDescription;
    private TextView freeTokens;
    private TextView freeOutput;
    private TextView paidName;
    private TextView paidPrice;
    private TextView paidDescription;
    private TextView paidTokens;
    private TextView paidOutput;
    private TextView paymentStatus;
    private TextView freeBadge;
    private TextView paidBadge;
    private TextView error;
    private MaterialCardView freeCard;
    private MaterialCardView paidCard;
    private MaterialButton freeAction;
    private MaterialButton paidAction;
    private LinearProgressIndicator usageProgress;
    private RecyclerView plansList;
    private PlanCatalogAdapter plansAdapter;
    private PaymentHistoryStore paymentHistoryStore;
    private final List<PlanCatalogAdapter.Plan> catalogPlans = new ArrayList<>();
    private final Map<String, String> catalogPlanNames = new HashMap<>();
    private boolean accountLoadLogged;
    private boolean checkoutReady;
    private boolean checkoutInProgress;
    private int paidPriceCents;
    private int paidCycleDays = 30;
    private String paidCurrencyId = "BRL";
    private String freePlanName = "";
    private String paidPlanName = "";
    private String activeCheckoutId = "";
    private AlertDialog pixDialog;
    private CountDownTimer pixTimer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            openAuthentication();
            return;
        }

        setContentView(R.layout.activity_plans);
        accountStore = new FirebaseAccountStore(this);
        paymentHistoryStore = new PaymentHistoryStore(this);
        currentName = findViewById(R.id.plans_current_name);
        balance = findViewById(R.id.plans_balance);
        periodStatus = findViewById(R.id.plans_period_status);
        freeName = findViewById(R.id.plans_free_name);
        freePrice = findViewById(R.id.plans_free_price);
        freeDescription = findViewById(R.id.plans_free_description);
        freeTokens = findViewById(R.id.plans_free_tokens);
        freeOutput = findViewById(R.id.plans_free_output);
        paidName = findViewById(R.id.plans_paid_name);
        paidPrice = findViewById(R.id.plans_paid_price);
        paidDescription = findViewById(R.id.plans_paid_description);
        paidTokens = findViewById(R.id.plans_paid_tokens);
        paidOutput = findViewById(R.id.plans_paid_output);
        paymentStatus = findViewById(R.id.plans_payment_status);
        freeBadge = findViewById(R.id.plans_free_badge);
        paidBadge = findViewById(R.id.plans_paid_badge);
        error = findViewById(R.id.plans_error);
        freeCard = findViewById(R.id.plans_free_card);
        paidCard = findViewById(R.id.plans_paid_card);
        freeAction = findViewById(R.id.plans_free_action);
        paidAction = findViewById(R.id.plans_paid_action);
        usageProgress = findViewById(R.id.plans_usage_progress);
        plansList = findViewById(R.id.plans_catalog_list);
        plansAdapter = new PlanCatalogAdapter(plan -> startCheckout(plan.id));
        plansList.setLayoutManager(new LinearLayoutManager(this));
        plansList.setNestedScrollingEnabled(false);
        plansList.setAdapter(plansAdapter);
        findViewById(R.id.plans_payment_history).setOnClickListener(v -> showPaymentHistory());
        // Os cards antigos preservam os textos traduzidos do APK. O Firebase
        // atualiza apenas o preço/ciclo; o checkout continua usando o plano pago.
        freePlanName = getString(R.string.plans_free_name);
        paidPlanName = getString(R.string.plans_paid_name);

        ((com.google.android.material.appbar.MaterialToolbar) findViewById(
                R.id.plans_toolbar
        )).setNavigationOnClickListener(v -> finish());
        paidAction.setOnClickListener(v -> startCheckout(AxionAccount.PLAN_PAID));
        AxionAnalytics.logEvent(this, AxionAnalytics.Events.PLANS_OPENED);
        loadPlanCatalog();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (accountStore == null || currentUser == null) {
            return;
        }
        syncAccountFromServer();
        accountStore.start(currentUser, false, new FirebaseAccountStore.Listener() {
            @Override
            public void onAccountChanged(@NonNull AxionAccount account) {
                render(account);
            }

            @Override
            public void onError(@NonNull Exception exception) {
                error.setVisibility(View.VISIBLE);
                if (!accountLoadLogged) {
                    accountLoadLogged = true;
                    AxionAnalytics.logResult(
                            PlansActivity.this,
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

    @Override
    protected void onDestroy() {
        if (pixDialog != null) {
            pixDialog.dismiss();
            pixDialog = null;
        }
        super.onDestroy();
    }

    private void loadPlanCatalog() {
        String databaseUrl = com.saaspaymentsolutions.axion.BuildConfig.FIREBASE_DATABASE_URL;
        if (databaseUrl != null && !databaseUrl.trim().isEmpty()) {
            com.google.firebase.database.FirebaseDatabase.getInstance(databaseUrl)
                    .getReference("config/plans")
                    .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                            catalogPlans.clear();
                            catalogPlanNames.clear();
                            for (com.google.firebase.database.DataSnapshot item : snapshot.getChildren()) {
                                Boolean active = item.child("active").getValue(Boolean.class);
                                if (Boolean.FALSE.equals(active)) continue;
                                String id = item.child("id").getValue(String.class);
                                if (id == null || id.trim().isEmpty()) id = item.getKey();
                                if (id == null || !id.matches("[a-z0-9_-]{2,80}")) continue;
                                String name = item.child("name").getValue(String.class);
                                if (name == null || name.trim().isEmpty()) name = id;
                                String description = item.child("description").getValue(String.class);
                                Long price = item.child("price_cents").getValue(Long.class);
                                Long cycle = item.child("cycle_days").getValue(Long.class);
                                Long monthly = item.child("monthly_credits").getValue(Long.class);
                                Long signup = item.child("signup_credits").getValue(Long.class);
                                Long output = item.child("max_output_tokens").getValue(Long.class);
                                Long daily = item.child("daily_credit_limit").getValue(Long.class);
                                Long rpm = item.child("requests_per_minute").getValue(Long.class);
                                String currency = item.child("currency_id").getValue(String.class);
                                long credits = "free".equals(id)
                                        ? (signup == null ? 0L : signup)
                                        : (monthly == null ? 0L : monthly);
                                catalogPlans.add(new PlanCatalogAdapter.Plan(
                                        id, name, description == null ? "" : description,
                                        currency == null ? "BRL" : currency,
                                        price == null ? 0 : price.intValue(),
                                        cycle == null ? 30 : cycle.intValue(),
                                        credits, output == null ? 1_024L : output,
                                        daily == null ? credits : daily,
                                        rpm == null ? 3 : rpm.intValue()));
                                catalogPlanNames.put(id, name);
                            }
                            catalogPlans.sort((left, right) -> {
                                if ("free".equals(left.id)) return -1;
                                if ("free".equals(right.id)) return 1;
                                return Integer.compare(left.priceCents, right.priceCents);
                            });
                            checkoutReady = true;
                            plansAdapter.submit(catalogPlans,
                                    currentAccount == null ? "free" : currentAccount.planId);
                            renderCurrentPlanName();
                            hidePaymentStatus();
                            updatePaidAction();
                        }

                        @Override
                        public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                            renderCurrentPlanName();
                            checkoutReady = false;
                            hidePaymentStatus();
                            updatePaidAction();
                        }
                    });
        } else {
            renderCurrentPlanName();
            checkoutReady = false;
            hidePaymentStatus();
            updatePaidAction();
        }
    }

    private void render(@NonNull AxionAccount account) {
        boolean becamePaid = currentAccount != null
                && !currentAccount.isPaid()
                && account.isPaid();
        currentAccount = account;
        plansAdapter.submit(catalogPlans, account.planId);
        error.setVisibility(View.GONE);
        boolean paid = account.isPaid();
        renderCurrentPlanName();
        balance.setText(getString(
                R.string.plans_usage_summary,
                format(account.tokensRemaining),
                format(account.tokenLimit),
                format(account.tokensUsed)
        ));
        usageProgress.setProgress(
                progress(account.tokensUsed, account.tokenLimit),
                true
        );
        if (paid && account.periodEnd > 0L) {
            periodStatus.setText(getString(
                    R.string.plans_valid_until,
                    DateFormat.getDateTimeInstance(
                            DateFormat.SHORT,
                            DateFormat.SHORT,
                            new Locale("pt", "BR")
                    ).format(new Date(account.periodEnd))
            ));
        } else {
            periodStatus.setText(R.string.plans_free_no_expiration);
        }

        freeBadge.setVisibility(paid ? View.GONE : View.VISIBLE);
        paidBadge.setVisibility(paid ? View.VISIBLE : View.GONE);
        styleCard(freeCard, !paid);
        styleCard(paidCard, paid);
        if (becamePaid) {
            paymentHistoryStore.updateStatus(activeCheckoutId, "approved");
            activeCheckoutId = "";
            checkoutInProgress = false;
            if (pixDialog != null && pixDialog.isShowing()) {
                pixDialog.dismiss();
            }
            showPaymentStatus(
                    getString(R.string.plans_payment_approved),
                    false
            );
        }
        freeAction.setText(
                paid ? freePlanName : getString(R.string.plans_current_action)
        );
        updatePaidAction();
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

    private void renderCatalog(
            @Nullable JSONObject free,
            @Nullable JSONObject paid
    ) {
        freeName.setText(R.string.plans_free_name);
        paidName.setText(R.string.plans_paid_name);
        freePrice.setText(R.string.plans_free_price);
        freeDescription.setVisibility(View.GONE);
        paidDescription.setVisibility(View.GONE);
        renderPaidPrice();
    }

    private void renderPaidPrice() {
        if (paidPriceCents <= 0) {
            paidPrice.setText(R.string.plans_paid_price_unconfigured);
            return;
        }
        NumberFormat currency = NumberFormat.getCurrencyInstance(
                new Locale("pt", "BR")
        );
        try {
            currency.setCurrency(Currency.getInstance(paidCurrencyId));
        } catch (IllegalArgumentException ignored) {
            currency.setCurrency(Currency.getInstance("BRL"));
        }
        String price = currency.format(
                BigDecimal.valueOf(paidPriceCents, 2)
        );
        paidPrice.setText(getString(
                R.string.plans_paid_price_cycle,
                price,
                paidCycleDays
        ));
    }

    private void renderCurrentPlanName() {
        if (currentAccount == null) {
            return;
        }
        String name = catalogPlanNames.get(currentAccount.planId);
        currentName.setText(name == null || name.trim().isEmpty()
                ? (currentAccount.isPaid() ? paidPlanName : freePlanName)
                : name);
    }

    private void renderDescription(TextView view, String value) {
        String description = value == null ? "" : value.trim();
        view.setText(description);
        view.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void renderPlanLimits(
            TextView creditsView,
            TextView outputView,
            long credits,
            long maxOutput
    ) {
        creditsView.setText(getString(
                R.string.plans_catalog_credits,
                format(Math.max(0L, credits))
        ));
        outputView.setText(getString(
                R.string.plans_catalog_output,
                format(Math.max(1L, maxOutput))
        ));
    }

    private String planName(@Nullable JSONObject plan, String fallback) {
        if (plan == null) {
            return fallback;
        }
        String value = plan.optString("name", "").trim();
        return value.isEmpty() ? fallback : value;
    }

    private void syncAccountFromServer() {
        AxionPaymentApi.syncAccount(
                this,
                new AxionPaymentApi.ResultCallback() {
                    @Override
                    public void onSuccess(@NonNull JSONObject result) {
                        com.saaspaymentsolutions.axion.AxionManagedApi
                                .applyWalletPayload(result);
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        // O catálogo já apresenta o erro de conexão quando
                        // necessário; a conta permanece disponível no cache.
                    }
                }
        );
    }

    private void updatePaidAction() {
        if (paidAction == null) {
            return;
        }
        boolean paid = currentAccount != null && currentAccount.isPaid();
        if (paid) {
            paidAction.setEnabled(false);
            paidAction.setText(R.string.plans_current_action);
            return;
        }
        paidAction.setEnabled(checkoutReady && !checkoutInProgress);
        paidAction.setText(
                checkoutInProgress
                        ? R.string.plans_payment_creating
                        : R.string.plans_subscribe_action
        );
    }

    private void startCheckout(@NonNull String planId) {
        if (checkoutInProgress) {
            return;
        }
        if (!checkoutReady) {
            showPaymentStatus(getString(R.string.plans_catalog_loading), false);
            return;
        }
        checkoutInProgress = true;
        updatePaidAction();
        showPaymentStatus(
                getString(R.string.plans_payment_creating),
                false
        );
        AxionPaymentApi.createCheckout(
                this,
                planId,
                new AxionPaymentApi.ResultCallback() {
                    @Override
                    public void onSuccess(@NonNull JSONObject result) {
                        JSONObject checkout = result.optJSONObject("checkout");
                        String checkoutId = checkout == null
                                ? ""
                                : checkout.optString("checkoutId", "");
                        String checkoutUrl = checkout == null
                                ? ""
                                : checkout.optString("checkoutUrl", "");
                        String pixCopyPaste = checkout == null
                                ? ""
                                : checkout.optString("pixCopyPaste", "");
                        String qrCodeBase64 = checkout == null
                                ? ""
                                : checkout.optString("qrCodeBase64", "");
                        long expiresAt = checkout == null ? 0L : checkout.optLong("expiresAt", 0L);
                        if (!checkoutId.matches("[a-f0-9]{32}")
                                || (pixCopyPaste.trim().isEmpty()
                                && !checkoutUrl.startsWith("https://"))) {
                            checkoutInProgress = false;
                            showPaymentStatus(
                                    getString(
                                            R.string.plans_payment_invalid_link
                                    ),
                                    true
                            );
                            updatePaidAction();
                            return;
                        }
                        activeCheckoutId = checkoutId;
                        PlanCatalogAdapter.Plan selected = findCatalogPlan(planId);
                        paymentHistoryStore.recordCreated(
                                checkoutId,
                                planId,
                                selected == null ? planId : selected.name,
                                selected == null ? 0 : selected.priceCents,
                                expiresAt
                        );
                        checkoutInProgress = false;
                        showPaymentStatus(
                                getString(R.string.plans_payment_pending),
                                false
                        );
                        updatePaidAction();
                        showPixDialog(
                                checkoutId,
                                checkoutUrl,
                                pixCopyPaste,
                                qrCodeBase64,
                                expiresAt
                        );
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        checkoutInProgress = false;
                        showPaymentStatus(message, true);
                        Toast.makeText(PlansActivity.this, message, Toast.LENGTH_LONG).show();
                        updatePaidAction();
                    }
                }
        );
    }

    private void showPixDialog(
            @NonNull String checkoutId,
            @NonNull String checkoutUrl,
            @NonNull String pixCopyPaste,
            @NonNull String qrCodeBase64,
            long expiresAt
    ) {
        View content = LayoutInflater.from(this).inflate(
                R.layout.dialog_pix_payment,
                null,
                false
        );
        ImageView qrCode = content.findViewById(R.id.pix_qr_code);
        TextView copyCode = content.findViewById(R.id.pix_copy_code);
        TextView status = content.findViewById(R.id.pix_status);
        MaterialButton openLink = content.findViewById(R.id.pix_open_link);
        copyCode.setText(pixCopyPaste);
        copyCode.setVisibility(pixCopyPaste.trim().isEmpty() ? View.GONE : View.VISIBLE);
        Bitmap bitmap = decodeQrCode(qrCodeBase64);
        if (bitmap == null) {
            qrCode.setVisibility(View.GONE);
        } else {
            qrCode.setImageBitmap(bitmap);
        }
        boolean validLink = checkoutUrl.startsWith("https://");
        openLink.setVisibility(validLink ? View.VISIBLE : View.GONE);
        openLink.setOnClickListener(v -> openPaymentLink(checkoutUrl));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.plans_pix_title)
                .setView(content)
                .setPositiveButton(R.string.plans_pix_copy, null)
                .setNeutralButton(R.string.plans_pix_verify, null)
                .setNegativeButton(R.string.plans_pix_close, null)
                .create();
        pixDialog = dialog;
        dialog.setOnShowListener(ignored -> {
            startPixTimer(checkoutId, expiresAt, status, dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(
                    !pixCopyPaste.trim().isEmpty()
            );
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(
                        Context.CLIPBOARD_SERVICE
                );
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Pix Axion", pixCopyPaste));
                    Toast.makeText(this, R.string.plans_pix_copied, Toast.LENGTH_SHORT).show();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                    v -> verifyCheckout(checkoutId, status)
            );
        });
        dialog.setOnDismissListener(ignored -> {
            if (pixTimer != null) { pixTimer.cancel(); pixTimer = null; }
            if (pixDialog == dialog) pixDialog = null;
        });
        dialog.show();
    }

    private void startPixTimer(
            @NonNull String checkoutId,
            long expiresAt,
            @NonNull TextView status,
            @NonNull AlertDialog dialog
    ) {
        if (pixTimer != null) pixTimer.cancel();
        long remaining = expiresAt - System.currentTimeMillis();
        if (remaining <= 0L) {
            paymentHistoryStore.updateStatus(checkoutId, "expired");
            status.setText(R.string.plans_pix_expired);
            return;
        }
        pixTimer = new CountDownTimer(remaining, 1_000L) {
            @Override public void onTick(long millis) { long seconds = Math.max(0L, millis / 1_000L); status.setText(getString(R.string.plans_payment_waiting_expiry, seconds / 60L, seconds % 60L)); }
            @Override public void onFinish() {
                paymentHistoryStore.updateStatus(checkoutId, "expired");
                status.setText(R.string.plans_pix_expired);
                if (dialog.isShowing()) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            }
        }.start();
    }

    private void verifyCheckout(@NonNull String checkoutId, @NonNull TextView statusView) {
        statusView.setText(R.string.plans_payment_pending);
        AxionPaymentApi.fetchCheckout(this, checkoutId, new AxionPaymentApi.ResultCallback() {
            @Override
            public void onSuccess(@NonNull JSONObject result) {
                JSONObject checkout = result.optJSONObject("checkout");
                boolean activated = checkout != null && checkout.optBoolean("activated", false);
                String status = checkout == null ? "" : checkout.optString("status", "");
                if (activated) {
                    paymentHistoryStore.updateStatus(checkoutId, "approved");
                    activeCheckoutId = "";
                    statusView.setText(R.string.plans_pix_approved);
                    showPaymentStatus(getString(R.string.plans_payment_approved), false);
                    syncAccountFromServer();
                    com.saaspaymentsolutions.axion.AxionManagedApi
                            .refreshAccountAndProviders(PlansActivity.this, null);
                } else if ("expired".equalsIgnoreCase(status)
                        || "cancelled".equalsIgnoreCase(status)
                        || "canceled".equalsIgnoreCase(status)) {
                    paymentHistoryStore.updateStatus(checkoutId, "expired");
                    activeCheckoutId = "";
                    statusView.setText(R.string.plans_pix_expired);
                } else if ("failed".equalsIgnoreCase(status)
                        || "refunded".equalsIgnoreCase(status)) {
                    paymentHistoryStore.updateStatus(checkoutId, "failed");
                    activeCheckoutId = "";
                    statusView.setText(R.string.plans_payment_failed);
                } else {
                    statusView.setText(R.string.plans_payment_pending);
                }
            }

            @Override
            public void onError(@NonNull String message) {
                statusView.setText(message);
            }
        });
    }

    @Nullable
    private PlanCatalogAdapter.Plan findCatalogPlan(@NonNull String planId) {
        for (PlanCatalogAdapter.Plan plan : catalogPlans) {
            if (planId.equals(plan.id)) return plan;
        }
        return null;
    }

    private void showPaymentHistory() {
        List<PaymentHistoryStore.Entry> entries = paymentHistoryStore.entries();
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.plans_history_title)
                    .setMessage(R.string.plans_history_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        DateFormat date = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT, new Locale("pt", "BR")
        );
        StringBuilder text = new StringBuilder();
        for (PaymentHistoryStore.Entry entry : entries) {
            if (text.length() > 0) text.append("\n\n");
            String status;
            if ("approved".equals(entry.status)) status = getString(R.string.plans_history_approved);
            else if ("expired".equals(entry.status)) status = getString(R.string.plans_history_expired);
            else if ("failed".equals(entry.status)) status = getString(R.string.plans_history_failed);
            else status = getString(R.string.plans_history_pending);
            text.append(entry.planName)
                    .append(" · ")
                    .append(currency.format(BigDecimal.valueOf(entry.amountCents, 2)))
                    .append("\n")
                    .append(status);
            if (entry.createdAt > 0L) text.append(" · ").append(date.format(new Date(entry.createdAt)));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.plans_history_title)
                .setMessage(text.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openPaymentLink(@NonNull String checkoutUrl) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl)));
        } catch (ActivityNotFoundException exception) {
            showPaymentStatus(getString(R.string.plans_payment_browser_error), true);
        }
    }

    @Nullable
    private Bitmap decodeQrCode(@NonNull String encoded) {
        String value = encoded.trim();
        int comma = value.indexOf(',');
        if (comma >= 0) value = value.substring(comma + 1);
        if (value.isEmpty() || value.length() > 3_000_000) return null;
        try {
            byte[] bytes = Base64.decode(value, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void showPaymentStatus(String message, boolean isError) {
        paymentStatus.setText(message);
        paymentStatus.setTextColor(getColor(
                isError ? R.color.chat_error : R.color.chat_text_secondary
        ));
        paymentStatus.setVisibility(View.VISIBLE);
    }

    private void hidePaymentStatus() {
        paymentStatus.setVisibility(View.GONE);
    }

    private void styleCard(MaterialCardView card, boolean current) {
        card.setStrokeColor(getColor(
                current ? R.color.chat_accent : R.color.chat_border
        ));
        card.setStrokeWidth(dp(current ? 2 : 1));
    }

    private int progress(long used, long limit) {
        if (limit <= 0L) {
            return 0;
        }
        return (int) Math.min(
                1000L,
                Math.max(0L, used * 1000L / limit)
        );
    }

    private String format(long value) {
        return NumberFormat.getIntegerInstance(
                new Locale("pt", "BR")
        ).format(value);
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private void openAuthentication() {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
        );
        startActivity(intent);
        finish();
    }
}
