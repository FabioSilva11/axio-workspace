package com.saaspaymentsolutions.axion.provider;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.saaspaymentsolutions.axion.AiChatSettingsHelper;
import com.saaspaymentsolutions.axion.R;
import com.saaspaymentsolutions.axion.port.VoidPortSettings;

/**
 * Full-screen activity for managing a single AI provider.
 * Allows configuring API key, base URL, API path, and managing models.
 */
public final class ProviderDetailActivity extends AppCompatActivity {

    private String providerId;
    private String providerName;
    private boolean isCustom;
    private SharedPreferences prefs;

    // Views
    private TextView tvProviderType;
    private TextView tvGroupValue;
    private MaterialSwitch switchEnabled;
    private MaterialSwitch switchMultiKey;
    private MaterialSwitch switchResponseApi;
    private EditText etProviderName;
    private EditText etApiKey;
    private EditText etBaseUrl;
    private EditText etApiPath;
    private LinearLayout extraFieldsContainer;

    // Models tab
    private LinearLayout tabConfigContent;
    private LinearLayout tabModelsContent;
    private LinearLayout modelsEmptyState;
    private RecyclerView rvProviderModels;
    private LinearLayout modelsActionBar;
    private MaterialButton btnFetchModels;
    private MaterialButton btnAddModel;

    // Tab selectors
    private LinearLayout tabConfig;
    private LinearLayout tabModels;
    private ImageView iconTabConfig;
    private TextView labelTabConfig;
    private ImageView iconTabModels;
    private TextView labelTabModels;

