package com.termux.app;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.terminal.TerminalBookmark;
import com.termux.app.terminal.TerminalBookmarkStore;
import com.termux.app.terminal.TerminalDrawerAdapter;
import com.termux.app.terminal.TerminalDrawerRecyclerView;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN-w320dp-h640dp")
public class SessionDrawerTest {
    @Test
    public void sessionsStayVisibleWhenRefreshingAndRestoringOldPreferences() {
        TermuxActivity activity = host(true, 1);
        View drawer = activity.findViewById(R.id.left_drawer);
        measure(drawer, 320, 640);
        int actionTop = activity.findViewById(R.id.terminal_drawer_actions).getTop();
        assertFalse(activity.findViewById(R.id.terminal_sessions_header).performClick());
        adapter(activity).refreshSessions();
        measure(drawer, 320, 640);
        assertEquals(View.VISIBLE, list(activity).getVisibility());
        assertNotNull(row(list(activity), 2));
        assertEquals(actionTop, activity.findViewById(R.id.terminal_drawer_actions).getTop());

        activity.getSharedPreferences("terminal_sessions", 0).edit().putBoolean("collapsed", true).commit();
        TermuxActivity restored = host(false, 1);
        measure(restored.findViewById(R.id.left_drawer), 320, 640);
        assertEquals(View.VISIBLE, list(restored).getVisibility());
        assertNotNull(row(list(restored), 2));
    }

    @Test
    public void compactDrawerKeepsTitleSessionsAndNewSessionActionAvailable() {
        TermuxActivity activity = host(true, 1);
        ReflectionHelpers.callInstanceMethod(activity, "setAdaptiveDrawerLayout");
        ViewGroup drawer = activity.findViewById(R.id.left_drawer);
        TerminalDrawerRecyclerView list = list(activity);
        View action = activity.findViewById(R.id.new_session_button);
        for (int height : new int[]{320, 640, 320}) {
            measure(drawer, 320, height);
            list.scrollBy(0, Integer.MAX_VALUE);
            measure(drawer, 320, height);
            assertEquals(View.VISIBLE, activity.findViewById(R.id.terminal_sessions_header).getVisibility());
            Rect card = bounds(drawer, row(list, 2));
            assertTrue(card.top >= bounds(drawer, list).top);
            assertTrue(card.bottom <= bounds(drawer, action).top);
            assertEquals(dp(drawer, 56), action.getWidth());
            assertEquals(dp(drawer, 56), action.getHeight());
            assertEquals(dp(drawer, 16), drawer.getHeight() - bounds(drawer, action).bottom);
        }
    }

    @Test
    public void bookmarksAndSessionsScrollTogetherWhileHeaderAndFabStayFixed() {
        TermuxActivity activity = host(true, 8);
        addBookmarks(activity, 8);
        ViewGroup drawer = activity.findViewById(R.id.left_drawer);
        TerminalDrawerRecyclerView list = list(activity);
        View header = activity.findViewById(R.id.terminal_drawer_header);
        View fab = activity.findViewById(R.id.new_session_button);
        measure(drawer, 320, 640);
        assertEquals(18, adapter(activity).getItemCount());
        assertTrue(list.getChildCount() < adapter(activity).getItemCount());
        assertTrue(list.canScrollVertically(1));
        assertCardSizesAndSpacing(list);
        Rect fixedHeader = bounds(drawer, header), fixedFab = bounds(drawer, fab);

        // Both card types and the section heading must move in the same viewport.
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(9, dp(list, 100));
        measure(drawer, 320, 640);
        int bookmarkTop = row(list, 8).getTop(), titleTop = row(list, 9).getTop();
        int sessionTop = row(list, 10).getTop();
        list.scrollBy(0, dp(list, 24));
        assertEquals(bookmarkTop - dp(list, 24), row(list, 8).getTop());
        assertEquals(titleTop - dp(list, 24), row(list, 9).getTop());
        assertEquals(sessionTop - dp(list, 24), row(list, 10).getTop());
        assertEquals(fixedHeader, bounds(drawer, header));
        assertEquals(fixedFab, bounds(drawer, fab));
        assertCardSizesAndSpacing(list);

        list.scrollBy(0, Integer.MAX_VALUE);
        Rect lastSession = bounds(drawer, row(list, 17));
        assertTrue("The last card must scroll fully above the floating action", lastSession.bottom <= fixedFab.top);
        assertTrue(lastSession.top >= bounds(drawer, list).top);
        assertFalse(list.canScrollVertically(1));
        assertEquals(fixedHeader, bounds(drawer, header));
        assertEquals(fixedFab, bounds(drawer, fab));
    }

