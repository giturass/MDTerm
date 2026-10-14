package com.termux.view;

import android.app.Application;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.test.ShadowGhosttyTerminal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class,
    shadows = ShadowGhosttyTerminal.class)
public class TerminalComposingPreviewTest {

    @Test
    public void previewPreferenceAppliesEvenWhenImeComposesForTypeNull() {
        boolean[] previewEnabled = {false};
        TerminalView view = createView(previewEnabled);
        EditorInfo editorInfo = new EditorInfo();
        InputConnection connection = view.onCreateInputConnection(editorInfo);
        assertEquals(InputType.TYPE_NULL, editorInfo.inputType);

        // An IME is allowed to send composing updates even when TYPE_NULL was requested.
        connection.setComposingText("ni", 1);
        RecordingCanvas disabledCanvas = new RecordingCanvas();
        view.onDraw(disabledCanvas);
        assertFalse(disabledCanvas.textRuns.contains("ni"));

        previewEnabled[0] = true;
        RecordingCanvas enabledCanvas = new RecordingCanvas();
        view.onDraw(enabledCanvas);
        assertTrue(enabledCanvas.textRuns.contains("ni"));

        previewEnabled[0] = false;
        RecordingCanvas hiddenAgainCanvas = new RecordingCanvas();
        view.onDraw(hiddenAgainCanvas);
        assertFalse(hiddenAgainCanvas.textRuns.contains("ni"));
        assertEquals("ni", connection.getTextBeforeCursor(2, 0).toString());
        assertEquals("", takeOutput(view));
        assertEquals("", view.getTerminal().getTranscript(true, true));
    }

    @Test
    public void committingCompositionWritesUtf8OnceAndClearsPreviewWithoutLocalEcho() {
        TerminalView view = createView(new boolean[]{true});
        GhosttyTerminal terminal = view.getTerminal();
        feed(terminal, "prompt> ");
        String screenBefore = terminal.getTranscript(true, false);
        int[] cursorBefore = terminal.getCursorState();
        EditorInfo editorInfo = new EditorInfo();
        InputConnection connection = view.onCreateInputConnection(editorInfo);
        assertEquals(InputType.TYPE_CLASS_TEXT, editorInfo.inputType & InputType.TYPE_MASK_CLASS);

        assertTrue(connection.setComposingText("ni", 1));
        assertTrue(connection.setComposingText("你\ud83d\ude42", 1));
        RecordingCanvas composingCanvas = new RecordingCanvas();
        view.onDraw(composingCanvas);
        assertEquals(1, composingCanvas.textRuns.size());
        assertEquals("你\ud83d\ude42", composingCanvas.textRuns.get(0));
        assertEquals("", takeOutput(view));
        assertEquals(screenBefore, terminal.getTranscript(true, false));
        assertArrayEquals(cursorBefore, terminal.getCursorState());

        assertTrue(connection.commitText("你\ud83d\ude42", 1));
        assertEquals("你\ud83d\ude42", takeOutput(view));
        assertEquals("", connection.getTextBeforeCursor(10, 0).toString());
        assertTrue(connection.finishComposingText());
        assertEquals("", takeOutput(view));
        RecordingCanvas committedCanvas = new RecordingCanvas();
        view.onDraw(committedCanvas);
        assertTrue(committedCanvas.textRuns.isEmpty());
        assertEquals(screenBefore, terminal.getTranscript(true, false));
        assertArrayEquals(cursorBefore, terminal.getCursorState());
    }

    @Test
    public void finishingCompositionCommitsItOnceAndClearsPreview() {
        TerminalView view = createView(new boolean[]{true});
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        assertTrue(connection.setComposingText("你\ud83d\ude42", 1));
        assertEquals("", takeOutput(view));

        assertTrue(connection.finishComposingText());
        assertEquals("你\ud83d\ude42", takeOutput(view));
        assertTrue(connection.finishComposingText());
        assertEquals("", takeOutput(view));
        assertEquals("", connection.getTextBeforeCursor(10, 0).toString());
        RecordingCanvas canvas = new RecordingCanvas();
        view.onDraw(canvas);
        assertTrue(canvas.textRuns.isEmpty());
        assertEquals("", view.getTerminal().getTranscript(true, true));
    }

