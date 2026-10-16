package com.termux.app;

import android.app.Application;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.MenuItem;
import android.widget.ListAdapter;

import androidx.appcompat.app.AlertDialog;

import com.termux.R;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class TerminalHyperlinkActionsTest {
    private static final String ORIGINAL_LINK = "https://example.com/original?q=%E4%BD%A0#section";

    private TermuxActivity activity;
    private ClipboardManager clipboard;
    private AlertDialog dialog;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        activity.mTerminalView.setTerminalViewClient(new TermuxTerminalViewClientBase());
        activity.mTerminalView.mTermSession = session();
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));
        clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.clearPrimaryClip();
    }

    @After
    public void tearDown() {
        if (dialog != null) dialog.dismiss();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void linkActionsAppearOnlyWhenAHyperlinkIsTargeted() {
        for (String hyperlink : new String[]{null, ""}) {
            showMenu(hyperlink);
            assertEquals(-1, indexOf(R.string.action_open_link));
            assertEquals(-1, indexOf(R.string.action_copy_link));
            assertTrue(indexOf(R.string.action_select_url) >= 0);
            dialog.dismiss();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }

        showMenu(ORIGINAL_LINK);
        for (int label : new int[]{R.string.action_open_link, R.string.action_copy_link}) {
            int index = indexOf(label);
            assertTrue(index >= 0);
            MenuItem item = (MenuItem) dialog.getListView().getAdapter().getItem(index);
            assertTrue(item.isEnabled());
            assertNotNull(item.getIcon());
        }
    }

    @Test
    public void copyUsesTheMenuOriginalUriAndClearsContextOnDismissal() {
        showMenu(ORIGINAL_LINK);
        setHyperlink("https://example.com/replaced");

        click(R.string.action_copy_link);

        assertEquals(ORIGINAL_LINK, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertFalse(dialog.isShowing());
        assertNull(activity.mTerminalView.getContextHyperlink());
    }

    @Test
    public void openUsesTheMenuOriginalUriAndClearsContextOnDismissal() {
        showMenu(ORIGINAL_LINK);
        setHyperlink("https://example.com/replaced");

        click(R.string.action_open_link);

        Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(Intent.ACTION_VIEW, launched.getAction());
        assertEquals(ORIGINAL_LINK, launched.getDataString());
        assertFalse(dialog.isShowing());
        assertNull(activity.mTerminalView.getContextHyperlink());
    }

    @Test
    public void switchingSessionsMakesAnOpenLinkMenuInert() {
        for (int label : new int[]{R.string.action_open_link, R.string.action_copy_link}) {
            showMenu(ORIGINAL_LINK);
            activity.mTerminalView.mTermSession = session();

            click(label);

            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            assertFalse(clipboard.hasPrimaryClip());
            assertNull(activity.mTerminalView.getContextHyperlink());
        }
    }

    private void showMenu(String hyperlink) {
        setHyperlink(hyperlink);
        assertTrue(activity.showTerminalActions(10, 10));
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
    }

    private void setHyperlink(String hyperlink) {
        ReflectionHelpers.setField(activity.mTerminalView, "mContextHyperlink", hyperlink);
    }

    private int indexOf(int label) {
        ListAdapter adapter = dialog.getListView().getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            MenuItem item = (MenuItem) adapter.getItem(i);
            if (activity.getString(label).contentEquals(item.getTitle())) return i;
        }
        return -1;
    }

    private void click(int label) {
        int index = indexOf(label);
        assertTrue(index >= 0);
        ListAdapter adapter = dialog.getListView().getAdapter();
        dialog.getListView().performItemClick(adapter.getView(index, null, dialog.getListView()),
            index, adapter.getItemId(index));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static TerminalSession session() {
        return new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
    }
}
