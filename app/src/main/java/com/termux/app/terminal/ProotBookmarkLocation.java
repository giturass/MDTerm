package com.termux.app.terminal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/** Resolves ordinary rootfs directories without sending a command to the guest shell. */
final class ProotBookmarkLocation {
    private ProotBookmarkLocation() {}

    /** Returns a guest path restoring the same physical directory, or null when uncertain. */
    static String resolve(List<String> prootArgs, String prootCwd, String hostCwd) {
        if (prootArgs == null || prootArgs.isEmpty() || prootArgs.get(0) == null
            || !"proot".equals(new File(prootArgs.get(0)).getName()) || !validAbsolute(hostCwd)) return null;
        try {
            String rootArgument = BookmarkEnvironment.prootRootfsArgument(prootArgs);
            String root = hostPath(rootArgument, prootCwd);
            if (root == null || !contains(root, hostCwd)) return null;
            String guest = root.equals(hostCwd) ? "/" : "/".equals(root) ? hostCwd : hostCwd.substring(root.length());

            for (int i = 1; i < prootArgs.size(); i++) {
                String argument = prootArgs.get(i);
                if (argument == null) return null;
                if ("--".equals(argument) || !argument.startsWith("-")) break;
                if (flag(argument)) continue;

                String option;
                String value;
                if (argument.startsWith("--")) {
                    int separator = argument.indexOf('=');
                    option = separator < 0 ? argument : argument.substring(0, separator);
                    value = separator < 0 ? null : argument.substring(separator + 1);
                } else {
                    if (argument.length() < 2) return null;
                    option = argument.substring(0, 2);
                    value = argument.length() == 2 ? null : argument.substring(2);
                }
                // These options introduce implicit bindings (including $HOME and /host-rootfs).
                if ("-R".equals(option) || "-S".equals(option)
                    || "-q".equals(option) || "--qemu".equals(option)) return null;
                if (!valueOption(option)) return null;
                if (value == null) {
                    if (++i >= prootArgs.size()) return null;
                    value = prootArgs.get(i);
                }
                if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) return null;

                if ("-b".equals(option) || "--bind".equals(option)
                    || "-m".equals(option) || "--mount".equals(option)) {
                    int separator = value.indexOf(':');
                    if (separator >= 0 && (separator != value.lastIndexOf(':')
                        || separator == value.length() - 1)) return null;
                    String source = hostPath(separator < 0 ? value : value.substring(0, separator), prootCwd);
                    String target = separator < 0 ? value : value.substring(separator + 1);
                    boolean noDereference = target.endsWith("!");
                    if (noDereference) target = target.substring(0, target.length() - 1);
                    if (source == null || !validAbsolute(target)) return null;
                    // Collapsing '..' before following a guest symlink could change its target.
                    if (target.contains("/../") || target.endsWith("/..")) return null;
                    target = new File(target).toPath().normalize().toString();
                    // Resolving arbitrary guest symlinks requires the full binding state. Keep
                    // these uncommon configurations on the shell query rather than guessing.
                    if (hasGuestSymlink(root, target, noDereference)) return null;
                    if (contains(target, guest)) {
                        String suffix = guest.substring("/".equals(target) ? 1 : target.length());
                        String boundPath = new File(source + "/" + suffix).getCanonicalPath();
                        if (!hostCwd.equals(boundPath)) return null;
                    }
                }
            }
            return guest;
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            return null;
        }
    }

    private static boolean validAbsolute(String path) {
        return path != null && path.startsWith("/") && path.indexOf('\0') < 0
            && !path.endsWith(" (deleted)");
    }

    private static String hostPath(String path, String cwd) throws IOException {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0 || path.endsWith(" (deleted)")) return null;
        if (!path.startsWith("/")) {
            if (!validAbsolute(cwd)) return null;
            path = new File(cwd, path).getPath();
        }
        // Rootfs and bind arguments may themselves use host symlinks. /proc/PID/cwd is physical.
        return new File(path).getCanonicalPath();
    }

    private static boolean contains(String directory, String path) {
        return "/".equals(directory) || path.equals(directory) || path.startsWith(directory + "/");
    }

    private static boolean hasGuestSymlink(String root, String target, boolean noDereference) {
        File directory = new File(root);
        String[] components = target.substring(1).split("/");
        for (int i = 0; i < components.length; i++) {
            directory = new File(directory, components[i]);
            if (noDereference && i == components.length - 1) break;
            if (Files.isSymbolicLink(directory.toPath())) return true;
        }
        return false;
    }

    private static boolean flag(String option) {
        return "-0".equals(option) || "--root-id".equals(option)
            || "--kill-on-exit".equals(option) || "--link2symlink".equals(option)
            || "-l".equals(option) || "--sysvipc".equals(option)
            || "--ashmem-memfd".equals(option) || "-H".equals(option)
            || "-p".equals(option) || "-L".equals(option);
    }

    private static boolean valueOption(String option) {
        return "-r".equals(option) || "--rootfs".equals(option)
            || "-b".equals(option) || "--bind".equals(option)
            || "-m".equals(option) || "--mount".equals(option)
            || "-w".equals(option) || "--pwd".equals(option) || "--cwd".equals(option)
            || "-v".equals(option) || "--verbose".equals(option)
            || "-k".equals(option) || "--kernel-release".equals(option)
            || "-i".equals(option) || "--change-id".equals(option);
    }
}
