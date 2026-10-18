package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;

import com.termux.shared.file.FileUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

import java.io.File;
import java.util.HashMap;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class SharedUpstreamRegressionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void normalizedDirectoryPathsRemoveSlashesLeftByDotSegments() {
        assertEquals("/storage/emulated/0", FileUtils.normalizePath("/storage/emulated/0/././"));
        assertTrue(FileUtils.isPathInDirPath("/storage/emulated/0/file.txt", "/storage/emulated/0/././", false));
    }

    @Test
    public void allRequestedPermissionsAreIncludedInOneAndroidRequest() {
        Application application = RuntimeEnvironment.getApplication();
        String[] permissions = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        Shadows.shadowOf(application).denyPermissions(permissions);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        PermissionUtils.requestPermissions(activity, permissions, 1000);

        ShadowActivity.PermissionsRequest request = Shadows.shadowOf(activity).getLastRequestedPermission();
        assertNotNull(request);
        assertEquals(1000, request.requestCode);
        assertArrayEquals(permissions, request.requestedPermissions);
    }

    @Test
    public void alreadyGrantedPermissionsDoNotOpenAnotherRequest() {
        Application application = RuntimeEnvironment.getApplication();
        String[] permissions = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        Shadows.shadowOf(application).grantPermissions(permissions);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        PermissionUtils.requestPermissions(activity, permissions, 1000);

        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
    }

    @Test
    public void backgroundTaskUsesTheRequestedFailsafeEnvironment() {
        assertTaskEnvironment(true, "failsafe");
        assertTaskEnvironment(false, "normal");
    }

    @Test
    public void missingBackgroundExecutableReturnsAnErrorInsteadOfCrashing() {
        for (String executable : new String[]{null, ""}) {
            ExecutionCommand command = new ExecutionCommand(-1, executable, null, null,
                temporary.getRoot().getAbsolutePath(), Runner.APP_SHELL.getName(), false);

            AppShell task = AppShell.execute(RuntimeEnvironment.getApplication(), command, null,
                new TermuxShellEnvironment(), null, true);

            assertNull(task);
            assertTrue(command.isStateFailed());
            assertTrue(command.resultData.getErrCode() > 0);
        }
    }

    @Test
    public void emptyAndNullTerminalExecutablesBothSelectTheDefaultShell() {
        IShellEnvironment environment = new TermuxShellEnvironment() {
            @Override public String getDefaultBinPath() { return temporary.getRoot().getAbsolutePath(); }
            @Override public HashMap<String, String> setupShellCommandEnvironment(Context context,
                                                                                 ExecutionCommand command) {
                return new HashMap<>();
            }
            @Override public String[] setupShellCommandArguments(String executable, String[] arguments) {
                return new String[]{executable};
            }
        };
        for (String executable : new String[]{null, ""}) {
            ExecutionCommand command = new ExecutionCommand(-1, executable, null, null,
                temporary.getRoot().getAbsolutePath(), Runner.TERMINAL_SESSION.getName(), true);

            TermuxSession session = TermuxSession.execute(RuntimeEnvironment.getApplication(), command,
                new TermuxTerminalSessionClientBase(), null, environment, null, false);

            assertNotNull(session);
            assertEquals("/system/bin/sh", command.executable);
            assertArrayEquals(new String[]{"sh"}, command.arguments);
        }
    }

    private void assertTaskEnvironment(boolean failsafe, String expected) {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isEmpty())
            shell = new File("/system/bin/sh").canExecute() ? "/system/bin/sh" : "/bin/sh";
        ExecutionCommand command = new ExecutionCommand(-1, shell,
            new String[]{"-c", "printf '%s' \"$MDTERM_TEST_ENV\""}, null,
            temporary.getRoot().getAbsolutePath(), Runner.APP_SHELL.getName(), failsafe);
        IShellEnvironment environment = new IShellEnvironment() {
            @Override public String getDefaultWorkingDirectoryPath() { return temporary.getRoot().getAbsolutePath(); }
            @Override public String getDefaultBinPath() { return "/"; }
            @Override public HashMap<String, String> setupShellCommandEnvironment(Context context,
                                                                                 ExecutionCommand command) {
                HashMap<String, String> environment = new HashMap<>();
                environment.put("MDTERM_TEST_ENV", command.isFailsafe ? "failsafe" : "normal");
                return environment;
            }
            @Override public String[] setupShellCommandArguments(String executable, String[] arguments) {
                return new String[]{executable, arguments[0], arguments[1]};
            }
        };

        AppShell task = AppShell.execute(RuntimeEnvironment.getApplication(), command, null, environment, null, true);

        assertNotNull(task);
        assertEquals(Integer.valueOf(0), command.resultData.exitCode);
        assertEquals(expected + "\n", command.resultData.stdout.toString());
    }
}
