package com.termux.app;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.terminal.TerminalBookmark;
import com.termux.app.terminal.TerminalBookmarkStore;
import com.termux.app.terminal.TerminalDrawerAdapter;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.Collections;
import java.time.Duration;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN-w320dp-h640dp")
public class BookmarkUiTest {
    @Test
    public void savingBookmarkBeyondVisibleCardsRevealsItWithoutPinningLaterRefreshes() {
        verifyNewBookmarkIsRevealed(false);
    }

    @Test
    public void savingBookmarkWithLegacyCollapsedPreferenceRevealsTheNewCard() {
        verifyNewBookmarkIsRevealed(true);
    }

    private void verifyNewBookmarkIsRevealed(boolean legacyCollapsedPreference) {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        for (int i = 0; i < 8; i++) {
            store.add(new TerminalBookmark("saved-" + i, "Bookmark " + i, "local", "",
                Collections.emptyList(), "/tmp/" + i));
        }
        activity.getSharedPreferences("terminal_bookmarks", 0).edit()
            .putBoolean("collapsed", legacyCollapsedPreference).commit();
        TerminalDrawerAdapter adapter =
            new TerminalDrawerAdapter(activity, store, item -> {});
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        activity.getDrawer().openDrawer(Gravity.START, false);
        measureDrawer(drawer);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        RecyclerView list = activity.findViewById(R.id.terminal_drawer_list);
        assertEquals(View.VISIBLE, list.getVisibility());
        assertEquals(8, adapter.getBookmarkCount());
        RecyclerView.ViewHolder lastCard = list.findViewHolderForAdapterPosition(
            adapter.findBookmarkPosition("saved-7"));
        assertTrue(lastCard == null || bounds(drawer, lastCard.itemView).bottom
            > bounds(drawer, list).bottom - list.getPaddingBottom());

        store.add(bookmark());
        adapter.refreshAndRevealBookmark("location");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        assertEquals(View.VISIBLE, list.getVisibility());
        int newPosition = adapter.findBookmarkPosition("location");
        assertEquals(9, adapter.getBookmarkCount());
        View newCard = itemView(list, newPosition);
        assertEquals("location", newCard.getTag());
        Rect viewport = bounds(drawer, list);
        Rect cardBounds = bounds(drawer, newCard);
        assertTrue("The drawer must scroll to reveal the new bookmark", list.canScrollVertically(-1));
        assertTrue("The new card must be fully visible", cardBounds.top >= viewport.top + list.getPaddingTop());
        assertTrue("The new card must be fully visible",
            cardBounds.bottom <= viewport.bottom - list.getPaddingBottom());
        assertTrue("The new card must stay above the floating action",
            cardBounds.bottom <= bounds(drawer, activity.findViewById(R.id.new_session_button)).top);

        // Once the user scrolls elsewhere, terminal refreshes must not pull them back to the new item.
        list.scrollToPosition(0);
        measureDrawer(drawer);
        list.scrollBy(0, Math.round(72 * drawer.getResources().getDisplayMetrics().density));
        measureDrawer(drawer);
        LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
        int firstPosition = layout.findFirstVisibleItemPosition();
        int firstTop = bounds(drawer, itemView(list, firstPosition)).top;
        store.rename("saved-1", "Renamed while browsing");
        adapter.refreshBookmarks();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        assertEquals(firstPosition, layout.findFirstVisibleItemPosition());
        assertEquals(firstTop, bounds(drawer, itemView(list, firstPosition)).top);
    }

    @Test
    public void legacyCollapsedPreferenceKeepsBookmarksVisibleAfterRefreshAndRecreation() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        assertTrue(activity.getSharedPreferences("terminal_bookmarks", 0).edit()
            .putBoolean("collapsed", true).commit());
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        TerminalDrawerAdapter adapter =
            new TerminalDrawerAdapter(activity, store, item -> {});
        ViewGroup drawer = activity.getDrawer();
        RecyclerView list = activity.findViewById(R.id.terminal_drawer_list);
        controller.visible();
        activity.getDrawer().openDrawer(Gravity.START, false);
        measureDrawer(drawer);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        assertEquals(View.VISIBLE, list.getVisibility());
        assertTrue(list.getHeight() > 0);
        assertEquals(1, adapter.getBookmarkCount());
        assertEquals(3, adapter.getItemCount());
        assertEquals("location", itemView(list, 1).getTag());
        store.rename("location", "Updated bookmark");
        adapter.refreshBookmarks();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        assertEquals(View.VISIBLE, list.getVisibility());
        assertEquals("Updated bookmark", ((TextView) itemView(list, 1)
            .findViewById(R.id.session_name)).getText().toString());

