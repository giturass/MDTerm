package com.termux.terminal;

public class NotificationTest extends TerminalTestCase {
    public void testNotificationWithEitherTerminatorDoesNotRingOrPrint() {
        for (String terminator : new String[]{"\007", "\033\\"}) {
            withTerminalSized(8, 2);
            mOutput.notifications.clear();
            enterString("A\033]9;需要操作 🔔; approval" + terminator + "B")
                .assertLinesAre("AB      ", "        ");
            assertEquals(1, mOutput.notifications.size());
            assertEquals("需要操作 🔔; approval", mOutput.notifications.get(0));
            assertEquals(0, mOutput.bellsRung);
            assertTrue(mOutput.titleChanges.isEmpty());
            assertEquals("", mOutput.getOutputAndClear());
        }
    }

    public void testSplitNotificationWaitsForCompleteTerminator() {
        withTerminalSized(8, 2);
        enterString("\033]9;Action ");
        assertTrue(mOutput.notifications.isEmpty());
        enterString("required\033");
        assertTrue(mOutput.notifications.isEmpty());
        enterString("\\");
        assertEquals(1, mOutput.notifications.size());
        assertEquals("Action required", mOutput.notifications.get(0));
    }

    public void testEmptyNotificationsAndProgressReportsAreIgnored() {
        withTerminalSized(8, 2);
        for (String payload : new String[]{"", "   ", "4", "4;0", "4;1;50", "4;2;75", "4;3", "4;4;25"}) {
            enterString("\033]9;" + payload + "\007");
        }
        assertTrue(mOutput.notifications.isEmpty());
        assertEquals(0, mOutput.bellsRung);
        enterString("\033]9;4 files completed\007");
        assertEquals("4 files completed", mOutput.notifications.get(0));
    }

    public void testCancelledNotificationDoesNotLeakIntoNextSequence() {
        withTerminalSized(8, 2);
        enterString("\033]9;cancelled\030");
        assertTrue(mOutput.notifications.isEmpty());
        enterString("\033]9;Ready\007\033]2;Title\007\007");
        assertEquals(1, mOutput.notifications.size());
        assertEquals("Ready", mOutput.notifications.get(0));
        assertEquals(1, mOutput.titleChanges.size());
        assertEquals(1, mOutput.bellsRung);
    }
}
