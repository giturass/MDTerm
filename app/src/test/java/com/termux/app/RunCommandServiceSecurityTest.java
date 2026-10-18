package com.termux.app;

import android.app.Application;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;

import com.termux.shared.termux.plugins.TermuxPluginUtils;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.termux.TermuxUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLinux;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, shadows = {
    RunCommandServiceSecurityTest.Policy.class, RunCommandServiceSecurityTest.PosixFileStat.class
})
public class RunCommandServiceSecurityTest {
    private static final String DENIED = "External apps are disabled";
    private static final String CALLBACK = "com.termux.test.COMMAND_RESULT";
    private static final String CONTENT = "allow-external-apps=true";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Application application;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        Policy.allowed = false;
        Policy.notifications = 0;
        Policy.lastNotification = null;
    }

    @Test
    public void deniedCommandCannotOverwriteAnExistingResultFile() throws Exception {
        File properties = temporary.newFile("termux.properties");
        byte[] original = "allow-external-apps=false\n".getBytes(StandardCharsets.UTF_8);
        Files.write(properties.toPath(), original);

        run(fileResultIntent(properties));

        assertArrayEquals(original, Files.readAllBytes(properties.toPath()));
        assertEquals(1, temporary.getRoot().list().length);
        assertEquals(1, Policy.notifications);
    }

    @Test
    public void deniedCommandCannotCreateSeparateResultFiles() throws Exception {
        Intent intent = new Intent(RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND);
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_RESULT_DIRECTORY, temporary.getRoot().getAbsolutePath());
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_RESULT_SINGLE_FILE, false);

        run(intent);

        assertArrayEquals(new String[0], temporary.getRoot().list());
        assertEquals(1, Policy.notifications);
    }

    @Test
    public void deniedCommandStillReturnsPolicyErrorToPendingIntentWithoutWritingFiles() throws Exception {
        File result = new File(temporary.getRoot(), "result.txt");
        Intent intent = fileResultIntent(result);
        PendingIntent callback = PendingIntent.getBroadcast(application, 7,
            new Intent(CALLBACK).setPackage(application.getPackageName()),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT, callback);

        run(intent);

        Intent delivered = null;
        for (Intent broadcast : Shadows.shadowOf(application).getBroadcastIntents()) {
            if (CALLBACK.equals(broadcast.getAction())) delivered = broadcast;
        }
        assertNotNull("A denied caller must still receive its PendingIntent error result", delivered);
        Bundle bundle = delivered.getBundleExtra(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE);
        assertNotNull(bundle);
        assertTrue(bundle.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERR) > 0);
        assertTrue(bundle.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG).contains(DENIED));
        assertFalse(result.exists());
        assertEquals(1, Policy.notifications);
    }

    @Test
    public void authorizedCommandStillWritesItsValidationErrorToTheRequestedFile() throws Exception {
        Policy.allowed = true;
        File result = new File(temporary.getRoot(), "result.txt");

        // No executable: the authorized request must return the validation error as a file.
        run(fileResultIntent(result));

        assertTrue(Policy.lastNotification, result.isFile());
        assertEquals(CONTENT, new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8));
    }

    private Intent fileResultIntent(File file) {
        return new Intent(RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND)
            .putExtra(RUN_COMMAND_SERVICE.EXTRA_RESULT_DIRECTORY, file.getParent())
            .putExtra(RUN_COMMAND_SERVICE.EXTRA_RESULT_SINGLE_FILE, true)
            .putExtra(RUN_COMMAND_SERVICE.EXTRA_RESULT_FILE_BASENAME, file.getName())
            .putExtra(RUN_COMMAND_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, CONTENT);
    }

    private void run(Intent intent) {
        RunCommandService service = Robolectric.buildService(RunCommandService.class).create().get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1));
        service.onDestroy();
    }

    // Replace only the policy source and UI notification. Command parsing, error dispatch,
    // PendingIntent delivery and result-file handling all exercise their production code.
    @Implements(TermuxPluginUtils.class)
    public static class Policy {
        static boolean allowed;
        static int notifications;
        static String lastNotification;

        @Implementation
        protected static String checkIfAllowExternalAppsPolicyIsViolated(Context context, String apiName) {
            return allowed ? null : DENIED;
        }

        @Implementation
        protected static void sendPluginCommandErrorNotification(Context context, String logTag,
                CharSequence title, String notificationText, String message, boolean forceNotification,
                boolean showToast, TermuxUtils.AppInfoMode appInfoMode, boolean addDeviceInfo,
                String callingPackageName) {
            notifications++;
            lastNotification = notificationText;
        }
    }

    // Robolectric 4.10 returns mode 0 for missing files. Android reports ENOENT, which is needed
    // for ResultSender to recognize that its temporary output file can be created.
    @Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
    public static class PosixFileStat extends ShadowLinux {
        @Implementation
        @Override
        public StructStat stat(String path) throws ErrnoException {
            if (path == null || !Files.exists(new File(path).toPath(), LinkOption.NOFOLLOW_LINKS))
                throw new ErrnoException("stat", OsConstants.ENOENT);
            return super.stat(path);
        }
    }
}
