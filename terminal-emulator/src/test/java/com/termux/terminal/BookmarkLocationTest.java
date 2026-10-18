package com.termux.terminal;

public class BookmarkLocationTest extends TerminalTestCase {

    public void testBookmarkReplyWithEitherStringTerminator() {
        for (String terminator : new String[]{"\007", "\033\\"}) {
            withTerminalSized(8, 2);
            mOutput.bookmarkLocations.clear();
            enterString("A\033]777;mdterm;nonce;L2hvbWUK" + terminator + "B")
                .assertLinesAre("AB      ", "        ");
            assertEquals(1, mOutput.bookmarkLocations.size());
            assertEquals("mdterm;nonce;L2hvbWUK", mOutput.bookmarkLocations.get(0));
            assertEquals("", mOutput.getOutputAndClear());
            assertEquals(0, mOutput.bellsRung);
        }
    }

    public void testSplitReplyWaitsForTerminator() {
        withTerminalSized(8, 2);
        enterString("\033]777;mdterm;nonce;");
        assertEquals(0, mOutput.bookmarkLocations.size());
        enterString("L2hvbWUK\033");
        assertEquals(0, mOutput.bookmarkLocations.size());
        enterString("\\");
        assertEquals(1, mOutput.bookmarkLocations.size());
        assertEquals("mdterm;nonce;L2hvbWUK", mOutput.bookmarkLocations.get(0));
    }
}