    @Test
    public void drawerFillsContentAndActionsFollowRightEdgeWhenViewportChanges() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller, true, 2);
        addBookmarks(activity, 2);
        controller.visible();
        activity.getDrawer().openDrawer(Gravity.START, false);
        ViewGroup content = activity.getDrawer(), drawer = activity.findViewById(R.id.left_drawer);
        View fab = activity.findViewById(R.id.new_session_button);
        View settings = activity.findViewById(R.id.settings_button);
        TerminalDrawerRecyclerView list = list(activity);
        int previousWidth = 0, previousSettingsRight = 0;
        for (int[] size : new int[][]{{320, 640}, {600, 320}, {360, 720}, {320, 320}}) {
            measure(content, size[0], size[1]);
            assertEquals(content.getWidth(), drawer.getWidth());
            assertEquals(0, bounds(content, drawer).left);
            assertEquals(content.getWidth(), bounds(content, drawer).right);
            Rect action = bounds(drawer, fab);
            assertEquals(dp(drawer, 56), action.width());
            assertEquals(dp(drawer, 56), action.height());
            assertEquals(dp(drawer, 16), drawer.getWidth() - action.right);
            assertEquals(dp(drawer, 16), drawer.getHeight() - action.bottom);
            int settingsRight = bounds(drawer, settings).right;
            if (previousWidth != 0) {
                assertEquals(drawer.getWidth() - previousWidth, settingsRight - previousSettingsRight);
            }
            previousWidth = drawer.getWidth();
            previousSettingsRight = settingsRight;
            assertCardSizesAndSpacing(list);
            list.scrollBy(0, Integer.MAX_VALUE);
            assertCardSizesAndSpacing(list);
            Rect card = bounds(drawer, row(list, 5));
            assertEquals(dp(drawer, 20), card.left);
            assertEquals(dp(drawer, 20), drawer.getWidth() - card.right);
            assertTrue(card.bottom <= action.top);
            View menu = row(list, 5).findViewById(R.id.session_menu_button);
            assertEquals(bounds(drawer, settings).centerX(), bounds(drawer, menu).centerX());
        }
    }

    @Test
    public void sectionTitlesKeepTheSameGapAndCardDividersFollowInsertionsAndDeletions() {
        TermuxActivity activity = host(true, 1);
        addBookmarks(activity, 1);
        ViewGroup drawer = activity.findViewById(R.id.left_drawer);
        TerminalDrawerRecyclerView list = list(activity);
        measure(drawer, 320, 640);
        int bookmarkGap = bounds(drawer, row(list, 1)).top
            - bounds(drawer, activity.findViewById(R.id.terminal_bookmarks_header)).bottom;
        int sessionGap = bounds(drawer, row(list, 3)).top
            - bounds(drawer, activity.findViewById(R.id.terminal_sessions_header)).bottom;
        assertEquals(bookmarkGap, sessionGap);

        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark(1));
        adapter(activity).refreshBookmarks();
        measure(drawer, 320, 640);
        assertEquals(dp(list, 8), row(list, 2).getTop() - row(list, 1).getBottom());
        store.delete("bookmark-1");
        adapter(activity).refreshBookmarks();
        measure(drawer, 320, 640);
        assertEquals(0, row(list, 2).getTop() - row(list, 1).getBottom());
    }

    @Test
    public void hundredsOfCardsOnlyCreateViewsForTheViewportAndUnchangedRefreshesDoNotRebind() {
        TermuxActivity activity = host(true, 200);
        addBookmarks(activity, 200);
        View drawer = activity.findViewById(R.id.left_drawer);
        TerminalDrawerRecyclerView list = list(activity);
        TerminalDrawerAdapter adapter = adapter(activity);
        measure(drawer, 320, 640);
        assertEquals(402, adapter.getItemCount());
        assertTrue("Only visible cards should be inflated", list.getChildCount() <= 12);
        list.scrollToPosition(401);
        measure(drawer, 320, 640);
        assertNotNull(row(list, 401));
        assertTrue("Scrolling must keep attached cards bounded", list.getChildCount() <= 12);

        int[] notifications = {0, 0};
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { notifications[0]++; }
            @Override public void onItemRangeChanged(int start, int count, Object payload) {
                assertNotNull("Content updates must keep the existing holder", payload);
                notifications[1] += count;
            }
        });
        View anchor = list.getChildAt(0);
        int anchorTop = anchor.getTop();
        long anchorId = list.getChildViewHolder(anchor).getItemId();
        adapter.refreshSessions();
        adapter.refreshBookmarks();
        measure(drawer, 320, 640);
        assertArrayEquals(new int[]{0, 0}, notifications);
        TerminalSession session = (TerminalSession) row(list, 401).getTag();
        session.mSessionName = "Changed last session";
        adapter.refreshSessions();
        measure(drawer, 320, 640);
        assertArrayEquals(new int[]{0, 1}, notifications);
        assertSame(anchor, list.findViewHolderForItemId(anchorId).itemView);
        assertEquals(anchorTop, anchor.getTop());
    }

    private static TermuxActivity host(boolean clearPreferences, int sessionCount) {
        return host(Robolectric.buildActivity(TermuxActivity.class), clearPreferences, sessionCount);
    }

    private static TermuxActivity host(ActivityController<TermuxActivity> controller, boolean clearPreferences,
                                       int sessionCount) {
        TermuxActivity activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        if (clearPreferences) {
            activity.getSharedPreferences("terminal_sessions", 0).edit().clear().commit();
            activity.getSharedPreferences("terminal_bookmarks", 0).edit().clear().commit();
        }
        activity.mDrawerAdapter = new TerminalDrawerAdapter(activity, new TerminalBookmarkStore(activity), item -> {});
        List<TermuxSession> sessions = new ArrayList<>();
        for (int i = 0; i < sessionCount; i++) {
            TerminalSession terminal = new TerminalSession("", "", new String[0], new String[0], 100,
                new TermuxTerminalSessionClientBase());
            terminal.mSessionName = "Session " + (i + 1);
            sessions.add(ReflectionHelpers.callConstructor(TermuxSession.class,
                ClassParameter.from(TerminalSession.class, terminal),
                ClassParameter.from(ExecutionCommand.class, new ExecutionCommand()),
                ClassParameter.from(TermuxSession.TermuxSessionClient.class,
                    (TermuxSession.TermuxSessionClient) sessions::remove),
                ClassParameter.from(boolean.class, false)));
        }
        activity.mDrawerAdapter.setSessions(sessions);
        return activity;
    }

    private static TerminalDrawerRecyclerView list(TermuxActivity activity) {
        return activity.findViewById(R.id.terminal_drawer_list);
    }

    private static TerminalDrawerAdapter adapter(TermuxActivity activity) {
        return (TerminalDrawerAdapter) list(activity).getAdapter();
    }

    private static void addBookmarks(TermuxActivity activity, int count) {
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        for (int i = 0; i < count; i++) store.add(bookmark(i));
        adapter(activity).refreshBookmarks();
    }

    private static TerminalBookmark bookmark(int index) {
        return new TerminalBookmark("bookmark-" + index, "Bookmark " + index, "local", "",
            Collections.emptyList(), "/tmp/" + index);
    }

    private static View row(RecyclerView list, int position) {
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        assertNotNull("Expected visible drawer position " + position, holder);
        return holder.itemView;
    }

    private static void measure(View view, int widthDp, int heightDp) {
        measureOnce(view, widthDp, heightDp);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureOnce(view, widthDp, heightDp);
    }

    private static void measureOnce(View view, int widthDp, int heightDp) {
        int width = dp(view, widthDp), height = dp(view, heightDp);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void assertCardSizesAndSpacing(RecyclerView list) {
        int cards = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View card = list.getChildAt(i);
            if (card.findViewById(R.id.session_card_content) == null) continue;
            cards++;
            assertEquals(list.getWidth() - list.getPaddingLeft() - list.getPaddingRight(), card.getWidth());
            assertEquals(dp(list, 64), card.getHeight());
            if (i == 0) continue;
            View previous = list.getChildAt(i - 1);
            if (list.getChildViewHolder(previous).getItemViewType() == list.getChildViewHolder(card).getItemViewType())
                assertEquals(dp(list, 8), card.getTop() - previous.getBottom());
        }
        assertTrue(cards > 0);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static Rect bounds(ViewGroup parent, View view) {
        Rect bounds = new Rect();
        view.getDrawingRect(bounds);
        parent.offsetDescendantRectToMyCoords(view, bounds);
        return bounds;
    }
}
