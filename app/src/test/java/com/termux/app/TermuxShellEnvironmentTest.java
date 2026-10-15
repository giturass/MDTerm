package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.GhosttyShellIntegration;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.HashMap;

public class TermuxShellEnvironmentTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void selectsGhosttyTermOnlyWhenTerminfoIsAvailable() {
        assertEquals("xterm-ghostty", TermuxShellEnvironment.getTermValue(false, true));
        assertEquals("xterm-256color", TermuxShellEnvironment.getTermValue(false, false));
        assertEquals("xterm-256color", TermuxShellEnvironment.getTermValue(true, false));
        assertEquals("xterm-256color", TermuxShellEnvironment.getTermValue(true, true));
    }

    @Test
    public void shellIntegrationPreservesHomeAndShellStartupConfiguration() throws Exception {
        File resources = temporary.newFolder("share", "ghostty");
        HashMap<String, String> environment = new HashMap<>();
        environment.put("HOME", "/custom/home");
        environment.put("ZDOTDIR", "/custom/zsh");
        environment.put("ENV", "/custom/shrc");
        environment.put("BASH_ENV", "/custom/bash-env");
        environment.put("PATH", "/custom/bin");
        HashMap<String, String> expected = new HashMap<>(environment);

        GhosttyShellIntegration.setupEnvironment(environment, resources);
        assertEquals(expected, environment);

        assertTrue(new File(resources, "shell-integration").mkdirs());
        GhosttyShellIntegration.setupEnvironment(environment, resources);
        expected.put("GHOSTTY_RESOURCES_DIR", resources.getAbsolutePath());
        expected.put("GHOSTTY_SHELL_FEATURES", "");
        expected.put("XDG_DATA_DIRS",
            resources.getParentFile().getAbsolutePath() + ":/usr/local/share:/usr/share");
        assertEquals(expected, environment);

        GhosttyShellIntegration.setupEnvironment(environment, resources);
        assertEquals(expected, environment);
    }

    @Test
    public void shellIntegrationKeepsCustomDataDirectoriesWithoutDuplicateSystemEntries() throws Exception {
        File resources = temporary.newFolder("share", "ghostty");
        assertTrue(new File(resources, "shell-integration").mkdirs());
        String share = resources.getParentFile().getAbsolutePath();
        HashMap<String, String> environment = new HashMap<>();
        environment.put("XDG_DATA_DIRS", "/custom/share:" + share + ":/second/share:" + share);

        GhosttyShellIntegration.setupEnvironment(environment, resources);
        assertEquals(share + ":/custom/share:/second/share", environment.get("XDG_DATA_DIRS"));
        HashMap<String, String> expected = new HashMap<>(environment);
        GhosttyShellIntegration.setupEnvironment(environment, resources);
        assertEquals(expected, environment);

        environment.put("XDG_DATA_DIRS", "");
        GhosttyShellIntegration.setupEnvironment(environment, resources);
        assertEquals(share + ":/usr/local/share:/usr/share", environment.get("XDG_DATA_DIRS"));
    }
}
