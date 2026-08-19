package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.saaspaymentsolutions.axion.account.AxionSession;
import com.saaspaymentsolutions.axion.account.PlanEntitlements;
import com.saaspaymentsolutions.axion.port.VoidPortSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Cliente do gateway gerenciado do Axion.
 *
 * <p>A fonte de verdade do catálogo é axionSettings/config/models no Firebase
 * (leitura pública, atualizada em tempo real). O gateway é usado somente para
 * bootstrap, carteira e chat.</p>
 */
public final class AxionManagedApi {
    public interface RefreshCallback {
        void onComplete(boolean changed, String error);
    }

    public interface ModelsListener {
        void onModelsUpdated();
    }

    public interface TokenRefreshCallback {
        void onComplete(String token, String error);
    }

    /** Modelo liberado pelo servidor dentro de um provedor. */
    public static final class ModelInfo {
        public final String id;
        public final String name;
        public final String minimumPlan;
        public final String providerId;

        public ModelInfo(String id, String name, String minimumPlan) {
            this(id, name, minimumPlan, "");
        }

        public ModelInfo(String id, String name, String minimumPlan, String providerId) {
            this.id = safe(id);
            this.name = safe(name).isEmpty() ? this.id : safe(name);
            this.minimumPlan = safe(minimumPlan).isEmpty() ? "server" : safe(minimumPlan);
            this.providerId = safe(providerId);
        }
    }

    /** Provedor já filtrado pelo servidor para o plano/status atual. */
    public static final class ProviderInfo {
        public final String id;
        public final String name;
        public final String availablePlans;
        public final List<ModelInfo> models;

        public ProviderInfo(String id, String name, String availablePlans, List<ModelInfo> models) {
            this.id = safe(id);
            this.name = safe(name).isEmpty() ? this.id : safe(name);
            this.availablePlans = safe(availablePlans).isEmpty() ? "all" : safe(availablePlans);
            this.models = Collections.unmodifiableList(new ArrayList<>(
                    models == null ? Collections.emptyList() : models));
        }
    }

    private interface TokenCallback {
        void onToken(String token, String error);
    }

    private static final String TAG = "AxionManagedApi";
    public static final String PROVIDER_ID = "axion_managed";
    public static final String PREF_SELECTED_SERVER_PROVIDER = "axion_managed_selected_server_provider";

    private static final String PREF_INSTALL_ID = "axion_managed_install_id";
    private static final String PREF_BASE_ENDPOINT = "axion_managed_base_endpoint";
    private static final String PREF_CHAT_ENDPOINT_LEGACY = "axion_managed_chat_endpoint";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final long TOKEN_TIMEOUT_MS = 15_000L;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean BOOTSTRAP_IN_FLIGHT = new AtomicBoolean(false);

    private static volatile String liveBaseEndpoint = "";
    private static volatile String liveChatEndpoint = "";
    private static volatile boolean endpointOnlineConfirmed = false;
    private static volatile String firebaseIdToken = "";
    private static volatile String livePlan = "";
    private static volatile long lastBootstrapElapsedMs = 0L;

    private static volatile List<ProviderInfo> liveProviders = Collections.emptyList();
    private static volatile boolean providersLoaded = false;
    private static volatile String lastProvidersError = "";
    private static final List<ModelsListener> modelsListeners = new CopyOnWriteArrayList<>();

    private AxionManagedApi() {
    }