        ActivityController<TermuxActivity> recreatedController = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity recreated = recreatedController.get();
        recreated.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        recreated.setContentView(R.layout.activity_termux);
        TerminalDrawerAdapter restored = new TerminalDrawerAdapter(
            recreated, new TerminalBookmarkStore(recreated), item -> {});
        RecyclerView restoredList = recreated.findViewById(R.id.terminal_drawer_list);
        assertEquals(View.VISIBLE, restoredList.getVisibility());
        recreatedController.visible();
        recreated.getDrawer().openDrawer(Gravity.START, false);
        measureDrawer(recreated.getDrawer());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(recreated.getDrawer());
        assertEquals(View.VISIBLE, restoredList.getVisibility());
        assertTrue(restoredList.getHeight() > 0);
        assertEquals(1, restored.getBookmarkCount());
        assertEquals(3, restored.getItemCount());
        assertEquals("Updated bookmark", restored.getBookmark(0).name);
        assertEquals("Updated bookmark", ((TextView) itemView(restoredList, 1)
            .findViewById(R.id.session_name)).getText().toString());
    }

    @Test
    @Config(shadows = SessionActionsTest.ShadowTerminalSession.class)
    public void terminalActionsOfferSavingOnlyForRunningSessions() {
        TermuxActivity activity = host();
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));
        TerminalSession terminal = new TerminalSession("", "", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase());
        activity.mTerminalView.mTermSession = terminal;
        for (boolean running : new boolean[]{true, false}) {
            ReflectionHelpers.setField(terminal, "mShellPid", running ? 1234 : -1);
            Menu menu = new PopupMenu(activity, activity.mTerminalView).getMenu();
            ReflectionHelpers.callInstanceMethod(activity, "populateTerminalActions",
                ReflectionHelpers.ClassParameter.from(Menu.class, menu));
            MenuItem save = null;
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (activity.getString(R.string.action_save_bookmark).contentEquals(item.getTitle())) save = item;
            }
            assertNotNull(save);
            assertEquals(running, save.isEnabled());
        }
    }

    @Test
    public void longSessionSummaryAndBookmarkPathStayOutsideMenuAtNarrowWidths() {
        TermuxActivity activity = host();
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        TerminalDrawerAdapter adapter =
            new TerminalDrawerAdapter(activity, store, item -> {});
        RecyclerView list = activity.findViewById(R.id.terminal_drawer_list);
        int bookmarkPosition = adapter.findBookmarkPosition("location");
        RecyclerView.ViewHolder holder = adapter.createViewHolder(list,
            adapter.getItemViewType(bookmarkPosition));
        adapter.bindViewHolder(holder, bookmarkPosition);
        View bookmark = holder.itemView;
        View session = activity.getLayoutInflater().inflate(R.layout.item_terminal_sessions_list, list, false);
        ((TextView) session.findViewById(R.id.session_number)).setText("12");
        TextView name = session.findViewById(R.id.session_name);
        name.setVisibility(View.VISIBLE);
        name.setText("非常长的会话名称 abcdefghijklmnopqrstuvwxyz");
        ((TextView) session.findViewById(R.id.session_title)).setText(bookmark().path);
        for (int width : new int[]{240, 288, 320}) {
            measure(bookmark, width);
            measure(session, width);
            assertOutsideMenu((ViewGroup) bookmark);
            assertOutsideMenu((ViewGroup) session);
            TextView path = bookmark.findViewById(R.id.session_title);
            assertEquals(1, path.getLineCount());
            assertEquals(TextUtils.TruncateAt.MARQUEE, path.getEllipsize());
            assertTrue("Long bookmark paths must be selected for marquee scrolling", path.isSelected());
            TextView summary = session.findViewById(R.id.session_title);
            assertEquals(1, summary.getLineCount());
            assertEquals(TextUtils.TruncateAt.MARQUEE, summary.getEllipsize());
            assertEquals(session.getWidth(), bookmark.getWidth());
            assertEquals(session.getHeight(), bookmark.getHeight());
            name.setVisibility(View.GONE);
            measure(session, width);
            assertEquals("Bookmarks must also match unnamed session cards", session.getHeight(), bookmark.getHeight());
            name.setVisibility(View.VISIBLE);
        }
    }

    @Test
    public void tappingBookmarkPathOpensTheSavedLocation() {
        verifyBookmarkTouch(false);
    }

    @Test
    public void refreshingBookmarkDuringPathTapStillOpensTheSavedLocation() {
        verifyBookmarkTouch(true);
    }

    @Test
    public void deletingBookmarkDuringTapDoesNotOpenItsReplacementBeforeLayout() {
        verifyBookmarkTouch(false, true);
    }

    @Test
    public void scrollingFromBookmarkMenuCancelsTheClick() {
        verifyBookmarkSwipe(R.id.session_menu_button);
    }

    @Test
    public void scrollingFromBookmarkContentDoesNotOpenTheLocation() {
        verifyBookmarkSwipe(R.id.session_card_content);
    }

    private void verifyBookmarkSwipe(int touchTargetId) {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        for (int i = 0; i < 12; i++) {
            store.add(new TerminalBookmark("saved-" + i, "Bookmark " + i, "local", "",
                Collections.emptyList(), "/tmp/" + i));
        }
        TerminalBookmark[] opened = {null};
        new TerminalDrawerAdapter(activity, store, item -> opened[0] = item);
        controller.visible();
        activity.getDrawer().openDrawer(Gravity.START, false);
        ViewGroup drawer = activity.getDrawer();
        measureDrawer(drawer);
        RecyclerView list = activity.findViewById(R.id.terminal_drawer_list);
        Rect target = bounds(drawer, itemView(list, 2).findViewById(touchTargetId));
        long time = SystemClock.uptimeMillis();
        float step = 40 * activity.getResources().getDisplayMetrics().density;
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(time, time + i * 30, actions[i],
                target.exactCenterX(), target.exactCenterY() - i * step, 0);
            drawer.dispatchTouchEvent(event);
            event.recycle();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Dragging a bookmark must scroll the shared page", list.canScrollVertically(-1));
        assertEquals(14, list.getAdapter().getItemCount());
        assertTrue("Off-screen bookmarks must be recycled", list.getChildCount() < 14);
        assertNull(ShadowApplication.getInstance().getLatestPopupWindow());
        assertNull(opened[0]);
    }

    private void verifyBookmarkTouch(boolean refresh) {
        verifyBookmarkTouch(refresh, false);
    }

    private void verifyBookmarkTouch(boolean refresh, boolean delete) {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        if (delete) store.add(new TerminalBookmark("remaining", "Remaining bookmark", "local", "",
            Collections.emptyList(), "/tmp"));
        TerminalBookmark[] opened = {null};
        int[] openCount = {0};
        TerminalDrawerAdapter adapter =
            new TerminalDrawerAdapter(activity, store, item -> {
                opened[0] = item;
                openCount[0]++;
            });
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measureDrawer(drawer);
        activity.getDrawer().openDrawer(Gravity.START, false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        RecyclerView list = activity.findViewById(R.id.terminal_drawer_list);
        View path = itemView(list, 1).findViewById(R.id.session_title);
        Rect bounds = bounds(drawer, path);
        assertTrue(path.isShown());
        assertTrue(bounds.width() > 0);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(down);
        down.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
        if (refresh) {
            store.rename("location", "Updated bookmark");
            adapter.refreshBookmarks();
            measureDrawer(drawer);
        }
        if (delete) {
            store.delete("location");
            adapter.refreshBookmarks();
            // Deliberately deliver UP before RecyclerView lays out its changed data.
        }
        MotionEvent up = MotionEvent.obtain(time, time + 150, MotionEvent.ACTION_UP,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(up);
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        if (delete) {
            assertEquals(0, openCount[0]);
            assertNull(opened[0]);
            assertEquals("remaining", adapter.getBookmark(0).id);
            return;
        }
        assertEquals("A card tap must open its bookmark exactly once", 1, openCount[0]);
        assertEquals("location", opened[0].id);
        assertEquals("ubuntu", opened[0].distro);
        assertEquals(bookmark().path, opened[0].path);
        if (refresh) assertEquals("Updated bookmark", opened[0].name);
        assertNull(ShadowApplication.getInstance().getLatestPopupWindow());
    }

    @Test
    public void drawerRefreshReflectsRenameAndDeleteAndOpeningKeepsSavedEnvironment() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        TerminalBookmark[] opened = {null};
        TerminalDrawerAdapter adapter =
            new TerminalDrawerAdapter(activity, store, item -> opened[0] = item);
        RecyclerView list = activity.findViewById(R.id.terminal_drawer_list);
        assertSame(adapter, list.getAdapter());
        controller.visible();
        activity.getDrawer().openDrawer(Gravity.START, false);
        measureDrawer(activity.getDrawer());
        View row = itemView(list, adapter.findBookmarkPosition("location"));
        assertTrue(row.findViewById(R.id.session_card_content).performClick());
        assertEquals("ubuntu", opened[0].distro);
        assertEquals(bookmark().path, opened[0].path);
        store.rename("location", "新名称");
        adapter.refreshBookmarks();
        measureDrawer(activity.getDrawer());
        row = itemView(list, adapter.findBookmarkPosition("location"));
        assertEquals("新名称", ((TextView) row.findViewById(R.id.session_name)).getText().toString());
        assertEquals("新名称", new TerminalBookmarkStore(activity).getAll().get(0).name);
        store.delete("location");
        adapter.refreshBookmarks();
        assertEquals(0, adapter.getBookmarkCount());
    }

    @Test
    public void cancellingBookmarkDeletionKeepsTheBookmark() {
        verifyBookmarkDeletion(AlertDialog.BUTTON_NEGATIVE);
    }

    @Test
    public void pressingBackDuringBookmarkDeletionKeepsTheBookmark() {
        verifyBookmarkDeletion(KeyEvent.KEYCODE_BACK);
    }

    @Test
    public void confirmingBookmarkDeletionRemovesOnlyItsTargetAndRefreshesTheList() {
        verifyBookmarkDeletion(AlertDialog.BUTTON_POSITIVE);
    }

    private void verifyBookmarkDeletion(int action) {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        store.add(new TerminalBookmark("remaining", "Remaining bookmark", "local", "",
            Collections.emptyList(), "/tmp"));
        TerminalBookmark[] opened = {null};
        TerminalDrawerAdapter adapter =
            new TerminalDrawerAdapter(activity, store, item -> opened[0] = item);
        controller.visible();
        View drawer = activity.getDrawer();
        activity.getDrawer().openDrawer(Gravity.START, false);
        measureDrawer(drawer);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        RecyclerView list = activity.findViewById(R.id.terminal_drawer_list);
        itemView(list, 1).findViewById(R.id.session_menu_button).performClick();
        PopupWindow popup = ShadowApplication.getInstance().getLatestPopupWindow();
        assertNotNull(popup);
        ListView actions = popupList(popup.getContentView());
        assertNotNull(actions);
        actions.performItemClick(actions.getAdapter().getView(1, null, actions), 1, 1);

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("Deleting a bookmark must first ask for confirmation", dialog);
        assertTrue(dialog.isShowing());
        TextView message = dialog.findViewById(android.R.id.message);
        assertEquals(activity.getString(R.string.message_confirm_delete_bookmark, bookmark().name),
            message.getText().toString());
        assertEquals(2, adapter.getBookmarkCount());
        assertEquals(2, new TerminalBookmarkStore(activity).getAll().size());
        assertNull(opened[0]);

        if (action == KeyEvent.KEYCODE_BACK) {
            dialog.onBackPressed();
        } else {
            dialog.getButton(action).performClick();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        assertFalse(dialog.isShowing());
        boolean deleted = action == AlertDialog.BUTTON_POSITIVE;
        assertEquals(deleted ? 1 : 2, adapter.getBookmarkCount());
        assertEquals(deleted ? 1 : 2, new TerminalBookmarkStore(activity).getAll().size());
        String firstId = deleted ? "remaining" : "location";
        assertEquals(firstId, adapter.getBookmark(0).id);
        assertEquals(firstId, itemView(list, 1).getTag());
        assertEquals(firstId, new TerminalBookmarkStore(activity).getAll().get(0).id);
        assertNull(opened[0]);
    }

    @Test
    public void refreshingBookmarkNameDuringMenuTapKeepsTheMenuUsable() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        TerminalBookmark[] opened = {null};
        TerminalDrawerAdapter adapter =
            new TerminalDrawerAdapter(activity, store, item -> opened[0] = item);
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measureDrawer(drawer);
        activity.getDrawer().openDrawer(Gravity.START, false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        RecyclerView list = activity.findViewById(R.id.terminal_drawer_list);
        View menu = itemView(list, 1).findViewById(R.id.session_menu_button);
        Rect bounds = bounds(drawer, menu);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(down);
        down.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
        store.rename("location", "Updated bookmark");
        adapter.refreshBookmarks();
        measureDrawer(drawer);
        MotionEvent up = MotionEvent.obtain(time, time + 150, MotionEvent.ACTION_UP,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(up);
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        PopupWindow popup = ShadowApplication.getInstance().getLatestPopupWindow();
        assertNotNull(popup);
        assertTrue(popup.isShowing());
        assertNull(opened[0]);
        assertEquals("Updated bookmark", ((TextView) itemView(list, 1)
            .findViewById(R.id.session_name)).getText().toString());
        popup.dismiss();
    }

    @Test
    public void deleteThenRenameBeforeLayoutUpdatesTheRemainingBookmark() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        store.add(new TerminalBookmark("remaining", "Second", "local", "",
            Collections.emptyList(), "/tmp"));
        TerminalDrawerAdapter adapter =
            new TerminalDrawerAdapter(activity, store, item -> {});
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measureDrawer(drawer);
        activity.getDrawer().openDrawer(Gravity.START, false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        store.delete("location");
        adapter.refreshBookmarks();
        store.rename("remaining", "Updated survivor");
        adapter.refreshBookmarks();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        RecyclerView list = activity.findViewById(R.id.terminal_drawer_list);
        assertEquals(1, adapter.getBookmarkCount());
        assertEquals(3, adapter.getItemCount());
        assertEquals("Updated survivor", ((TextView) itemView(list, 1)
            .findViewById(R.id.session_name)).getText().toString());
    }

    private static View itemView(RecyclerView list, int position) {
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        assertNotNull("Expected visible drawer item at adapter position " + position, holder);
        return holder.itemView;
    }

    private static void measureDrawer(View drawer) {
        measureDrawerOnce(drawer);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawerOnce(drawer);
    }

    private static void measureDrawerOnce(View drawer) {
        // Include parent margins so real layout passes do not resize cards during a touch.
        View root = drawer.getRootView().findViewById(R.id.activity_termux_root_view);
        assertNotNull(root);
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(640 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static ListView popupList(View view) {
        if (view instanceof ListView) return (ListView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView list = popupList(group.getChildAt(i));
                if (list != null) return list;
            }
        }
        return null;
    }

    private static TerminalBookmark bookmark() {
        return new TerminalBookmark("location", "非常长的书签名称 abcdefghijklmnopqrstuvwxyz", "proot",
            "ubuntu", Collections.emptyList(), "/very/long/目录/with spaces/projects/abcdefghijklmnopqrstuvwxyz/subdirectory");
    }

    private static TermuxActivity host() {
        return host(Robolectric.buildActivity(TermuxActivity.class));
    }

    private static TermuxActivity host(ActivityController<TermuxActivity> controller) {
        TermuxActivity activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        activity.getSharedPreferences("terminal_bookmarks", 0).edit().clear().commit();
        return activity;
    }

    private static void measure(View row, int widthDp) {
        int width = Math.round(widthDp * row.getResources().getDisplayMetrics().density);
        int rowHeight = row.getLayoutParams().height;
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(Math.max(0, rowHeight),
                rowHeight >= 0 ? View.MeasureSpec.EXACTLY : View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
    }

    private static void assertOutsideMenu(ViewGroup row) {
        View menu = row.findViewById(R.id.session_menu_button);
        Rect menuBounds = bounds(row, menu);
        assertTrue(menu.getWidth() > 0);
        for (int id : new int[]{R.id.session_name, R.id.session_title}) {
            TextView text = row.findViewById(id);
            assertTrue(text.getWidth() > 0);
            assertFalse("Text must not overlap the three-dot menu", Rect.intersects(bounds(row, text), menuBounds));
        }
    }

    private static Rect bounds(ViewGroup row, View child) {
        Rect rect = new Rect();
        child.getDrawingRect(rect);
        row.offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }
}
