package com.termux.test;

import android.view.Surface;

import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-memory terminal fixture for host-side UI tests, where the Android native
 * library and Vulkan surfaces are unavailable. The legacy parser supplies text
 * fixtures; these tests do not verify libghostty's protocol implementations.
 */
@Implements(value = GhosttyTerminal.class, isInAndroidSdk = false)
public class ShadowGhosttyTerminal {
    private TerminalEmulator emulator;
    private TerminalOutput output;
    private int cellWidthPixels;
    private int cellHeightPixels;
    private int transcriptRows;
    private int[] colorScheme;
    private int defaultCursorStyle = GhosttyTerminal.CURSOR_STYLE_BLOCK;
    private boolean defaultCursorBlink;
    private int topRow;
    private int[] selection;
    private volatile boolean closed;
    private final List<Integer> promptScrollRequests = new ArrayList<>();
    private int keyEventCount;

    @Implementation
    protected static void __staticInitializer__() { }

    @Implementation
    protected void __constructor__(TerminalOutput output, int columns, int rows,
                                   int cellWidthPixels, int cellHeightPixels,
                                   int transcriptRows) {
        this.output = output;
        this.cellWidthPixels = cellWidthPixels;
        this.cellHeightPixels = cellHeightPixels;
        this.transcriptRows = transcriptRows;
        emulator = new TerminalEmulator(output, columns, rows, cellWidthPixels,
            cellHeightPixels, transcriptRows, null);
    }

    /** Access the Java text fixture without adding a legacy API to production. */
    public TerminalEmulator getEmulator() {
        return emulator;
    }

    @Implementation
    protected static int[] measureFonts(int textSize, String[] fontPaths) {
        return new int[]{10, 20};
    }

    @Implementation
    protected static int[] measureFont(int textSize, String fontPath) {
        return measureFonts(textSize, null);
    }

    @Implementation
    protected long getNativeHandle() {
        throw new UnsupportedOperationException("The UI test fixture has no native terminal handle");
    }

    @Implementation
    protected void feed(byte[] data, int offset, int count) {
        if (closed || count <= 0) return;
        int previousScroll = emulator.getScrollCounter();
        emulator.append(offset == 0 ? data : Arrays.copyOfRange(data, offset, offset + count), count);
        int scrolled = emulator.getScrollCounter() - previousScroll;
        if (topRow < 0) topRow -= scrolled;
        if (selection != null) {
            selection[1] -= scrolled;
            selection[3] -= scrolled;
            if (selection[1] < -getScrollbackRows() || selection[3] < -getScrollbackRows())
                selection = null;
        }
        clampViewport();
    }

