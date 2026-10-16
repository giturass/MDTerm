package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;

import com.termux.shared.file.FileUtils;
import com.termux.shared.models.ExecutionCommand;
import com.termux.shared.packages.PermissionUtils;
import com.termux.shared.shell.ShellEnvironmentClient;
import com.termux.shared.shell.TermuxSession;
import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.shell.TermuxTask;
import com.termux.shared.terminal.TermuxTerminalSessionClientBase;

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
                temporary.getRoot().getAbsolutePath(), true, false);

            TermuxTask task = TermuxTask.execute(RuntimeEnvironment.getApplication(), command, null,
                new TermuxShellEnvironmentClient(), true);

            assertNull(task);
            assertTrue(command.isStateFailed());
            assertTrue(command.resultData.getErrCode() > 0);
        }
    }

    @Test
    public void emptyAndNullTerminalExecutablesBothSelectTheDefaultShell() {
        ShellEnvironmentClient environment = new TermuxShellEnvironmentClient() {
            @Override public String getDefaultBinPath() { return temporary.getRoot().getAbsolutePath(); }
            @Override public String[] buildEnvironment(Context context, boolean failsafe, String workingDirectory) {
                return new String[0];
            }
            @Override public String[] setupProcessArgs(String executable, String[] arguments) {
                return new String[]{executable};
            }
        };
        for (String executable : new String[]{null, ""}) {
            ExecutionCommand command = new ExecutionCommand(-1, executable, null, null,
                temporary.getRoot().getAbsolutePath(), false, true);

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
            temporary.getRoot().getAbsolutePath(), true, failsafe);
        ShellEnvironmentClient environment = new ShellEnvironmentClient() {
            @Override public String getDefaultWorkingDirectoryPath() { return temporary.getRoot().getAbsolutePath(); }
            @Override public String getDefaultBinPath() { return "/"; }
            @Override public String[] buildEnvironment(Context context, boolean isFailSafe, String workingDirectory) {
                return new String[]{"MDTERM_TEST_ENV=" + (isFailSafe ? "failsafe" : "normal")};
            }
            @Override public String[] setupProcessArgs(String executable, String[] arguments) {
                return new String[]{executable, arguments[0], arguments[1]};
            }
        };

        TermuxTask task = TermuxTask.execute(RuntimeEnvironment.getApplication(), command, null, environment, true);

        assertNotNull(task);
        assertEquals(Integer.valueOf(0), command.resultData.exitCode);
        assertEquals(expected + "\n", command.resultData.stdout.toString());
    }
}
