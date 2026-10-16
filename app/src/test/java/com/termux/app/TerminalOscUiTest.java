package com.termux.app;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;

import com.google.android.material.color.MaterialColors;
import com.termux.R;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.terminal.TerminalSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;
import java.util.Collections;

import static org.junit.Assert.*;

/** Android host behavior after libghostty has parsed the OSC callbacks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "en-w320dp-h640dp")
public class TerminalOscUiTest {
    private TermuxActivity activity;
    private TermuxTerminalSessionActivityClient client;
    private TerminalSession current;
    private TerminalSession other;
    private ShadowNotificationManager notifications;
    private ProgressBar progress;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        activity.mTerminalView.setVisibility(View.GONE);
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(activity);
        properties.loadTermuxPropertiesFromDisk();
        ReflectionHelpers.setField(activity, "mProperties", properties);
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        client = new TermuxTerminalSessionActivityClient(activity) {
            @Override public void termuxSessionListNotifyUpdated() {
                if (activity.mDrawerAdapter != null) activity.mDrawerAdapter.refreshSessions();
            }
        };
        ReflectionHelpers.setField(activity, "mTermuxTerminalSessionActivityClient", client);
        activity.mTermuxTerminalViewClient = new TermuxTerminalViewClient(activity, client);
        activity.mTerminalView.setTerminalViewClient(activity.mTermuxTerminalViewClient);
        current = session("Current");
        other = session("Other");
        // The hidden, zero-sized view can exercise real attachment without starting a PTY.
        activity.mTerminalView.attachSession(current);
        notifications = Shadows.shadowOf((NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE));
        progress = activity.findViewById(R.id.terminal_progress_bar);
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp")
    public void notificationsUseCurrentVisibleSessionAndUpstreamChannelOptions() {
        advance(1000);
        other.onDesktopNotification("Background", "Ignored");
        ReflectionHelpers.setField(activity, "mIsVisible", false);
        current.onDesktopNotification("Hidden", "Ignored");
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        current.onDesktopNotification("Empty", "\u0001\u007f");
        assertTrue(notifications.getAllNotifications().isEmpty());

        current.onDesktopNotification("Build\u0007", "<b>Done 😀</b>\u001b\u0080");
        assertEquals(1, notifications.getAllNotifications().size());
        Notification notification = notifications.getAllNotifications().get(0);
        assertEquals("Build", notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("<b>Done 😀</b>", notification.extras.getString(Notification.EXTRA_TEXT));
        assertEquals("<b>Done 😀</b>", notification.extras.getString(Notification.EXTRA_BIG_TEXT));
        assertEquals("termux_osc_notification_channel", notification.getChannelId());
        NotificationChannel channel = ((NotificationManager) activity.getSystemService(
            Context.NOTIFICATION_SERVICE)).getNotificationChannel(notification.getChannelId());
        assertEquals("Terminal notifications", channel.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.getImportance());
        assertEquals(Notification.PRIORITY_DEFAULT, notification.priority);
        assertEquals(0, notification.defaults);
        assertNull(notification.deleteIntent);
        assertEquals(R.drawable.ic_service_notification, notification.getSmallIcon().getResId());
        assertTrue((notification.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN));
        assertTrue(notification.contentIntent.isImmutable());
        Intent target = Shadows.shadowOf(notification.contentIntent).getSavedIntent();
        assertEquals(TermuxActivity.class.getName(), target.getComponent().getClassName());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, target.getFlags());
        assertNull(target.getExtras());
    }

    @Test
    public void notificationsUseUpstreamBurstAndDuplicateWindows() {
        advance(1000);
        current.onDesktopNotification("", "First");
        assertEquals(1, notifications.getAllNotifications().size());
        assertEquals(TermuxConstants.TERMUX_APP_NAME,
            notifications.getAllNotifications().get(0).extras.getString(Notification.EXTRA_TITLE));
        current.onDesktopNotification("", "Second");
        assertEquals(1, notifications.getAllNotifications().size());
        advance(1000);
        current.onDesktopNotification("", "First");
        assertEquals(1, notifications.getAllNotifications().size());
        advance(4000);
        current.onDesktopNotification("", "First");
        assertEquals(2, notifications.getAllNotifications().size());
        advance(1000);
        current.onDesktopNotification("", "Second");
        assertEquals(3, notifications.getAllNotifications().size());
    }

    @Test
    @Config(shadows = SessionWithTitle.class)
    public void emptyNotificationTitleUsesTheUnmodifiedSessionTitle() {
        advance(1000);
        current.onDesktopNotification("\u0007", "Use the session title");
        assertEquals(SessionWithTitle.TITLE,
            notifications.getAllNotifications().get(0).extras.getString(Notification.EXTRA_TITLE));
    }

    @Test
    public void notificationSanitizationUsesTheUpstream4096UnitLimit() {
        // Test the upstream sanitizer before Android applies its own field limits.
        String prefix = String.join("", Collections.nCopies(4095, "x"));
        assertEquals(prefix, sanitizeNotification(prefix + "😀"));
        String fitting = String.join("", Collections.nCopies(4094, "x")) + "😀";
        assertEquals(fitting, sanitizeNotification(fitting + "tail"));
        assertEquals("a\n\tb", sanitizeNotification("\u0001a\n\t\u007f\u0080b"));
        assertNull(sanitizeNotification(null));
    }

    @Test
    public void notificationsCompareSanitizedInputBeforeAndroidDisplayTruncation() {
        advance(1000);
        String prefix = String.join("", Collections.nCopies(4096, "x"));
        current.onDesktopNotification("Build", prefix + "first tail");
        assertEquals(1, notifications.getAllNotifications().size());
        String platformPrefix = prefix.substring(0, 1024);
        Notification notification = notifications.getAllNotifications().get(0);
        assertEquals(platformPrefix, notification.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(platformPrefix, notification.extras.getString(Notification.EXTRA_BIG_TEXT));
        advance(1000);
        current.onDesktopNotification("Build", prefix + "different tail");
        assertEquals(1, notifications.getAllNotifications().size());

        advance(1000);
        current.onDesktopNotification("Build", platformPrefix + "second message");
        assertEquals(2, notifications.getAllNotifications().size());
        advance(1000);
        current.onDesktopNotification("Build", platformPrefix + "third message");
        assertEquals(3, notifications.getAllNotifications().size());
    }

    @Test
    public void progressSupportsDeterminateErrorIndeterminatePausedAndClear() {
        current.onProgressReport(1, 37);
        assertEquals(View.VISIBLE, progress.getVisibility());
        assertFalse(progress.isIndeterminate());
        assertEquals(37, progress.getProgress());
        assertTrue(progress.getContentDescription().toString().contains("37%"));
        other.onProgressReport(0, -1);
        assertEquals(View.VISIBLE, progress.getVisibility());

        current.onProgressReport(2, 140);
        assertEquals(100, progress.getProgress());
        assertEquals(MaterialColors.getColor(progress, com.google.android.material.R.attr.colorError),
            progress.getProgressTintList().getDefaultColor());
        current.onProgressReport(4, -1);
        assertFalse(progress.isIndeterminate());
        assertEquals(100, progress.getProgress());
        assertTrue(progress.getContentDescription().toString().contains("Paused"));
        current.onProgressReport(3, -1);
        assertTrue(progress.isIndeterminate());
        current.onProgressReport(1, -1);
        assertTrue(progress.isIndeterminate());
        current.onProgressReport(0, -1);
        assertEquals(View.GONE, progress.getVisibility());
    }

    @Test
    public void progressTimeoutRestartsOnReportsAndStopsWithTheActivity() {
        assertEquals(View.GONE, progress.getVisibility());
        ReflectionHelpers.setField(activity, "mIsVisible", false);
        current.onProgressReport(1, 1);
        assertEquals(View.GONE, progress.getVisibility());
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        current.onProgressReport(1, 20);
        advance(14000);
        current.onProgressReport(1, 30);
        advance(14000);
        assertEquals(View.VISIBLE, progress.getVisibility());
        advance(1000);
        assertEquals(View.GONE, progress.getVisibility());
        current.onProgressReport(1, 40);
        current.onProgressReport(3, -1);
        client.onStop();
        assertEquals(View.GONE, progress.getVisibility());
        assertFalse(progress.isIndeterminate());
        assertEquals(0, progress.getProgress());
        advance(15000);
        assertEquals(View.GONE, progress.getVisibility());
    }

    @Test
    public void pausedProgressRetainsPercentageAcrossIndeterminateUpdatesUntilClear() {
        current.onProgressReport(1, 60);
        for (int state : new int[]{3, 1, 2}) {
            current.onProgressReport(state, -1);
            assertTrue(progress.isIndeterminate());
            current.onProgressReport(4, -1);
            assertFalse(progress.isIndeterminate());
            assertEquals(60, progress.getProgress());
        }
        current.onProgressReport(3, -1);
        current.onProgressReport(0, -1);
        assertEquals(View.GONE, progress.getVisibility());
        assertFalse(progress.isIndeterminate());
        assertEquals(0, progress.getProgress());
        assertNull(progress.getContentDescription());
        current.onProgressReport(4, -1);
        assertEquals(0, progress.getProgress());
    }

    @Test
    public void omittedPausedProgressNeverUsesAnotherSessionsPercentage() {
        current.onProgressReport(1, 60);
        client.setCurrentSession(current);
        assertEquals(View.VISIBLE, progress.getVisibility());
        assertEquals(60, progress.getProgress());

        client.setCurrentSession(other);
        assertSame(other, activity.getCurrentSession());
        assertEquals(View.GONE, progress.getVisibility());
        current.onProgressReport(1, 80);
        other.onProgressReport(4, -1); // Valid OSC 9;4;4 with no percentage.
        assertFalse(progress.isIndeterminate());
        assertEquals(0, progress.getProgress());
        assertTrue(progress.getContentDescription().toString().contains("0%"));

        other.onProgressReport(1, 25);
        other.onProgressReport(3, -1);
        client.setCurrentSession(current);
        assertSame(current, activity.getCurrentSession());
        assertEquals(View.GONE, progress.getVisibility());
        current.onProgressReport(4, -1);
        assertFalse(progress.isIndeterminate());
        assertEquals(0, progress.getProgress());
    }

    @Test
    public void finishingCurrentSessionClearsStoredIndeterminateProgress() {
        current.onProgressReport(1, 70);
        current.onProgressReport(3, -1);
        client.onSessionFinished(current);
        assertEquals(View.GONE, progress.getVisibility());
        assertFalse(progress.isIndeterminate());
        assertEquals(0, progress.getProgress());
        assertNull(progress.getContentDescription());
        advance(15000);
        assertEquals(View.GONE, progress.getVisibility());
    }

    private TerminalSession session(String name) {
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0], 100, client);
        session.mSessionName = name;
        return session;
    }

    private static String sanitizeNotification(String value) {
        return ReflectionHelpers.callStaticMethod(TermuxTerminalSessionActivityClient.class,
            "sanitizeOscNotificationText", ClassParameter.from(String.class, value));
    }

    private static void advance(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    @Implements(value = TerminalSession.class, isInAndroidSdk = false)
    public static class SessionWithTitle {
        static final String TITLE = "Session\u0007\u007f";

        @Implementation
        protected String getTitle() { return TITLE; }
    }
}
