package com.termux.app.terminal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The effective PRoot login, including the guest account, mounts and initial environment. */
public final class BookmarkProotLaunch {
    public final List<String> arguments;
    public final List<String> environment;
    public final String workingDirectory;

    public BookmarkProotLaunch(List<String> arguments, List<String> environment, String workingDirectory) {
        if (arguments == null || arguments.isEmpty() || environment == null
            || !usablePath(workingDirectory)) throw new IllegalArgumentException("Invalid PRoot login");
        for (String arg : arguments) requireArgument(arg);
        for (String value : environment) {
            requireArgument(value);
            if (value.indexOf('=') <= 0) throw new IllegalArgumentException("Invalid PRoot environment");
        }
        if (!"proot".equals(new File(arguments.get(0)).getName()))
            throw new IllegalArgumentException("Invalid PRoot executable");
        // Stored data must retain a real shell boundary, never guess where options end.
        shellIndex(arguments, commandIndex(arguments));
        this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
        this.environment = Collections.unmodifiableList(new ArrayList<>(environment));
        this.workingDirectory = workingDirectory;
    }

    static BookmarkProotLaunch capture(BookmarkProcessSnapshot.Process process, String cwd,
                                       File procDirectory) throws IOException {
        if (!usablePath(cwd)) throw new IllegalStateException("PRoot 的启动目录已不可用，未保存书签");
        List<String> args = new ArrayList<>(process.args);
        int command = commandIndex(args);
        int shell = shellIndex(args, command);
        boolean guestEnv = command < shell;
        if (isSu(args, shell)) args = new ArrayList<>(args.subList(0, shell + 3));
        else if (args.size() >= shell + 3 && isCommandFlag(args.get(args.size() - 2))) {
            String flag = args.get(args.size() - 2);
            args = new ArrayList<>(args.subList(0, args.size() - 2));
            if (flag.indexOf('l') >= 0) args.add("-l");
        }
        for (int i = 1; i < command; i++) {
            String arg = args.get(i);
            if (arg.startsWith("--rootfs=") || arg.startsWith("--bind=")
                || arg.startsWith("--mount=")) checkPersistentPath(arg.substring(arg.indexOf('=') + 1), cwd);
            else if (arg.equals("-r") || arg.equals("-R") || arg.equals("-S")
                || arg.equals("--rootfs") || arg.equals("-b") || arg.equals("--bind")
                || arg.equals("-m") || arg.equals("--mount")) checkPersistentPath(args.get(++i), cwd);
            else if (arg.startsWith("-r") || arg.startsWith("-R") || arg.startsWith("-S")
                || arg.startsWith("-b") || arg.startsWith("-m")) checkPersistentPath(arg.substring(2), cwd);
        }
        List<String> environment = new ArrayList<>();
        for (String value : BookmarkProcessSnapshot.readEnvironment(procDirectory, process.pid)) {
            int separator = value.indexOf('=');
            if (separator <= 0) continue;
            String name = value.substring(0, separator);
            // Shell proot-distro puts the guest environment after `env -i` in argv.
            // Python proot-distro instead execvpe's PRoot with that environment. Do
            // not save the older launcher's unrelated host environment or passwords.
            if (!guestEnv || name.startsWith("PROOT_") || name.equals("HOME") || name.equals("PATH")
                || name.equals("LD_LIBRARY_PATH")) environment.add(value);
        }
        return new BookmarkProotLaunch(args, environment, cwd);
    }

    String launch(String path, String commandOverride) {
        int command = commandIndex(arguments);
        int shell = shellIndex(arguments, command);
        boolean su = isSu(arguments, shell);
        String executable = arguments.get(0);
        // Resolve a PATH-based executable before installing the guest PATH.
        String binary = executable.indexOf('/') < 0 ? "\"$(command -v proot)\""
            : BookmarkEnvironment.quote(executable);
        StringBuilder result = new StringBuilder("cd -- ")
            .append(BookmarkEnvironment.quote(workingDirectory)).append(" && exec env -i --");
        for (String value : environment) result.append(' ').append(BookmarkEnvironment.quote(value));
        result.append(' ').append(binary);
        for (int i = 1; i < command; i++) {
            String arg = arguments.get(i);
            if (arg.equals("--")) continue;
            if (path != null && (arg.equals("-w") || arg.equals("--pwd") || arg.equals("--cwd"))) { i++; continue; }
            if (path != null && (arg.startsWith("--pwd=") || arg.startsWith("--cwd=")
                || (arg.startsWith("-w") && !arg.startsWith("--")))) continue;
            result.append(' ').append(BookmarkEnvironment.quote(arg));
            if (takesValue(arg)) result.append(' ').append(BookmarkEnvironment.quote(arguments.get(++i)));
        }
        if (path != null) result.append(' ').append(BookmarkEnvironment.quote("--cwd=" + path));
        int guestEnd = su ? shell + 3 : arguments.size();
        for (int i = command; i < guestEnd; i++)
            result.append(' ').append(BookmarkEnvironment.quote(arguments.get(i)));
        if (su) {
            // proot-distro 3 uses su to establish the guest identity. su -l changes
            // to HOME, so enter the saved directory after it has initialized login.
            String enter = commandOverride != null ? commandOverride : "cd -- "
                + BookmarkEnvironment.quote(path) + " && exec \"${SHELL:-/bin/sh}\" -l";
            result.append(" -c ").append(BookmarkEnvironment.quote("/bin/sh -c " + BookmarkEnvironment.quote(enter)));
        } else if (commandOverride != null) {
            result.append(" -c ").append(BookmarkEnvironment.quote("/bin/sh -c "
                + BookmarkEnvironment.quote(commandOverride)));
        }
        return result.toString();
    }

