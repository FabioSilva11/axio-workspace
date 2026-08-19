package com.saaspaymentsolutions.axion.account;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.saaspaymentsolutions.axion.AxionEndpointRegistry;
import com.saaspaymentsolutions.axion.AxionManagedApi;
import com.saaspaymentsolutions.axion.BuildConfig;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class AxionPaymentApi {
    private static final String TAG = "AxionPaymentApi";
    public interface ResultCallback {
        void onSuccess(@NonNull JSONObject result);

        void onError(@NonNull String message);
    }

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build();

    private AxionPaymentApi() {
    }

    public static void fetchPlans(
            @NonNull Context context,
            @NonNull ResultCallback callback
    ) {
        String baseUrl = serverBaseUrl(context);
        if (baseUrl.isEmpty()) {
            dispatchError(callback, "Servidor Axion não configurado.");
            return;
        }
        Request request = new Request.Builder()
                .url(baseUrl + "/v1/plans")
                .header("Accept", "application/json")
                .header("X-Axion-Client", "android/" + BuildConfig.VERSION_NAME)
                .get()
                .build();
        execute(request, callback);
    }

    public static void createCheckout(
            @NonNull Context context,
            @NonNull String planId,
            @NonNull ResultCallback callback
    ) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("planId", planId);
        } catch (Exception error) {
            dispatchError(callback, "Não foi possível preparar o pagamento.");
            return;
        }
        authorizedRequest(
                context,
                "/v1/payments/checkout",
                "POST",
                payload,
                callback
        );
    }

    public static void syncAccount(
            @NonNull Context context,
            @NonNull ResultCallback callback
    ) {
        authorizedRequest(
                context,
                "/v1/account/bootstrap",
                "POST",
                new JSONObject(),
                callback
        );
    }

    public static void fetchCheckout(
            @NonNull Context context,
            @NonNull String checkoutId,
            @NonNull ResultCallback callback
    ) {
        // O servidor pode usar UUID com hífens ou outro identificador opaco seguro.
        // Bloqueie somente caracteres que permitiriam escapar do segmento da URL.
        if (!checkoutId.matches("[A-Za-z0-9_-]{8,128}")) {
            dispatchError(callback, "Pagamento inválido.");
            return;
        }
        authorizedRequest(
                context,
                "/v1/payments/checkouts/" + checkoutId,
                "GET",
                null,
                callback
        );
    }

    private static void authorizedRequest(
            Context context,
            String path,
            String method,
            @Nullable JSONObject payload,
            ResultCallback callback
    ) {
        authorizedRequest(context, path, method, payload, callback, false);
    }

    private static void authorizedRequest(
            Context context,
            String path,
            String method,
            @Nullable JSONObject payload,
            ResultCallback callback,
            boolean forceTokenRefresh
    ) {
        String baseUrl = serverBaseUrl(context);
        if (baseUrl.isEmpty()) {
            dispatchError(callback, "Servidor Axion não configurado ou offline.");
            return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            dispatchError(callback, "Entre na sua conta para continuar.");
            return;
        }
        user.getIdToken(forceTokenRefresh).addOnCompleteListener(task -> {
            if (!task.isSuccessful()
                    || task.getResult() == null
                    || task.getResult().getToken() == null
                    || task.getResult().getToken().trim().isEmpty()) {
                dispatchError(callback, "Não foi possível autenticar no servidor Axion.");
                return;
            }
            String token = task.getResult().getToken().trim();
            AxionManagedApi.setFirebaseIdToken(context, token);
            Request.Builder builder = new Request.Builder()
                    .url(baseUrl + path)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Axion-Client", "android/" + BuildConfig.VERSION_NAME);
            if ("POST".equals(method)) {
                builder.post(RequestBody.create(
                        payload == null ? "{}" : payload.toString(),
                        JSON
                ));
            } else {
                builder.get();
            }
            executeAuthorized(
                    context,
                    path,
                    method,
                    payload,
                    builder.build(),
                    callback,
                    forceTokenRefresh
            );
        });
    }

    private static void executeAuthorized(
            Context context,
            String path,
            String method,
            @Nullable JSONObject payload,
            Request request,
            ResultCallback callback,
            boolean tokenAlreadyRefreshed
    ) {
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                Log.w(TAG, "Falha em " + call.request().url().encodedPath()
                        + ": " + error.getClass().getSimpleName());
                dispatchError(callback, readableError(
                        error, "Não foi possível acessar o servidor Axion."));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    String raw = response.body() == null ? "" : response.body().string();
                    if (response.code() == 401 && !tokenAlreadyRefreshed) {
                        authorizedRequest(context, path, method, payload, callback, true);
                        return;
                    }
                    JSONObject result = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
                    if (!response.isSuccessful()) {
                        Log.w(TAG, "HTTP " + response.code() + " em "
                                + request.url().encodedPath());
                        dispatchError(callback, apiErrorMessage(response.code(), result));
                        return;
                    }
                    AxionManagedApi.applyWalletPayload(result);
                    MAIN.post(() -> callback.onSuccess(result));
                } catch (Exception error) {
                    dispatchError(callback, readableError(
                            error, "O servidor retornou uma resposta inválida."));
                }
            }
        });
    }

    private static void execute(
            Request request,
            ResultCallback callback
    ) {
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                Log.w(TAG, "Falha em " + call.request().url().encodedPath()
                        + ": " + error.getClass().getSimpleName());
                dispatchError(
                        callback,
                        readableError(
                                error,
                                "Não foi possível acessar o servidor Axion."
                        )
                );
            }

            @Override
            public void onResponse(
                    @NonNull Call call,
                    @NonNull Response response
            ) {
                try (response) {
                    String raw = response.body() == null
                            ? ""
                            : response.body().string();
                    JSONObject result = raw.isEmpty()
                            ? new JSONObject()
                            : new JSONObject(raw);
                    if (!response.isSuccessful()) {
                        Log.w(TAG, "HTTP " + response.code() + " em "
                                + request.url().encodedPath());
                        JSONObject error = result.optJSONObject("error");
                        String message = error == null
                                ? ""
                                : error.optString("message", "").trim();
                        dispatchError(
                                callback,
                                message.isEmpty()
                                        ? "Servidor Axion retornou HTTP "
                                        + response.code()
                                        + "."
                                        : message
                        );
                        return;
                    }
                    MAIN.post(() -> callback.onSuccess(result));
                } catch (Exception error) {
                    dispatchError(
                            callback,
                            readableError(
                                    error,
                                    "O servidor retornou uma resposta inválida."
                            )
                    );
                }
            }
        });
    }

    private static String serverBaseUrl(Context context) {
        String value = AxionEndpointRegistry.getEndpoint(
                context.getApplicationContext()
        );
        value = value == null ? "" : value.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String apiErrorMessage(int statusCode, JSONObject result) {
        JSONObject error = result == null ? null : result.optJSONObject("error");
        String code = error == null ? "" : error.optString("code", "").trim();
        String message = error == null ? "" : error.optString("message", "").trim();
        if (!message.isEmpty()) {
            return code.isEmpty() ? message : message + " (" + code + ")";
        }
        return code.isEmpty()
                ? "Servidor Axion retornou HTTP " + statusCode + "."
                : code;
    }

    private static String readableError(Throwable error, String fallback) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? fallback
                : message.trim();
    }

    private static void dispatchError(
            ResultCallback callback,
            String message
    ) {
        MAIN.post(() -> callback.onError(message));
    }
}
