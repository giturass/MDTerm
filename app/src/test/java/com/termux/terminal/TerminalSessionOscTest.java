package com.termux.terminal;

import android.app.Application;
import android.os.Message;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.test.ShadowGhosttyTerminal;
import com.termux.terminal.ProgramStatusStore.Record;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

import static com.termux.terminal.ProgramStatusStore.*;
import static org.junit.Assert.*;

/** Exercise real session dispatch/lifecycle with already-parsed native callback fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, shadows = TerminalSessionOscTest.ProgramFeedShadow.class)
public class TerminalSessionOscTest {
    private TerminalSession session;
    private final List<List<Record>> updates = new ArrayList<>();
    private final List<List<Record>> finished = new ArrayList<>();

    @Before
    public void setUp() {
        session = new TerminalSession("", "", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase() {
                @Override public void onProgramStatusChanged(TerminalSession changedSession) {
                    updates.add(changedSession.getProgramStatuses());
                }

                @Override public void onSessionFinished(TerminalSession finishedSession) {
                    finished.add(finishedSession.getProgramStatuses());
                }
            });
        GhosttyTerminal terminal = new GhosttyTerminal(session, 80, 24, 10, 20, 100);
        ReflectionHelpers.setField(session, "mTerminal", terminal);
    }

    @Test
    public void reportsAndPromptInOneReadPublishOnlyTheFinalOrderedState() {
        feed('x');
        assertEquals(1, updates.size());
        assertEquals(1, updates.get(0).size());
        assertEquals("result", updates.get(0).get(0).id);
        assertEquals(DONE, updates.get(0).get(0).state);
        session.dismissFinishedProgramStatuses();
        assertEquals(2, updates.size());
        assertTrue(updates.get(1).isEmpty());
        assertEquals(1, updates.get(0).size());
    }

    @Test
    public void processExitClearsActivityButKeepsResultsUntilDismissed() {
        report("working", WORKING);
        report("blocked", BLOCKED);
        report("done", DONE);
        report("error", ERROR);
        updates.clear();
        Message exit = Message.obtain();
        exit.what = 4; // MSG_PROCESS_EXITED
        exit.obj = 7;
        session.mMainThreadHandler.handleMessage(exit);
        assertFalse(session.isRunning());
        assertEquals(7, session.getExitStatus());
        assertEquals(1, finished.size());
        assertEquals(2, finished.get(0).size());
        assertEquals(DONE, finished.get(0).get(0).state);
        assertEquals(ERROR, finished.get(0).get(1).state);
        assertEquals(1, updates.size());
    }

    @Test
    public void feedFailureStillFlushesStatusAndRestoresDirectCallbackDelivery() {
        try {
            feed('!');
            fail("Expected the feed fixture to fail");
        } catch (IllegalStateException expected) {
            assertEquals("fixture feed failure", expected.getMessage());
        }
        assertEquals(1, updates.size());
        report("later", DONE);
        assertEquals(2, updates.size());
        assertEquals(2, updates.get(1).size());
    }

    @Test
    public void replacingTheSessionClientRetainsProgramStateForTheNewHost() {
        report("running", WORKING);
        List<Record> before = session.getProgramStatuses();
        List<List<Record>> replacementUpdates = new ArrayList<>();
        session.updateTerminalSessionClient(new TermuxTerminalSessionClientBase() {
            @Override public void onProgramStatusChanged(TerminalSession changedSession) {
                replacementUpdates.add(changedSession.getProgramStatuses());
            }
        });
        assertSame(before, session.getProgramStatuses());
        report("running", DONE);
        assertEquals(1, updates.size());
        assertEquals(1, replacementUpdates.size());
        assertEquals(DONE, replacementUpdates.get(0).get(0).state);
    }

    private void report(String id, int state) {
        session.onProgramStatusReport(state, KIND_NONE, -1, id, "", "", "");
    }

    private void feed(char marker) {
        byte[] input = new byte[]{(byte) marker};
        assertTrue(session.mProcessToTerminalIOQueue.write(input, 0, input.length));
        session.mMainThreadHandler.handleMessage(Message.obtain());
    }

    @Implements(value = GhosttyTerminal.class, isInAndroidSdk = false)
    public static class ProgramFeedShadow extends ShadowGhosttyTerminal {
        private TerminalOutput output;

        @Implementation
        protected void __constructor__(TerminalOutput output, int columns, int rows,
                                       int cellWidth, int cellHeight, int transcriptRows) {
            super.__constructor__(output, columns, rows, cellWidth, cellHeight, transcriptRows);
            this.output = output;
        }

        @Implementation
        protected void feed(byte[] data, int offset, int count) {
            if (count == 1 && (data[offset] == 'x' || data[offset] == '!')) {
                output.onProgramStatusReport(WORKING, KIND_NONE, 20, "transient", "", "", "");
                if (data[offset] == '!') throw new IllegalStateException("fixture feed failure");
                output.onProgramStatusPrompt();
                output.onProgramStatusReport(DONE, KIND_NONE, -1, "result", "", "", "");
            } else {
                super.feed(data, offset, count);
            }
        }
    }
}