    boolean isInitialSu(List<String> processArgs) {
        int shell = shellIndex(arguments, commandIndex(arguments));
        if (!isSu(arguments, shell) || !isSu(processArgs, 0)) return false;
        try {
            return shellIndex(processArgs, 0) == 0
                && arguments.get(shell + 2).equals(processArgs.get(2));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The PRoot option grammar ends at the first guest executable. */
    private static int commandIndex(List<String> args) {
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--")) return i + 1;
            if (!arg.startsWith("-")) return i;
            if (takesValue(arg) && ++i >= args.size())
                throw new IllegalArgumentException("Incomplete PRoot option");
        }
        throw new IllegalArgumentException("Missing PRoot shell");
    }

    private static boolean takesValue(String arg) {
        return arg.equals("-r") || arg.equals("--rootfs") || arg.equals("-R") || arg.equals("-S")
            || arg.equals("-b") || arg.equals("--bind") || arg.equals("-m") || arg.equals("--mount")
            || arg.equals("-w") || arg.equals("--pwd") || arg.equals("--cwd")
            || arg.equals("-q") || arg.equals("--qemu") || arg.equals("-v") || arg.equals("--verbose")
            || arg.equals("-k") || arg.equals("--kernel-release") || arg.equals("-i") || arg.equals("--change-id");
    }

    private static int shellIndex(List<String> args, int command) {
        int index = command;
        if (index < args.size() && new File(args.get(index)).getName().equals("env")) {
            index++;
            if (index >= args.size() || !(args.get(index).equals("-i")
                || args.get(index).equals("--ignore-environment")))
                throw new IllegalArgumentException("Unsupported PRoot environment wrapper");
            index++;
            while (index < args.size() && args.get(index).indexOf('=') > 0) index++;
        }
        if (index < args.size() && isSu(args, index)) {
            if ((args.size() != index + 3 && args.size() != index + 5)
                || !(args.get(index + 1).equals("-l") || args.get(index + 1).equals("--login"))
                || args.get(index + 2).isEmpty() || args.get(index + 2).startsWith("-")
                || (args.size() == index + 5 && (!args.get(index + 3).equals("-c")
                    || !BookmarkEnvironment.isProotRestoreCommand(args.get(index + 4)))))
                throw new IllegalArgumentException("Unsupported PRoot su command");
            return index;
        }
        if (index >= args.size() || !BookmarkEnvironment.isShell(new File(args.get(index)).getName()))
            throw new IllegalArgumentException("Unsupported PRoot login command");
        for (int i = index + 1; i < args.size(); i++) {
            // A command/script can re-enter another environment; replaying it is not
            // equivalent to opening a bookmark at the current shell prompt.
            if (i == args.size() - 2 && isCommandFlag(args.get(i))
                && BookmarkEnvironment.isProotRestoreCommand(args.get(i + 1))) break;
            if (!args.get(i).equals("--login") && !args.get(i).equals("--interactive")
                && !args.get(i).matches("-[il]+"))
                throw new IllegalArgumentException("Unsupported PRoot shell command");
        }
        return index;
    }

    private static boolean isSu(List<String> args, int index) {
        return index < args.size() && new File(args.get(index)).getName().equals("su");
    }

    private static boolean isCommandFlag(String arg) {
        return arg.equals("-c") || arg.equals("-lc") || arg.equals("-cl");
    }

    private static void checkPersistentPath(String binding, String cwd) {
        String source = binding.split(":", 2)[0];
        source = (source.startsWith("/") ? new File(source) : new File(cwd, source))
            .toPath().normalize().toString();
        if (source.endsWith(" (deleted)") || source.matches("/proc/[0-9]+(?:/.*)?")
            || source.matches("/(?:proc/self/fd|dev/fd)/(?:[3-9]|[1-9][0-9]+)(?:/.*)?")
            || source.matches("/dev/pts/[0-9]+(?:/.*)?"))
            throw new IllegalStateException("PRoot 使用了当前进程的临时挂载，无法可靠恢复书签");
    }

    private static boolean usablePath(String path) {
        return path != null && path.startsWith("/") && !path.endsWith(" (deleted)") && path.indexOf('\0') < 0;
    }

    private static void requireArgument(String value) {
        if (value == null || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid PRoot argument");
    }
}