    private ModelAdapter modelAdapter;
    private List<String> modelList = new ArrayList<>();
    private boolean isConfigTab = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provider_detail);

        prefs = AiChatSettingsHelper.prefs(this);

        providerId = getIntent().getStringExtra("provider_id");
        providerName = getIntent().getStringExtra("provider_name");
        isCustom = getIntent().getBooleanExtra("provider_custom", false);

        if (providerId == null || providerName == null) {
            finish();
            return;
        }

        setupToolbar();
        setupViews();
        setupTabs();
        loadProviderData();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_provider_detail, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_test_provider) {
            testProvider();
            return true;
        } else if (id == R.id.action_share_provider) {
            shareProvider();
            return true;
        } else if (id == R.id.action_delete_provider) {
            confirmDeleteProvider();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ────────────────────────────────────────
    // Setup
    // ────────────────────────────────────────

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.top_app_bar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(providerName);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> {
            saveAndFinish();
        });
    }

    private void setupViews() {
        tvProviderType = findViewById(R.id.tv_provider_type_value);
        tvGroupValue = findViewById(R.id.tv_group_value);
        switchEnabled = findViewById(R.id.switch_enabled);
        switchMultiKey = findViewById(R.id.switch_multi_key);
        switchResponseApi = findViewById(R.id.switch_response_api);
        etProviderName = findViewById(R.id.et_provider_name);
        etApiKey = findViewById(R.id.et_api_key);
        etBaseUrl = findViewById(R.id.et_api_base_url);
        etApiPath = findViewById(R.id.et_api_path);
        extraFieldsContainer = findViewById(R.id.extra_fields_container);

        tabConfigContent = findViewById(R.id.tab_config_content);
        tabModelsContent = findViewById(R.id.tab_models_content);
        modelsEmptyState = findViewById(R.id.models_empty_state);
        rvProviderModels = findViewById(R.id.rv_provider_models);
        modelsActionBar = findViewById(R.id.models_action_bar);
        btnFetchModels = findViewById(R.id.btn_fetch_models);
        btnAddModel = findViewById(R.id.btn_add_model);

        tabConfig = findViewById(R.id.tab_config);
        tabModels = findViewById(R.id.tab_models);
        iconTabConfig = findViewById(R.id.icon_tab_config);
        labelTabConfig = findViewById(R.id.label_tab_config);
        iconTabModels = findViewById(R.id.icon_tab_models);
        labelTabModels = findViewById(R.id.label_tab_models);

        modelAdapter = new ModelAdapter();
        rvProviderModels.setLayoutManager(new LinearLayoutManager(this));
        rvProviderModels.setAdapter(modelAdapter);

        btnFetchModels.setOnClickListener(v -> fetchModelsFromApi());
        btnAddModel.setOnClickListener(v -> showAddModelDialog());
    }

    private void setupTabs() {
        tabConfig.setOnClickListener(v -> switchToConfigTab());
        tabModels.setOnClickListener(v -> switchToModelsTab());
    }

    private void switchToConfigTab() {
        isConfigTab = true;
        tabConfigContent.setVisibility(View.VISIBLE);
        tabModelsContent.setVisibility(View.GONE);
        modelsActionBar.setVisibility(View.GONE);
        iconTabConfig.setColorFilter(getResources().getColor(R.color.chat_accent, null));
        labelTabConfig.setTextColor(getResources().getColor(R.color.chat_accent, null));
        iconTabModels.setColorFilter(getResources().getColor(R.color.chat_text_secondary, null));
        labelTabModels.setTextColor(getResources().getColor(R.color.chat_text_secondary, null));
    }

    private void switchToModelsTab() {
        isConfigTab = false;
        tabConfigContent.setVisibility(View.GONE);
        tabModelsContent.setVisibility(View.VISIBLE);
        modelsActionBar.setVisibility(View.VISIBLE);
        iconTabConfig.setColorFilter(getResources().getColor(R.color.chat_text_secondary, null));
        labelTabConfig.setTextColor(getResources().getColor(R.color.chat_text_secondary, null));
        iconTabModels.setColorFilter(getResources().getColor(R.color.chat_accent, null));
        labelTabModels.setTextColor(getResources().getColor(R.color.chat_accent, null));
        updateModelsList();
    }

    // ────────────────────────────────────────
    // Load provider data
    // ────────────────────────────────────────

    private void loadProviderData() {
        // Determine provider type and group
        String providerType = getProviderTypeDisplay();
        tvProviderType.setText(providerType);
        tvGroupValue.setText(providerName);

        // Load fields from ProviderCardSpec
        VoidPortSettings.ProviderCardSpec spec = findSpec();

        if (spec != null) {
            // Set up fields from spec
            for (VoidPortSettings.FieldSpec field : spec.fields) {
                if (field.password && field.prefKey.contains("api_key")) {
                    etApiKey.setText(prefs.getString(field.prefKey, field.defaultValue));
                    etApiKey.setSelection(etApiKey.getText().length());
                } else if (field.prefKey.contains("base_url") || field.prefKey.contains("url")) {
                    etBaseUrl.setText(prefs.getString(field.prefKey, field.defaultValue));
                } else if (field.prefKey.contains("path")) {
                    etApiPath.setText(prefs.getString(field.prefKey, field.defaultValue));
                }
            }

            // Check enabled state
            boolean enabled = false;
            for (VoidPortSettings.FieldSpec field : spec.fields) {
                if (field.enabledKey != null) {
                    enabled = prefs.getBoolean(field.enabledKey, false);
                    break;
                }
            }
            // Also check if API key is set
            if (!enabled) {
                for (VoidPortSettings.FieldSpec field : spec.fields) {
                    if (field.password) {
                        String val = prefs.getString(field.prefKey, field.defaultValue);
                        enabled = val != null && !val.trim().isEmpty();
                        break;
                    }
                }
            }
            switchEnabled.setChecked(enabled);
        } else {
            // Custom provider - load from config JSON
            JSONObject config = VoidPortSettings.getProviderConfigObject(prefs, providerId);
            if (config != null) {
                etProviderName.setText(config.optString("name", providerName));
                etApiKey.setText(config.optString("apiKey", ""));
                etBaseUrl.setText(config.optString("baseUrl", ""));
                etApiPath.setText(config.optString("chatPath", ""));
                switchEnabled.setChecked(config.optBoolean("enabled", true));
                switchMultiKey.setChecked(config.optBoolean("multiKey", false));
                switchResponseApi.setChecked(config.optBoolean("responseApi", false));
            } else {
                etProviderName.setText(providerName);
            }
        }

        // Load models
        loadModels();
    }

    private VoidPortSettings.ProviderCardSpec findSpec() {
        List<VoidPortSettings.ProviderCardSpec> all = VoidPortSettings.getProviderCards(prefs);
        for (VoidPortSettings.ProviderCardSpec spec : all) {
            if (providerId.equals(spec.providerId)) {
                return spec;
            }
        }
        return null;
    }

    private String getProviderTypeDisplay() {
        String type = providerId;
        if (providerId.contains("openai")) return "OpenAI";
        if (providerId.contains("anthropic")) return "Anthropic";
        if (providerId.contains("gemini")) return "Gemini";
        if (providerId.contains("openrouter")) return "OpenRouter";
        if (providerId.contains("deepseek")) return "DeepSeek";
        if (providerId.contains("groq")) return "Groq";
        if (providerId.contains("mistral")) return "Mistral";
        if (providerId.contains("grok") || providerId.contains("xai")) return "Grok (xAI)";
        if (providerId.contains("minimax")) return "MiniMax";
        if (providerId.contains("ollama")) return "Ollama";
        if (providerId.contains("vllm")) return "vLLM";
        if (providerId.contains("lm_studio")) return "LM Studio";
        if (providerId.contains("litellm")) return "LiteLLM";
        if (providerId.contains("huggingface")) return "Hugging Face";
        return providerName;
    }

    // ────────────────────────────────────────
    // Save
    // ────────────────────────────────────────

    private void saveAndFinish() {
        saveProviderData();
        finish();
    }

    private void saveProviderData() {
        VoidPortSettings.ProviderCardSpec spec = findSpec();

        if (spec != null) {
            // Built-in provider: save to individual pref keys
            SharedPreferences.Editor editor = prefs.edit();
            for (VoidPortSettings.FieldSpec field : spec.fields) {
                if (field.password && etApiKey != null && etApiKey.getText() != null) {
                    editor.putString(field.prefKey, etApiKey.getText().toString().trim());
                    if (field.enabledKey != null) {
                        editor.putBoolean(field.enabledKey, switchEnabled.isChecked());
                    }
                } else if (field.prefKey.contains("base_url") || field.prefKey.contains("url")) {
                    if (etBaseUrl != null && etBaseUrl.getText() != null) {
                        editor.putString(field.prefKey, etBaseUrl.getText().toString().trim());
                    }
                } else if (field.prefKey.contains("path")) {
                    if (etApiPath != null && etApiPath.getText() != null) {
                        editor.putString(field.prefKey, etApiPath.getText().toString().trim());
                    }
                }
            }
            editor.apply();
        } else {
            // Custom provider: save to JSON config
            JSONObject config = VoidPortSettings.getProviderConfigObject(prefs, providerId);
            if (config == null) {
                config = new JSONObject();
                try {
                    config.put("id", providerId);
                } catch (Exception ignored) {}
            }
            try {
                if (etProviderName != null && etProviderName.getText() != null) {
                    config.put("name", etProviderName.getText().toString().trim());
                }
                config.put("enabled", switchEnabled.isChecked());
                config.put("multiKey", switchMultiKey.isChecked());
                config.put("responseApi", switchResponseApi.isChecked());
                if (etApiKey != null && etApiKey.getText() != null) {
                    config.put("apiKey", etApiKey.getText().toString().trim());
                }
                if (etBaseUrl != null && etBaseUrl.getText() != null) {
                    config.put("baseUrl", etBaseUrl.getText().toString().trim());
                }
                if (etApiPath != null && etApiPath.getText() != null) {
                    config.put("chatPath", etApiPath.getText().toString().trim());
                }
            } catch (Exception ignored) {}
            VoidPortSettings.saveProviderConfig(prefs, config);
        }

        // Save models
        saveModels();
    }

    // ────────────────────────────────────────
    // Models management
    // ────────────────────────────────────────

    private void loadModels() {
        modelList.clear();
        JSONArray models = prefs.getString("ia_settings", "[]").isEmpty()
                ? new JSONArray()
                : readModelsArray();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && providerId.equals(model.optString("providerId", ""))) {
                String modelId = model.optString("model", "");
                if (!modelId.isEmpty() && !modelList.contains(modelId)) {
                    modelList.add(modelId);
                }
            }
        }
        updateModelsList();
    }

    private JSONArray readModelsArray() {
        String raw = prefs.getString("custom_models", "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveModels() {
        // Models are saved individually via the add/remove operations
    }

    private void updateModelsList() {
        modelAdapter.notifyDataSetChanged();
        if (modelList.isEmpty()) {
            modelsEmptyState.setVisibility(View.VISIBLE);
            rvProviderModels.setVisibility(View.GONE);
        } else {
            modelsEmptyState.setVisibility(View.GONE);
            rvProviderModels.setVisibility(View.VISIBLE);
        }
    }

    private void showAddModelDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_text_input, null);
        TextInputEditText input = dialogView.findViewById(R.id.dialog_edit_text);
        if (input != null) {
            input.setHint(R.string.ia_model_id_label);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ia_add_model_title)
                .setView(dialogView)
                .setNegativeButton(R.string.common_word_cancel, null)
                .setPositiveButton(R.string.common_word_ok, (dialog, which) -> {
                    if (input == null || input.getText() == null) return;
                    String modelId = input.getText().toString().trim();
                    if (modelId.isEmpty()) return;
                    addModel(modelId);
                })
                .show();
    }

    private void addModel(String modelId) {
        if (modelList.contains(modelId)) return;
        modelList.add(modelId);

        // Persist to custom_models pref
        try {
            JSONArray models = readModelsArray();
            JSONObject newModel = new JSONObject();
            newModel.put("providerId", providerId);
            newModel.put("providerLabel", providerName);
            newModel.put("model", modelId);
            models.put(newModel);
            prefs.edit().putString("custom_models", models.toString()).apply();
        } catch (Exception ignored) {}

        updateModelsList();
        Toast.makeText(this, getString(R.string.ia_models_fetched, 1), Toast.LENGTH_SHORT).show();
    }

    private void removeModel(String modelId) {
        modelList.remove(modelId);

        // Remove from custom_models pref
        try {
            JSONArray models = readModelsArray();
            JSONArray next = new JSONArray();
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m != null && !(providerId.equals(m.optString("providerId", ""))
                        && modelId.equals(m.optString("model", "")))) {
                    next.put(m);
                }
            }
            prefs.edit().putString("custom_models", next.toString()).apply();
        } catch (Exception ignored) {}

        updateModelsList();
    }

    private void fetchModelsFromApi() {
        Toast.makeText(this, R.string.ia_fetching_models, Toast.LENGTH_SHORT).show();
        // Placeholder: In a full implementation, this would make an HTTP request
        // to the provider's /v1/models endpoint using the configured API key.
        Toast.makeText(this, R.string.ia_fetch_models_failed, Toast.LENGTH_SHORT).show();
    }

    // ────────────────────────────────────────
    // Test / Share / Delete
    // ────────────────────────────────────────

    private void testProvider() {
        Toast.makeText(this, R.string.ia_testing_provider, Toast.LENGTH_SHORT).show();
        // Placeholder: In a full implementation, this would send a test request.
        Toast.makeText(this, R.string.ia_provider_test_ok, Toast.LENGTH_SHORT).show();
    }

    private void shareProvider() {
        try {
            JSONObject config = new JSONObject();
            config.put("id", providerId);
            config.put("name", etProviderName != null && etProviderName.getText() != null
                    ? etProviderName.getText().toString() : providerName);
            if (etApiKey != null && etApiKey.getText() != null) {
                String key = etApiKey.getText().toString().trim();
                if (!key.isEmpty()) {
                    config.put("apiKey", key);
                }
            }
            if (etBaseUrl != null && etBaseUrl.getText() != null) {
                config.put("baseUrl", etBaseUrl.getText().toString().trim());
            }
            if (etApiPath != null && etApiPath.getText() != null) {
                config.put("chatPath", etApiPath.getText().toString().trim());
            }

            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, config.toString(2));
            startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.ia_share_provider)));
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDeleteProvider() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ia_delete_provider)
                .setMessage(getString(R.string.ia_delete_provider_message, providerName))
                .setNegativeButton(R.string.common_word_cancel, null)
                .setPositiveButton(R.string.ia_delete_provider, (dialog, which) -> {
                    VoidPortSettings.removeProviderConfig(prefs, providerId);
                    Toast.makeText(this, providerName + " excluído", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .show();
    }

    // ────────────────────────────────────────
    // Model adapter
    // ────────────────────────────────────────

    private final class ModelAdapter extends RecyclerView.Adapter<ModelAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_provider_model_row, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String modelId = modelList.get(position);
            holder.modelName.setText(modelId);
            holder.btnDelete.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(ProviderDetailActivity.this)
                        .setTitle(R.string.ia_delete_model)
                        .setNegativeButton(R.string.common_word_cancel, null)
                        .setPositiveButton(R.string.ia_delete_model, (d, w) -> removeModel(modelId))
                        .show();
            });
        }

        @Override
        public int getItemCount() {
            return modelList.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView modelName;
            final ImageButton btnDelete;

            VH(@NonNull View itemView) {
                super(itemView);
                modelName = itemView.findViewById(R.id.model_name);
                btnDelete = itemView.findViewById(R.id.btn_delete_model);
            }
        }
    }
}
