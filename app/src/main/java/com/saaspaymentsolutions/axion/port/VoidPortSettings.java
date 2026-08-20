package com.saaspaymentsolutions.axion.port;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.saaspaymentsolutions.axion.Tool;
import com.saaspaymentsolutions.axion.agent.MultiAgentPolicy;

public final class VoidPortSettings {
    public static final String PREF_GITHUB_TOKEN = "github_token";

    public static final String PREFS_NAME = "ia_settings";
    public static final String PREF_CURRENT_PROVIDER = "current_ai_provider";
    public static final String PREF_CURRENT_MODEL = "current_ai_model";
    public static final String PREF_CUSTOM_MODELS = "custom_models_json";
    public static final String PREF_PROVIDER_CONFIGS = "provider_configs_v1";
    public static final String PREF_CHAT_MODE = "chat_mode";
    public static final String PREF_MULTI_AGENT_MODE = "multi_agent_mode";
    public static final String PREF_MCP_CONFIG = "mcp_config_json";

    public static final String PREF_AUTO_REFRESH_MODELS = "local_auto_detect_models";
    public static final String PREF_AI_INSTRUCTIONS = "void_ai_instructions";
    public static final String PREF_ENABLE_AUTOCOMPLETE = "feature_autocomplete_enabled";
    public static final String PREF_SYNC_APPLY_TO_CHAT = "feature_apply_same_as_chat_model";
    public static final String PREF_APPLY_MODE = "feature_apply_mode";
    public static final String PREF_SYNC_SCM_TO_CHAT = "feature_commit_same_as_chat_model";
    public static final String PREF_TOOLS_AUTO_APPROVE_EDITS = "feature_tools_auto_approve_edits";
    public static final String PREF_TOOLS_AUTO_APPROVE_TERMINAL = "feature_tools_auto_approve_terminal";
    public static final String PREF_TOOLS_AUTO_APPROVE_MCP = "feature_tools_auto_approve_mcp";
    public static final String PREF_INCLUDE_TOOL_LINT_ERRORS = "feature_tools_fix_lint";
    public static final String PREF_AUTO_ACCEPT_LLM_CHANGES = "feature_tools_auto_accept_changes";
    public static final String PREF_SHOW_INLINE_SUGGESTIONS = "feature_editor_show_suggestions_on_select";
    public static final String PREF_PORT_PROMPTS_ENABLED = "feature_void_port_prompts_enabled";
    public static final String PREF_PORT_TOOL_POLICY_ENABLED = "feature_void_port_tool_policy_enabled";

    public static final String DEFAULT_MCP_CONFIG = "{\n  \"mcpServers\": {}\n}";
    public static final String MCP_CONFIG_ONLY_NOTICE = "Android can call URL-based MCP servers through JSON-RPC HTTP; command/stdio servers need an Android-accessible URL endpoint.";
    public static final String APPLY_MODE_FAST = "Fast Apply";
    public static final String APPLY_MODE_BALANCED = "Balanced";
    public static final String APPLY_MODE_CAREFUL = "Careful";

    public static final String APPROVAL_EDITS = "edits";
    public static final String APPROVAL_TERMINAL = "terminal";
    public static final String APPROVAL_MCP_TOOLS = "MCP tools";

    private VoidPortSettings() {
    }

    public static final class ModelOption {
        public final String providerId;
        public final String providerLabel;
        public final String model;

        public ModelOption(String providerId, String providerLabel, String model) {
            this.providerId = providerId;
            this.providerLabel = providerLabel;
            this.model = model;
        }

        public String getDisplayLabel() {
            return providerLabel + " - " + model;
        }
    }

    public static final class ProviderGroup {
        public final String providerId;
        public final String voidProviderName;
        public final String label;
        public final boolean localProvider;
        public final List<String> models;

        public ProviderGroup(String providerId, String voidProviderName, String label, boolean localProvider, List<String> models) {
            this.providerId = providerId;
            this.voidProviderName = voidProviderName;
            this.label = label;
            this.localProvider = localProvider;
            this.models = models == null ? new ArrayList<>() : models;
        }
    }

    public static final class ProviderCardSpec {
        public final String providerId;
        public final String title;
        public final String description;
        public final String helpUrl;
        public final boolean custom;
        public final List<FieldSpec> fields = new ArrayList<>();

        public ProviderCardSpec(String title, String description, String helpUrl) {
            this(slugify(title), title, description, helpUrl, false);
        }

