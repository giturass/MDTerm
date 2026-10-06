package com.termux.app.terminal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the foreground process and its ancestors without optional kernel procfs interfaces. */
final class BookmarkProcessSnapshot {
    final int foregroundGroup;
    final Process foreground;

    private BookmarkProcessSnapshot(int foregroundGroup, Process foreground) {
        this.foregroundGroup = foregroundGroup;
        this.foreground = foreground;
    }

    static BookmarkProcessSnapshot read(int rootPid) throws IOException {
        return read(new File("/proc"), rootPid);
    }

    static int readForegroundGroup(int rootPid) throws IOException {
        return readStat(new File("/proc"), rootPid).foregroundGroup;
    }

    static BookmarkProcessSnapshot read(File procDirectory, int rootPid) throws IOException {
        Stat root = readStat(procDirectory, rootPid);
        File[] entries = procDirectory.listFiles();
        if (entries == null) throw new IOException("Cannot list terminal processes");
        Map<Integer, Stat> processes = new HashMap<>();
        processes.put(rootPid, root);
        // Android kernels may omit /proc/PID/task/PID/children. PPID in stat is sufficient
        // to reconstruct ancestry, and remains available on those devices.
        for (File entry : entries) {
            int pid;
            try {
                pid = Integer.parseInt(entry.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (pid <= 0 || pid == rootPid) continue;
            try {
                processes.put(pid, readStat(procDirectory, pid));
            } catch (IOException | SecurityException ignored) {
                // Unrelated processes may be inaccessible, or exit while /proc is scanned.
            }
        }

        List<Stat> foregroundChain = Collections.emptyList();
        for (Stat process : processes.values()) {
            if (root.foregroundGroup <= 0 || process.group != root.foregroundGroup) continue;
            List<Stat> chain = ancestors(processes, process, rootPid);
            if (chain.size() > foregroundChain.size()) foregroundChain = chain;
        }
        if (foregroundChain.isEmpty()) {
            throw new IllegalStateException("无法确认前台终端环境，请返回 Shell 提示符后重试");
        }

        Process foreground = null;
        for (int i = foregroundChain.size() - 1; i >= 0; i--) {
            int pid = foregroundChain.get(i).pid;
            // Read argv only for the selected ancestry. A background job's unreadable
            // command line must not prevent saving a usable foreground shell.
            foreground = readProcess(procDirectory, pid, foreground);
        }
        foreground = sshpassClient(procDirectory, processes, foreground);
        return new BookmarkProcessSnapshot(root.foregroundGroup, foreground);
    }

    private static Process readProcess(File procDirectory, int pid, Process parent) throws IOException {
        String command = new String(readBytes(new File(new File(procDirectory,
            Integer.toString(pid)), "cmdline")), StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>();
        if (!command.isEmpty()) {
            // Remove the final terminator only; an empty last argv is meaningful too.
            if (command.endsWith("\u0000")) command = command.substring(0, command.length() - 1);
            Collections.addAll(args, command.split("\u0000", -1));
        }
        return new Process(pid, args, parent);
    }

    private static Process sshpassClient(File procDirectory, Map<Integer, Stat> processes,
                                         Process foreground) throws IOException {
        if (foreground.args.isEmpty()
            || !"sshpass".equals(new File(foreground.args.get(0)).getName())) return foreground;

        // sshpass runs its client on a private PTY, outside the original foreground group.
        // Only cross this boundary for its direct, foreground SSH child. Arbitrary
        // descendants may be background jobs, and SSH children may be jump-host helpers.
        Process client = null;
        for (Stat process : processes.values()) {
            if (process.parentPid != foreground.pid || process.group <= 0
                || process.group != process.foregroundGroup) continue;
            Process child = readProcess(procDirectory, process.pid, foreground);
            if (child.args.isEmpty() || !"ssh".equals(new File(child.args.get(0)).getName())) continue;
            if (client != null)
                throw new IllegalStateException("无法确认 sshpass 中的 SSH 连接，请直接使用 ssh 后保存");
            client = child;
        }
        return client == null ? foreground : client;
    }

    private static List<Stat> ancestors(Map<Integer, Stat> processes, Stat process, int rootPid) {
        List<Stat> chain = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        while (process != null && visited.add(process.pid)) {
            chain.add(process);
            if (process.pid == rootPid) return chain;
            process = processes.get(process.parentPid);
        }
        // A matching process group alone does not prove this is part of our session.
        return Collections.emptyList();
    }

    private static Stat readStat(File procDirectory, int pid) throws IOException {
        String stat = new String(readBytes(new File(new File(procDirectory,
            Integer.toString(pid)), "stat")), StandardCharsets.UTF_8);
        int end = stat.lastIndexOf(')');
        if (end < 0) throw new IOException("Invalid process stat for " + pid);
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length < 6) throw new IOException("Incomplete process stat for " + pid);
        try {
            // After comm: state, ppid, pgrp, session, tty_nr, tpgid.
            return new Stat(pid, Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                Integer.parseInt(fields[5]));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid process stat for " + pid, e);
        }
    }

    private static byte[] readBytes(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 131072) throw new IOException("Process metadata too long");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static final class Stat {
        final int pid, parentPid, group, foregroundGroup;

        Stat(int pid, int parentPid, int group, int foregroundGroup) {
            this.pid = pid;
            this.parentPid = parentPid;
            this.group = group;
            this.foregroundGroup = foregroundGroup;
        }
    }

    static final class Process {
        final int pid;
        final List<String> args;
        final Process parent;

        Process(int pid, List<String> args, Process parent) {
            this.pid = pid;
            this.args = args;
            this.parent = parent;
        }
    }
}
