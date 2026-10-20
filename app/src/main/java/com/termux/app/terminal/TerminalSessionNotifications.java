package com.termux.app.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import java.util.HashMap;
import java.util.Map;

/** Session-scoped notifications, owned by the service rather than the terminal activity. */
public final class TerminalSessionNotifications {
    public static final String CHANNEL_ID = "terminal_session_notifications";
    private static final String TAG_PREFIX = "terminal-session:";
    private static final int NOTIFICATION_ID = 1;
    private static final long ALERT_INTERVAL_MILLIS = 5000;

    private final Context context;
    private final NotificationManager manager;
    private final Map<String, Reminder> reminders = new HashMap<>();

    public TerminalSessionNotifications(Context context) {
        this.context = context;
        manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            context.getString(R.string.session_notification_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.session_notification_channel_description));
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    public void show(TerminalSession session, int sessionNumber, String text) {
        String message = notificationText(text, 2000);
        if (message.isEmpty() || !manager.areNotificationsEnabled()) return;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;

        long now = SystemClock.elapsedRealtime();
        Reminder previous = reminders.get(session.mHandle);
        boolean quiet = previous != null && now - previous.lastAlert < ALERT_INTERVAL_MILLIS;
        if (quiet && message.equals(previous.message)) return;

        String title = notificationText(session.mSessionName, 120);
        if (title.isEmpty()) title = context.getString(R.string.session_notification_title, sessionNumber);
        Intent intent = TermuxActivity.newInstance(context)
            .setAction(TermuxActivity.ACTION_OPEN_SESSION)
            // Intent extras do not distinguish PendingIntents; use the stable session handle in data.
            .setData(new Uri.Builder().scheme("mdterm").authority("session").appendPath(session.mHandle).build())
            .putExtra(TermuxActivity.EXTRA_SESSION_HANDLE, session.mHandle);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setCategory(Notification.CATEGORY_EVENT)
            .build();
        try {
            manager.notify(TAG_PREFIX + session.mHandle, NOTIFICATION_ID, notification);
            reminders.put(session.mHandle, new Reminder(message, quiet ? previous.lastAlert : now));
        } catch (SecurityException ignored) {
            // Notification permission can be revoked between the check and notify().
        }
    }

    public void dismiss(String sessionHandle) {
        manager.cancel(TAG_PREFIX + sessionHandle, NOTIFICATION_ID);
        reminders.remove(sessionHandle);
    }

    public void clearAll() {
        // Also remove reminders left behind if a previous app process was killed.
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            String tag = notification.getTag();
            if (tag != null && tag.startsWith(TAG_PREFIX)) manager.cancel(tag, notification.getId());
        }
        reminders.clear();
    }

    private static String notificationText(String text, int limit) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t') continue;
            if (result.length() + Character.charCount(codePoint) > limit) break;
            result.appendCodePoint(codePoint);
        }
        return result.toString().trim();
    }

    private static final class Reminder {
        final String message;
        final long lastAlert;

        Reminder(String message, long lastAlert) {
            this.message = message;
            this.lastAlert = lastAlert;
        }
    }
}