        public ProviderCardSpec(String providerId, String title, String description, String helpUrl, boolean custom) {
            this.providerId = providerId == null ? slugify(title) : providerId;
            this.title = title;
            this.description = description;
            this.helpUrl = helpUrl;
            this.custom = custom;
        }

        public ProviderCardSpec addField(String label, String prefKey, String defaultValue, boolean password, String enabledKey) {
            fields.add(new FieldSpec(label, prefKey, defaultValue, password, enabledKey));
            return this;
        }
    }

    public static final class FieldSpec {
        public final String label;
        public final String prefKey;
        public final String defaultValue;
        public final boolean password;
        public final String enabledKey;

        public FieldSpec(String label, String prefKey, String defaultValue, boolean password, String enabledKey) {
            this.label = label;
            this.prefKey = prefKey;
            this.defaultValue = defaultValue;
            this.password = password;
            this.enabledKey = enabledKey;
        }
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getChatMode(SharedPreferences prefs) {
        String mode = prefs.getString(PREF_CHAT_MODE, "agent");
        if ("normal".equals(mode) || "gather".equals(mode) || "agent".equals(mode)) {
            return mode;
        }
        return "agent";
    }

    public static void setChatMode(SharedPreferences prefs, String mode) {
        prefs.edit().putString(PREF_CHAT_MODE, normalizeChatMode(mode)).apply();
    }

    public static String getMultiAgentMode(SharedPreferences prefs) {
        return MultiAgentPolicy.normalizeMode(
                prefs.getString(PREF_MULTI_AGENT_MODE, MultiAgentPolicy.MODE_AUTO));
    }

    public static void setMultiAgentMode(SharedPreferences prefs, String mode) {
        prefs.edit()
                .putString(PREF_MULTI_AGENT_MODE, MultiAgentPolicy.normalizeMode(mode))
                .apply();
    }

    public static List<ModelOption> getVisibleModelOptions(SharedPreferences prefs) {
        List<ModelOption> options = new ArrayList<>();
        // Collect models from all locally configured providers
        List<ProviderCardSpec> cards = getProviderCards(prefs);
        for (ProviderCardSpec card : cards) {
            boolean enabled = isProviderConfigured(prefs, card.providerId);
            if (!enabled) continue;
            // For built-in providers with no custom models, add a placeholder
            if (!card.custom && card.fields.isEmpty()) {
                options.add(new ModelOption(card.providerId, card.title, card.title));
            }
        }
        // Add models from custom provider configs
        JSONArray configs = readProviderConfigs(prefs);
        for (int i = 0; i < configs.length(); i++) {
            JSONObject config = configs.optJSONObject(i);
            if (config == null) continue;
            String pid = config.optString("id", "");
            String name = config.optString("name", pid);
            boolean enabled = config.optBoolean("enabled", true);
            if (!enabled || pid.isEmpty()) continue;
            JSONArray models = config.optJSONArray("models");
            if (models != null) {
                for (int j = 0; j < models.length(); j++) {
                    String model = models.optString(j, "").trim();
                    if (!model.isEmpty()) {
                        options.add(new ModelOption(pid, name, model));
                    }
                }
            }
        }
        return options;
    }

    public static void ensureValidCurrentSelection(SharedPreferences prefs) {
        String provider = prefs.getString(PREF_CURRENT_PROVIDER, "");
        String model = prefs.getString(PREF_CURRENT_MODEL, "");
        if (isCurrentSelectionValid(prefs, provider, model)) {
            return;
        }

        List<ModelOption> options = getVisibleModelOptions(prefs);
        if (options.isEmpty()) {
            if (provider != null && !provider.trim().isEmpty() && model != null && !model.trim().isEmpty()) {
                return;
            }
            return;
        }

        ModelOption first = options.get(0);
        prefs.edit()
                .putString(PREF_CURRENT_PROVIDER, first.providerId)
                .putString(PREF_CURRENT_MODEL, first.model)
                .apply();
    }

