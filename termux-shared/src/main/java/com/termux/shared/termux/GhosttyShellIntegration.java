package com.termux.shared.termux;

import java.io.File;
import java.util.Map;

/** Environment shared by the system shell integration loaders. */
public final class GhosttyShellIntegration {

    public static final String RESOURCES_DIR = TermuxConstants.TERMUX_SHARE_PREFIX_DIR_PATH + "/ghostty";

    private GhosttyShellIntegration() {
    }

    public static void setupEnvironment(Map<String, String> environment, File resources) {
        if (!new File(resources, "shell-integration").isDirectory())
            return;

        environment.put("GHOSTTY_RESOURCES_DIR", resources.getAbsolutePath());
        // Keep prompt/cwd reporting; desktop wrappers need the Ghostty CLI.
        environment.put("GHOSTTY_SHELL_FEATURES", "");

        // Nushell vendor autoload and Elvish module discovery use the Termux
        // system share directory. Keep existing directories without duplicates.
        String data = resources.getAbsoluteFile().getParentFile().getAbsolutePath();
        String previous = environment.get("XDG_DATA_DIRS");
        if (previous == null || previous.isEmpty())
            previous = "/usr/local/share:/usr/share";
        StringBuilder directories = new StringBuilder(data);
        for (String entry : previous.split(":", -1)) {
            if (!entry.equals(data))
                directories.append(':').append(entry);
        }
        environment.put("XDG_DATA_DIRS", directories.toString());
    }
}