    @Implementation
    protected void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (closed) return;
        this.cellWidthPixels = cellWidthPixels;
        this.cellHeightPixels = cellHeightPixels;
        boolean dimensionsChanged = columns != emulator.mColumns || rows != emulator.mRows;
        emulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        if (dimensionsChanged) selection = null;
        clampViewport();
    }

    @Implementation
    protected void reset() {
        if (closed) return;
        String oldTitle = emulator.getTitle();
        emulator = new TerminalEmulator(output, emulator.mColumns, emulator.mRows,
            cellWidthPixels, cellHeightPixels, transcriptRows, null);
        if (colorScheme != null) setColorScheme(colorScheme);
        setDefaultCursor(defaultCursorStyle, defaultCursorBlink);
        topRow = 0;
        selection = null;
        if (oldTitle != null) output.titleChanged(oldTitle, null);
    }

    @Implementation
    protected int getColumns() { return closed ? 0 : emulator.mColumns; }

    @Implementation
    protected int getRows() { return closed ? 0 : emulator.mRows; }

    @Implementation
    protected int getScrollbackRows() {
        return closed ? 0 : emulator.getScreen().getActiveTranscriptRows();
    }

    @Implementation
    protected int getTotalRows() { return getScrollbackRows() + getRows(); }

    @Implementation
    protected int getViewportOffset() {
        clampViewport();
        return closed ? 0 : getScrollbackRows() + topRow;
    }

    @Implementation
    protected boolean isAlternateBufferActive() {
        return !closed && emulator.isAlternateBufferActive();
    }

    @Implementation
    protected boolean isMouseTrackingActive() {
        return !closed && emulator.isMouseTrackingActive();
    }

    @Implementation
    protected int getMouseShape() { return GhosttyTerminal.MOUSE_SHAPE_TEXT; }

    @Implementation
    protected boolean isCursorVisible() { return !closed && emulator.isCursorEnabled(); }

    @Implementation
    protected boolean isViewportActive() {
        clampViewport();
        return !closed && topRow == 0;
    }

    @Implementation
    protected int getBackgroundColor() {
        return closed ? 0 : 0xff000000 | emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
    }

    @Implementation
    protected int[] getCursorState() {
        if (closed) return null;
        clampViewport();
        int row = emulator.getCursorRow();
        int viewportRow = row - topRow;
        if (viewportRow < 0 || viewportRow >= getRows()) return null;
        int column = emulator.getCursorCol();
        TerminalRow line = emulator.getScreen().allocateFullLineIfNecessary(
            emulator.getScreen().externalToInternalRow(row));
        if (column > 0 && line.findStartOfColumn(column) == line.findStartOfColumn(column - 1))
            --column;
        long style = emulator.getScreen().getStyleAt(row, column);
        int effects = TextStyle.decodeEffect(style) & (TextStyle.CHARACTER_ATTRIBUTE_BOLD
            | TextStyle.CHARACTER_ATTRIBUTE_ITALIC | TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
            | TextStyle.CHARACTER_ATTRIBUTE_BLINK | TextStyle.CHARACTER_ATTRIBUTE_INVERSE
            | TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE | TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH
            | TextStyle.CHARACTER_ATTRIBUTE_DIM);
        int foreground = resolveColor(TextStyle.decodeForeColor(style));
        int background = resolveColor(TextStyle.decodeBackColor(style));
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0) {
            int previousForeground = foreground;
            foreground = background;
            background = previousForeground;
            effects &= ~TextStyle.CHARACTER_ATTRIBUTE_INVERSE;
        }
        return new int[]{column, viewportRow, foreground, background, effects};
    }

    private int resolveColor(int color) {
        if ((color & 0xff000000) == 0xff000000) return color;
        if (emulator.isReverseVideo()) {
            if (color == TextStyle.COLOR_INDEX_FOREGROUND) color = TextStyle.COLOR_INDEX_BACKGROUND;
            else if (color == TextStyle.COLOR_INDEX_BACKGROUND) color = TextStyle.COLOR_INDEX_FOREGROUND;
        }
        return 0xff000000 | emulator.mColors.mCurrentColors[color];
    }

    @Implementation
    protected String getTitle() { return closed ? null : emulator.getTitle(); }

    @Implementation
    protected String getWorkingDirectory() { return null; }

    @Implementation
    protected String getTranscript(boolean unwrap, boolean trim) {
        if (closed) return "";
        String text = emulator.getScreen().getSelectedText(0, -getScrollbackRows(),
            getColumns() - 1, getRows() - 1, unwrap);
        return trim ? text.trim() : text;
    }

    @Implementation
    protected String getViewportText() {
        if (closed) return "";
        clampViewport();
        return emulator.getScreen().getSelectedText(0, topRow, getColumns() - 1,
            topRow + getRows() - 1, false);
    }

    @Implementation
    protected void setColorScheme(int[] colors) {
        if (closed) return;
        if (colors == null || colors.length < TextStyle.NUM_INDEXED_COLORS)
            throw new IllegalArgumentException("Terminal color scheme must have 259 colors");
        colorScheme = Arrays.copyOf(colors, TextStyle.NUM_INDEXED_COLORS);
        System.arraycopy(colorScheme, 0, emulator.mColors.mCurrentColors, 0, colorScheme.length);
        output.onColorsChanged();
    }

    @Implementation
    protected void setDefaultCursor(int style, boolean blink) {
        if (closed) return;
        defaultCursorStyle = style;
        defaultCursorBlink = blink;
        int legacyStyle = style == GhosttyTerminal.CURSOR_STYLE_BAR
            ? TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
            : style == GhosttyTerminal.CURSOR_STYLE_UNDERLINE
                ? TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
                : TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
        ReflectionHelpers.setField(emulator, "mCursorStyle", legacyStyle);
        emulator.setCursorBlinkingEnabled(blink);
        emulator.setCursorBlinkState(true);
    }

    @Implementation
    protected void setXtChecksumReportEnabled(boolean enabled) { }

    @Implementation
    protected void setGlyphProtocolEnabled(boolean enabled) { }

    @Implementation
    protected void setKittyGraphicsOptions(long storageLimit, String tempDirectory) { }

    @Implementation
    protected void paste(String text) {
        if (!closed && text != null && !text.isEmpty()) emulator.paste(text);
    }

    @Implementation
    protected boolean sendKey(int key, int action, int modifiers, String text,
                              int unshiftedCodePoint) {
        return sendKey(key, action, modifiers, 0, text, unshiftedCodePoint, false);
    }

    @Implementation
    protected boolean sendKey(int key, int action, int modifiers, int consumedModifiers,
                              String text, int unshiftedCodePoint, boolean composing) {
        keyEventCount++;
        // Basic fixture input only; native extended keyboard encoding is not exercised here.
        if (closed || action == GhosttyTerminal.KEY_ACTION_RELEASE || composing) return false;
        modifiers &= ~consumedModifiers;
        int legacyModifiers = 0;
        if ((modifiers & GhosttyTerminal.MOD_SHIFT) != 0) legacyModifiers |= KeyHandler.KEYMOD_SHIFT;
        if ((modifiers & GhosttyTerminal.MOD_CTRL) != 0) legacyModifiers |= KeyHandler.KEYMOD_CTRL;
        if ((modifiers & GhosttyTerminal.MOD_ALT) != 0) legacyModifiers |= KeyHandler.KEYMOD_ALT;
        if ((modifiers & GhosttyTerminal.MOD_NUM_LOCK) != 0) legacyModifiers |= KeyHandler.KEYMOD_NUM_LOCK;
        String encoded = KeyHandler.getCode(key, legacyModifiers,
            emulator.isCursorKeysApplicationMode(), emulator.isKeypadApplicationMode());
        if (encoded == null && text != null && !text.isEmpty()) {
            encoded = text;
            if ((modifiers & GhosttyTerminal.MOD_CTRL) != 0 && text.codePointCount(0, text.length()) == 1) {
                int codePoint = text.codePointAt(0);
                if (codePoint >= 'a' && codePoint <= 'z') codePoint -= 'a' - 1;
                else if (codePoint >= '@' && codePoint <= '_') codePoint &= 31;
                else if (codePoint == ' ' || codePoint == '2') codePoint = 0;
                else if (codePoint >= '3' && codePoint <= '7') codePoint -= 24;
                else if (codePoint == '/') codePoint = 31;
                else if (codePoint == '8') codePoint = 127;
                encoded = new String(Character.toChars(codePoint));
            }
            if ((modifiers & GhosttyTerminal.MOD_ALT) != 0) encoded = "\033" + encoded;
        }
        if (encoded == null || encoded.isEmpty()) return false;
        output.write(encoded);
        return true;
    }

    @Implementation
    protected boolean sendMouse(int action, int button, int modifiers, float x, float y,
                                int screenWidth, int screenHeight, int cellWidth, int cellHeight) {
        if (!isMouseTrackingActive() || cellWidth <= 0 || cellHeight <= 0) return false;
        if (action == GhosttyTerminal.MOUSE_ACTION_MOTION
            && !isModeEnabled("DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT")) return false;
        int legacyButton;
        switch (button) {
            case GhosttyTerminal.MOUSE_BUTTON_LEFT: legacyButton = 0; break;
            case GhosttyTerminal.MOUSE_BUTTON_MIDDLE: legacyButton = 1; break;
            case GhosttyTerminal.MOUSE_BUTTON_RIGHT: legacyButton = 2; break;
            case GhosttyTerminal.MOUSE_BUTTON_FOUR: legacyButton = 64; break;
            case GhosttyTerminal.MOUSE_BUTTON_FIVE: legacyButton = 65; break;
            default: return false;
        }
        if (action == GhosttyTerminal.MOUSE_ACTION_MOTION) legacyButton |= 32;
        if ((modifiers & GhosttyTerminal.MOD_SHIFT) != 0) legacyButton |= 4;
        if ((modifiers & GhosttyTerminal.MOD_ALT) != 0) legacyButton |= 8;
        if ((modifiers & GhosttyTerminal.MOD_CTRL) != 0) legacyButton |= 16;
        emulator.sendMouseEvent(legacyButton, (int) (x / cellWidth) + 1,
            (int) (y / cellHeight) + 1, action != GhosttyTerminal.MOUSE_ACTION_RELEASE);
        return true;
    }

    @Implementation
    protected void sendFocus(boolean focused) {
        if (!closed && isModeEnabled("DECSET_BIT_SEND_FOCUS_EVENTS"))
            output.write(focused ? "\033[I" : "\033[O");
    }

    private boolean isModeEnabled(String modeField) {
        int modes = ReflectionHelpers.getField(emulator, "mCurrentDecSetFlags");
        int mode = ReflectionHelpers.getStaticField(TerminalEmulator.class, modeField);
        return (modes & mode) != 0;
    }

    @Implementation
    protected void scrollViewport(int rows) {
        if (closed) return;
        topRow = (int) Math.max(-getScrollbackRows(), Math.min(0L, (long) topRow + rows));
    }

    @Implementation
    protected void scrollToBottom() { topRow = 0; }

    @Implementation
    protected void scrollToPrompt(int count) {
        if (!closed) promptScrollRequests.add(count);
    }

    /** Record UI dispatch only; prompt grouping is covered by Ghostty's native tests. */
    public List<Integer> getPromptScrollRequests() {
        return new ArrayList<>(promptScrollRequests);
    }

    /** Includes releases even though this fixture does not encode release reports. */
    public int getKeyEventCount() {
        return keyEventCount;
    }

    private void clampViewport() {
        topRow = Math.max(-getScrollbackRows(), Math.min(0, topRow));
    }

    @Implementation
    protected int[] search(String query, boolean next) { return new int[]{0, 0}; }

    @Implementation
    protected void clearSearch() { selection = null; }

    @Implementation
    protected void attachSurfaceWithFonts(Surface surface, int width, int height,
                                          int textSize, String[] fontPaths) { }

    @Implementation
    protected void attachSurface(Surface surface, int width, int height,
                                 int textSize, String fontPath) { }

    @Implementation
    protected void resizeSurfaceWithFonts(int width, int height, int textSize, String[] fontPaths) { }

    @Implementation
    protected void resizeSurface(int width, int height, int textSize, String fontPath) { }

    @Implementation
    protected boolean render(boolean cursorVisible) {
        // A successful no-op keeps the view from scheduling repeated render retries.
        return !closed;
    }

    @Implementation
    protected long getRenderHoldDelay() { return -1; }

    @Implementation
    protected long tickKittyGraphicsAnimations(long nowMillis) { return -1; }

    @Implementation
    protected void detachSurface() { }

    @Implementation
    protected int[] selectWordOrOutput(int column, int viewportRow) {
        int[] word = wordSelection(column, viewportRow);
        if (word == null) return null;
        selection = word;
        return new int[]{word[0], word[1] - topRow, word[2], word[3] - topRow};
    }

    /** Whitespace-delimited fixture words may continue over soft-wrapped rows. */
    private int[] wordSelection(int column, int viewportRow) {
        if (closed || column < 0 || column >= getColumns()
            || viewportRow < 0 || viewportRow >= getRows()) return null;
        clampViewport();
        int row = topRow + viewportRow;
        int startColumn = column;
        int endColumn = column;
        int startRow = row;
        int endRow = row;
        // A blank-cell selection still opens the toolbar, with copying disabled.
        if (cellText(column, row).trim().isEmpty()) return new int[]{column, row, column, row};
        TerminalBuffer screen = emulator.getScreen();
        while (true) {
            int previousColumn = startColumn - 1;
            int previousRow = startRow;
            if (previousColumn < 0) {
                if (startRow <= -getScrollbackRows() || !screen.getLineWrap(startRow - 1)) break;
                previousColumn = getColumns() - 1;
                --previousRow;
            }
            if (cellText(previousColumn, previousRow).trim().isEmpty()) break;
            startColumn = previousColumn;
            startRow = previousRow;
        }
        while (true) {
            int nextColumn = endColumn + 1;
            int nextRow = endRow;
            if (nextColumn >= getColumns()) {
                if (endRow >= getRows() - 1 || !screen.getLineWrap(endRow)) break;
                nextColumn = 0;
                ++nextRow;
            }
            if (cellText(nextColumn, nextRow).trim().isEmpty()) break;
            endColumn = nextColumn;
            endRow = nextRow;
        }
        return new int[]{startColumn, startRow, endColumn, endRow};
    }

    private String cellText(int column, int row) {
        return emulator.getScreen().getSelectedText(column, row, column, row);
    }

    @Implementation
    protected String getWordAt(int column, int viewportRow) {
        int[] word = wordSelection(column, viewportRow);
        return word == null ? null : emulator.getSelectedText(word[0], word[1], word[2], word[3]);
    }

    @Implementation
    protected boolean setSelection(int startColumn, int startViewportRow,
                                   int endColumn, int endViewportRow) {
        if (closed || startColumn < 0 || startColumn >= getColumns()
            || endColumn < 0 || endColumn >= getColumns()) return false;
        clampViewport();
        int startRow = topRow + startViewportRow;
        int endRow = topRow + endViewportRow;
        if (startRow < -getScrollbackRows() || endRow < -getScrollbackRows()
            || startRow >= getRows() || endRow >= getRows()) return false;
        selection = new int[]{startColumn, startRow, endColumn, endRow};
        return true;
    }

    @Implementation
    protected void clearSelection() { selection = null; }

    @Implementation
    protected String getSelectedText() {
        if (closed || selection == null) return null;
        int[] ordered = selection;
        if (selection[1] > selection[3] || (selection[1] == selection[3] && selection[0] > selection[2]))
            ordered = new int[]{selection[2], selection[3], selection[0], selection[1]};
        return emulator.getSelectedText(ordered[0], ordered[1], ordered[2], ordered[3]);
    }

    @Implementation
    protected String getHyperlink(int column, int viewportRow) { return null; }

    @Implementation
    protected long[] getMemoryUsageForCompression() { return null; }

    @Implementation
    protected int compressScrollbackIncrementally() { return 0; }

    @Implementation
    protected boolean isClosed() { return closed; }

    @Implementation
    protected void close() {
        closed = true;
        selection = null;
        topRow = 0;
    }
}
