package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.icu.text.BreakIterator;

import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/** Draws uncommitted IME text over the Vulkan surface without changing terminal contents. */
final class TerminalComposingRenderer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BreakIterator graphemes = BreakIterator.getCharacterInstance();

    void render(Canvas canvas, int[] cursor, CharSequence composition, int cursorOffset,
                int textSize, Typeface typeface, int cellWidth, int cellHeight) {
        if (cursor == null || cursor.length < 5 || composition == null || composition.length() == 0)
            return;
        String text = composition.toString();
        int effects = cursor[4];
        paint.setTypeface(typeface);
        paint.setTextSize(textSize);
        paint.setFakeBoldText((effects & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0);
        paint.setTextSkewX((effects & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0 ? -0.25f : 0);
        paint.setUnderlineText(true);
        paint.setStrikeThruText((effects & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0);
        paint.setStyle(Paint.Style.FILL);

        int columns = columns(text, text.length());
        float left = cursor[0] * cellWidth;
        float top = cursor[1] * cellHeight;
        float width = Math.max(1, columns) * cellWidth;
        paint.setColor(cursor[3]);
        canvas.drawRect(left, top, left + width, top + cellHeight, paint);

        int foreground = cursor[2];
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) {
            foreground = (foreground & 0xff000000) | ((foreground & 0x00fefefe) >>> 1);
        }
        paint.setColor(foreground);
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            char[] chars = text.toCharArray();
            float measured = paint.measureText(chars, 0, chars.length);
            int save = canvas.save();
            if (measured > 0) canvas.scale(width / measured, 1, left, top);
            canvas.drawTextRun(chars, 0, chars.length, 0, chars.length,
                left, top + cellHeight - paint.descent(), false, paint);
            canvas.restoreToCount(save);
        }

        if (cursorOffset >= 0 && cursorOffset <= text.length()) {
            float x = left + columns(text, cursorOffset) * cellWidth;
            canvas.drawRect(x, top, x + Math.max(2, cellWidth * 0.12f), top + cellHeight, paint);
        }
    }

    private int columns(String text, int end) {
        graphemes.setText(text);
        int total = 0;
        for (int start = graphemes.first(), next = graphemes.next();
             next != BreakIterator.DONE && next <= end;
             start = next, next = graphemes.next()) {
            int width = 0;
            int regionalIndicators = 0;
            for (int offset = start; offset < next; ) {
                int codePoint = text.codePointAt(offset);
                width = Math.max(width, WcWidth.width(codePoint));
                if (codePoint == 0xfe0f || codePoint == 0x20e3) width = Math.max(width, 2);
                if (codePoint >= 0x1f1e6 && codePoint <= 0x1f1ff) regionalIndicators++;
                offset += Character.charCount(codePoint);
            }
            // A flag is one grapheme made from two narrow regional indicators.
            if (regionalIndicators == 2) width = Math.max(width, 2);
            total += width;
        }
        return total;
    }
}
