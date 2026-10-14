package com.termux.app.terminal;

import android.content.Context;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Makes the bundled Android font available to the native FreeType renderer. */
final class TerminalFontFiles {
    private TerminalFontFiles() { }

    static File[] resolve(Context context, File customFont) throws IOException {
        List<File> fonts = new ArrayList<>();
        if (customFont.isFile() && customFont.length() > 0) {
            fonts.add(customFont);
        } else {
            File bundled = new File(context.getCacheDir(), "mdterm-terminal-font.ttf");
            byte[] data;
            try (InputStream input = context.getResources().openRawResource(R.font.terminal_default);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
                data = output.toByteArray();
            }
            // Refresh the cache after an APK upgrade that changes the bundled font.
            if (!bundled.isFile() || !Arrays.equals(data, Files.readAllBytes(bundled.toPath()))) {
                File temporary = File.createTempFile("terminal-font-", ".ttf", context.getCacheDir());
                try {
                    Files.write(temporary.toPath(), data);
                    Files.move(temporary.toPath(), bundled.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary.toPath());
                }
            }
            fonts.add(bundled);
        }

        File directory = new File(TermuxConstants.TERMUX_DATA_HOME_DIR, "fonts");
        File[] fallbackFonts = directory.listFiles(file -> {
            String name = file.getName().toLowerCase(Locale.ROOT);
            return file.isFile() && file.canRead() &&
                (name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc"));
        });
        if (fallbackFonts != null) {
            Arrays.sort(fallbackFonts, Comparator.comparing(File::getName));
            fonts.addAll(Arrays.asList(fallbackFonts));
        }
        return fonts.toArray(new File[0]);
    }
}