    @Test
    public void composingPreviewInheritsTextCellColorsAndEffectsWithoutChangingTheScreen() {
        GhosttyTerminal terminal = createTerminal();
        // Seed a styled text cell through the UI fixture; no native parser is exercised here.
        feed(terminal, "\033[1;3;9;38;2;161;83;231;48;2;17;34;51mX\033[D");
        ShadowGhosttyTerminal shadow = Shadow.extract(terminal);
        long cellStyle = shadow.getEmulator().getScreen().getStyleAt(0, 0);
        int[] cursor = terminal.getCursorState();
        int[] originalCursor = cursor.clone();
        TerminalComposingRenderer renderer = new TerminalComposingRenderer();
        RecordingPaint paint = new RecordingPaint();
        ReflectionHelpers.setField(renderer, "paint", paint);
        RecordingCanvas canvas = new RecordingCanvas();

        renderer.render(canvas, cursor, "你", -1, 20, Typeface.MONOSPACE, 10, 20);

        assertEquals(1, canvas.textRuns.size());
        assertEquals("你", canvas.textRuns.get(0));
        // The renderer receives resolved ARGB colors from the Ghostty cursor snapshot.
        assertEquals(0xffa153e7, canvas.textColor);
        assertEquals(0xff112233, (int) canvas.rectangleColors.get(0));
        assertTrue(paint.fakeBoldText);
        assertTrue(canvas.textSkewX < 0);
        assertTrue(canvas.textUnderlined);
        assertTrue(paint.strikeThruText);
        assertArrayEquals(originalCursor, cursor);
        assertArrayEquals(originalCursor, terminal.getCursorState());
        assertEquals(cellStyle, shadow.getEmulator().getScreen().getStyleAt(0, 0));
        assertEquals("X", terminal.getTranscript(true, true));

        RecordingCanvas plainCanvas = new RecordingCanvas();
        renderer.render(plainCanvas, new int[]{0, 0, 0xffa153e7, 0xff112233, 0},
            "a", -1, 20, Typeface.MONOSPACE, 10, 20);
        assertFalse(paint.fakeBoldText);
        assertEquals(0, plainCanvas.textSkewX, 0);
        assertFalse(paint.strikeThruText);
        assertTrue(plainCanvas.textUnderlined);
    }

    @Test
    public void composingPreviewDimsResolvedForegroundExactlyOnce() {
        int[] cursor = {2, 1, 0xffa153e7, 0xff112233, TextStyle.CHARACTER_ATTRIBUTE_DIM};
        int[] originalCursor = cursor.clone();
        RecordingCanvas canvas = new RecordingCanvas();

        new TerminalComposingRenderer().render(canvas, cursor, "a", -1,
            20, Typeface.MONOSPACE, 10, 20);

        assertEquals(0xff502973, canvas.textColor);
        assertEquals(0xff112233, (int) canvas.rectangleColors.get(0));
        assertArrayEquals(originalCursor, cursor);
    }

    @Test
    public void concealedCompositionKeepsBackgroundAndCaretWithoutDrawingText() {
        int[] cursor = {2, 1, 0xffa153e7, 0xff112233, TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE};
        RecordingCanvas canvas = new RecordingCanvas();

        new TerminalComposingRenderer().render(canvas, cursor, "你", 1,
            20, Typeface.MONOSPACE, 10, 20);

        assertTrue(canvas.textRuns.isEmpty());
        assertEquals(2, canvas.rectangles.size());
        assertEquals(new RectF(20, 20, 40, 40), canvas.rectangles.get(0));
        assertEquals(0xff112233, (int) canvas.rectangleColors.get(0));
        assertEquals(new RectF(40, 20, 42, 40), canvas.rectangles.get(1));
        assertEquals(0xffa153e7, (int) canvas.rectangleColors.get(1));
    }

