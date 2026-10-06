package com.termux.app.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A saved location, including the environment needed to reach it. */
public final class TerminalBookmark {
    public final String id;
    public final String name;
    public final String kind;
    public final String distro;
    public final List<String> sshArgs;
    public final String path;

    public TerminalBookmark(String id, String name, String kind, String distro,
                            List<String> sshArgs, String path) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.distro = distro == null ? "" : distro;
        this.sshArgs = Collections.unmodifiableList(new ArrayList<>(
            sshArgs == null ? Collections.emptyList() : sshArgs));
        this.path = path;
    }

    public TerminalBookmark withName(String name) {
        return new TerminalBookmark(id, name, kind, distro, sshArgs, path);
    }
}
