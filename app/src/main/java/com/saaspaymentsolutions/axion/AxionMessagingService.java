package com.saaspaymentsolutions.axion;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.RemoteMessage;

/** Receives data-only FCM messages and renders them consistently in foreground/background. */
public class AxionMessagingService extends FirebaseMessagingService {
    public static final String TOPIC_ALL = "axion_all";
    private static final String CHANNEL_ID = "axion_admin";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        String title = value(remoteMessage.getData().get("title"));
        String body = value(remoteMessage.getData().get("body"));
        String url = value(remoteMessage.getData().get("url"));
        if ((title.isEmpty() || body.isEmpty()) && remoteMessage.getNotification() != null) {
            if (title.isEmpty()) title = value(remoteMessage.getNotification().getTitle());
            if (body.isEmpty()) body = value(remoteMessage.getNotification().getBody());
        }
        if (title.isEmpty() || body.isEmpty()) {
            ChatFlowLogger.event("push", "message_ignored", "reason=missing_title_or_body");
            return;
        }

        createChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ChatFlowLogger.event("push", "notification_suppressed", "reason=permission_denied");
            return;
        }

        Intent intent;
        if (url.startsWith("https://")) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        } else {
            intent = new Intent(this, MainActivity.class);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int notificationId = remoteMessage.getMessageId() == null
                ? (int) (System.currentTimeMillis() & 0x7fffffff)
                : remoteMessage.getMessageId().hashCode() & 0x7fffffff;
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_axion)
                .setColor(Color.rgb(0, 200, 160))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);
        NotificationManagerCompat.from(this).notify(notificationId, notification.build());
        ChatFlowLogger.event("push", "notification_displayed", "id=" + notificationId
                + ", hasUrl=" + !url.isEmpty());
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        // Never persist or print the token. Topic subscription is retried and
        // stored by the Firebase SDK across app restarts.
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_ALL)
                .addOnSuccessListener(ignored -> ChatFlowLogger.event(
                        "push", "token_topic_subscribed", "topic=" + TOPIC_ALL))
                .addOnFailureListener(error -> ChatFlowLogger.error(
                        "push", "token_topic_subscription_failed", error));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.push_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.push_channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
