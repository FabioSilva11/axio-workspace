package com.saaspaymentsolutions.axion;

import android.app.Application;
import android.content.Context;

import com.saaspaymentsolutions.axion.analytics.AxionAnalytics;
import com.google.firebase.messaging.FirebaseMessaging;

public class SketchApplication extends Application {
    private static SketchApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AxionAppBlockManager.initialize(this);
        ChatFlowLogger.initialize(this);
        AxionAnalytics.initialize(this);
        // Payments use the live HTTPS endpoint published at Firebase config/api.
        // Start this listener before any screen can create a checkout.
        AxionEndpointRegistry.start(this);
        AxionManagedApi.start(this);
        FirebaseMessaging.getInstance().subscribeToTopic(AxionMessagingService.TOPIC_ALL)
                .addOnSuccessListener(ignored -> ChatFlowLogger.event(
                        "push", "topic_subscribed", "topic=" + AxionMessagingService.TOPIC_ALL))
                .addOnFailureListener(error -> ChatFlowLogger.error(
                        "push", "topic_subscription_failed", error));
    }

    public static Context getContext() {
        return instance != null ? instance.getApplicationContext() : null;
    }

    public static SketchApplication getInstance() {
        return instance;
    }
}
