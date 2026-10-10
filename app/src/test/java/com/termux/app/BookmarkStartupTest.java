package com.termux.app;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ListView;

import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.R;
import com.termux.app.terminal.TerminalBookmark;
import com.termux.app.terminal.TerminalBookmarkStore;
import com.termux.app.terminal.TerminalBookmarksListViewController;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "w320dp-h640dp",
    shadows = {BookmarkStartupTest.ShadowInstaller.class,
        BookmarkStartupTest.ShadowSessionService.class, BookmarkStartupTest.ShadowNativeSession.class})
public class BookmarkStartupTest {
    private TermuxActivity activity;
    private TermuxService service;
    private TerminalBookmarkStore bookmarks;

    @Before
    public void setUp() {
        Application application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("terminal_bookmarks", Context.MODE_PRIVATE).edit().clear().commit();
        // Seed the preference file before build() opens private and multi-process handles.
        assertTrue(application.getSharedPreferences(
            TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION, Context.MODE_PRIVATE)
            .edit().clear()
            .putBoolean(TermuxPreferenceConstants.TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, true)
            .putBoolean(TermuxPreferenceConstants.TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, true)
            .commit());
        TermuxAppSharedProperties.init(application).loadTermuxPropertiesFromDisk();
        bookmarks = new TerminalBookmarkStore(application);
        service = Robolectric.buildService(TermuxService.class).get();
        ReflectionHelpers.setField(service, "mShellManager", new TermuxShellManager(application));
        ShadowInstaller.calls = 0;
        ShadowInstaller.pending = null;
    }

    @Test
    public void startupWithoutBookmarksCreatesOneTerminalAfterBootstrap() {
        host(new Intent(Intent.ACTION_MAIN), false);
        connect();
        assertEquals(1, ShadowInstaller.calls);
        assertEquals(0, service.getTermuxSessionsSize());

        completeBootstrap();

        assertEquals(1, service.getTermuxSessionsSize());
        assertSame(service.getTermuxSession(0).getTerminalSession(), activity.getCurrentSession());
        assertTerminalVisible();
    }

