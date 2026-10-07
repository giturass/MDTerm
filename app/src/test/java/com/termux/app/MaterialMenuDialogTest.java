package com.termux.app;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.PopupMenu;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;
import com.termux.R;
import com.termux.app.ui.MaterialMenuDialog;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class MaterialMenuDialogTest {
    @Test
    public void actionListPreservesEnabledCheckedAndDismissBehavior() {
        try (ActivityController<AppCompatActivity> controller = host()) {
            AppCompatActivity activity = controller.get();
            Menu menu = new PopupMenu(activity, new View(activity)).getMenu();
            menu.add(Menu.NONE, 7, Menu.NONE, "Disabled").setEnabled(false);
            menu.add(Menu.NONE, 8, Menu.NONE, "Keep screen on").setCheckable(true).setChecked(true);
            menu.add(Menu.NONE, 9, Menu.NONE, "Hidden").setVisible(false);
            List<Integer> actions = new ArrayList<>();
            AtomicInteger dismissed = new AtomicInteger();
            AlertDialog dialog = MaterialMenuDialog.show(activity, "Actions", menu,
                item -> actions.add(item.getItemId()), dismissed::incrementAndGet);
            ListAdapter adapter = dialog.getListView().getAdapter();
            assertEquals(2, adapter.getCount());
            assertFalse(adapter.isEnabled(0));
            View row = adapter.getView(1, null, new FrameLayout(activity));
            MaterialSwitch toggle = row.findViewById(R.id.action_switch);
            assertTrue(toggle.isChecked());
            toggle.performClick();
            assertFalse(menu.findItem(8).isChecked());
            assertTrue(dialog.isShowing());
            dialog.getListView().performItemClick(row, 1, 8);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(menu.findItem(8).isChecked());
            assertEquals(java.util.Arrays.asList(8, 8), actions);
            assertTrue(dialog.isShowing());
            dialog.dismiss();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1, dismissed.get());
        }
    }

    @Test
    public void terminalMenuRoutesBothContextMenuEntryPointsToHost() {
        try (ActivityController<AppCompatActivity> controller = host()) {
            TerminalView terminal = new TerminalView(controller.get(), null);
            AtomicInteger requests = new AtomicInteger();
            terminal.setContextMenuAction(requests::incrementAndGet);
            assertTrue(terminal.showContextMenu());
            assertTrue(terminal.showContextMenu(10, 20));
            assertEquals(2, requests.get());
        }
    }

    @Test
    @Config(shadows = SessionActionsTest.ShadowTerminalSession.class)
    public void terminalActionsPlaceStylingAboveTranscriptAndLaunchPlugin() {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));
        activity.mTerminalView.mTermSession = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
        Menu menu = new PopupMenu(activity, activity.mTerminalView).getMenu();
        ReflectionHelpers.callInstanceMethod(activity, "populateTerminalActions",
            ReflectionHelpers.ClassParameter.from(Menu.class, menu));

        int stylingIndex = -1;
        for (int i = 0; i < menu.size(); i++) {
            if (activity.getString(R.string.action_style_terminal).contentEquals(menu.getItem(i).getTitle())) {
                assertEquals("Styling must appear only once", -1, stylingIndex);
                stylingIndex = i;
            }
        }
        assertTrue("Styling must precede Share transcript", stylingIndex >= 0 && stylingIndex + 1 < menu.size());
        assertEquals(activity.getString(R.string.action_share_transcript),
            menu.getItem(stylingIndex + 1).getTitle().toString());
        MenuItem styling = menu.getItem(stylingIndex);
        assertTrue(styling.isVisible());
        assertTrue(styling.isEnabled());
        assertNotNull(styling.getIcon());

        assertTrue(activity.onContextItemSelected(styling));
        Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(new ComponentName("com.termux.styling", "com.termux.styling.TermuxStyleActivity"),
            launched.getComponent());
    }

    @Test
    public void materialRenameFieldKeepsInitialTextAndSubmitsImeAction() {
        try (ActivityController<AppCompatActivity> controller = host()) {
            List<String> names = new ArrayList<>();
            TextInputDialogUtils.textInput(controller.get(), R.string.title_rename_session, "old name",
                R.string.action_rename_session_confirm, names::add, -1, null, -1, null, null);
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            TextInputEditText input = dialog.findViewById(com.termux.shared.R.id.dialog_text_input);
            assertNotNull(input);
            assertTrue(input.getParent().getParent() instanceof TextInputLayout);
            assertEquals("old name", input.getText().toString());
            input.setText("新会话");
            input.onEditorAction(EditorInfo.IME_ACTION_DONE);
            assertEquals(java.util.Collections.singletonList("新会话"), names);
            assertFalse(dialog.isShowing());
        }
    }

    private static ActivityController<AppCompatActivity> host() {
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_TermuxApp_DayNight_NoActionBar);
        return controller.setup();
    }
}
