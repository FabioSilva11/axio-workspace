package com.saaspaymentsolutions.axion.account;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.saaspaymentsolutions.axion.R;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/** Catálogo dinâmico publicado pelo administrador na configuração remota. */
final class PlanCatalogAdapter extends RecyclerView.Adapter<PlanCatalogAdapter.Holder> {
    static final class Plan {
        final String id;
        final String name;
        final String description;
        final String currency;
        final int priceCents;
        final int cycleDays;
        final long credits;
        final long maxOutput;
        final long dailyCredits;
        final int requestsPerMinute;

        Plan(String id, String name, String description, String currency, int priceCents,
             int cycleDays, long credits, long maxOutput, long dailyCredits,
             int requestsPerMinute) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.currency = currency;
            this.priceCents = priceCents;
            this.cycleDays = cycleDays;
            this.credits = credits;
            this.maxOutput = maxOutput;
            this.dailyCredits = dailyCredits;
            this.requestsPerMinute = requestsPerMinute;
        }
    }

    interface Listener {
        void onPlanSelected(@NonNull Plan plan);
    }

    private final List<Plan> items = new ArrayList<>();
    private final Listener listener;
    private String currentPlanId = "free";

    PlanCatalogAdapter(Listener listener) {
        this.listener = listener;
    }

    void submit(List<Plan> plans, String currentPlanId) {
        items.clear();
        if (plans != null) {
            items.addAll(plans);
        }
        this.currentPlanId = currentPlanId == null ? "free" : currentPlanId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Context context = parent.getContext();
        int padding = dp(context, 18);
        MaterialCardView card = new MaterialCardView(context);
        card.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        card.setUseCompatPadding(true);
        card.setCardBackgroundColor(context.getColor(R.color.chat_surface));
        card.setStrokeColor(context.getColor(R.color.chat_border));
        card.setStrokeWidth(dp(context, 1));
        card.setRadius(dp(context, 18));

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(padding, padding, padding, padding);
        card.addView(box);

        TextView title = text(context, 20, true);
        TextView price = text(context, 14, false);
        TextView description = text(context, 14, false);
        TextView limits = text(context, 13, false);
        box.addView(title);
        box.addView(price);
        box.addView(description);
        box.addView(limits);

        MaterialButton action = new MaterialButton(context);
        action.setText(R.string.plans_subscribe_action);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = dp(context, 12);
        box.addView(action, actionParams);
        return new Holder(card, title, price, description, limits, action);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Plan plan = items.get(position);
        boolean current = plan.id.equals(currentPlanId);
        boolean payable = !current && plan.priceCents >= 100;

        holder.title.setText(plan.name);
        holder.price.setText(price(holder.itemView.getContext(), plan));
        holder.description.setText(plan.description);
        holder.limits.setText(holder.itemView.getContext().getString(
                R.string.plan_catalog_limits_detailed,
                fmt(plan.credits), fmt(plan.dailyCredits), fmt(plan.maxOutput),
                plan.requestsPerMinute));
        holder.action.setEnabled(payable);
        holder.action.setText(current
                ? R.string.plans_current_action
                : plan.priceCents <= 0
                ? R.string.plans_free_price
                : R.string.plans_subscribe_action);

        View.OnClickListener selectPlan = view -> {
            if (payable) {
                listener.onPlanSelected(plan);
            }
        };
        holder.action.setOnClickListener(selectPlan);
        holder.itemView.setClickable(payable);
        holder.itemView.setFocusable(payable);
        holder.itemView.setOnClickListener(selectPlan);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView price;
        final TextView description;
        final TextView limits;
        final MaterialButton action;

        Holder(MaterialCardView view, TextView title, TextView price, TextView description,
               TextView limits, MaterialButton action) {
            super(view);
            this.title = title;
            this.price = price;
            this.description = description;
            this.limits = limits;
            this.action = action;
        }
    }

    private static TextView text(Context context, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setTextColor(context.getColor(R.color.chat_text_primary));
        view.setTextSize(size);
        if (bold) {
            view.setTypeface(null, 1);
        }
        return view;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    private static String fmt(long value) {
        return NumberFormat.getIntegerInstance(Locale.getDefault()).format(Math.max(0, value));
    }

    private static String price(Context context, Plan plan) {
        if (plan.priceCents <= 0) {
            return context.getString(R.string.plans_free_price);
        }
        NumberFormat formatter = NumberFormat.getCurrencyInstance(Locale.getDefault());
        try {
            formatter.setCurrency(Currency.getInstance(plan.currency));
        } catch (Exception ignored) {
            // Usa BRL como formato visual padrão se a moeda remota for inválida.
        }
        return context.getString(R.string.plan_price_cycle,
                formatter.format(BigDecimal.valueOf(plan.priceCents, 2)), plan.cycleDays);
    }
}
