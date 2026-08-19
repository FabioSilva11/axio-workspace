package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Seletor em duas etapas: provedores do servidor -> modelos do provedor. */
public final class KelivoModelBottomSheet {

    public interface Callback {
        void onModelSelected(String providerId, String modelId);
    }

    private static final String PREF_PINNED = "pinned_models_v1";
    private static final String PREF_PINNED_LEGACY = "kelivo_pinned_models";

    private KelivoModelBottomSheet() {
    }

    public static void show(@NonNull ChatActivity activity, @NonNull Callback callback) {
        if (!AxionManagedApi.providersLoaded()) {
            showLoadingThenOpen(activity, callback);
            return;
        }

        List<AxionManagedApi.ProviderInfo> initialProviders = AxionManagedApi.availableProviders();

        SharedPreferences prefs = AiChatSettingsHelper.prefs(activity);
        AxionManagedApi.ensureManagedSelection(prefs);

        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        View content = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_kelivo_model, null);
        dialog.setContentView(content);

        ImageView back = content.findViewById(R.id.model_back);
        TextView title = content.findViewById(R.id.model_sheet_title);
        EditText search = content.findViewById(R.id.model_search);
        ImageView favoritesJump = content.findViewById(R.id.model_favorites_jump);
        RecyclerView list = content.findViewById(R.id.model_list);
        LinearLayout legacyChips = content.findViewById(R.id.provider_chips);
        if (legacyChips != null) legacyChips.setVisibility(View.GONE);

