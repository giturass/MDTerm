package com.termux.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GhosttyCursorStyleTest {
    @Test
    public void mapsPersistedMdtermStylesWithoutLoadingNativeLibrary() {
        assertEquals(GhosttyTerminal.CURSOR_STYLE_BLOCK, TerminalSession.toGhosttyCursorStyle(0));
        assertEquals(GhosttyTerminal.CURSOR_STYLE_UNDERLINE, TerminalSession.toGhosttyCursorStyle(1));
        assertEquals(GhosttyTerminal.CURSOR_STYLE_BAR, TerminalSession.toGhosttyCursorStyle(2));
    }

    @Test
    public void missingOrInvalidPreferenceUsesBlockCursor() {
        assertEquals(GhosttyTerminal.CURSOR_STYLE_BLOCK, TerminalSession.toGhosttyCursorStyle(null));
        assertEquals(GhosttyTerminal.CURSOR_STYLE_BLOCK, TerminalSession.toGhosttyCursorStyle(-1));
        assertEquals(GhosttyTerminal.CURSOR_STYLE_BLOCK, TerminalSession.toGhosttyCursorStyle(99));
    }
}
