package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;

import com.termux.BuildConfig;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class NativeRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installedIdentityMatchesOfficialPackagesAndProviders() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("com.termux", BuildConfig.APPLICATION_ID);
        assertEquals(BuildConfig.APPLICATION_ID, context.getPackageName());
        assertEquals(BuildConfig.APPLICATION_ID, TermuxConstants.TERMUX_PACKAGE_NAME);
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        assertEquals("com.termux", info.sharedUserId);
        assertNotNull(context.getPackageManager().resolveContentProvider("com.termux.files", 0));
        assertNotNull(context.getPackageManager().resolveContentProvider("com.termux.documents", 0));
        assertEquals("/data/data/com.termux/files/usr", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
    }

    @Test
    public void terminalReceivesExecutableArgumentsAndEnvironmentDirectly() throws Exception {
        ExecutionCommand command = new ExecutionCommand();
        command.executable = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
        command.arguments = new String[]{"-c", "printf '%s' \"$1\"", "sh", "a b"};
        command.workingDirectory = temporary.getRoot().getAbsolutePath();
        HashMap<String, String> extra = new HashMap<>();
        extra.put("LD_PRELOAD", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libtermux-exec.so");
        TerminalSession terminal = session(command, new TestEnvironment(), extra);
        assertEquals(command.executable, ReflectionHelpers.getField(terminal, "mShellPath"));
        assertEquals(command.workingDirectory, ReflectionHelpers.getField(terminal, "mCwd"));
        assertArrayEquals(new String[]{"sh", "-c", "printf '%s' \"$1\"", "sh", "a b"},
            ReflectionHelpers.getField(terminal, "mArgs"));
        String[] environment = ReflectionHelpers.getField(terminal, "mEnv");
        assertTrue(Arrays.asList(environment).contains("LD_PRELOAD=" + extra.get("LD_PRELOAD")));
        assertTrue(Arrays.stream(environment).noneMatch(value -> value.startsWith("PROOT_")));
    }

    @Test
    public void defaultLoginAndFailsafeKeepUpstreamArgvZero() throws Exception {
        File shell = temporary.newFile("bash");
        Files.write(shell.toPath(), new byte[]{0x7f, 'E', 'L', 'F', 0});
        assertTrue(shell.setExecutable(true));
        TestEnvironment environment = new TestEnvironment() {
            @Override public String getDefaultBinPath() {
                return temporary.getRoot().getAbsolutePath();
            }
        };
        TerminalSession login = session(new ExecutionCommand(), environment, null);
        assertEquals(shell.getAbsolutePath(), ReflectionHelpers.getField(login, "mShellPath"));
        assertArrayEquals(new String[]{"-bash"}, ReflectionHelpers.getField(login, "mArgs"));

        ExecutionCommand failsafe = new ExecutionCommand();
        failsafe.isFailsafe = true;
        TerminalSession fallback = session(failsafe, environment, null);
        assertEquals("/system/bin/sh", ReflectionHelpers.getField(fallback, "mShellPath"));
        assertArrayEquals(new String[]{"sh"}, ReflectionHelpers.getField(fallback, "mArgs"));
    }

    @Test
    public void backgroundCommandPreservesArgumentsWorkingDirectoryAndExitCode() {
        ExecutionCommand command = new ExecutionCommand();
        command.executable = new File("/system/bin/sh").canExecute() ? "/system/bin/sh" : "/bin/sh";
        command.arguments = new String[]{"-c", "printf '%s\\n' \"$1\" \"$PWD\" \"$MDTERM_TEST\"; exit 7", "sh", "a b"};
        command.workingDirectory = temporary.getRoot().getAbsolutePath();
        HashMap<String, String> extra = new HashMap<>();
        extra.put("MDTERM_TEST", "direct execution");
        AppShell shell = AppShell.execute(RuntimeEnvironment.getApplication(), command, null,
            new TestEnvironment(), extra, true);
        assertNotNull(shell);
        assertEquals(Integer.valueOf(7), command.resultData.exitCode);
        assertEquals("a b\n" + command.workingDirectory + "\ndirect execution\n", command.resultData.stdout.toString());
    }

    private TerminalSession session(ExecutionCommand command, TermuxShellEnvironment environment,
                                    HashMap<String, String> extra) {
        TermuxSession session = TermuxSession.execute(RuntimeEnvironment.getApplication(), command,
            new TermuxTerminalSessionClientBase(), null, environment, extra, false);
        assertNotNull(session);
        // Inspect the launch request before attaching a view, which starts the Android PTY/JNI process.
        return session.getTerminalSession();
    }

    private class TestEnvironment extends TermuxShellEnvironment {
        @Override public String getDefaultWorkingDirectoryPath() {
            return temporary.getRoot().getAbsolutePath();
        }

        @Override public HashMap<String, String> setupShellCommandEnvironment(Context context,
                                                                             ExecutionCommand command) {
            // Isolate process-launch checks from device-only environment discovery and file writes.
            HashMap<String, String> environment = new HashMap<>();
            environment.put("HOME", getDefaultWorkingDirectoryPath());
            environment.put("PATH", "/system/bin:/usr/bin:/bin");
            return environment;
        }
    }
}
