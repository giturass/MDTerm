package com.termux.app.terminal;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Looper;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class TerminalSessionNotificationsTest {
    private Context context;
    private NotificationManager manager;
    private TerminalSessionNotifications notifications;
    private TerminalSession session;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(NotificationManager.class);
        notifications = new TerminalSessionNotifications(context);
        session = newSession();
    }

    @Test
    public void notificationCarriesTextAndAnImmutableIntentForItsSession() {
        session.mSessionName = "Build server";
        notifications.show(session, 2, "需要操作 🔔; approval");

        Notification notification = notification(session);
        assertEquals("Build server", notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("需要操作 🔔; approval", notification.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(TerminalSessionNotifications.CHANNEL_ID, notification.getChannelId());
        assertTrue((notification.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertFalse((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertEquals(TermuxActivity.ACTION_OPEN_SESSION,
            Shadows.shadowOf(notification.contentIntent).getSavedIntent().getAction());
        assertEquals(session.mHandle, Shadows.shadowOf(notification.contentIntent).getSavedIntent()
            .getStringExtra(TermuxActivity.EXTRA_SESSION_HANDLE));
        assertTrue((Shadows.shadowOf(notification.contentIntent).getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(TerminalSessionNotifications.CHANNEL_ID).getImportance());
    }

    @Test
    public void differentSessionsHaveSeparateNotificationsAndPendingIntents() {
        TerminalSession other = newSession();
        notifications.show(session, 1, "First");
        notifications.show(other, 2, "Second");

        assertEquals(2, manager.getActiveNotifications().length);
        assertNotEquals(notification(session).contentIntent, notification(other).contentIntent);
        assertEquals(context.getString(R.string.session_notification_title, 2),
            notification(other).extras.getString(Notification.EXTRA_TITLE));
        notifications.dismiss(session.mHandle);
        assertNull(notification(session));
        assertNotNull(notification(other));
    }

    @Test
    public void duplicateMessagesAreDebouncedAndUpdatesOnlyAlertAfterCooldown() {
        notifications.show(session, 1, "Ready");
        Notification first = notification(session);
        notifications.show(session, 1, "Ready");
        assertSame(first, notification(session));

        notifications.show(session, 1, "Approval required");
        assertEquals(1, manager.getActiveNotifications().length);
        assertEquals("Approval required", notification(session).extras.getString(Notification.EXTRA_TEXT));
        assertTrue((notification(session).flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        notifications.show(session, 1, "Approval required");
        assertFalse((notification(session).flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);

        notifications.dismiss(session.mHandle);
        notifications.show(session, 1, "Approval required");
        assertFalse((notification(session).flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
    }

    @Test
    public void disabledAppOrChannelDoesNotPostOrConsumeTheNextAlert() {
        Shadows.shadowOf(manager).setNotificationsEnabled(false);
        notifications.show(session, 1, "Ready");
        assertNull(notification(session));

        Shadows.shadowOf(manager).setNotificationsEnabled(true);
        notifications.show(session, 1, "Ready");
        assertNotNull(notification(session));
        assertFalse((notification(session).flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        notifications.dismiss(session.mHandle);
        NotificationChannel channel = manager.getNotificationChannel(TerminalSessionNotifications.CHANNEL_ID);
        channel.setImportance(NotificationManager.IMPORTANCE_NONE);
        manager.createNotificationChannel(channel);
        // Recreating the helper must not override the user's channel setting.
        notifications = new TerminalSessionNotifications(context);
        notifications.show(session, 1, "Ready");
        assertNull(notification(session));
    }

    @Test
    public void emptyPayloadIsIgnoredAndLongUnicodeTextIsBoundedWithoutSplittingSurrogates() {
        notifications.show(session, 1, " \000\033 ");
        assertNull(notification(session));
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 1023; i++) message.append('a');
        notifications.show(session, 1, message + "🔔tail");
        assertEquals(message.toString(), notification(session).extras.getString(Notification.EXTRA_TEXT));
        message.setLength(1022);
        notifications.show(session, 1, message + "🔔tail");
        assertEquals(message + "🔔", notification(session).extras.getString(Notification.EXTRA_TEXT));
    }

    @Test
    public void clearAllRemovesStaleSessionNotificationsAndPreservesOtherChannels() {
        notifications.show(session, 1, "Ready");
        manager.createNotificationChannel(new NotificationChannel("service", "Service", NotificationManager.IMPORTANCE_LOW));
        Notification serviceNotification = new Notification.Builder(context, "service")
            .setSmallIcon(R.drawable.ic_service_notification).build();
        manager.notify(1, serviceNotification);

        new TerminalSessionNotifications(context).clearAll();

        assertNull(notification(session));
        assertEquals(1, manager.getActiveNotifications().length);
        assertSame(serviceNotification, manager.getActiveNotifications()[0].getNotification());
    }

    private Notification notification(TerminalSession terminal) {
        return Shadows.shadowOf(manager).getNotification("terminal-session:" + terminal.mHandle, 1);
    }

    private static TerminalSession newSession() {
        return new TerminalSession("", "", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase());
    }
}
