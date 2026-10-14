package com.termux.terminal;

import java.nio.charset.StandardCharsets;

/** Host callbacks shared by libghostty-vt and the legacy Java terminal parser. */
public abstract class TerminalOutput {

    public static final int OSC_CLIPBOARD_RESULT_SUCCESS = 0;
    public static final int OSC_CLIPBOARD_RESULT_DENIED = 1;
    public static final int OSC_CLIPBOARD_RESULT_UNSUPPORTED = 2;
    public static final int OSC_CLIPBOARD_RESULT_BUSY = 3;
    public static final int OSC_CLIPBOARD_RESULT_INVALID_DATA = 4;
    public static final int OSC_CLIPBOARD_RESULT_IO_ERROR = 5;

    /** Private, nonce-correlated shell reply. Ignored unless a session has requested one. */
    public void onBookmarkLocation(String payload) {}

    /** Write a string using the UTF-8 encoding to the terminal client. */
    public final void write(String data) {
        if (data == null) return;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    /** Write bytes to the terminal client. */
    public abstract void write(byte[] data, int offset, int count);

    /** Notify the terminal client that the terminal title has changed. */
    public abstract void titleChanged(String oldTitle, String newTitle);

    public void workingDirectoryChanged(String workingDirectory) {}

    public void onMouseShapeChanged(int shape) {}

    public void onDesktopNotification(String title, String body) {}

    public void onProgressReport(int state, int progress) {}

    public void onProgramStatusReport(int state, int kind, int progress,
                                      String id, String app, String title, String message) {}

    public void onProgramStatusPrompt() {}

    /** Notify the terminal client that text should be copied to clipboard. */
    public abstract void onCopyTextToClipboard(String text);

    /** Preserve text copying without advertising support for binary clipboard formats. */
    public int onOscClipboard(int location, String[] mimeTypes, byte[][] data, boolean clear) {
        if (clear) {
            onCopyTextToClipboard("");
            return OSC_CLIPBOARD_RESULT_SUCCESS;
        }
        if (mimeTypes == null || data == null || mimeTypes.length != data.length)
            return OSC_CLIPBOARD_RESULT_INVALID_DATA;
        for (int i = 0; i < mimeTypes.length; i++) {
            if (mimeTypes[i] == null || data[i] == null)
                return OSC_CLIPBOARD_RESULT_INVALID_DATA;
        }
        for (int i = 0; i < mimeTypes.length; i++) {
            if ("text/plain".equalsIgnoreCase(mimeTypes[i]) ||
                "text/plain;charset=utf-8".equalsIgnoreCase(mimeTypes[i])) {
                onCopyTextToClipboard(new String(data[i], StandardCharsets.UTF_8));
                return OSC_CLIPBOARD_RESULT_SUCCESS;
            }
        }
        return OSC_CLIPBOARD_RESULT_UNSUPPORTED;
    }

    /** Terminal programs cannot read the Android clipboard without a host implementation. */
    public int onOscClipboardReadPermission(String name, boolean granted, boolean canRemember) {
        return 0;
    }

    public String[] onOscClipboardMimeTypes(int location) { return null; }

    public byte[] onOscClipboardRead(int location, String mimeType) { return null; }

    public void onOscClipboardReadComplete() {}

    /** Notify the terminal client that text should be pasted from clipboard. */
    public abstract void onPasteTextFromClipboard();

    /** Notify the terminal client that a bell character (ASCII 7, bell, BEL, \a, ^G)) has been received. */
    public abstract void onBell();

    public abstract void onColorsChanged();

}