    public static boolean isCurrentSelectionValid(SharedPreferences prefs, String providerId, String model) {
        if (providerId == null || model == null || providerId.trim().isEmpty() || model.trim().isEmpty()) {
            return false;
        }
        for (ModelOption option : getVisibleModelOptions(prefs)) {
            if (providerId.equals(option.providerId) && model.equals(option.model)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isProviderSupportedInChat(String providerId) {
        return "anthropic".equals(providerId)
                || "openai".equals(providerId)
                || "gemini".equals(providerId)
                || "groq".equals(providerId)
                || "deepseek".equals(providerId)
                || "openrouter".equals(providerId)
                || "grok_xai".equals(providerId)
                || "mistral".equals(providerId)
                || "minimax".equals(providerId)
                || "huggingface".equals(providerId)
                || "openai_compatible".equals(providerId)
                || "litellm".equals(providerId)
                || "ollama".equals(providerId)
                || "vllm".equals(providerId)
                || "lm_studio".equals(providerId)
                || (providerId != null && providerId.startsWith("custom_"));
    }

    public static boolean isProviderConfigured(SharedPreferences prefs, String providerId) {
        if (!isProviderAllowedByPlan(prefs, providerId)) {
            return false;
        }
        JSONObject custom = getProviderConfigObject(prefs, providerId);
        if (custom != null) {
            if (!custom.optBoolean("enabled", true)) {
                return false;
            }
            String type = providerType(custom);
            String baseUrl = custom.optString("baseUrl", "").trim();
            String apiKey = custom.optString("apiKey", "").trim();
            if ("openai".equals(type) || "anthropic".equals(type) || "gemini".equals(type)) {
                return !apiKey.isEmpty() && !baseUrl.isEmpty();
            }
            return !baseUrl.isEmpty();
        }
        return switch (providerId) {
            case "ollama" -> !getPreferenceValue(prefs, "local_provider_ollama_url", "").isEmpty()
                    && !getPreferenceValue(prefs, "ollama_api_key", "").isEmpty();
            case "huggingface" -> !getPreferenceValue(prefs, "huggingface_api_key", "").isEmpty();
            case "vllm" -> !getPreferenceValue(prefs, "local_provider_vllm_url", "").isEmpty();
            case "lm_studio" -> !getPreferenceValue(prefs, "local_provider_lm_studio_url", "").isEmpty();
            case "anthropic" -> !getPreferenceValue(prefs, "anthropic_api_key", "").isEmpty();
            case "openai" -> !getPreferenceValue(prefs, "openai_api_key", "").isEmpty();
            case "deepseek" -> !getPreferenceValue(prefs, "deepseek_api_key", "").isEmpty();
            case "openrouter" -> !getPreferenceValue(prefs, "openrouter_api_key", "").isEmpty();
            case "openai_compatible" -> !getPreferenceValue(prefs, "openai_compatible_base_url", "").isEmpty();
            case "gemini" -> !getPreferenceValue(prefs, "gemini_api_key", "").isEmpty();
            case "groq" -> !getPreferenceValue(prefs, "groq_api_key", "").isEmpty();
            case "grok_xai" -> !getPreferenceValue(prefs, "grok_xai_api_key", "").isEmpty();
            case "mistral" -> !getPreferenceValue(prefs, "mistral_api_key", "").isEmpty();
            case "litellm" -> !getPreferenceValue(prefs, "litellm_base_url", "").isEmpty();
            default -> false;
        };
    }

    public static boolean isProviderAllowedByPlan(SharedPreferences prefs, String providerId) {
        return true;
    }

    public static boolean isLocalProvider(String providerId) {
        return "vllm".equals(providerId) || "lm_studio".equals(providerId);
    }

    public static List<ProviderGroup> getAllProviderGroups(SharedPreferences prefs) {
        List<ProviderGroup> groups = getCatalogProviderGroups();
        for (ProviderGroup providerConfigGroup : getProviderConfigGroups(prefs)) {
            groups.add(providerConfigGroup);
        }
        for (ProviderGroup customGroup : getCustomProviderGroups(prefs)) {
            boolean merged = false;
            for (ProviderGroup group : groups) {
                if (!group.providerId.equals(customGroup.providerId)) {
                    continue;
                }
                for (String model : customGroup.models) {
                    if (!group.models.contains(model)) {
                        group.models.add(model);
                    }
                }
                merged = true;
                break;
            }
            if (!merged) {
                groups.add(customGroup);
            }
        }
        List<ProviderGroup> allowed = new ArrayList<>();
        for (ProviderGroup group : groups) {
            if (isProviderAllowedByPlan(prefs, group.providerId)) {
                allowed.add(group);
            }
        }
        return allowed;
    }

    public static List<ProviderGroup> getCatalogProviderGroups() {
        List<ProviderGroup> groups = new ArrayList<>();
        groups.add(new ProviderGroup("ollama", "ollama", "Ollama Cloud", false, new ArrayList<>()));
        groups.add(new ProviderGroup("vllm", "vLLM", "vLLM", true, new ArrayList<>()));
        groups.add(new ProviderGroup("lm_studio", "lmStudio", "LM Studio", true, new ArrayList<>()));
        groups.add(new ProviderGroup("anthropic", "anthropic", "Anthropic", false, new ArrayList<>()));
        groups.add(new ProviderGroup("openai", "openAI", "OpenAI", false, new ArrayList<>()));
        groups.add(new ProviderGroup("deepseek", "deepseek", "DeepSeek", false, new ArrayList<>()));
        groups.add(new ProviderGroup("openrouter", "openRouter", "OpenRouter", false, new ArrayList<>()));
        groups.add(new ProviderGroup("openai_compatible", "openAICompatible", "OpenAI-Compatible", false, new ArrayList<>()));
        groups.add(new ProviderGroup("gemini", "gemini", "Gemini", false, new ArrayList<>()));
        groups.add(new ProviderGroup("groq", "groq", "Groq", false, new ArrayList<>()));
        groups.add(new ProviderGroup("grok_xai", "xAI", "Grok (xAI)", false, new ArrayList<>()));
        groups.add(new ProviderGroup("mistral", "mistral", "Mistral", false, new ArrayList<>()));
        groups.add(new ProviderGroup("minimax", "minimax", "MiniMax", false, new ArrayList<>()));
        groups.add(new ProviderGroup("litellm", "liteLLM", "LiteLLM", false, new ArrayList<>()));
        groups.add(new ProviderGroup("huggingface", "huggingFace", "Hugging Face", false, new ArrayList<>()));
        return groups;
    }

    public static List<ProviderCardSpec> getProviderCards() {
        List<ProviderCardSpec> providers = new ArrayList<>();
        providers.add(new ProviderCardSpec("openai", "OpenAI", "Get your API key here.", "https://platform.openai.com/api-keys", false)
                .addField("API Key", "openai_api_key", "", true, "openai_enabled"));
        providers.add(new ProviderCardSpec("anthropic", "Anthropic", "Get your API key here.", "https://console.anthropic.com/settings/keys", false)
                .addField("API Key", "anthropic_api_key", "", true, null));
        providers.add(new ProviderCardSpec("gemini", "Gemini", "Google AI Studio OpenAI-compatible endpoint.", "https://aistudio.google.com/apikey", false)
                .addField("API Key", "gemini_api_key", "", true, "gemini_enabled"));
        providers.add(new ProviderCardSpec("ollama", "Ollama Cloud", "Remote Ollama API. Requires an Ollama API key.", "https://ollama.com/settings/keys", false)
                .addField("API Key", "ollama_api_key", "", true, null)
                .addField("Base URL", "local_provider_ollama_url", "https://ollama.com/api", false, null));
        providers.add(new ProviderCardSpec("vllm", "vLLM", "OpenAI-compatible server. Use its /v1 base URL.", "https://docs.vllm.ai/en/latest/serving/openai_compatible_server.html", false)
                .addField("Base URL", "local_provider_vllm_url", "http://127.0.0.1:8000/v1", false, null));
        providers.add(new ProviderCardSpec("lm_studio", "LM Studio", "OpenAI-compatible local server. Use its /v1 base URL.", "https://lmstudio.ai/docs/developer/openai-compat", false)
                .addField("Base URL", "local_provider_lm_studio_url", "http://127.0.0.1:1234/v1", false, null));
        providers.add(new ProviderCardSpec("openrouter", "OpenRouter", "Get your API key here. Rate limits depend on the selected model.", "https://openrouter.ai/keys", false)
                .addField("API Key", "openrouter_api_key", "", true, null));
        providers.add(new ProviderCardSpec("deepseek", "DeepSeek", "Get your API key here.", "https://platform.deepseek.com/api_keys", false)
                .addField("API Key", "deepseek_api_key", "", true, null));
        providers.add(new ProviderCardSpec("groq", "Groq", "Use Groq-hosted OpenAI-compatible models.", "https://console.groq.com/keys", false)
                .addField("API Key", "groq_api_key", "", true, "groq_enabled"));
        providers.add(new ProviderCardSpec("mistral", "Mistral", "Mistral API access.", "https://console.mistral.ai/api-keys/", false)
                .addField("API Key", "mistral_api_key", "", true, null));
        providers.add(new ProviderCardSpec("grok_xai", "Grok (xAI)", "xAI's OpenAI-compatible API.", "https://console.x.ai/", false)
                .addField("API Key", "grok_xai_api_key", "", true, null));
        providers.add(new ProviderCardSpec("minimax", "MiniMax", "MiniMax text models through its OpenAI-compatible API.", "https://platform.minimax.io/user-center/basic-information/interface-key", false)
                .addField("API Key", "minimax_api_key", "", true, "minimax_enabled"));
        providers.add(new ProviderCardSpec("openai_compatible", "OpenAI-Compatible", "Use any provider that exposes an OpenAI-compatible endpoint.", null, false)
                .addField("Base URL", "openai_compatible_base_url", "https://my-endpoint.example/v1", false, null)
                .addField("API Key", "openai_compatible_api_key", "", true, null)
                .addField("Headers JSON", "openai_compatible_headers", "{}", false, null));
        providers.add(new ProviderCardSpec("litellm", "LiteLLM", "Point this to a LiteLLM proxy if you use one.", null, false)
                .addField("Base URL", "litellm_base_url", "http://localhost:4000", false, null));
        providers.add(new ProviderCardSpec("huggingface", "Hugging Face", "Inference Providers API (OpenAI-compatible).", "https://huggingface.co/settings/tokens", false)
                .addField("API Key", "huggingface_api_key", "", true, null));
        return providers;
    }

    public static List<ProviderCardSpec> getProviderCards(SharedPreferences prefs) {
        List<ProviderCardSpec> providers = getProviderCards();
        JSONArray configs = readProviderConfigs(prefs);
        for (int i = 0; i < configs.length(); i++) {
            JSONObject config = configs.optJSONObject(i);
            if (config == null) {
                continue;
            }
            String providerId = config.optString("id", "").trim();
            String name = config.optString("name", providerId).trim();
            if (providerId.isEmpty() || name.isEmpty()) {
                continue;
            }
            ProviderCardSpec spec = new ProviderCardSpec(providerId, name, "Custom provider", null, true)
                    .addField("API Key", providerPrefKey(providerId, "api_key"), "", true, providerPrefKey(providerId, "enabled"))
                    .addField("Base URL", providerPrefKey(providerId, "base_url"), defaultBaseForProviderType(providerType(config)), false, null)
                    .addField("Headers JSON", providerPrefKey(providerId, "headers"), "{}", false, null);
            providers.add(spec);
        }
        List<ProviderCardSpec> allowed = new ArrayList<>();
        for (ProviderCardSpec provider : providers) {
            if (isProviderAllowedByPlan(prefs, provider.providerId)) {
                allowed.add(provider);
            }
        }
        return allowed;
    }

    public static JSONArray readProviderConfigs(SharedPreferences prefs) {
        return readJsonArrayPreference(prefs, PREF_PROVIDER_CONFIGS);
    }

    public static JSONObject getProviderConfigObject(SharedPreferences prefs, String providerId) {
        if (prefs == null || providerId == null || providerId.trim().isEmpty()) {
            return null;
        }
        JSONArray configs = readProviderConfigs(prefs);
        for (int i = 0; i < configs.length(); i++) {
            JSONObject config = configs.optJSONObject(i);
            if (config != null && providerId.equals(config.optString("id", ""))) {
                return config;
            }
        }
        return null;
    }

    /**
     * Returns a user-declared image-input capability for a custom/discovered
     * model, or {@code null} when the provider has not supplied that metadata.
     */
    public static Boolean getCustomModelImageInputOverride(SharedPreferences prefs,
                                                            String providerId,
                                                            String modelId) {
        if (prefs == null || providerId == null || modelId == null) {
            return null;
        }
        JSONArray models = readJsonArrayPreference(prefs, PREF_CUSTOM_MODELS);
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model == null
                    || !providerId.equals(model.optString("providerId", ""))
                    || !modelId.equals(model.optString("model", ""))
                    || !model.has("supportsImageInput")) {
                continue;
            }
            return model.optBoolean("supportsImageInput");
        }
        return null;
    }

    public static boolean supportsImageInput(SharedPreferences prefs, String providerId, String modelId) {
        Boolean override = getCustomModelImageInputOverride(prefs, providerId, modelId);
        return override != null
                ? override
                : ModelImageCapabilities.supportsImageInput(providerId, modelId);
    }

    public static JSONObject getProviderConfigForTitle(SharedPreferences prefs, String title) {
        if (title == null) {
            return null;
        }
        JSONArray configs = readProviderConfigs(prefs);
        for (int i = 0; i < configs.length(); i++) {
            JSONObject config = configs.optJSONObject(i);
            if (config != null && title.equals(config.optString("name", ""))) {
                return config;
            }
        }
        return null;
    }

    public static void saveProviderConfig(SharedPreferences prefs, JSONObject config) {
        if (prefs == null || config == null) {
            return;
        }
        String providerId = config.optString("id", "").trim();
        if (providerId.isEmpty()) {
            return;
        }
        JSONArray existing = readProviderConfigs(prefs);
        JSONArray next = new JSONArray();
        boolean updated = false;
        for (int i = 0; i < existing.length(); i++) {
            JSONObject item = existing.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (providerId.equals(item.optString("id", ""))) {
                next.put(config);
                updated = true;
            } else {
                next.put(item);
            }
        }
        if (!updated) {
            next.put(config);
        }
        writeProviderConfigPrefs(prefs, config);
        prefs.edit().putString(PREF_PROVIDER_CONFIGS, next.toString()).apply();
    }

    public static void updateProviderConfigValue(SharedPreferences prefs, String providerId, String key, Object value) {
        JSONObject config = getProviderConfigObject(prefs, providerId);
        if (config == null || key == null || key.trim().isEmpty()) {
            return;
        }
        try {
            config.put(key, value);
            saveProviderConfig(prefs, config);
        } catch (Exception ignored) {
        }
    }

    public static void removeProviderConfig(SharedPreferences prefs, String providerId) {
        if (prefs == null || providerId == null || providerId.trim().isEmpty()) {
            return;
        }
        JSONArray existing = readProviderConfigs(prefs);
        JSONArray next = new JSONArray();
        for (int i = 0; i < existing.length(); i++) {
            JSONObject item = existing.optJSONObject(i);
            if (item != null && !providerId.equals(item.optString("id", ""))) {
                next.put(item);
            }
        }
        prefs.edit()
                .putString(PREF_PROVIDER_CONFIGS, next.toString())
                .remove(providerPrefKey(providerId, "enabled"))
                .remove(providerPrefKey(providerId, "api_key"))
                .remove(providerPrefKey(providerId, "base_url"))
                .remove(providerPrefKey(providerId, "headers"))
                .remove(providerPrefKey(providerId, "api_path"))
                .apply();
    }

    public static String uniqueProviderId(SharedPreferences prefs, String seed) {
        String base = "custom_" + slugify(seed == null || seed.trim().isEmpty() ? "provider" : seed);
        if ("custom_".equals(base)) {
            base = "custom_provider";
        }
        String candidate = base;
        int suffix = 2;
        while (getProviderConfigObject(prefs, candidate) != null || isBuiltInProviderId(candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    public static String providerPrefKey(String providerId, String key) {
        return "provider_config_" + slugify(providerId) + "_" + key;
    }

    public static String providerType(JSONObject config) {
        String raw = config == null ? "" : config.optString("providerType", config.optString("type", ""));
        String normalized = raw.trim().toLowerCase(Locale.US);
        if ("google".equals(normalized) || "gemini".equals(normalized)) {
            return "gemini";
        }
        if ("claude".equals(normalized) || "anthropic".equals(normalized)) {
            return "anthropic";
        }
        if ("openai-compatible".equals(normalized) || "openai_compatible".equals(normalized)) {
            return "openai_compatible";
        }
        if ("ollama".equals(normalized) || "vllm".equals(normalized)
                || "lm_studio".equals(normalized) || "litellm".equals(normalized)) {
            return normalized;
        }
        return "openai";
    }

    public static String defaultBaseForProviderType(String type) {
        String normalized = type == null ? "openai" : type;
        return switch (normalized) {
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta";
            case "anthropic" -> "https://api.anthropic.com/v1";
            case "minimax" -> "https://api.minimax.io/v1";
            case "ollama" -> "https://ollama.com/api";
            case "vllm" -> "http://127.0.0.1:8000/v1";
            case "lm_studio" -> "http://127.0.0.1:1234/v1";
            case "litellm" -> "http://127.0.0.1:4000/v1";
            case "openai_compatible" -> "";
            default -> "https://api.openai.com/v1";
        };
    }

    public static String defaultChatPathForProviderType(String type) {
        String normalized = type == null ? "openai" : type;
        return switch (normalized) {
            case "gemini", "anthropic" -> "";
            default -> "/chat/completions";
        };
    }

    private static List<ProviderGroup> getProviderConfigGroups(SharedPreferences prefs) {
        List<ProviderGroup> groups = new ArrayList<>();
        JSONArray configs = readProviderConfigs(prefs);
        for (int i = 0; i < configs.length(); i++) {
            JSONObject config = configs.optJSONObject(i);
            if (config == null) {
                continue;
            }
            String providerId = config.optString("id", "").trim();
            String label = config.optString("name", providerId).trim();
            if (providerId.isEmpty() || label.isEmpty()) {
                continue;
            }
            List<String> models = new ArrayList<>();
            JSONArray configModels = config.optJSONArray("models");
            for (int j = 0; configModels != null && j < configModels.length(); j++) {
                String model = configModels.optString(j, "").trim();
                if (!model.isEmpty() && !models.contains(model)) {
                    models.add(model);
                }
            }
            if (!models.isEmpty()) {
                groups.add(new ProviderGroup(providerId, toVoidProviderName(providerId), label, false, models));
            }
        }
        return groups;
    }

    private static void writeProviderConfigPrefs(SharedPreferences prefs, JSONObject config) {
        String providerId = config.optString("id", "").trim();
        if (providerId.isEmpty()) {
            return;
        }
        prefs.edit()
                .putBoolean(providerPrefKey(providerId, "enabled"), config.optBoolean("enabled", true))
                .putString(providerPrefKey(providerId, "api_key"), config.optString("apiKey", ""))
                .putString(providerPrefKey(providerId, "base_url"), config.optString("baseUrl", defaultBaseForProviderType(providerType(config))))
                .putString(providerPrefKey(providerId, "headers"), config.optString("headers", "{}"))
                .putString(providerPrefKey(providerId, "api_path"), config.optString("chatPath", defaultChatPathForProviderType(providerType(config))))
                .apply();
    }

    private static boolean isBuiltInProviderId(String providerId) {
        if (providerId == null) {
            return false;
        }
        for (ProviderCardSpec spec : getProviderCards()) {
            if (providerId.equals(spec.providerId)) {
                return true;
            }
        }
        return false;
    }

    public static List<ProviderGroup> getCustomProviderGroups(SharedPreferences prefs) {
        JSONArray array = readJsonArrayPreference(prefs, PREF_CUSTOM_MODELS);
        Map<String, ProviderGroup> groups = new LinkedHashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String providerId = item.optString("providerId", "custom");
            String providerLabel = item.optString("providerLabel", "Custom");
            String model = item.optString("model", "");
            if (model.isEmpty()) {
                continue;
            }
            ProviderGroup group = groups.get(providerId);
            if (group == null) {
                group = new ProviderGroup(providerId, toVoidProviderName(providerId), providerLabel, isLocalProvider(providerId), new ArrayList<>());
                groups.put(providerId, group);
            }
            if (!group.models.contains(model)) {
                group.models.add(model);
            }
        }
        return new ArrayList<>(groups.values());
    }

    public static String modelHiddenKey(String providerId, String model) {
        return "model_hidden_" + slugify(providerId) + "_" + slugify(model);
    }

    public static boolean isModelHidden(SharedPreferences prefs, String providerId, String model) {
        return prefs.getBoolean(modelHiddenKey(providerId, model), false);
    }

    public static void setModelHidden(SharedPreferences prefs, String providerId, String model, boolean hidden) {
        prefs.edit().putBoolean(modelHiddenKey(providerId, model), hidden).apply();
    }

    public static String getAiInstructions(SharedPreferences prefs) {
        return prefs.getString(PREF_AI_INSTRUCTIONS, "").trim();
    }

    public static boolean isPortedPromptsEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_PORT_PROMPTS_ENABLED, true);
    }

    public static boolean isPortedToolPolicyEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_PORT_TOOL_POLICY_ENABLED, true);
    }