    @Test
    public void composingCaretUsesWholeGraphemeCellWidths() {
        String[] clusters = {
            "a\u0301", "\ud83d\udc69\u200d\ud83d\udcbb", "1\ufe0f\u20e3", "\ud83c\uddfa\ud83c\uddf8", "你"
        };
        int[] widths = {1, 2, 2, 2, 2};
        int[] cursor = {2, 1, 0xffa153e7, 0xff112233, 0};
        TerminalComposingRenderer renderer = new TerminalComposingRenderer();
        for (int i = 0; i < clusters.length; i++) {
            String cluster = clusters[i];
            String composition = cluster + "x";
            RecordingCanvas canvas = new RecordingCanvas();
            renderer.render(canvas, cursor, composition, cluster.length(),
                20, Typeface.MONOSPACE, 10, 20);

            assertEquals(1, canvas.textRuns.size());
            assertEquals(composition, canvas.textRuns.get(0));
            assertEquals(2, canvas.rectangles.size());
            assertEquals(composition, new RectF(20, 20, 20 + (widths[i] + 1) * 10, 40),
                canvas.rectangles.get(0));
            float caretX = 20 + widths[i] * 10;
            assertEquals(composition, new RectF(caretX, 20, caretX + 2, 40), canvas.rectangles.get(1));

            // An IME offset inside a combining sequence or surrogate pair stays before the cluster.
            for (int offset = 1; offset < cluster.length(); offset++) {
                RecordingCanvas partialCanvas = new RecordingCanvas();
                renderer.render(partialCanvas, cursor, composition, offset,
                    20, Typeface.MONOSPACE, 10, 20);
                assertEquals(composition + " at offset " + offset,
                    new RectF(20, 20, 22, 40), partialCanvas.rectangles.get(1));
            }
        }
    }

    private static TerminalView createView(boolean[] previewEnabled) {
        TerminalView view = new TerminalView(RuntimeEnvironment.getApplication(), null);
        view.setTerminalViewClient((TerminalViewClient) Proxy.newProxyInstance(
            TerminalViewClient.class.getClassLoader(), new Class<?>[]{TerminalViewClient.class},
            (proxy, method, args) -> {
                if (method.getName().equals("shouldEnableImeComposing")) return previewEnabled[0];
                if (method.getName().equals("isTerminalViewSelected")) return true;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == float.class) return 1.0f;
                return null;
            }));
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
        GhosttyTerminal terminal = new GhosttyTerminal(session, 10, 4, 10, 20, 100);
        ReflectionHelpers.setField(session, "mTerminal", terminal);
        ReflectionHelpers.setField(session, "mShellPid", 1);
        view.setTextSize(20);
        view.setTypeface(Typeface.MONOSPACE);
        view.attachSession(session);
        assertEquals(10, view.getCellWidth());
        assertEquals(20, view.getCellHeight());
        return view;
    }

    private static void feed(GhosttyTerminal terminal, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        terminal.feed(bytes, 0, bytes.length);
    }

    private static String takeOutput(TerminalView view) {
        Object queue = ReflectionHelpers.getField(view.mTermSession, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    private static GhosttyTerminal createTerminal() {
        TerminalOutput output = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) { }
            @Override public void titleChanged(String oldTitle, String newTitle) { }
            @Override public void onCopyTextToClipboard(String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
        };
        return new GhosttyTerminal(output, 10, 4, 10, 20, 100);
    }

    /** Robolectric 4.10's legacy ShadowPaint does not record setFakeBoldText(). */
    private static final class RecordingPaint extends Paint {
        boolean fakeBoldText;
        boolean strikeThruText;

        @Override
        public void setFakeBoldText(boolean enabled) {
            fakeBoldText = enabled;
            super.setFakeBoldText(enabled);
        }

        @Override
        public void setStrikeThruText(boolean enabled) {
            strikeThruText = enabled;
            super.setStrikeThruText(enabled);
        }
    }

    private static class RecordingCanvas extends Canvas {
        final List<String> textRuns = new ArrayList<>();
        final List<RectF> rectangles = new ArrayList<>();
        final List<Integer> rectangleColors = new ArrayList<>();
        int textColor;
        float textSkewX;
        boolean textUnderlined;

        @Override
        public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            rectangles.add(new RectF(left, top, right, bottom));
            rectangleColors.add(paint.getColor());
        }

        @Override
        public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
                                float x, float y, boolean isRtl, Paint paint) {
            textRuns.add(new String(text, index, count));
            textColor = paint.getColor();
            textSkewX = paint.getTextSkewX();
            textUnderlined = paint.isUnderlineText();
        }
    }
}
