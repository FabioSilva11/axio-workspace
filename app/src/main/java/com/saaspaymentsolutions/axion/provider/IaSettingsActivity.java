package com.saaspaymentsolutions.axion.provider;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.saaspaymentsolutions.axion.AiChatSettingsHelper;
import com.saaspaymentsolutions.axion.KelivoModelIconResolver;
import com.saaspaymentsolutions.axion.R;
import com.saaspaymentsolutions.axion.port.VoidPortSettings;

/**
 * Full-screen activity that lists all AI providers (built-in + custom) and
 * allows the user to manage their API keys, base URLs, and models manually.
 */
public final class IaSettingsActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private EditText searchInput;
    private ProviderAdapter adapter;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ia_settings);

        prefs = AiChatSettingsHelper.prefs(this);

        MaterialToolbar toolbar = findViewById(R.id.top_app_bar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        searchInput = findViewById(R.id.et_search_providers);
        recyclerView = findViewById(R.id.rv_providers);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProviderAdapter();
        recyclerView.setAdapter(adapter);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadProviders();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProviders();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_providers, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_check_providers) {
            Toast.makeText(this, R.string.ia_providers_checked, Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_import_provider) {
            showImportDialog();
            return true;
        } else if (id == R.id.action_add_provider) {
            showAddProviderDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ────────────────────────────────────────
    // Load providers
    // ────────────────────────────────────────

    private void loadProviders() {
        List<VoidPortSettings.ProviderCardSpec> allProviders = VoidPortSettings.getProviderCards(prefs);
        adapter.setData(allProviders);
    }

    // ────────────────────────────────────────
    // Import dialog
    // ────────────────────────────────────────

    private void showImportDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_text_input, null);
        TextInputEditText input = dialogView.findViewById(R.id.dialog_edit_text);
        if (input != null) {
            input.setHint(R.string.ia_import_provider_hint);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.common_word_import)
                .setView(dialogView)
                .setNegativeButton(R.string.common_word_cancel, null)
                .setPositiveButton(R.string.common_word_ok, (dialog, which) -> {
                    if (input == null || input.getText() == null) return;
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) return;
                    importProviderJson(raw);
                })
                .show();
    }

    private void importProviderJson(String raw) {
        try {
            JSONObject obj = new JSONObject(raw);
            VoidPortSettings.saveProviderConfig(prefs, obj);
            loadProviders();
            Toast.makeText(this,
                    getString(R.string.ia_imported_providers, 1),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.ia_import_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ────────────────────────────────────────
    // Add provider dialog
    // ────────────────────────────────────────

    private void showAddProviderDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_text_input, null);
        TextInputEditText input = dialogView.findViewById(R.id.dialog_edit_text);
        if (input != null) {
            input.setHint(R.string.ia_name_label);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ia_add_provider)
                .setView(dialogView)
                .setNegativeButton(R.string.common_word_cancel, null)
                .setPositiveButton(R.string.common_word_ok, (dialog, which) -> {
                    if (input == null || input.getText() == null) return;
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.ia_provider_name_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createNewProvider(name);
                })
                .show();
    }

    private void createNewProvider(String name) {
        try {
            String id = VoidPortSettings.uniqueProviderId(prefs, name);
            JSONObject config = new JSONObject();
            config.put("id", id);
            config.put("name", name);
            config.put("enabled", true);
            config.put("apiKey", "");
            config.put("baseUrl", VoidPortSettings.defaultBaseForProviderType("openai_compatible"));
            config.put("chatPath", "/chat/completions");
            config.put("providerType", "openai_compatible");
            VoidPortSettings.saveProviderConfig(prefs, config);

            loadProviders();

            // Open the detail screen for the new provider
            Intent intent = new Intent(this, ProviderDetailActivity.class);
            intent.putExtra("provider_id", id);
            intent.putExtra("provider_name", name);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ────────────────────────────────────────
    // RecyclerView adapter
    // ────────────────────────────────────────

    private final class ProviderAdapter extends RecyclerView.Adapter<ProviderAdapter.VH> {

        private final List<VoidPortSettings.ProviderCardSpec> allItems = new ArrayList<>();
        private final List<VoidPortSettings.ProviderCardSpec> displayed = new ArrayList<>();

        void setData(List<VoidPortSettings.ProviderCardSpec> items) {
            allItems.clear();
            allItems.addAll(items);
            filter(searchInput != null && searchInput.getText() != null
                    ? searchInput.getText().toString() : "");
        }

        void filter(String query) {
            displayed.clear();
            String q = query.trim().toLowerCase(Locale.getDefault());
            for (VoidPortSettings.ProviderCardSpec spec : allItems) {
                if (q.isEmpty()
                        || spec.title.toLowerCase(Locale.getDefault()).contains(q)
                        || spec.providerId.toLowerCase(Locale.getDefault()).contains(q)
                        || spec.description.toLowerCase(Locale.getDefault()).contains(q)) {
                    displayed.add(spec);
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_provider_row, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            VoidPortSettings.ProviderCardSpec spec = displayed.get(position);
            holder.name.setText(spec.title);

            // Resolve icon — show colored icons (no tint override)
            int iconRes = KelivoModelIconResolver.resolveProvider(spec.providerId, spec.title);
            holder.icon.clearColorFilter();
            if (iconRes != 0) {
                holder.icon.setImageResource(iconRes);
            } else {
                holder.icon.setImageResource(R.drawable.kelivo_icon_openai);
                holder.icon.clearColorFilter();
            }

            // Status badge
            boolean enabled = isProviderEnabled(spec);
            holder.badge.setText(enabled ? R.string.ia_status_on : R.string.ia_status_off);
            holder.badge.setTextColor(holder.itemView.getContext().getResources().getColor(
                    enabled ? R.color.provider_status_on_text : R.color.provider_status_off_text,
                    null));
            holder.badge.setBackgroundResource(enabled
                    ? R.drawable.bg_provider_status_badge_on
                    : R.drawable.bg_provider_status_badge);

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(IaSettingsActivity.this, ProviderDetailActivity.class);
                intent.putExtra("provider_id", spec.providerId);
                intent.putExtra("provider_name", spec.title);
                intent.putExtra("provider_description", spec.description);
                intent.putExtra("provider_custom", spec.custom);
                startActivity(intent);
            });
        }


        @Override
        public int getItemCount() {
            return displayed.size();
        }

        private boolean isProviderEnabled(VoidPortSettings.ProviderCardSpec spec) {
            if (spec.custom) {
                JSONObject config = VoidPortSettings.getProviderConfigObject(prefs, spec.providerId);
                return config != null && config.optBoolean("enabled", true);
            }
            // For built-in providers, check if any API key or enabled pref is set
            for (VoidPortSettings.FieldSpec field : spec.fields) {
                if (field.enabledKey != null) {
                    return prefs.getBoolean(field.enabledKey, false);
                }
            }
            // If there's an API key field, check if it has a value
            for (VoidPortSettings.FieldSpec field : spec.fields) {
                if (field.password) {
                    String val = prefs.getString(field.prefKey, field.defaultValue);
                    return val != null && !val.trim().isEmpty();
                }
            }
            return false;
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView badge;

            VH(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.provider_icon);
                name = itemView.findViewById(R.id.provider_name);
                badge = itemView.findViewById(R.id.provider_status_badge);
            }
        }
    }
}
