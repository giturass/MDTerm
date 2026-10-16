package com.termux.app;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.termux.R;
import com.termux.app.terminal.TerminalBookmarkStore;
import com.termux.app.terminal.TerminalDrawerAdapter;
import com.termux.app.terminal.TerminalDrawerRecyclerView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.termux.terminal.ProgramStatusStore.*;
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
    private final List<TermuxSession> sessions = new ArrayList<>();

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
    public void notificationsUseCurrentVisibleSessionAndSanitizedPlainText() {
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
        assertTrue((notification.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertTrue(notification.contentIntent.isImmutable());
        assertEquals(TermuxActivity.class.getName(),
            Shadows.shadowOf(notification.contentIntent).getSavedIntent().getComponent().getClassName());
    }

    @Test
    public void notificationsThrottleBurstsAndDuplicatesWithoutDroppingFirstRequest() {
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
    public void notificationLimitsKeepUnicodeCodePointsWhole() {
        String prefix = String.join("", java.util.Collections.nCopies(4095, "x"));
        current.onDesktopNotification(prefix + "😀", prefix + "😀");
        Notification notification = notifications.getAllNotifications().get(0);
        assertEquals(prefix, notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals(prefix, notification.extras.getString(Notification.EXTRA_TEXT));
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

    @Test
    public void cardsShowLatestTaskStateAndDetailsWithoutChangingOtherSessionSelection() {
        makeDrawer();
        current.onProgramStatusReport(WORKING, KIND_NONE, 25, "build", "cargo", "Compile", "");
        current.onProgramStatusReport(BLOCKED, KIND_QUESTION, -1, "build/test", "", "Continue?", "<b>Yes 😀</b>");
        measureDrawer();
        View card = card(current);
        TextView status = card.findViewById(R.id.session_program_status);
        assertEquals(View.VISIBLE, status.getVisibility());
        assertEquals(2, status.getMaxLines());
        assertTrue(status.getText().toString().startsWith("Blocked · Answer needed · cargo · build/test"));
        assertTrue(status.getText().toString().contains("25%"));
        assertTrue(card.getHeight() > Math.round(64 * card.getResources().getDisplayMetrics().density));
        assertTrue(status.getContentDescription().toString().contains("Task status"));

        card.findViewById(R.id.session_card_content).performClick();
        AlertDialog details = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(details);
        String message = ((TextView) details.findViewById(android.R.id.message)).getText().toString();
        assertTrue(message.contains("<b>Yes 😀</b>"));
        assertTrue(message.contains("Compile"));
        assertSame(current, activity.getCurrentSession());
        details.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

        card(other).findViewById(R.id.session_card_content).performClick();
        assertSame(other, activity.getCurrentSession());
    }

    @Test
    public void cardUpdatesDetectStatusChangesAndDismissOnlyFinishedTasks() {
        makeDrawer();
        current.onProgramStatusReport(DONE, KIND_NONE, -1, "finished", "", "Built", "");
        current.onProgramStatusReport(ERROR, KIND_NONE, -1, "failed", "", "Failed", "");
        current.onProgramStatusReport(WORKING, KIND_NONE, 5, "active", "", "Running", "");
        measureDrawer();
        current.onProgramStatusReport(WORKING, KIND_NONE, 80, "active", "", "Running", "");
        measureDrawer();
        TextView status = card(current).findViewById(R.id.session_program_status);
        assertTrue(status.getText().toString().contains("80%"));
        card(current).findViewById(R.id.session_card_content).performClick();
        AlertDialog details = (AlertDialog) ShadowDialog.getLatestDialog();
        details.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        measureDrawer();
        assertEquals(1, current.getProgramStatuses().size());
        assertEquals("active", current.getProgramStatuses().get(0).id);
        assertFalse(status.getText().toString().contains("Built"));
        current.onProgramStatusPrompt();
        measureDrawer();
        assertEquals(View.GONE, status.getVisibility());
        assertEquals(View.GONE, card(other).findViewById(R.id.session_program_status).getVisibility());
    }

    private TerminalSession session(String name) {
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0], 100, client);
        session.mSessionName = name;
        return session;
    }

    private void makeDrawer() {
        activity.getSharedPreferences("terminal_bookmarks", 0).edit().clear().commit();
        for (TerminalSession terminal : new TerminalSession[]{current, other}) {
            sessions.add(ReflectionHelpers.callConstructor(TermuxSession.class,
                ClassParameter.from(TerminalSession.class, terminal),
                ClassParameter.from(ExecutionCommand.class, new ExecutionCommand()),
                ClassParameter.from(TermuxSession.TermuxSessionClient.class,
                    (TermuxSession.TermuxSessionClient) sessions::remove),
                ClassParameter.from(boolean.class, false)));
        }
        activity.mDrawerAdapter = new TerminalDrawerAdapter(activity, new TerminalBookmarkStore(activity), item -> {});
        activity.mDrawerAdapter.setSessions(sessions);
    }

    private View card(TerminalSession session) {
        TerminalDrawerRecyclerView list = activity.findViewById(R.id.terminal_drawer_list);
        int position = activity.mDrawerAdapter.findSessionPosition(session);
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        assertNotNull(holder);
        return holder.itemView;
    }

    private void measureDrawer() {
        View drawer = activity.findViewById(R.id.left_drawer);
        float density = drawer.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(640 * density);
        for (int i = 0; i < 2; i++) {
            drawer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            drawer.layout(0, 0, width, height);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static void advance(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
}
