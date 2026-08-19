package com.saaspaymentsolutions.axion.port;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import com.saaspaymentsolutions.axion.SslUtils;

/**
 * Android port of common/refreshModelService.ts for local model discovery.
 */
public final class VoidPortRefreshModelService {
    private static final long REFRESH_INTERVAL_MS = 5_000L;
    private static final OkHttpClient CLIENT = SslUtils.relaxedClientBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean autoRefreshStarted = false;

    private VoidPortRefreshModelService() {
    }

    public enum RefreshState {
        INIT,
        REFRESHING,
        FINISHED,
        ERROR
    }

    public static final class RefreshResult {
        public final String providerId;
        public final RefreshState state;
        public final List<String> models;
        public final String error;

        RefreshResult(String providerId, RefreshState state, List<String> models, String error) {
            this.providerId = providerId == null ? "" : providerId;
            this.state = state == null ? RefreshState.INIT : state;
            this.models = models == null ? new ArrayList<>() : models;
            this.error = error == null ? "" : error;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("providerId", providerId);
                obj.put("state", state.name().toLowerCase(java.util.Locale.US));
                obj.put("models", new JSONArray(models));
                obj.put("error", error);
            } catch (Exception ignored) {
            }
            return obj;
        }
    }

    public interface Listener {
        void onResult(RefreshResult result);
    }

    public static void startAutoRefresh(Context context) {
        if (context == null || autoRefreshStarted) {
            return;
        }
        autoRefreshStarted = true;
        Context appContext = context.getApplicationContext();
        MAIN.post(() -> scheduleAutoRefresh(appContext));
    }

    public static void refreshProviderAsync(Context context, String providerId, boolean enableProviderOnSuccess,
                                            Listener listener) {
        Context appContext = context == null ? null : context.getApplicationContext();
        new Thread(() -> {
            RefreshResult result = refreshProvider(appContext, providerId, enableProviderOnSuccess);
            if (listener != null) {
                MAIN.post(() -> listener.onResult(result));
            }
        }, "void-refresh-models-" + providerId).start();
    }

    public static RefreshResult refreshProvider(Context context, String providerId, boolean enableProviderOnSuccess) {
        if (context == null) {
            return new RefreshResult(providerId, RefreshState.ERROR, null, "Context unavailable.");
        }
        String normalizedProvider = normalizeProvider(providerId);
        if (normalizedProvider.isEmpty()) {
            return new RefreshResult(providerId, RefreshState.ERROR, null, "Provider not supported.");
        }
        try {
            SharedPreferences prefs = VoidPortSettings.prefs(context);
            List<String> models = switch (normalizedProvider) {
                case "ollama" -> fetchOllamaModels(endpoint(prefs, "local_provider_ollama_url", "http://127.0.0.1:11434"));
                case "vllm" -> fetchOpenAiCompatibleModels(endpoint(prefs, "local_provider_vllm_url", "http://localhost:8000"));
                case "lm_studio" -> fetchOpenAiCompatibleModels(endpoint(prefs, "local_provider_lm_studio_url", "http://localhost:1234"));
                default -> new ArrayList<>();
            };
            saveAutodetectedModels(prefs, normalizedProvider, providerLabel(normalizedProvider), models);
            if (enableProviderOnSuccess && !models.isEmpty()) {
                prefs.edit().putString(VoidPortSettings.PREF_CURRENT_PROVIDER, normalizedProvider).apply();
            }
            return new RefreshResult(normalizedProvider, RefreshState.FINISHED, models, "");
        } catch (Exception e) {
            return new RefreshResult(normalizedProvider, RefreshState.ERROR, null, e.getMessage());
        }
    }

    public static JSONArray refreshAll(Context context, boolean enableProviderOnSuccess) {
        JSONArray array = new JSONArray();
        for (String provider : new String[]{"ollama", "vllm", "lm_studio"}) {
            array.put(refreshProvider(context, provider, enableProviderOnSuccess).toJson());
        }
        return array;
    }

    private static void scheduleAutoRefresh(Context context) {
        SharedPreferences prefs = VoidPortSettings.prefs(context);
        if (prefs.getBoolean(VoidPortSettings.PREF_AUTO_REFRESH_MODELS, true)) {
            new Thread(() -> {
                for (String provider : new String[]{"ollama", "vllm", "lm_studio"}) {
                    refreshProvider(context, provider, true);
                }
                MAIN.postDelayed(() -> scheduleAutoRefresh(context), REFRESH_INTERVAL_MS);
            }, "void-auto-refresh-models").start();
        } else {
            MAIN.postDelayed(() -> scheduleAutoRefresh(context), REFRESH_INTERVAL_MS);
        }
    }

    private static List<String> fetchOllamaModels(String baseUrl) throws Exception {
        JSONObject json = fetchJson(trimTrailingSlash(baseUrl) + "/api/tags");
        JSONArray models = json.optJSONArray("models");
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; models != null && i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            String name = model == null ? "" : model.optString("name", "");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    private static List<String> fetchOpenAiCompatibleModels(String baseUrl) throws Exception {
        JSONObject json = fetchJson(modelListUrl(baseUrl));
        JSONArray data = json.optJSONArray("data");
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject model = data.optJSONObject(i);
            String id = model == null ? "" : model.optString("id", "");
            if (!id.isEmpty()) {
                names.add(id);
            }
        }
        return new ArrayList<>(names);
    }

    private static JSONObject fetchJson(String url) throws Exception {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = CLIENT.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code() + " from " + url + ": " + body);
            }
            return new JSONObject(body);
        }
    }

    private static void saveAutodetectedModels(SharedPreferences prefs, String providerId,
                                               String providerLabel, List<String> models) {
        JSONArray existing = readArray(prefs.getString(VoidPortSettings.PREF_CUSTOM_MODELS, "[]"));
        JSONArray next = new JSONArray();
        for (int i = 0; i < existing.length(); i++) {
            JSONObject item = existing.optJSONObject(i);
            if (item == null) {
                continue;
            }
            boolean sameProvider = providerId.equals(item.optString("providerId", ""));
            boolean autodetected = item.optBoolean("autodetected", false);
            if (!(sameProvider && autodetected)) {
                next.put(item);
            }
        }
        for (String model : models) {
            if (model == null || model.trim().isEmpty()) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("providerId", providerId);
                item.put("providerLabel", providerLabel);
                item.put("model", model.trim());
                item.put("autodetected", true);
                next.put(item);
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(VoidPortSettings.PREF_CUSTOM_MODELS, next.toString()).apply();
    }

    private static JSONArray readArray(String raw) {
        try {
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static String endpoint(SharedPreferences prefs, String key, String fallback) {
        return prefs.getString(key, fallback).trim();
    }

    private static String modelListUrl(String baseUrl) {
        String trimmed = trimTrailingSlash(baseUrl);
        if (trimmed.endsWith("/v1/chat/completions")) {
            return trimmed.substring(0, trimmed.length() - "/chat/completions".length()) + "/models";
        }
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed.substring(0, trimmed.length() - "/chat/completions".length()) + "/models";
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed + "/models";
        }
        return trimmed + "/v1/models";
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String normalizeProvider(String providerId) {
        if (providerId == null) {
            return "";
        }
        return switch (providerId) {
            case "ollama" -> "ollama";
            case "vllm", "vLLM" -> "vllm";
            case "lm_studio", "lmStudio" -> "lm_studio";
            default -> "";
        };
    }

    private static String providerLabel(String providerId) {
        return switch (providerId) {
            case "ollama" -> "Ollama";
            case "vllm" -> "vLLM";
            case "lm_studio" -> "LM Studio";
            default -> providerId;
        };
    }
}
