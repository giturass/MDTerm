package com.termux.app;

import android.app.Application;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import com.termux.R;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, shadows = SessionActionsTest.ShadowTerminalSession.class)
public class SessionActionsTest {
    private TermuxActivity activity;
    private TermuxService service;
    private TermuxShellManager manager;
    private TermuxTerminalSessionActivityClient client;

    @Before
    public void setUp() {
        // Attach hosts without starting a native shell or the foreground service.
        activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        service = Robolectric.buildService(TermuxService.class).get();
        manager = new TermuxShellManager(activity);
        ReflectionHelpers.setField(service, "mShellManager", manager);
        ReflectionHelpers.setField(activity, "mTermuxService", service);
        client = new TermuxTerminalSessionActivityClient(activity) {
            @Override public void setCurrentSession(TerminalSession session) {
                // Avoid attaching a native PTY while exercising session selection.
                activity.mTerminalView.mTermSession = session;
            }
        };
        ReflectionHelpers.setField(activity, "mTermuxTerminalSessionActivityClient", client);
    }

    @Test
    public void closingRunningBackgroundSessionRemovesOnlyThatSessionAndPreservesSelection() {
        TermuxSession background = addSession(true);
        TermuxSession current = addSession(true);
        client.setCurrentSession(current.getTerminalSession());

        client.closeSession(background.getTerminalSession());

        assertEquals(1, service.getTermuxSessionsSize());
        assertSame(current, service.getTermuxSession(0));
        assertSame(current.getTerminalSession(), activity.getCurrentSession());
        assertTrue(wasKilled(background));
        assertFalse(wasKilled(current));
        assertEquals(Integer.valueOf(137), background.getExecutionCommand().resultData.exitCode);
        assertSame(service.getTermuxTerminalSessionClient(),
            ReflectionHelpers.getField(background.getTerminalSession(), "mClient"));
    }

    @Test
    public void closingFinishedCurrentSessionSelectsNeighborAndClosingLastFinishesActivity() {
        TermuxSession first = addSession(false);
        TermuxSession last = addSession(false);
        client.setCurrentSession(last.getTerminalSession());

        activity.showCloseSessionDialog(last.getTerminalSession());
        assertEquals(1, service.getTermuxSessionsSize());
        assertSame(first.getTerminalSession(), activity.getCurrentSession());
        assertFalse(activity.isFinishing());
        assertFalse(wasKilled(last));

        activity.showCloseSessionDialog(first.getTerminalSession());
        assertEquals(0, service.getTermuxSessionsSize());
        assertTrue(activity.isFinishing());
    }

    @Test
    public void closeDialogCanBeCancelledAndKeepsItsTargetWhenCurrentSessionChanges() {
        TermuxSession first = addSession(true);
        TermuxSession second = addSession(true);
        client.setCurrentSession(first.getTerminalSession());
        activity.showCloseSessionDialog(first.getTerminalSession());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, service.getTermuxSessionsSize());
        assertFalse(wasKilled(first));

        activity.showCloseSessionDialog(first.getTerminalSession());
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        client.setCurrentSession(second.getTerminalSession());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(second.getTerminalSession(), activity.getCurrentSession());
        assertTrue(wasKilled(first));
        assertFalse(wasKilled(second));

        // A repeated action from a stale menu must not select or remove another session.
        client.closeSession(first.getTerminalSession());
        assertEquals(1, service.getTermuxSessionsSize());
        assertSame(second.getTerminalSession(), activity.getCurrentSession());
    }

    private TermuxSession addSession(boolean running) {
        TerminalSession terminal = new TerminalSession("", "", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase());
        ReflectionHelpers.setField(terminal, "mShellPid", running ? 1234 : -1);
        ExecutionCommand command = new ExecutionCommand();
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

    private boolean wasKilled(TermuxSession session) {
        ShadowTerminalSession shadow = Shadow.extract(session.getTerminalSession());
        return shadow.killed;
    }

    @Implements(TerminalSession.class)
    public static class ShadowTerminalSession {
        boolean killed;

        @Implementation
        protected void finishIfRunning() {
            killed = true;
        }
    }
}