    public static void start(Context context) {
        if (context == null || !STARTED.compareAndSet(false, true)) return;
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = VoidPortSettings.prefs(appContext);

        String cached = prefs.getString(PREF_BASE_ENDPOINT,
                prefs.getString(PREF_CHAT_ENDPOINT_LEGACY, ""));
        applyEndpoint(prefs, cached, true);
        prefs.edit().remove("axion_managed_api_key").apply();

        String databaseUrl = BuildConfig.FIREBASE_DATABASE_URL;
        if (databaseUrl == null || databaseUrl.trim().isEmpty()) {
            providersLoaded = true;
            lastProvidersError = "Firebase Database URL não configurada.";
            notifyModelsListeners();
            return;
        }

        FirebaseDatabase db = FirebaseDatabase.getInstance(databaseUrl);
        db.getReference("config/api").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean online = snapshot.child("online").getValue(Boolean.class);
                String endpoint = snapshot.child("endpoint").getValue(String.class);
                if (!Boolean.TRUE.equals(online) || !applyEndpoint(prefs, endpoint, false)) {
                    endpointOnlineConfirmed = false;
                    return;
                }
                endpointOnlineConfirmed = true;
                refreshAccountAndProviders(appContext, null);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                endpointOnlineConfirmed = false;
                clearProviders("Falha ao ler config/api: " + error.getMessage());
            }
        });

        FirebaseAuth.getInstance().addIdTokenListener((FirebaseAuth.IdTokenListener) auth -> {
            FirebaseUser user = auth.getCurrentUser();
            if (user == null) {
                firebaseIdToken = "";
                livePlan = "";
                lastBootstrapElapsedMs = 0L;
                clearProviders("Autenticação necessária.");
                return;
            }
            user.getIdToken(false)
                    .addOnSuccessListener(result -> {
                        firebaseIdToken = safe(result.getToken());
                        refreshAccountAndProviders(appContext, null);
                    })
                    .addOnFailureListener(error ->
                            Log.e(TAG, "Falha ao obter ID token do Firebase.", error));
        });

        // users/{uid} continua sendo observado pelo FirebaseAccountStore. Sempre que
        // o plano/status mudar, revalide a lista no servidor, que é a fonte de verdade.
        AxionSession.addListener(() -> {
            if (AxionSession.isLoaded()
                    && isConfigured()
                    && FirebaseAuth.getInstance().getCurrentUser() != null) {
                refreshProviders(appContext, null);
            }
        });

        // O Firebase apenas sinaliza que o catálogo mudou. A lista efetiva vem
        // de /v1/ai/providers, já filtrada pelo servidor para o plano atual.
        // Isso preserva a diferença entre "somente Free" e "todos os planos",
        // que não pode ser representada pelo espelho legado min_plan=free.
        db.getReference("axionSettings/config/models")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (FirebaseAuth.getInstance().getCurrentUser() != null
                                && isConfigured()) {
                            refreshProviders(appContext, null);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        clearProviders("Falha ao ler o catálogo: " + error.getMessage());
                    }
                });
    }

    public static boolean isConfigured() {
        return endpointOnlineConfirmed
                && !liveBaseEndpoint.isEmpty()
                && !liveChatEndpoint.isEmpty();
    }

    public static boolean isSelected(Context context) {
        if (context == null) return false;
        return PROVIDER_ID.equals(VoidPortSettings.prefs(context)
                .getString(VoidPortSettings.PREF_CURRENT_PROVIDER, ""));
    }

    public static void setFirebaseIdToken(Context context, String idToken) {
        firebaseIdToken = safe(idToken);
        if (context != null && !firebaseIdToken.isEmpty()) {
            refreshAccountAndProviders(context.getApplicationContext(), null);
        }
    }

    /** Força a renovação do ID token para repetir uma chamada que recebeu HTTP 401. */
    public static void refreshFirebaseToken(TokenRefreshCallback callback) {
        obtainToken(true, (token, error) -> {
            if (callback != null) MAIN.post(() -> callback.onComplete(token, error));
        });
    }

    public static String chatEndpoint() {
        return isConfigured() ? liveChatEndpoint : "";
    }

    public static JSONObject requestHeaders(SharedPreferences prefs) {
        JSONObject headers = new JSONObject();
        try {
            headers.put("X-Axion-Install-Id", installId(prefs));
            headers.put("X-Axion-Client", "android/" + BuildConfig.VERSION_NAME);
            String token = safe(firebaseIdToken);
            if (!token.isEmpty()) {
                headers.put("Authorization", "Bearer " + token);
            }
        } catch (Exception ignored) {
        }
        return headers;
    }

    /** True assim que o catálogo axionSettings/config/models chegou do Firebase. */
    public static boolean modelsLoaded() {
        return providersLoaded;
    }

    public static boolean providersLoaded() {
        return providersLoaded;
    }

    public static String lastProvidersError() {
        return lastProvidersError;
    }

    public static void addModelsListener(ModelsListener listener) {
        if (listener != null) modelsListeners.add(listener);
    }

    public static void removeModelsListener(ModelsListener listener) {
        modelsListeners.remove(listener);
    }

    public static List<ProviderInfo> availableProviders() {
        return new ArrayList<>(liveProviders);
    }

    public static ProviderInfo providerById(String providerId) {
        String id = safe(providerId);
        for (ProviderInfo provider : liveProviders) {
            if (id.equals(provider.id)) return provider;
        }
        return null;
    }

    public static String providerDisplayName(String providerId) {
        ProviderInfo provider = providerById(providerId);
        return provider == null ? safe(providerId) : provider.name;
    }

    public static List<String> visibleModelIds(SharedPreferences prefs) {
        List<String> result = new ArrayList<>();
        if (!providersLoaded) return result;
        boolean paid = AxionSession.isLoaded() && AxionSession.isPaid();
        for (ProviderInfo provider : liveProviders) {
            for (ModelInfo model : provider.models) {
                if (!isModelAllowedForPlan(model.minimumPlan, paid)) continue;
                if (!model.id.isEmpty() && !result.contains(model.id)) {
                    result.add(model.id);
                }
            }
        }
        return result;
    }

    public static String modelDisplayName(String modelId) {
        String id = safe(modelId);
        for (ProviderInfo provider : liveProviders) {
            for (ModelInfo model : provider.models) {
                if (id.equals(model.id)) return model.name;
            }
        }
        return id;
    }

    public static String providerIdForModel(String modelId) {
        String id = safe(modelId);
        for (ProviderInfo provider : liveProviders) {
            for (ModelInfo model : provider.models) {
                if (id.equals(model.id)) return provider.id;
            }
        }
        return "";
    }

    public static boolean isModelAvailable(String serverProviderId, String modelId) {
        ProviderInfo provider = providerById(serverProviderId);
        if (provider == null) return false;
        String id = safe(modelId);
        for (ModelInfo model : provider.models) {
            if (id.equals(model.id)) return true;
        }
        return false;
    }

    public static String selectedServerProviderId(SharedPreferences prefs) {
        if (prefs == null) return "";
        String selected = safe(prefs.getString(PREF_SELECTED_SERVER_PROVIDER, ""));
        String currentModel = safe(prefs.getString(VoidPortSettings.PREF_CURRENT_MODEL, ""));
        if (isModelAvailable(selected, currentModel)) return selected;
        return providerIdForModel(currentModel);
    }

    public static void saveManagedSelection(
            SharedPreferences prefs,
            String serverProviderId,
            String modelId) {
        if (prefs == null) return;
        prefs.edit()
                .putString(PREF_SELECTED_SERVER_PROVIDER, safe(serverProviderId))
                .putString(VoidPortSettings.PREF_CURRENT_PROVIDER, PROVIDER_ID)
                .putString(VoidPortSettings.PREF_CURRENT_MODEL, safe(modelId))
                .apply();
    }

    public static void ensureManagedSelection(SharedPreferences prefs) {
        if (prefs == null || liveProviders.isEmpty()) return;
        String currentProvider = safe(prefs.getString(VoidPortSettings.PREF_CURRENT_PROVIDER, ""));
        String currentModel = safe(prefs.getString(VoidPortSettings.PREF_CURRENT_MODEL, ""));
        String selectedProvider = selectedServerProviderId(prefs);
        if (PROVIDER_ID.equals(currentProvider)
                && isModelAvailable(selectedProvider, currentModel)) {
            return;
        }
        ProviderInfo firstProvider = liveProviders.get(0);
        if (firstProvider.models.isEmpty()) return;
        saveManagedSelection(prefs, firstProvider.id, firstProvider.models.get(0).id);
    }

    /** Paid é um plano superior: acessa modelos free e paid; Free acessa só free. */
    public static boolean isModelAllowedForPlan(String minimumPlan, boolean paid) {
        String normalized = minimumPlan == null ? "free" : minimumPlan.trim();
        return paid
                || !("paid".equalsIgnoreCase(normalized)
                || "pro".equalsIgnoreCase(normalized));
    }

    /** Atualiza somente o catálogo de provedores/modelos (releitura do Firebase). */
    public static void refresh(Context context, RefreshCallback callback) {
        refreshProviders(context, callback);
    }

    /** Executa bootstrap e em seguida revalida provedores/modelos. */
    public static void refreshAccountAndProviders(Context context, RefreshCallback callback) {
        Log.i(TAG, "refreshAccountAndProviders: configured=" + isConfigured()
                + " providersLoaded=" + providersLoaded + " endpoint=" + liveBaseEndpoint);
        if (context == null || !isConfigured()) {
            dispatch(callback, false, "Gateway Axion indisponível.");
            return;
        }
        bootstrap(context.getApplicationContext(), false, (changed, error) -> {
            if (error != null) {
                dispatch(callback, changed, error);
                return;
            }
            refreshProviders(context.getApplicationContext(), (providersChanged, providersError) ->
                    dispatch(callback, changed || providersChanged, providersError));
        });
    }

    public static void refreshProviders(Context context, RefreshCallback callback) {
        if (context == null) {
            dispatch(callback, false, "Contexto inválido.");
            return;
        }
        if (!isConfigured()) {
            dispatch(callback, false, "Gateway Axion indisponível.");
            return;
        }
        obtainToken(false, (token, tokenError) -> {
            if (tokenError != null) {
                dispatch(callback, false, tokenError);
                return;
            }
            requestProviderCatalog(token, false, callback);
        });
    }

    private static void requestProviderCatalog(
            String token,
            boolean retriedAuth,
            RefreshCallback callback
    ) {
        Request request = new Request.Builder()
                .url(gatewayUrl(liveBaseEndpoint, "/v1/ai/providers"))
                .header("Authorization", "Bearer " + token)
                .header("X-Axion-Client", "android/" + BuildConfig.VERSION_NAME)
                .get()
                .build();
        HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                dispatch(callback, false, "Não foi possível atualizar os provedores.");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    String body = closeable.body() == null ? "" : closeable.body().string();
                    if (closeable.code() == 401 && !retriedAuth) {
                        obtainToken(true, (newToken, tokenError) -> {
                            if (tokenError != null) dispatch(callback, false, tokenError);
                            else requestProviderCatalog(newToken, true, callback);
                        });
                        return;
                    }
                    if (!closeable.isSuccessful()) {
                        dispatch(callback, false, apiErrorMessage(closeable.code(), body,
                                "Falha ao atualizar os provedores."));
                        return;
                    }
                    boolean changed = applyProviderCatalogPayload(new JSONObject(body));
                    dispatch(callback, changed, null);
                } catch (Exception error) {
                    dispatch(callback, false, "Resposta inválida do catálogo de provedores.");
                }
            }
        });
    }

    /** Consulta o saldo autoritativo no gateway; nunca calcula consumo no cliente. */
    public static void refreshWallet(Context context, RefreshCallback callback) {
        if (context == null || !isConfigured()) {
            dispatch(callback, false, "Gateway Axion indisponível.");
            return;
        }
        obtainToken(false, (token, tokenError) -> {
            if (tokenError != null) {
                dispatch(callback, false, tokenError);
                return;
            }
            requestWallet(token, false, callback);
        });
    }

    private static void requestWallet(String token, boolean retriedAuth, RefreshCallback callback) {
        Request request = new Request.Builder()
                .url(gatewayUrl(liveBaseEndpoint, "/v1/usage"))
                .header("Authorization", "Bearer " + token)
                .header("X-Axion-Client", "android/" + BuildConfig.VERSION_NAME)
                .get()
                .build();
        HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                dispatch(callback, false, "Não foi possível atualizar o saldo.");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    String body = closeable.body() == null ? "" : closeable.body().string();
                    if (closeable.code() == 401 && !retriedAuth) {
                        obtainToken(true, (newToken, tokenError) -> {
                            if (tokenError != null) dispatch(callback, false, tokenError);
                            else requestWallet(newToken, true, callback);
                        });
                        return;
                    }
                    if (!closeable.isSuccessful()) {
                        dispatch(callback, false, apiErrorMessage(closeable.code(), body,
                                "Falha ao atualizar o saldo."));
                        return;
                    }
                    boolean applied = applyWalletPayload(new JSONObject(body));
                    dispatch(callback, applied, applied ? null : "Resposta de saldo inválida.");
                } catch (Exception error) {
                    dispatch(callback, false, "Resposta de saldo inválida.");
                }
            }
        });
    }

    public static boolean applyWalletPayload(JSONObject payload) {
        if (payload == null) return false;
        JSONObject wallet = payload.optJSONObject("axion_wallet");
        if (wallet == null) wallet = payload.optJSONObject("wallet");
        if (wallet == null || !wallet.has("used") || !wallet.has("limit")) return false;
        long used = Math.max(0L, wallet.optLong("used", 0L));
        long limit = Math.max(1L, wallet.optLong("limit", 1L));
        long reserved = Math.max(0L, wallet.optLong("reserved", 0L));
        long available = Math.max(0L, wallet.optLong("available", limit - used - reserved));
        Context appContext = SketchApplication.getContext();
        if (appContext != null) {
            TokenUsageStore.updateFromServer(appContext, used, limit, available, reserved);
            PlanEntitlements.syncManagedWallet(appContext, used, limit, available);
        }
        return true;
    }

    private static void bootstrap(Context context, boolean force, RefreshCallback callback) {
        long age = SystemClock.elapsedRealtime() - lastBootstrapElapsedMs;
        Log.i(TAG, "bootstrap: force=" + force + " cacheAge=" + age + "ms inFlight=" + BOOTSTRAP_IN_FLIGHT.get());
        if (!force && lastBootstrapElapsedMs > 0L && age < 30_000L) {
            dispatch(callback, false, null);
            return;
        }
        if (!BOOTSTRAP_IN_FLIGHT.compareAndSet(false, true)) {
            dispatch(callback, false, null);
            return;
        }
        obtainToken(false, (token, tokenError) -> {
            if (tokenError != null) {
                BOOTSTRAP_IN_FLIGHT.set(false);
                dispatch(callback, false, tokenError);
                return;
            }
            Log.i(TAG, "bootstrap: token ok, chamando /v1/account/bootstrap");
            requestBootstrap(token, false, callback);
        });
    }

    private static void requestBootstrap(String token, boolean retriedAuth, RefreshCallback callback) {
        Log.i(TAG, "requestBootstrap: POST " + gatewayUrl(liveBaseEndpoint, "/v1/account/bootstrap"));
        Request request = new Request.Builder()
                .url(gatewayUrl(liveBaseEndpoint, "/v1/account/bootstrap"))
                .header("Authorization", "Bearer " + token)
                .header("X-Axion-Client", "android/" + BuildConfig.VERSION_NAME)
                .post(RequestBody.create("{}", JSON))
                .build();
        HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                Log.w(TAG, "requestBootstrap: falha HTTP " + error);
                BOOTSTRAP_IN_FLIGHT.set(false);
                dispatch(callback, false, "Não foi possível inicializar a conta.");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    String body = closeable.body() == null ? "" : closeable.body().string();
                    Log.i(TAG, "requestBootstrap: HTTP " + closeable.code());
                    if (closeable.code() == 401 && !retriedAuth) {
                        obtainToken(true, (newToken, tokenError) -> {
                            if (tokenError != null) {
                                BOOTSTRAP_IN_FLIGHT.set(false);
                                dispatch(callback, false, tokenError);
                            } else {
                                requestBootstrap(newToken, true, callback);
                            }
                        });
                        return;
                    }
                    if (!closeable.isSuccessful()) {
                        BOOTSTRAP_IN_FLIGHT.set(false);
                        dispatch(callback, false, apiErrorMessage(closeable.code(), body,
                                "Falha ao inicializar a conta."));
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    boolean walletChanged = applyWalletPayload(json);
                    JSONObject account = json.optJSONObject("account");
                    if (account != null) {
                        livePlan = safe(account.optString("plan", livePlan));
                        JSONObject subscription = account.optJSONObject("subscription");
                        String status = subscription == null
                                ? ""
                                : safe(subscription.optString("status", ""));
                        Context appContext = SketchApplication.getContext();
                        if (appContext != null) {
                            PlanEntitlements.syncServerPlan(appContext, livePlan, status);
                        }
                    }
                    lastBootstrapElapsedMs = SystemClock.elapsedRealtime();
                    BOOTSTRAP_IN_FLIGHT.set(false);
                    dispatch(callback, walletChanged, null);
                } catch (Exception error) {
                    BOOTSTRAP_IN_FLIGHT.set(false);
                    dispatch(callback, false, "Resposta inválida do bootstrap.");
                }
            }
        });
    }

    private static void obtainToken(boolean forceRefresh, TokenCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            callback.onToken("", "Autenticação necessária.");
            return;
        }
        if (!forceRefresh && !safe(firebaseIdToken).isEmpty()) {
            callback.onToken(firebaseIdToken, null);
            return;
        }
        AtomicBoolean done = new AtomicBoolean(false);
        Runnable timeout = () -> {
            if (!done.compareAndSet(false, true)) return;
            Log.w(TAG, "Timeout ao renovar o token do Firebase; rede indisponível.");
            callback.onToken("", "Tempo esgotado ao renovar a autenticação. Verifique sua conexão.");
        };
        user.getIdToken(forceRefresh)
                .addOnSuccessListener(result -> {
                    if (!done.compareAndSet(false, true)) return;
                    MAIN.removeCallbacks(timeout);
                    firebaseIdToken = safe(result.getToken());
                    if (firebaseIdToken.isEmpty()) {
                        callback.onToken("", "Token Firebase inválido.");
                    } else {
                        callback.onToken(firebaseIdToken, null);
                    }
                })
                .addOnFailureListener(error -> {
                    if (!done.compareAndSet(false, true)) return;
                    MAIN.removeCallbacks(timeout);
                    callback.onToken("", "Falha ao autenticar no servidor.");
                });
        MAIN.postDelayed(timeout, TOKEN_TIMEOUT_MS);
    }

    private static boolean applyEndpoint(SharedPreferences prefs, String endpoint, boolean fromCache) {
        String base = normalizeBaseEndpoint(endpoint);
        if (base.isEmpty()) {
            if (!fromCache) {
                liveBaseEndpoint = "";
                liveChatEndpoint = "";
                endpointOnlineConfirmed = false;
                prefs.edit()
                        .remove(PREF_BASE_ENDPOINT)
                        .remove(PREF_CHAT_ENDPOINT_LEGACY)
                        .apply();
            }
            return false;
        }
        liveBaseEndpoint = base;
        liveChatEndpoint = gatewayUrl(base, "/v1/ai/chat/completions");
        prefs.edit()
                .putString(PREF_BASE_ENDPOINT, base)
                .remove(PREF_CHAT_ENDPOINT_LEGACY)
                .apply();
        return true;
    }

    private static String normalizeBaseEndpoint(String endpoint) {
        String value = safe(endpoint);
        if (value.isEmpty()) return "";
        int marker = value.indexOf("/v1/");
        if (marker >= 0) value = value.substring(0, marker);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            Uri uri = Uri.parse(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || safe(uri.getHost()).isEmpty()
                    || uri.getUserInfo() != null) {
                return "";
            }
            return value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String gatewayUrl(String endpoint, String path) {
        String value = safe(endpoint);
        if (value.isEmpty()) return "";
        int marker = value.indexOf("/v1/");
        if (marker >= 0) value = value.substring(0, marker);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value + path;
    }

    static boolean applyProviderCatalogPayload(JSONObject payload) {
        if (payload == null) return false;
        JSONArray providerArray = payload.optJSONArray("providers");
        if (providerArray == null) return false;
        List<String> before = snapshotProviderKeys(liveProviders);
        List<ProviderInfo> providers = new ArrayList<>();
        for (int i = 0; i < providerArray.length(); i++) {
            JSONObject rawProvider = providerArray.optJSONObject(i);
            if (rawProvider == null) continue;
            String providerId = safe(rawProvider.optString("id", ""));
            if (providerId.isEmpty()) continue;
            List<ModelInfo> models = new ArrayList<>();
            JSONArray modelArray = rawProvider.optJSONArray("models");
            if (modelArray != null) {
                for (int j = 0; j < modelArray.length(); j++) {
                    JSONObject rawModel = modelArray.optJSONObject(j);
                    if (rawModel == null) continue;
                    String modelId = safe(rawModel.optString("id", ""));
                    if (modelId.isEmpty()) continue;
                    models.add(new ModelInfo(
                            modelId,
                            rawModel.optString("name", modelId),
                            "server",
                            providerId));
                }
            }
            if (models.isEmpty()) continue;
            Collections.sort(models, (a, b) -> a.name.compareToIgnoreCase(b.name));
            providers.add(new ProviderInfo(
                    providerId,
                    rawProvider.optString("name", providerId),
                    rawProvider.optString("availablePlans", "all"),
                    models));
        }
        Collections.sort(providers, (a, b) -> a.name.compareToIgnoreCase(b.name));
        liveProviders = Collections.unmodifiableList(providers);
        providersLoaded = true;
        lastProvidersError = "";
        notifyModelsListeners();
        return !before.equals(snapshotProviderKeys(liveProviders));
    }

    private static List<String> snapshotProviderKeys(List<ProviderInfo> providers) {
        List<String> keys = new ArrayList<>();
        for (ProviderInfo provider : providers) {
            keys.add("P:" + provider.id + ":" + provider.name + ":" + provider.availablePlans);
            for (ModelInfo model : provider.models) {
                keys.add("M:" + provider.id + ":" + model.id + ":" + model.name);
            }
        }
        return keys;
    }

    private static String apiErrorMessage(int status, String body, String fallback) {
        try {
            JSONObject root = new JSONObject(body == null ? "" : body);
            JSONObject error = root.optJSONObject("error");
            String code = error == null ? "" : safe(error.optString("code", ""));
            String message = error == null ? "" : safe(error.optString("message", ""));
            if (!message.isEmpty()) return message + (code.isEmpty() ? "" : " (" + code + ")");
            if (!code.isEmpty()) return code;
        } catch (Exception ignored) {
        }
        return fallback + " HTTP " + status + ".";
    }

    private static void clearProviders(String error) {
        liveProviders = Collections.emptyList();
        providersLoaded = true;
        lastProvidersError = safe(error);
        notifyModelsListeners();
    }

    private static void notifyModelsListeners() {
        MAIN.post(() -> {
            for (ModelsListener listener : modelsListeners) {
                listener.onModelsUpdated();
            }
        });
    }

    private static String installId(SharedPreferences prefs) {
        if (prefs == null) return UUID.randomUUID().toString();
        String existing = safe(prefs.getString(PREF_INSTALL_ID, ""));
        if (existing.length() >= 16) return existing;
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(PREF_INSTALL_ID, created).commit();
        return created;
    }

    private static void dispatch(RefreshCallback callback, boolean changed, String error) {
        if (callback != null) MAIN.post(() -> callback.onComplete(changed, error));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