        KelivoModelSheetAdapter adapter = new KelivoModelSheetAdapter();
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);

        AtomicReference<String> openProviderId = new AtomicReference<>(null);
        AtomicReference<List<AxionManagedApi.ProviderInfo>> providers =
                new AtomicReference<>(initialProviders);

        Runnable render = () -> {
            String query = search.getText() == null
                    ? ""
                    : search.getText().toString().trim().toLowerCase(Locale.getDefault());
            String providerId = openProviderId.get();
            if (providerId == null) {
                back.setVisibility(View.GONE);
                title.setText(R.string.axion_choose_provider);
                search.setHint(R.string.axion_search_providers);
                favoritesJump.setVisibility(View.GONE);
                adapter.submit(buildProviderRows(activity, providers.get(), query, prefs));
                return;
            }

            AxionManagedApi.ProviderInfo provider = findProvider(providers.get(), providerId);
            if (provider == null) {
                openProviderId.set(null);
                renderProviderStage(activity, adapter, back, title, search, favoritesJump,
                        providers.get(), query, prefs);
                return;
            }
            back.setVisibility(View.VISIBLE);
            title.setText(provider.name);
            search.setHint(R.string.axion_search_models);
            favoritesJump.setVisibility(hasPinnedForProvider(activity, provider.id)
                    ? View.VISIBLE : View.GONE);
            adapter.submit(buildModelRows(activity, provider, query, prefs));
        };

        adapter.setListener(new KelivoModelSheetAdapter.Listener() {
            @Override
            public void onProviderSelected(String providerId) {
                openProviderId.set(providerId);
                search.setText("");
                list.scrollToPosition(0);
                render.run();
            }

            @Override
            public void onModelSelected(String providerId, String modelId) {
                if (!AxionManagedApi.isModelAvailable(providerId, modelId)) {
                    Toast.makeText(activity, R.string.axion_no_models_in_provider, Toast.LENGTH_LONG).show();
                    AxionManagedApi.refreshProviders(activity, null);
                    return;
                }
                AxionManagedApi.saveManagedSelection(prefs, providerId, modelId);
                callback.onModelSelected(AxionManagedApi.PROVIDER_ID, modelId);
                dialog.dismiss();
            }

            @Override
            public void onFavoriteToggle(String providerId, String modelId) {
                togglePinned(activity, providerId, modelId);
                render.run();
            }
        });

        back.setOnClickListener(v -> {
            openProviderId.set(null);
            search.setText("");
            list.scrollToPosition(0);
            render.run();
        });

        favoritesJump.setOnClickListener(v -> {
            String providerId = openProviderId.get();
            if (providerId == null) return;
            if (search.getText() != null && search.getText().length() > 0) search.setText("");
            AxionManagedApi.ProviderInfo provider = findProvider(providers.get(), providerId);
            if (provider != null) {
                adapter.submit(buildModelRows(activity, provider, "", prefs, true));
                list.scrollToPosition(0);
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        AxionManagedApi.ModelsListener modelsListener = () -> {
            List<AxionManagedApi.ProviderInfo> updated = AxionManagedApi.availableProviders();
            providers.set(updated);
            String open = openProviderId.get();
            if (open != null && findProvider(updated, open) == null) openProviderId.set(null);
            AxionManagedApi.ensureManagedSelection(prefs);
            render.run();
        };
        AxionManagedApi.addModelsListener(modelsListener);
        dialog.setOnDismissListener(d -> AxionManagedApi.removeModelsListener(modelsListener));

        dialog.setOnShowListener(d -> {
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                int targetHeight = (int) (metrics.heightPixels * 0.82f);
                sheet.getLayoutParams().height = targetHeight;
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setPeekHeight(targetHeight);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        render.run();
        dialog.show();
    }

    private static void showLoadingThenOpen(ChatActivity activity, Callback callback) {
        android.app.ProgressDialog progress = new android.app.ProgressDialog(activity);
        progress.setMessage(activity.getString(R.string.chat_models_loading));
        progress.setCancelable(true);
        progress.show();

        AtomicBoolean finished = new AtomicBoolean(false);
        AxionManagedApi.ModelsListener[] listener = new AxionManagedApi.ModelsListener[1];
        listener[0] = new AxionManagedApi.ModelsListener() {
            @Override
            public void onModelsUpdated() {
                if (!AxionManagedApi.providersLoaded() || !finished.compareAndSet(false, true)) return;
                AxionManagedApi.removeModelsListener(listener[0]);
                try { progress.dismiss(); } catch (Exception ignored) { }
                if (!activity.isFinishing() && !activity.isDestroyed()) show(activity, callback);
            }
        };
        AxionManagedApi.addModelsListener(listener[0]);
        progress.setOnCancelListener(d -> {
            finished.set(true);
            AxionManagedApi.removeModelsListener(listener[0]);
        });
        AxionManagedApi.refreshAccountAndProviders(activity, (changed, error) -> {
            if (error == null || !finished.compareAndSet(false, true)) return;
            AxionManagedApi.removeModelsListener(listener[0]);
            try { progress.dismiss(); } catch (Exception ignored) { }
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                if (AxionManagedApi.providersLoaded()) {
                    show(activity, callback);
                } else {
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static void renderProviderStage(
            Context context,
            KelivoModelSheetAdapter adapter,
            ImageView back,
            TextView title,
            EditText search,
            ImageView favorites,
            List<AxionManagedApi.ProviderInfo> providers,
            String query,
            SharedPreferences prefs) {
        back.setVisibility(View.GONE);
        title.setText(R.string.axion_choose_provider);
        search.setHint(R.string.axion_search_providers);
        favorites.setVisibility(View.GONE);
        adapter.submit(buildProviderRows(context, providers, query, prefs));
    }

    private static List<KelivoModelSheetAdapter.Row> buildProviderRows(
            Context context,
            List<AxionManagedApi.ProviderInfo> providers,
            String query,
            SharedPreferences prefs) {
        List<KelivoModelSheetAdapter.Row> rows = new ArrayList<>();
        String selected = AxionManagedApi.selectedServerProviderId(prefs);
        for (AxionManagedApi.ProviderInfo provider : providers) {
            if (!matches(provider.name, provider.id, query)) continue;
            String planLabel = planLabel(context, provider.availablePlans);
            String count = context.getString(R.string.axion_provider_models_count, provider.models.size());
            rows.add(KelivoModelSheetAdapter.Row.provider(
                    provider.id,
                    provider.name,
                    count + " • " + planLabel,
                    provider.id.equals(selected)));
        }
        if (rows.isEmpty()) {
            String error = AxionManagedApi.lastProvidersError();
            rows.add(KelivoModelSheetAdapter.Row.empty(
                    error.isEmpty()
                            ? context.getString(R.string.axion_no_providers_available)
                            : error));
        }
        return rows;
    }

    private static List<KelivoModelSheetAdapter.Row> buildModelRows(
            Context context,
            AxionManagedApi.ProviderInfo provider,
            String query,
            SharedPreferences prefs) {
        return buildModelRows(context, provider, query, prefs, false);
    }

    private static List<KelivoModelSheetAdapter.Row> buildModelRows(
            Context context,
            AxionManagedApi.ProviderInfo provider,
            String query,
            SharedPreferences prefs,
            boolean favoritesOnly) {
        List<KelivoModelSheetAdapter.Row> rows = new ArrayList<>();
        Set<String> pinned = getPinned(context);
        String currentProvider = AxionManagedApi.selectedServerProviderId(prefs);
        String currentModel = prefs.getString(AiChatSettingsHelper.PREF_CURRENT_MODEL, "");
        for (AxionManagedApi.ModelInfo model : provider.models) {
            if (!matches(model.name, model.id, query)) continue;
            boolean modelPinned = pinned.contains(pinnedKey(provider.id, model.id));
            if (favoritesOnly && !modelPinned) continue;
            boolean selected = provider.id.equals(currentProvider) && model.id.equals(currentModel);
            rows.add(new KelivoModelSheetAdapter.Row(
                    provider.id, provider.name, model.id, selected, modelPinned));
        }
        if (rows.isEmpty()) {
            rows.add(KelivoModelSheetAdapter.Row.empty(
                    context.getString(R.string.axion_no_models_in_provider)));
        }
        return rows;
    }

    private static AxionManagedApi.ProviderInfo findProvider(
            List<AxionManagedApi.ProviderInfo> providers,
            String providerId) {
        if (providers == null) return null;
        for (AxionManagedApi.ProviderInfo provider : providers) {
            if (provider.id.equals(providerId)) return provider;
        }
        return null;
    }

    private static boolean matches(String name, String id, String query) {
        if (query == null || query.isEmpty()) return true;
        String normalized = query.toLowerCase(Locale.getDefault());
        return (name != null && name.toLowerCase(Locale.getDefault()).contains(normalized))
                || (id != null && id.toLowerCase(Locale.getDefault()).contains(normalized));
    }

    private static String planLabel(Context context, String availablePlans) {
        String plan = availablePlans == null ? "all" : availablePlans.trim().toLowerCase(Locale.US);
        if ("free".equals(plan)) return context.getString(R.string.axion_provider_plan_free);
        if ("paid".equals(plan)) return context.getString(R.string.axion_provider_plan_paid);
        return context.getString(R.string.axion_provider_plan_all);
    }

    private static boolean hasPinnedForProvider(Context context, String providerId) {
        String prefix = (providerId == null ? "" : providerId) + "::";
        for (String item : getPinned(context)) {
            if (item.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Set<String> getPinned(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("chat_settings", Context.MODE_PRIVATE);
        Set<String> pinned = new HashSet<>(prefs.getStringSet(PREF_PINNED, new HashSet<>()));
        pinned.addAll(prefs.getStringSet(PREF_PINNED_LEGACY, new HashSet<>()));
        return pinned;
    }

    private static void togglePinned(Context context, String providerId, String modelId) {
        SharedPreferences prefs = context.getSharedPreferences("chat_settings", Context.MODE_PRIVATE);
        Set<String> pinned = new HashSet<>(prefs.getStringSet(PREF_PINNED, new HashSet<>()));
        Set<String> legacyPinned = new HashSet<>(prefs.getStringSet(PREF_PINNED_LEGACY, new HashSet<>()));
        pinned.addAll(legacyPinned);
        String key = pinnedKey(providerId, modelId);
        if (pinned.contains(key)) {
            pinned.remove(key);
            legacyPinned.remove(key);
        } else {
            pinned.add(key);
        }
        prefs.edit()
                .putStringSet(PREF_PINNED, pinned)
                .putStringSet(PREF_PINNED_LEGACY, legacyPinned)
                .apply();
    }

    private static String pinnedKey(String providerId, String modelId) {
        return (providerId == null ? "" : providerId) + "::" + (modelId == null ? "" : modelId);
    }
}