    @Test
    public void bookmarkedStartupKeepsEmptyNavigationAndRealBackDispatchExits() {
        bookmarks.add(localBookmark(existingDirectory()));
        host(new Intent(Intent.ACTION_MAIN), false);
        input().showSoftInput(activity.getTerminalView(), 0);
        assertTrue(Shadows.shadowOf(input()).isSoftInputVisible());

        connect();
        completeBootstrap();
        activity.getTermuxTerminalViewClient().onResume();
        activity.getTermuxTerminalViewClient().onToggleSoftKeyboardRequest();
        settle();
        assertEmptyNavigation();
        assertTrue(activity.getPreferences().shouldShowTerminalToolbar());
        assertTrue(activity.getPreferences().isSoftKeyboardEnabled());

        // Programmatic closure must also keep navigation available while there is no terminal.
        activity.getDrawer().closeDrawer(Gravity.START, false);
        settle();
        assertEmptyNavigation();
        activity.findViewById(R.id.new_session_button).requestFocus();
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
        assertFalse(activity.isFinishing());
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)));
        assertTrue(activity.isFinishing());
        assertEquals(0, service.getTermuxSessionsSize());
    }

    @Test
    public void bookmarkedStartupRestoresStoredSessionAndAllowsClosingDrawer() {
        bookmarks.add(localBookmark(existingDirectory()));
        TermuxSession stored = existingSession();
        existingSession();
        host(new Intent(Intent.ACTION_MAIN), false);
        activity.getPreferences().setCurrentSession(stored.getTerminalSession().mHandle);

        connect();

        assertEquals(0, ShadowInstaller.calls);
        assertEquals(2, service.getTermuxSessionsSize());
        assertSame(stored.getTerminalSession(), activity.getCurrentSession());
        assertFalse(activity.isWaitingForSession());
        assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, activity.getDrawer().getDrawerLockMode(Gravity.START));
        assertTrue(activity.getDrawer().isDrawerOpen(Gravity.START));
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertTerminalVisible();
        assertFalse(activity.isFinishing());
    }

    @Test
    public void bookmarkClickCreatesAndSelectsExactlyOneSessionEvenWhenBootstrapFinishesLater() {
        String directory = existingDirectory();
        bookmarks.add(localBookmark(directory));
        host(new Intent(Intent.ACTION_MAIN), false);
        connect();
        activity.getTermuxTerminalViewClient().onResume();
        assertEmptyNavigation();

        clickBookmark();
        completeBootstrap();
        settle();

        assertEquals(1, createdCommands().size());
        assertEquals(directory, createdCommands().get(0).workingDirectory);
        assertEquals(1, service.getTermuxSessionsSize());
        assertSame(service.getTermuxSession(0).getTerminalSession(), activity.getCurrentSession());
        assertSame(activity.getCurrentSession().getEmulator(), activity.getTerminalView().mEmulator);
        assertTerminalVisible();
        activity.getTermuxTerminalViewClient().onToggleSoftKeyboardRequest();
        assertTrue(Shadows.shadowOf(input()).isSoftInputVisible());
    }

    @Test
    public void unavailableBookmarkDirectoryKeepsEmptyNavigation() {
        File missing = new File(existingDirectory(), "removed-bookmark-directory");
        assertFalse(missing.exists());
        bookmarks.add(localBookmark(missing.getAbsolutePath()));
        host(new Intent(Intent.ACTION_MAIN), false);
        connect();
        completeBootstrap();

        clickBookmark();
        settle();

        assertTrue(createdCommands().isEmpty());
        assertEmptyNavigation();
        assertFalse(activity.isFinishing());
    }

    @Test
    public void explicitNewSessionIntentStillCreatesFailsafeSessionWithBookmarks() {
        bookmarks.add(localBookmark(existingDirectory()));
        host(new Intent(Intent.ACTION_RUN)
            .putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, true), false);
        connect();
        completeBootstrap();

        assertEquals(1, createdCommands().size());
        assertTrue(createdCommands().get(0).isFailsafe);
        assertEquals(1, service.getTermuxSessionsSize());
        assertTerminalVisible();
    }

    @Test
    public void explicitNewSessionIntentAddsSessionAlongsideAnExistingOne() {
        bookmarks.add(localBookmark(existingDirectory()));
        TermuxSession existing = existingSession();
        host(new Intent(Intent.ACTION_RUN), false);

        connect();

        assertEquals(0, ShadowInstaller.calls);
        assertEquals(1, createdCommands().size());
        assertEquals(2, service.getTermuxSessionsSize());
        assertNotSame(existing.getTerminalSession(), activity.getCurrentSession());
        assertSame(service.getTermuxSession(1).getTerminalSession(), activity.getCurrentSession());
        assertTerminalVisible();
    }

    @Test
    public void recreatedActivityWithBookmarksStillWaitsForItsFirstSession() {
        bookmarks.add(localBookmark(existingDirectory()));
        host(new Intent(Intent.ACTION_MAIN), true);

        connect();
        completeBootstrap();
        activity.getTermuxTerminalViewClient().onResume();
        settle();

        assertEmptyNavigation();
        assertFalse(activity.isFinishing());
    }

    @Test
    public void restoredEmptyNavigationDoesNotCreateSessionWhenBookmarksWereDeleted() {
        host(new Intent(Intent.ACTION_MAIN), true);
        // onCreate restores this flag independently of whether any bookmarks still exist.
        ReflectionHelpers.setField(activity, "mIsWaitingForSession", true);
        activity.updateSessionUi();

        connect();
        completeBootstrap();
        activity.getTermuxTerminalViewClient().onResume();
        settle();

        assertTrue(bookmarks.getAll().isEmpty());
        assertTrue(createdCommands().isEmpty());
        assertEmptyNavigation();
    }

    @Test
    public void firstSessionCreatedInBackgroundBecomesUsableFromEmptyNavigation() {
        bookmarks.add(localBookmark(existingDirectory()));
        host(new Intent(Intent.ACTION_MAIN), false);
        connect();
        completeBootstrap();
        assertEmptyNavigation();

        // Model the service's session-list notification without an explicit request to switch.
        TermuxSession background = existingSession();
        activity.termuxSessionListNotifyUpdated();

        assertSame(background.getTerminalSession(), activity.getCurrentSession());
        assertFalse(activity.isWaitingForSession());
        assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, activity.getDrawer().getDrawerLockMode(Gravity.START));
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertTerminalVisible();
    }

    @Test
    public void cursorBlinkingStopsForDetachedSessionAndStartsForNextBookmarkSession() {
        bookmarks.add(localBookmark(existingDirectory()));
        TermuxSession previous = existingSession();
        host(new Intent(Intent.ACTION_MAIN), false);
        Object shared = ReflectionHelpers.getField(activity.getProperties(), "mSharedProperties");
        java.util.Map<String, Object> properties = ReflectionHelpers.getField(shared, "mMap");
        properties.put(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, 500);
        connect();
        TerminalEmulator oldEmulator = previous.getTerminalSession().getEmulator();
        assertTrue(oldEmulator.shouldCursorBeVisible());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertFalse(oldEmulator.shouldCursorBeVisible());

        service.getTermuxSessions().clear();
        activity.termuxSessionListNotifyUpdated();
        assertEmptyNavigation();
        assertTrue(oldEmulator.shouldCursorBeVisible());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertTrue(oldEmulator.shouldCursorBeVisible());

        clickBookmark();
        measureDrawer();
        TerminalEmulator currentEmulator = activity.getCurrentSession().getEmulator();
        assertNotSame(oldEmulator, currentEmulator);
        assertSame(currentEmulator, activity.getTerminalView().mEmulator);
        assertTrue(currentEmulator.shouldCursorBeVisible());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertFalse(currentEmulator.shouldCursorBeVisible());
        assertTrue(oldEmulator.shouldCursorBeVisible());
    }

    @Test
    public void recreationDoesNotReplayNewSessionIntentOrReopenDrawerOverExistingSession() {
        bookmarks.add(localBookmark(existingDirectory()));
        TermuxSession existing = existingSession();
        host(new Intent(Intent.ACTION_RUN), true);

        connect();

        assertEquals(0, ShadowInstaller.calls);
        assertTrue(createdCommands().isEmpty());
        assertEquals(1, service.getTermuxSessionsSize());
        assertSame(existing.getTerminalSession(), activity.getCurrentSession());
        assertTerminalVisible();
    }

    private void host(Intent intent, boolean recreated) {
        // Exercise the real binding, clients and views without starting the foreground service.
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class, intent);
        activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(activity);
        assertNotNull(preferences);
        assertTrue(preferences.shouldShowTerminalToolbar());
        ReflectionHelpers.setField(activity, "mPreferences", preferences);
        ReflectionHelpers.setField(activity, "mProperties", TermuxAppSharedProperties.getProperties());
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        ReflectionHelpers.setField(activity, "mIsActivityRecreated", recreated);
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        TermuxTerminalSessionActivityClient client = new TermuxTerminalSessionActivityClient(activity);
        ReflectionHelpers.setField(activity, "mTermuxTerminalSessionActivityClient", client);
        activity.mTermuxTerminalViewClient = new TermuxTerminalViewClient(activity, client);
        activity.mTerminalView.setTerminalViewClient(activity.mTermuxTerminalViewClient);
        activity.mTerminalView.setTextSize(20);
        // Legacy Robolectric graphics have no usable font metrics for terminal resize.
        Object renderer = ReflectionHelpers.getField(activity.mTerminalView, "mRenderer");
        ReflectionHelpers.setField(renderer, "mFontWidth", 10f);
        ReflectionHelpers.setField(renderer, "mFontLineSpacing", 20);
        activity.getTerminalToolbar().setVisibility(View.VISIBLE);
        ReflectionHelpers.setField(activity, "mBookmarkStore", bookmarks);
        ReflectionHelpers.setField(activity, "mBookmarksController",
            new TerminalBookmarksListViewController(activity, bookmarks, client::openBookmark));
        ReflectionHelpers.callInstanceMethod(activity, "setAdaptiveDrawerLayout");
        controller.visible();
        measureDrawer();
    }

    private void connect() {
        IBinder binder = ReflectionHelpers.getField(service, "mBinder");
        activity.onServiceConnected(new ComponentName(activity, TermuxService.class), binder);
        measureDrawer();
    }

    private void clickBookmark() {
        ListView list = activity.findViewById(R.id.terminal_bookmarks_list);
        assertEquals(1, list.getAdapter().getCount());
        View row = list.getAdapter().getView(0, null, list);
        assertTrue(list.performItemClick(row, 0, list.getAdapter().getItemId(0)));
    }

    private void assertEmptyNavigation() {
        assertEquals(0, service.getTermuxSessionsSize());
        assertNull(activity.getCurrentSession());
        assertNull(activity.getTerminalView().mEmulator);
        assertTrue(activity.isWaitingForSession());
        assertEquals(DrawerLayout.LOCK_MODE_LOCKED_OPEN, activity.getDrawer().getDrawerLockMode(Gravity.START));
        assertTrue(activity.getDrawer().isDrawerOpen(Gravity.START));
        assertTrue(activity.findViewById(R.id.left_drawer).isShown());
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());
        assertFalse(Shadows.shadowOf(input()).isSoftInputVisible());
    }

    private void assertTerminalVisible() {
        assertFalse(activity.isWaitingForSession());
        assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, activity.getDrawer().getDrawerLockMode(Gravity.START));
        assertFalse(activity.getDrawer().isDrawerVisible(Gravity.START));
        assertEquals(View.VISIBLE, activity.getTerminalToolbar().getVisibility());
        assertNotNull(activity.getCurrentSession());
    }

    private InputMethodManager input() {
        return (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private void settle() {
        // Manual hosts do not receive rendering frames, so advance the real drawer animation.
        for (int frame = 0; frame < 64; frame++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            activity.getDrawer().computeScroll();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer();
    }

    private void measureDrawer() {
        View drawer = activity.getDrawer();
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(640 * density);
        drawer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        drawer.layout(0, 0, width, height);
    }

    private static void completeBootstrap() {
        assertNotNull(ShadowInstaller.pending);
        Runnable pending = ShadowInstaller.pending;
        ShadowInstaller.pending = null;
        pending.run();
    }

    private List<ExecutionCommand> createdCommands() {
        ShadowSessionService shadow = Shadow.extract(service);
        return shadow.created;
    }

    private TermuxSession existingSession() {
        ExecutionCommand command = new ExecutionCommand();
        command.workingDirectory = existingDirectory();
        return syntheticSession(service, command);
    }

    private static String existingDirectory() {
        return RuntimeEnvironment.getApplication().getFilesDir().getAbsolutePath();
    }

    private static TerminalBookmark localBookmark(String directory) {
        return new TerminalBookmark("saved", "Saved directory", "local", "", Collections.emptyList(), directory);
    }

    private static TermuxSession syntheticSession(TermuxService service, ExecutionCommand command) {
        TermuxShellManager manager = ReflectionHelpers.getField(service, "mShellManager");
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession terminal = new TerminalSession("", command.workingDirectory,
            new String[0], new String[0], 100, client);
        ReflectionHelpers.setField(terminal, "mShellPid", 1234);
        ReflectionHelpers.setField(terminal, "mEmulator", new TerminalEmulator(terminal, 80, 24, 10, 20, 100, client));
        command.setState(ExecutionCommand.ExecutionState.EXECUTING);
        TermuxSession session = ReflectionHelpers.callConstructor(TermuxSession.class,
            ClassParameter.from(TerminalSession.class, terminal),
            ClassParameter.from(ExecutionCommand.class, command),
            ClassParameter.from(TermuxSession.TermuxSessionClient.class,
                (TermuxSession.TermuxSessionClient) manager.mTermuxSessions::remove),
            ClassParameter.from(boolean.class, false));
        manager.mTermuxSessions.add(session);
        return session;
    }

    @Implements(TermuxInstaller.class)
    public static class ShadowInstaller {
        static int calls;
        static Runnable pending;

        @Implementation
        protected static void setupBootstrapIfNeeded(Activity activity, Runnable whenDone) {
            calls++;
            pending = whenDone;
        }
    }

    @Implements(TermuxService.class)
    public static class ShadowSessionService {
        @RealObject private TermuxService service;
        final List<ExecutionCommand> created = new ArrayList<>();

        @Implementation
        protected TermuxSession createTermuxSession(ExecutionCommand command) {
            created.add(command);
            return syntheticSession(service, command);
        }
    }

    @Implements(TerminalSession.class)
    public static class ShadowNativeSession {
        @RealObject private TerminalSession session;

        @Implementation
        protected void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
            // Keep emulator attachment and resizing real; only skip the native PTY ioctl.
            session.getEmulator().resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }
}
