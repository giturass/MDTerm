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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the foreground process, never a title or the session's initial directory. */
public final class BookmarkEnvironment {
    private static final Pattern PROOT_ROOTFS = Pattern.compile(
        "^(.*/(?:installed-rootfs/([A-Za-z0-9][A-Za-z0-9_.-]*)"
            + "|proot-distro/containers/([A-Za-z0-9][A-Za-z0-9_.-]*)/rootfs))(?=/|$)");

    private BookmarkEnvironment() {}

    public interface Callback {
        void onCaptured(TerminalBookmark bookmark);
        void onError(String message);
        default void onQueryStarted() {}
        default boolean isActive() { return true; }
    }

    public static void capture(TerminalSession session, Callback callback) {
        capture(session, callback, new File("/proc"));
    }

    static void capture(TerminalSession session, Callback callback, File procDirectory) {
        Handler main = new Handler(Looper.getMainLooper());
        if (session == null || !session.isRunning()) {
            callback.onError("当前会话未运行，无法保存书签");
            return;
        }
        new Thread(() -> {
            try {
                BookmarkProcessSnapshot snapshot = BookmarkProcessSnapshot.read(procDirectory, session.getPid());
                BookmarkProcessSnapshot.Process foreground = snapshot.foreground;
                BookmarkProcessSnapshot.Process ssh = findSshProcess(foreground);
                String distro = "";
                boolean proot = false;
                BookmarkProcessSnapshot.Process prootProcess = null;
                // A proxy helper's environment does not belong to the selected SSH client.
                for (BookmarkProcessSnapshot.Process process = ssh != null ? ssh : foreground;
                     process != null; process = process.parent) {
                    boolean isProot = "proot".equals(executable(process.args));
                    if (isProot) {
                        if (prootProcess != null)
                            throw new IllegalStateException("暂不支持保存多层嵌套的 PRoot 环境");
                        proot = true;
                        prootProcess = process;
                    }
                    String detected = distroFromArguments(process.args);
                    if (isProot && detected.isEmpty()) {
                        // New proot-distro execs proot with --rootfs=. after fchdir(rootfs).
                        // Use the tracer's cwd: the guest shell may be in a host bind mount.
                        detected = distroFromArguments(process.args,
                            readCwd(procDirectory, process.pid));
                    }
                    if (distro.isEmpty() && !detected.isEmpty()) distro = detected;
                }
                if (proot && distro.isEmpty()) {
                    throw new IllegalStateException("无法识别当前 proot 的 distro，未保存书签");
                }
                if (ssh == null && !isShell(executable(foreground.args))) {
                    throw new IllegalStateException("请先返回 Shell 提示符，再保存当前位置");
                }
                final BookmarkProotLaunch prootLaunch;
                try {
                    prootLaunch = prootProcess == null ? null : BookmarkProotLaunch.capture(
                        prootProcess, readCwd(procDirectory, prootProcess.pid), procDirectory);
                    boolean initialSu = false;
                    for (BookmarkProcessSnapshot.Process process = ssh == null ? foreground : ssh;
                         process != null && process != prootProcess; process = process.parent) {
                        String executable = executable(process.args);
                        if (executable.equals("su") || executable.equals("sudo")
                            || executable.equals("runuser") || executable.equals("doas")) {
                            if (prootLaunch != null && !initialSu && prootLaunch.isInitialSu(process.args))
                                initialSu = true;
                            else if (prootLaunch != null)
                                throw new IllegalStateException("PRoot 登录后切换了用户，请使用目标用户直接登录后保存");
                        }
                    }
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("无法完整还原当前 PRoot 启动命令，请使用交互式登录后保存", e);
                }
                String kind = ssh != null ? "ssh" : (proot || !distro.isEmpty() ? "proot" : "local");
                List<String> sshArgs = ssh == null ? Collections.emptyList()
                    : normalizeSshPaths(sshArguments(ssh.args), readCwd(procDirectory, ssh.pid), distro);
                final String targetDistro = distro;
                final int foregroundPid = foreground.pid;
                final int foregroundGroup = snapshot.foregroundGroup;
                if ("local".equals(kind)) {
                    final String path = readCwd(procDirectory, foregroundPid);
                    if (!path.startsWith("/") || path.endsWith(" (deleted)"))
                        throw new IllegalStateException("当前目录不可用，未保存书签");
                    main.post(() -> {
                        if (!callback.isActive()) { callback.onError(null); return; }
                        try {
                            if (BookmarkProcessSnapshot.readForegroundGroup(procDirectory, session.getPid()) != foregroundGroup
                                || !path.equals(readCwd(procDirectory, foregroundPid))) {
                                callback.onError("终端环境已变化，请重新保存书签");
                                return;
                            }
                            callback.onCaptured(target(kind, targetDistro, sshArgs, path, prootLaunch));
                        } catch (Exception e) {
                            callback.onError("当前终端环境已不可用");
                        }
                    });
                } else {
                    // PRoot emulates chdir/getcwd without updating the kernel cwd. Even a
                    // /proc/PID/cwd inside rootfs can be stale (often rootfs itself), so
                    // always ask the guest shell, as we do for a remote SSH directory.
                    main.post(() -> {
                        if (!callback.isActive()) { callback.onError(null); return; }
                        try {
                            if (BookmarkProcessSnapshot.readForegroundGroup(procDirectory, session.getPid()) != foregroundGroup
                                || !new File(procDirectory, Integer.toString(foregroundPid)).exists()) {
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
                                if (BookmarkProcessSnapshot.readForegroundGroup(procDirectory, session.getPid()) != foregroundGroup
                                    || !new File(procDirectory, Integer.toString(foregroundPid)).exists()) {
                                    callback.onError("终端环境已变化，请重新保存书签");
                                    return;
                                }
                            } catch (Exception e) {
                                callback.onError("当前终端环境已不可用");
                                return;
                            }
                            callback.onCaptured(target(kind, targetDistro, sshArgs, path, prootLaunch));
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

    private static String readCwd(File procDirectory, int pid) throws android.system.ErrnoException {
        return Os.readlink(new File(procDirectory, pid + "/cwd").getPath());
    }

    /** The outer SSH owns the destination; descendants may only connect to a jump host. */
    static BookmarkProcessSnapshot.Process findSshProcess(BookmarkProcessSnapshot.Process foreground) {
        BookmarkProcessSnapshot.Process ssh = null;
        for (BookmarkProcessSnapshot.Process process = foreground; process != null; process = process.parent) {
            if ("ssh".equals(executable(process.args))) ssh = process;
        }
        return ssh;
    }

    private static TerminalBookmark target(String kind, String distro, List<String> sshArgs, String path,
                                           BookmarkProotLaunch prootLaunch) {
        String name = new File(path).getName();
        if (name.isEmpty()) name = path;
        return new TerminalBookmark(UUID.randomUUID().toString(), name, kind, distro, sshArgs, path, prootLaunch);
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
            if (bookmark.prootLaunch != null) return bookmark.prootLaunch.launch(null, command.toString());
            if (bookmark.distro.isEmpty()) return command.toString();
            return prootLogin(bookmark.distro, command.toString());
        }
        if ("proot".equals(bookmark.kind)) {
            if (bookmark.prootLaunch != null) return bookmark.prootLaunch.launch(bookmark.path, null);
            // Let proot-distro select the guest account's login shell. Its environment
            // need not export SHELL, so a sh wrapper can otherwise fall back to /bin/sh.
            return "exec \"$(command -v pd || command -v proot-distro)\" login --work-dir "
                + quote(bookmark.path) + " " + quote(bookmark.distro);
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

    static boolean isRestoreCommand(String command) {
        String inner = unwrapShell(command);
        if (inner == null || !inner.startsWith("cd -- ")) return false;
        String suffix = " && exec \"${SHELL:-/bin/sh}\" -l";
        if (!inner.endsWith(suffix)) return false;
        String path = unquote(inner.substring(6, inner.length() - suffix.length()));
        return path != null && path.startsWith("/");
    }

    static boolean isProotRestoreCommand(String command) {
        if (isRestoreCommand(command)) return true;
        String inner = unwrapShell(command);
        if (inner == null) inner = command;
        if (!inner.startsWith("exec ssh -t ")) return false;
        List<String> args = new ArrayList<>();
        args.add("ssh");
        args.add("-t");
        String quoted = inner.substring("exec ssh -t ".length());
        for (int i = 0; i < quoted.length();) {
            if (quoted.charAt(i++) != '\'') return false;
            StringBuilder value = new StringBuilder();
            boolean closed = false;
            while (i < quoted.length()) {
                if (quoted.startsWith("'\\''", i)) { value.append('\''); i += 4; }
                else if (quoted.charAt(i) == '\'') { i++; closed = true; break; }
                else value.append(quoted.charAt(i++));
            }
            if (!closed) return false;
            args.add(value.toString());
            if (i < quoted.length() && quoted.charAt(i++) != ' ') return false;
        }
        if (args.size() < 4 || !isRestoreCommand(args.get(args.size() - 1))) return false;
        try {
            sshArguments(args);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static String unwrapShell(String command) {
        for (String prefix : new String[]{"sh -c ", "/bin/sh -c ", "sh -lc ", "/bin/sh -lc "}) {
            if (command.startsWith(prefix)) return unquote(command.substring(prefix.length()));
        }
        return null;
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
            Matcher rootfs = PROOT_ROOTFS.matcher(cwd);
            if (!rootfs.find() || !distro.equals(rootfsDistro(rootfs)))
                throw new IllegalStateException("无法还原 proot 中 SSH 文件的相对路径，请使用绝对路径后保存");
            cwd = cwd.substring(rootfs.end());
            if (cwd.isEmpty()) cwd = "/";
        }
        if (!cwd.startsWith("/") || cwd.endsWith(" (deleted)"))
            throw new IllegalStateException("SSH 的原始目录不可用，无法还原相对文件路径");
        return cwd + (cwd.endsWith("/") ? "" : "/") + path;
    }

    static String distroFromArguments(List<String> args) {
        return distroFromArguments(args, "");
    }

    static String distroFromArguments(List<String> args, String prootCwd) {
        if ("proot".equals(executable(args))) {
            String path = prootRootfsArgument(args);
            if (path.isEmpty()) return "";
            if (!path.startsWith("/")) {
                if (!prootCwd.startsWith("/") || prootCwd.endsWith(" (deleted)")) return "";
                path = new File(prootCwd, path).getPath();
            }
            path = new File(path).toPath().normalize().toString();
            Matcher rootfs = PROOT_ROOTFS.matcher(path);
            return rootfs.find() && rootfs.end() == path.length() ? rootfsDistro(rootfs) : "";
        }
        for (int i = 0; i + 2 < args.size(); i++) {
            String binary = new File(args.get(i)).getName();
            if (("pd".equals(binary) || "proot-distro".equals(binary)) && "login".equals(args.get(i + 1))
                && !args.get(i + 2).startsWith("-")) return args.get(i + 2);
        }
        return "";
    }

    private static String rootfsDistro(Matcher rootfs) {
        return rootfs.group(2) != null ? rootfs.group(2) : rootfs.group(3);
    }

    /** Read only PRoot options, never a bind source or the guest command's arguments. */
    static String prootRootfsArgument(List<String> args) {
        String rootfs = "";
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("-") || "--".equals(arg)) break;
            if (arg.equals("--rootfs") || arg.equals("-r") || arg.equals("-R") || arg.equals("-S")) {
                if (++i >= args.size()) return "";
                rootfs = args.get(i);
            } else if (arg.startsWith("--rootfs=")) {
                rootfs = arg.substring("--rootfs=".length());
            } else if (arg.startsWith("-r") || arg.startsWith("-R") || arg.startsWith("-S")) {
                rootfs = arg.substring(2);
            } else if (arg.equals("-b") || arg.equals("--bind") || arg.equals("-m") || arg.equals("--mount")
                || arg.equals("-q") || arg.equals("--qemu") || arg.equals("-w") || arg.equals("--pwd")
                || arg.equals("--cwd") || arg.equals("-v") || arg.equals("--verbose")
                || arg.equals("-k") || arg.equals("--kernel-release")
                || arg.equals("-i") || arg.equals("--change-id")) {
                if (++i >= args.size()) return "";
            }
        }
        return rootfs;
    }

    private static String executable(List<String> args) {
        if (args.isEmpty()) return "";
        String name = new File(args.get(0)).getName();
        return name.startsWith("-") ? name.substring(1) : name;
    }

    static boolean isShell(String name) {
        return name.equals("sh") || name.equals("bash") || name.equals("zsh") || name.equals("fish")
            || name.equals("dash") || name.equals("ksh") || name.equals("ash") || name.equals("nu");
    }

}
