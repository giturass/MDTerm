package com.termux.view;

import android.app.Application;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class)
public class TerminalComposingPreviewTest {

    @Test
    public void previewPreferenceAppliesEvenWhenImeComposesForTypeNull() {
        boolean[] previewEnabled = {false};
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
        view.mEmulator = createEmulator();
        view.mRenderer = new TerminalRenderer(20, Typeface.MONOSPACE);
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
    }

    @Test
    public void composingPreviewInheritsTextCellColorsAndEffectsWithoutChangingTheScreen() {
        TerminalEmulator emulator = createEmulator();
        // Put bold italic red text on a blue background, then return to that cell.
        byte[] output = "\033[1;3;31;44mX\033[D".getBytes(StandardCharsets.UTF_8);
        emulator.append(output, output.length);
        long cellStyle = emulator.getScreen().getStyleAt(0, 0);
        TerminalRenderer renderer = new TerminalRenderer(20, Typeface.MONOSPACE);
        RecordingCanvas canvas = new RecordingCanvas();

        renderer.renderComposingText(emulator, canvas, 0, new char[]{'你'}, 0, 1, -1);

        assertEquals(1, canvas.textRuns.size());
        assertEquals("你", canvas.textRuns.get(0));
        // Robolectric 4.10's legacy ShadowPaint does not implement setFakeBoldText().
        // Verify the bold effect through its bright foreground color instead.
        assertEquals(emulator.mColors.mCurrentColors[9], canvas.textColor);
        assertEquals(emulator.mColors.mCurrentColors[4], canvas.backgroundColor);
        assertTrue(canvas.textSkewX < 0);
        assertTrue(canvas.textUnderlined);
        assertEquals(cellStyle, emulator.getScreen().getStyleAt(0, 0));
        assertEquals("X", emulator.getScreen().getTranscriptText());
    }

    private static TerminalEmulator createEmulator() {
        TerminalOutput output = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) { }
            @Override public void titleChanged(String oldTitle, String newTitle) { }
            @Override public void onCopyTextToClipboard(String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
        };
        return new TerminalEmulator(output, 10, 4, 10, 20, 100, null);
    }

    private static class RecordingCanvas extends Canvas {
        final List<String> textRuns = new ArrayList<>();
        int textColor;
        int backgroundColor;
        float textSkewX;
        boolean textUnderlined;

        @Override
        public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            backgroundColor = paint.getColor();
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