    public static boolean shouldIncludeToolLintErrors(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_INCLUDE_TOOL_LINT_ERRORS, true);
    }

    public static boolean shouldAutoAcceptLlmChanges(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_AUTO_ACCEPT_LLM_CHANGES, false);
    }

    public static boolean requiresApproval(Context context, Tool tool) {
        if (tool == null || !tool.requiresApproval()) {
            return false;
        }
        SharedPreferences prefs = prefs(context);
        if (!isPortedToolPolicyEnabled(prefs)) {
            return true;
        }
        String approvalType = approvalTypeOfTool(tool.getName());
        if (approvalType == null) {
            return true;
        }
        return !isAutoApprovalEnabled(prefs, approvalType);
    }

    public static String approvalTypeOfTool(String toolName) {
        if (toolName == null) {
            return null;
        }
        return switch (toolName) {
            case "edit_project_file", "rewrite_project_file",
                    "rewrite_file", "edit_file", "create_file_or_folder", "delete_file_or_folder" -> APPROVAL_EDITS;
            case "run_shell_command", "run_command", "open_persistent_terminal",
                    "run_persistent_command", "kill_persistent_terminal" -> APPROVAL_TERMINAL;
            default -> toolName.startsWith("mcp_") ? APPROVAL_MCP_TOOLS : null;
        };
    }

    public static boolean isAutoApprovalEnabled(SharedPreferences prefs, String approvalType) {
        if (APPROVAL_EDITS.equals(approvalType)) {
            return prefs.getBoolean(PREF_TOOLS_AUTO_APPROVE_EDITS, true);
        }
        if (APPROVAL_TERMINAL.equals(approvalType)) {
            return prefs.getBoolean(PREF_TOOLS_AUTO_APPROVE_TERMINAL, true);
        }
        if (APPROVAL_MCP_TOOLS.equals(approvalType)) {
            return prefs.getBoolean(PREF_TOOLS_AUTO_APPROVE_MCP, false);
        }
        return false;
    }

    public static JSONObject readMcpConfigObject(SharedPreferences prefs) {
        String raw = prefs.getString(PREF_MCP_CONFIG, DEFAULT_MCP_CONFIG);
        try {
            JSONObject config = new JSONObject(raw);
            if (config.optJSONObject("mcpServers") == null) {
                config.put("mcpServers", new JSONObject());
            }
            return config;
        } catch (Exception ignored) {
            try {
                return new JSONObject(DEFAULT_MCP_CONFIG);
            } catch (Exception impossible) {
                return new JSONObject();
            }
        }
    }

    public static int countMcpServers(SharedPreferences prefs) {
        JSONObject servers = readMcpServersObject(prefs);
        return servers.length();
    }

    public static int countEnabledMcpServers(SharedPreferences prefs) {
        JSONObject servers = readMcpServersObject(prefs);
        JSONArray names = servers.names();
        int count = 0;
        for (int i = 0; names != null && i < names.length(); i++) {
            JSONObject server = servers.optJSONObject(names.optString(i, ""));
            if (server != null && server.optBoolean("enabled", true)) {
                count++;
            }
        }
        return count;
    }

    private static JSONObject readMcpServersObject(SharedPreferences prefs) {
        JSONObject config = readMcpConfigObject(prefs);
        JSONObject servers = config.optJSONObject("mcpServers");
        return servers == null ? new JSONObject() : servers;
    }

    private static JSONArray readJsonArrayPreference(SharedPreferences prefs, String key) {
        String raw = prefs.getString(key, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static String getPreferenceValue(SharedPreferences prefs, String key, String defaultValue) {
        return prefs.getString(key, defaultValue).trim();
    }

    private static String slugify(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_");
    }

    private static String normalizeChatMode(String chatMode) {
        if (chatMode == null) {
            return "agent";
        }
        String mode = chatMode.trim().toLowerCase(Locale.US);
        if ("normal".equals(mode) || "chat".equals(mode)) {
            return "normal";
        }
        if ("gather".equals(mode)) {
            return "gather";
        }
        return "agent";
    }

    private static String toVoidProviderName(String providerId) {
        return switch (providerId) {
            case "openai" -> "openAI";
            case "openrouter" -> "openRouter";
            case "openai_compatible" -> "openAICompatible";
            case "grok_xai" -> "xAI";
            case "lm_studio" -> "lmStudio";
            case "litellm" -> "liteLLM";
            case "vllm" -> "vLLM";
            default -> providerId;
        };
    }
}
