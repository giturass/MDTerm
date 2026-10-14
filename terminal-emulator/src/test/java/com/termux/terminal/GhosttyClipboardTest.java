package com.termux.terminal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class GhosttyClipboardTest {
    @Test
    public void plainTextClipboardWriteUsesExistingCopyCallback() {
        Output output = new Output();
        String text = "目录/😀\ntext";
        assertEquals(TerminalOutput.OSC_CLIPBOARD_RESULT_SUCCESS,
            output.onOscClipboard(0, new String[]{"text/html", "text/plain;charset=utf-8"},
                new byte[][]{"<p>text</p>".getBytes(StandardCharsets.UTF_8),
                    text.getBytes(StandardCharsets.UTF_8)}, false));
        assertEquals(text, output.copied);
    }

    @Test
    public void unsupportedOrMalformedClipboardDataLeavesClipboardUntouched() {
        Output output = new Output();
        assertEquals(TerminalOutput.OSC_CLIPBOARD_RESULT_UNSUPPORTED,
            output.onOscClipboard(0, new String[]{"image/png"}, new byte[][]{{1, 2}}, false));
        assertNull(output.copied);
        assertEquals(TerminalOutput.OSC_CLIPBOARD_RESULT_INVALID_DATA,
            output.onOscClipboard(0, new String[]{"text/plain"}, new byte[0][], false));
        assertNull(output.copied);
    }

    @Test
    public void clipboardReadsAreDeniedWithoutHostOptIn() {
        Output output = new Output();
        assertEquals(0, output.onOscClipboardReadPermission("program", false, true));
        assertEquals(0, output.onOscClipboardReadPermission("program", true, true));
        assertNull(output.onOscClipboardMimeTypes(0));
        assertNull(output.onOscClipboardRead(0, "text/plain"));
    }

    @Test
    public void clearUsesExistingClipboardCallback() {
        Output output = new Output();
        assertEquals(TerminalOutput.OSC_CLIPBOARD_RESULT_SUCCESS,
            output.onOscClipboard(0, new String[0], new byte[0][], true));
        assertEquals("", output.copied);
    }

    private static final class Output extends TerminalOutput {
        String copied;
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) { copied = text; }
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    }
}
