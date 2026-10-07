package com.termux.app;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.termux.R;
import com.termux.app.terminal.TerminalBookmark;
import com.termux.app.terminal.TerminalBookmarkStore;
import com.termux.app.terminal.TerminalBookmarksListViewController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN-w320dp-h640dp")
public class SessionCollapseTest {
    @Test
    public void collapsePreservesBottomActionAndRestoresAcrossActivityInstances() {
        TermuxActivity activity = host(true);
        View drawer = activity.findViewById(R.id.left_drawer);
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        View toggle = activity.findViewById(R.id.terminal_sessions_toggle);
        measure(drawer, 640);
        int actionTop = activity.findViewById(R.id.terminal_drawer_actions).getTop();
        toggle.performClick();
        measure(drawer, 640);
        assertEquals(View.INVISIBLE, sessions.getVisibility());
        assertEquals(actionTop, activity.findViewById(R.id.terminal_drawer_actions).getTop());
        assertEquals(activity.getString(R.string.action_expand_sessions), toggle.getContentDescription());
        ((ArrayAdapter<?>) sessions.getAdapter()).notifyDataSetChanged();
        assertEquals(View.INVISIBLE, sessions.getVisibility());

        TermuxActivity restored = host(false);
        ListView restoredSessions = restored.findViewById(R.id.terminal_sessions_list);
        assertEquals(View.INVISIBLE, restoredSessions.getVisibility());
        restored.findViewById(R.id.terminal_sessions_toggle).performClick();
        measure(restored.findViewById(R.id.left_drawer), 640);
        assertEquals(View.VISIBLE, restoredSessions.getVisibility());
        assertTrue(restoredSessions.getChildCount() > 0);
        assertFalse(restored.getSharedPreferences("terminal_sessions", 0).getBoolean("collapsed", true));
    }

    @Test
    public void compactDrawerKeepsTitleAndExpandControlAvailable() {
        TermuxActivity activity = host(true);
        ReflectionHelpers.callInstanceMethod(activity, "setAdaptiveDrawerLayout");
        View drawer = activity.findViewById(R.id.left_drawer);
        View toggle = activity.findViewById(R.id.terminal_sessions_toggle);
        toggle.performClick();
        for (int height : new int[]{320, 640, 320}) {
            measure(drawer, height);
            measure(drawer, height);
            assertEquals(View.VISIBLE, activity.findViewById(R.id.terminal_sessions_header).getVisibility());
            assertEquals(View.VISIBLE, toggle.getVisibility());
            assertTrue(toggle.getHeight() > 0);
        }
        toggle.performClick();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.terminal_sessions_list).getVisibility());
    }

    @Test
    public void sessionTitleHasSameGapToFirstCardAsBookmarks() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller, true);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(new TerminalBookmark("first", "First", "local", "", Collections.emptyList(), "/tmp"));
        TerminalBookmarksListViewController bookmarks =
            new TerminalBookmarksListViewController(activity, store, item -> {});
        controller.visible();
        activity.getDrawer().openDrawer(Gravity.START, false);
        ViewGroup drawer = activity.getDrawer();
        measure(drawer, 640);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measure(drawer, 640);
        ListView bookmarkList = activity.findViewById(R.id.terminal_bookmarks_list);
        ListView sessionList = activity.findViewById(R.id.terminal_sessions_list);
        assertNotNull(bookmarkList.getChildAt(0));
        assertNotNull(sessionList.getChildAt(0));
        int bookmarkGap = bounds(drawer, bookmarkList.getChildAt(0)).top
            - bounds(drawer, activity.findViewById(R.id.terminal_bookmarks_header)).bottom;
        int sessionGap = bounds(drawer, sessionList.getChildAt(0)).top
            - bounds(drawer, activity.findViewById(R.id.terminal_sessions_header)).bottom;
        assertEquals(bookmarkGap, sessionGap);
    }

    private static TermuxActivity host(boolean clearPreferences) {
        return host(Robolectric.buildActivity(TermuxActivity.class), clearPreferences);
    }

    private static TermuxActivity host(ActivityController<TermuxActivity> controller, boolean clearPreferences) {
        TermuxActivity activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        if (clearPreferences) {
            activity.getSharedPreferences("terminal_sessions", 0).edit().clear().commit();
            activity.getSharedPreferences("terminal_bookmarks", 0).edit().clear().commit();
        }
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        sessions.setAdapter(new ArrayAdapter<String>(activity, R.layout.item_terminal_sessions_list,
            Collections.singletonList("Session")) {
            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                return activity.getLayoutInflater().inflate(R.layout.item_terminal_sessions_list, parent, false);
            }
        });
        ReflectionHelpers.callInstanceMethod(activity, "setSessionsCollapseToggle");
        return activity;
    }

    private static void measure(View view, int heightDp) {
        float density = view.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(heightDp * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static Rect bounds(ViewGroup parent, View view) {
        Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
        parent.offsetDescendantRectToMyCoords(view, bounds);
        return bounds;
    }
}
