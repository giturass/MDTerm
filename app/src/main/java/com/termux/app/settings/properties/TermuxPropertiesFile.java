package com.termux.app.settings.properties;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

/** Edits individual UTF-8 properties without rewriting the user's configuration template. */
public final class TermuxPropertiesFile {
    // Serialize editors in this process, including separate settings screen instances.
    private static final Object WRITE_LOCK = new Object();
    private final File file;

    public TermuxPropertiesFile(File file) {
        if (file == null) throw new NullPointerException("file");
        this.file = file;
    }

    public synchronized Properties read() throws IOException {
        return parse(decode(Snapshot.read(file.getCanonicalFile()).bytes));
    }

    /** A null value removes every active assignment for the key, restoring the upstream default. */
    public synchronized void set(String key, String value) throws IOException {
        if (key == null) throw new NullPointerException("key");
        synchronized (WRITE_LOCK) {
            // Resolve links before replacing: rename must replace the target, not the user's link.
            File target = file.getCanonicalFile();
            Snapshot original = Snapshot.read(target);
            String source = decode(original.bytes);
            String updated = replace(source, key, value);
            if (source.equals(updated)) return;

            File parent = target.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs()
                && !parent.isDirectory())) {
                throw new IOException("Cannot create properties directory: " + parent);
            }

            File temporary = File.createTempFile(".termux-properties-", ".tmp", parent);
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(updated.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                // External editors do not share our lock. Check both link destination and bytes
                // immediately before renaming; a race after this check cannot be excluded.
                if (!target.equals(file.getCanonicalFile())
                    || !original.matches(Snapshot.read(target))) {
                    throw new IOException("Properties changed while saving; reload and try again");
                }
                // Android's rename within the same directory is atomic. Never delete the original
                // first and never fall back to copying over it if replacement fails.
                if (!temporary.renameTo(target)) {
                    throw new IOException("Cannot replace properties file: " + target);
                }
            } finally {
                if (temporary.exists()) temporary.delete();
            }
        }
    }

    private static String replace(String source, String key, String value) throws IOException {
        Properties originalProperties = parse(source);
        Properties expected = new Properties();
        expected.putAll(originalProperties);
        if (value == null) expected.remove(key);
        else expected.setProperty(key, value);

        StringBuilder result = new StringBuilder(source.length() + 80);
        String newline = preferredNewline(source);
        boolean found = false;
        int start = 0;
        while (start < source.length()) {
            int end = logicalLineEnd(source, start);
            String line = source.substring(start, end);
            Properties entry = parse(line);
            if (entry.containsKey(key)) {
                // Replace every duplicate, including escaped spellings of the same key.
                if (value != null) {
                    result.append(escape(key, true)).append(" = ").append(escape(value, false));
                    result.append(ending(line));
                }
                found = true;
            } else {
                result.append(line);
            }
            start = end;
        }
        if (!found && value != null) {
            if (result.length() != 0) {
                // A final continuation must be terminated before appending a new assignment.
                String finalLine = source.substring(lastNaturalLineStart(source));
                if (ending(source).isEmpty()) result.append(newline);
                if (continues(finalLine)) result.append(newline);
                if (!originalProperties.equals(parse(result.toString()))) {
                    // At EOF a lone backslash can represent an empty key. A newline would
                    // discard that entry, so complete its assignment before adding another.
                    result.setLength(source.length());
                    if (ending(source).isEmpty()) result.append(newline);
                    result.append('=').append(newline);
                }
            }
            result.append(escape(key, true)).append(" = ").append(escape(value, false))
                .append(newline);
        }

        String updated = result.toString();
        // Guard against obscure continuation combinations joining otherwise unrelated entries.
        // Refusing an ambiguous edit is preferable to changing another setting silently.
        if (!expected.equals(parse(updated))) {
            throw new IOException("Cannot safely edit this properties file; check line continuations");
        }
        return updated;
    }

    private static int logicalLineEnd(String source, int start) {
        int end = naturalLineEnd(source, start);
        int first = start;
        while (first < end && isSpace(source.charAt(first))) first++;
        // Comments never continue, even if their final character is a backslash.
        if (first == end || source.charAt(first) == '#' || source.charAt(first) == '!') return end;
        while (end < source.length() && continues(source.substring(start, end))) {
            start = end;
            end = naturalLineEnd(source, start);
        }
        return end;
    }

    private static int naturalLineEnd(String source, int start) {
        int end = start;
        while (end < source.length() && source.charAt(end) != '\r'
            && source.charAt(end) != '\n') end++;
        if (end < source.length()) {
            char separator = source.charAt(end++);
            if (separator == '\r' && end < source.length() && source.charAt(end) == '\n') end++;
        }
        return end;
    }

    private static int lastNaturalLineStart(String source) {
        int last = 0;
        for (int start = 0; start < source.length();) {
            last = start;
            start = naturalLineEnd(source, start);
        }
        return last;
    }

    private static boolean continues(String line) {
        int end = line.length() - ending(line).length();
        int slashes = 0;
        while (end > 0 && line.charAt(--end) == '\\') slashes++;
        return slashes % 2 != 0;
    }

    private static String ending(String line) {
        if (line.endsWith("\r\n")) return "\r\n";
        if (line.endsWith("\n")) return "\n";
        if (line.endsWith("\r")) return "\r";
        return "";
    }

    private static String preferredNewline(String source) {
        return source.isEmpty() ? "\n" : defaultNewline(ending(source.substring(0,
            naturalLineEnd(source, 0))));
    }

    private static String defaultNewline(String newline) {
        return newline.isEmpty() ? "\n" : newline;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    private static String escape(String text, boolean key) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\': escaped.append("\\\\"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                case '\f': escaped.append("\\f"); break;
                default:
                    // Preserve even isolated UTF-16 surrogates accepted by Properties.load;
                    // encoding those directly as UTF-8 would silently replace them with '?'.
                    if (Character.isSurrogate(c)) {
                        String hex = Integer.toHexString(c);
                        escaped.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) escaped.append('0');
                        escaped.append(hex);
                        break;
                    }
                    if ((c == ' ' && (key || i == 0)) || c == '=' || c == ':'
                        || c == '#' || c == '!') escaped.append('\\');
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static Properties parse(String source) throws IOException {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(source));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid properties escape sequence", e);
        }
        return properties;
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Properties file is not valid UTF-8", e);
        }
    }

    private static final class Snapshot {
        final boolean exists;
        final long modified;
        final byte[] bytes;

        Snapshot(boolean exists, long modified, byte[] bytes) {
            this.exists = exists;
            this.modified = modified;
            this.bytes = bytes;
        }

        static Snapshot read(File file) throws IOException {
            if (!file.exists()) return new Snapshot(false, 0, new byte[0]);
            if (!file.isFile()) throw new IOException("Not a regular properties file: " + file);
            long modified = file.lastModified();
            long length = file.length();
            ByteArrayOutputStream contents = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) contents.write(buffer, 0, count);
            }
            byte[] bytes = contents.toByteArray();
            if (!file.exists() || modified != file.lastModified() || length != file.length()
                || length != bytes.length) {
                throw new IOException("Properties changed while reading; reload and try again");
            }
            return new Snapshot(true, modified, bytes);
        }

        boolean matches(Snapshot other) {
            return exists == other.exists && modified == other.modified
                && Arrays.equals(bytes, other.bytes);
        }
    }
}
