package com.termux.app.terminal;

import android.os.Handler;
import android.os.Looper;
import android.system.Os;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Resolves the foreground process, never a title or the session's initial directory. */
public final class BookmarkEnvironment {
    private BookmarkEnvironment() {}

    public interface Callback {
        void onCaptured(TerminalBookmark bookmark);
        void onError(String message);
        default void onQueryStarted() {}
        default boolean isActive() { return true; }
    }

    public static void capture(TerminalSession session, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (session == null || !session.isRunning()) {
            callback.onError("当前会话未运行，无法保存书签");
            return;
        }
        new Thread(() -> {
            try {
                BookmarkProcessSnapshot snapshot = BookmarkProcessSnapshot.read(session.getPid());
                BookmarkProcessSnapshot.Process foreground = snapshot.foreground;
                BookmarkProcessSnapshot.Process ssh = null;
                String distro = "";
                boolean proot = false;
                for (BookmarkProcessSnapshot.Process process = foreground; process != null; process = process.parent) {
                    if (ssh == null && "ssh".equals(executable(process.args))) ssh = process;
                    if ("proot".equals(executable(process.args))) proot = true;
                    String detected = distroFromArguments(process.args);
                    if (distro.isEmpty() && !detected.isEmpty()) distro = detected;
                }
                if (proot && distro.isEmpty()) {
                    throw new IllegalStateException("无法识别当前 proot 的 distro，未保存书签");
                }
                if (ssh == null && !isShell(executable(foreground.args))) {
                    throw new IllegalStateException("请先返回 Shell 提示符，再保存当前位置");
                }
                String kind = ssh != null ? "ssh" : (proot || !distro.isEmpty() ? "proot" : "local");
                List<String> sshArgs = ssh == null ? Collections.emptyList()
                    : normalizeSshPaths(sshArguments(ssh.args), Os.readlink("/proc/" + ssh.pid + "/cwd"), distro);
                final String targetDistro = distro;
                final int foregroundPid = foreground.pid;
                final int foregroundGroup = snapshot.foregroundGroup;
                if ("local".equals(kind)) {
                    String path = Os.readlink("/proc/" + foregroundPid + "/cwd");
                    if (!path.startsWith("/") || path.endsWith(" (deleted)"))
                        throw new IllegalStateException("当前目录不可用，未保存书签");
                    main.post(() -> {
                        if (!callback.isActive()) { callback.onError(null); return; }
                        try {
                            if (BookmarkProcessSnapshot.readForegroundGroup(session.getPid()) != foregroundGroup
                                || !path.equals(Os.readlink("/proc/" + foregroundPid + "/cwd"))) {
                                callback.onError("终端环境已变化，请重新保存书签");
                                return;
                            }
                            callback.onCaptured(target(kind, targetDistro, sshArgs, path));
                        } catch (Exception e) {
                            callback.onError("当前终端环境已不可用");
                        }
                    });
                } else {
                    main.post(() -> {
                        if (!callback.isActive()) { callback.onError(null); return; }
                        try {
                            if (BookmarkProcessSnapshot.readForegroundGroup(session.getPid()) != foregroundGroup
                                || !new File("/proc/" + foregroundPid).exists()) {
                                callback.onError("终端环境已变化，请重新保存书签");
                                return;
                            }
                        } catch (Exception e) {
                            callback.onError("当前终端环境已不可用");
                            return;
                        }
                        callback.onQueryStarted();
                        session.requestBookmarkLocation(new TerminalSession.BookmarkLocationCallback() {
                        @Override public void onLocation(String path) {
                            if (!callback.isActive()) { callback.onError(null); return; }
                            try {
                                if (BookmarkProcessSnapshot.readForegroundGroup(session.getPid()) != foregroundGroup
                                    || !new File("/proc/" + foregroundPid).exists()) {
                                    callback.onError("终端环境已变化，请重新保存书签");
                                    return;
                                }
                            } catch (Exception e) {
                                callback.onError("当前终端环境已不可用");
                                return;
                            }
                            callback.onCaptured(target(kind, targetDistro, sshArgs, path));
                        }
                        @Override public void onFailure() {
                            callback.onError("未能读取环境中的当前路径，请返回 Shell 提示符后重试（需要 base64 命令）");
                        }
                        });
                    });
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage("BookmarkEnvironment", "Failed to capture terminal bookmark", e);
                String message = e instanceof IllegalStateException ? e.getMessage()
                    : "无法读取当前终端环境，未保存书签";
                main.post(() -> callback.onError(message));
            }
        }, "BookmarkEnvironment").start();
    }

    private static TerminalBookmark target(String kind, String distro, List<String> sshArgs, String path) {
        String name = new File(path).getName();
        if (name.isEmpty()) name = path;
        return new TerminalBookmark(UUID.randomUUID().toString(), name, kind, distro, sshArgs, path);
    }

    public static String launchWorkingDirectory(TerminalBookmark bookmark) {
        return "local".equals(bookmark.kind) ? bookmark.path : TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    /** Passed as one argument to the new session's shell -c; never typed after a timer. */
    public static String launchCommand(TerminalBookmark bookmark) {
        String enter = "cd -- " + quote(bookmark.path)
            + " && exec \"${SHELL:-/bin/sh}\" -l";
        if ("local".equals(bookmark.kind)) return enter;
        if ("ssh".equals(bookmark.kind)) {
            StringBuilder command = new StringBuilder("exec ssh -t");
            for (String arg : bookmark.sshArgs) command.append(' ').append(quote(arg));
            // SSH invokes the user's login shell, which may be fish: run our POSIX script via sh.
            command.append(' ').append(quote("sh -c " + quote(enter)));
            if (bookmark.distro.isEmpty()) return command.toString();
            return prootLogin(bookmark.distro, command.toString());
        }
        if ("proot".equals(bookmark.kind)) {
            return prootLogin(bookmark.distro, enter);
        }
        throw new IllegalArgumentException("Unknown bookmark environment");
    }

    private static String prootLogin(String distro, String command) {
        // pd may only be an interactive alias; proot-distro is the package's actual executable.
        return "exec \"$(command -v pd || command -v proot-distro)\" login " + quote(distro)
            + " -- sh -lc " + quote(command);
    }

    public static String quote(String value) {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in shell argument");
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Preserve SSH options and destination, reject sessions launched with a remote command. */
    static List<String> sshArguments(List<String> args) {
        List<String> result = new ArrayList<>();
        boolean destination = false;
        boolean options = true;
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (destination) {
                if (i == args.size() - 1 && isRestoreCommand(arg)) break;
                throw new IllegalStateException("带远程命令的 SSH 会话暂不支持保存书签");
            }
            if (options && "--".equals(arg)) {
                result.add(arg);
                options = false;
            } else if (options && arg.startsWith("-") && arg.length() > 1) {
                if ("-T".equals(arg)) continue; // Bookmarks always open an interactive PTY.
                result.add(arg);
                for (int j = 1; j < arg.length(); j++) {
                    char option = arg.charAt(j);
                    if (option == 'N' || option == 's' || option == 'f')
                        throw new IllegalStateException("当前 SSH 不是交互式会话，无法保存书签");
                    if ("BbcDEeFIiJLlmOopQRSWw".indexOf(option) >= 0) {
                        if (j == arg.length() - 1) {
                            if (++i >= args.size()) throw new IllegalStateException("SSH 参数不完整");
                            result.add(args.get(i));
                        }
                        break;
                    }
                }
            } else {
                if (arg.isEmpty()) throw new IllegalStateException("SSH 目标不可用");
                result.add(arg);
                destination = true;
            }
        }
        if (!destination) throw new IllegalStateException("无法读取 SSH 连接信息");
        return result;
    }

    private static boolean isRestoreCommand(String command) {
        if (!command.startsWith("sh -c ")) return false;
        String inner = unquote(command.substring(6));
        if (inner == null || !inner.startsWith("cd -- ")) return false;
        String suffix = " && exec \"${SHELL:-/bin/sh}\" -l";
        if (!inner.endsWith(suffix)) return false;
        String path = unquote(inner.substring(6, inner.length() - suffix.length()));
        return path != null && path.startsWith("/");
    }

    private static String unquote(String value) {
        if (value.length() < 2 || value.charAt(0) != '\'' || value.charAt(value.length() - 1) != '\'')
            return null;
        String decoded = value.substring(1, value.length() - 1).replace("'\\''", "'");
        return quote(decoded).equals(value) ? decoded : null;
    }

    /** Relative SSH files are resolved at the original client working directory, not the bookmark cwd. */
    static List<String> normalizeSshPaths(List<String> args, String clientCwd, String distro) {
        List<String> normalized = new ArrayList<>(args);
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--".equals(arg) || !arg.startsWith("-") || arg.length() < 2) break;
            for (int j = 1; j < arg.length(); j++) {
                char option = arg.charAt(j);
                if ("BbcDEeFIiJLlmOopQRSWw".indexOf(option) < 0) continue;
                boolean attached = j < arg.length() - 1;
                int valueIndex = attached ? i : ++i;
                String value = attached ? arg.substring(j + 1) : args.get(valueIndex);
                String replacement = value;
                if (option == 'i' || option == 'F' || option == 'E' || option == 'S') {
                    replacement = resolveSshPath(value, clientCwd, distro);
                } else if (option == 'o') {
                    replacement = normalizeSshConfigPath(value, clientCwd, distro);
                }
                normalized.set(valueIndex, attached ? arg.substring(0, j + 1) + replacement : replacement);
                break;
            }
        }
        return normalized;
    }

    private static String normalizeSshConfigPath(String option, String clientCwd, String distro) {
        int separator = option.indexOf('=');
        int space = option.indexOf(' ');
        if (separator < 0 || (space >= 0 && space < separator)) separator = space;
        if (separator < 0) return option;
        String key = option.substring(0, separator).toLowerCase(java.util.Locale.ROOT);
        if (!key.equals("identityfile") && !key.equals("certificatefile")
            && !key.equals("userknownhostsfile") && !key.equals("globalknownhostsfile")
            && !key.equals("controlpath")) return option;
        String value = option.substring(separator + 1).trim();
        char delimiter = value.isEmpty() ? 0 : value.charAt(0);
        boolean quoted = delimiter == '\'' || delimiter == '"';
        if (quoted && value.length() >= 2 && value.charAt(value.length() - 1) == delimiter) {
            String path = value.substring(1, value.length() - 1);
            if (path.indexOf(delimiter) >= 0 || path.indexOf('\\') >= 0)
                throw new IllegalStateException("SSH 文件选项包含复杂引号，请改用绝对路径后保存");
            return option.substring(0, separator + 1) + delimiter
                + resolveSshPath(path, clientCwd, distro) + delimiter;
        }
        if (quoted) throw new IllegalStateException("SSH 文件选项引号不完整，无法保存");
        if (value.matches(".*\\s+.*")) {
            boolean allAbsolute = true;
            for (String path : value.split("\\s+")) {
                if (!path.startsWith("/") && !path.startsWith("~") && !path.startsWith("%d/"))
                    allAbsolute = false;
            }
            if (allAbsolute) return option;
            throw new IllegalStateException("SSH 文件选项包含多个路径，请改用单个绝对路径后保存");
        }
        String resolved = resolveSshPath(value, clientCwd, distro);
        if (resolved.matches(".*[\\s\"\\\\].*"))
            resolved = "\"" + resolved.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return option.substring(0, separator + 1) + resolved;
    }

    private static String resolveSshPath(String path, String clientCwd, String distro) {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("~")
            || path.startsWith("%d/") || "none".equalsIgnoreCase(path)) return path;
        String cwd = clientCwd;
        if (!distro.isEmpty()) {
            String marker = "/installed-rootfs/" + distro;
            int index = cwd.indexOf(marker);
            int end = index + marker.length();
            if (index < 0 || (end < cwd.length() && cwd.charAt(end) != '/'))
                throw new IllegalStateException("无法还原 proot 中 SSH 文件的相对路径，请使用绝对路径后保存");
            cwd = cwd.substring(end);
            if (cwd.isEmpty()) cwd = "/";
        }
        if (!cwd.startsWith("/") || cwd.endsWith(" (deleted)"))
            throw new IllegalStateException("SSH 的原始目录不可用，无法还原相对文件路径");
        return cwd + (cwd.endsWith("/") ? "" : "/") + path;
    }

    static String distroFromArguments(List<String> args) {
        for (String arg : args) {
            String marker = "/installed-rootfs/";
            int index = arg.indexOf(marker);
            if (index >= 0) {
                String suffix = arg.substring(index + marker.length());
                int end = suffix.indexOf('/');
                if (end >= 0) suffix = suffix.substring(0, end);
                end = suffix.indexOf(':');
                if (end >= 0) suffix = suffix.substring(0, end);
                if (suffix.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) return suffix;
            }
        }
        for (int i = 0; i + 2 < args.size(); i++) {
            String binary = new File(args.get(i)).getName();
            if (("pd".equals(binary) || "proot-distro".equals(binary)) && "login".equals(args.get(i + 1))
                && !args.get(i + 2).startsWith("-")) return args.get(i + 2);
        }
        return "";
    }

    private static String executable(List<String> args) {
        if (args.isEmpty()) return "";
        String name = new File(args.get(0)).getName();
        return name.startsWith("-") ? name.substring(1) : name;
    }

    private static boolean isShell(String name) {
        return name.equals("sh") || name.equals("bash") || name.equals("zsh") || name.equals("fish")
            || name.equals("dash") || name.equals("ksh") || name.equals("ash") || name.equals("nu");
    }

}
