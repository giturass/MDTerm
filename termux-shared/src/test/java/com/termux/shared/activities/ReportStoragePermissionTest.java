package com.termux.shared.activities;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;

import com.termux.shared.R;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.models.ReportInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLinux;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class,
    shadows = {ReportStoragePermissionTest.StorageEnvironment.class,
        ReportStoragePermissionTest.PosixFileStat.class})
public class ReportStoragePermissionTest {
    private static final String[] LEGACY_PERMISSIONS = {
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private static final String REPORT = "# Diagnostic report\n\nSaved with storage permission.\n";

    private Application application;
    private File output;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        PackageInfo packageInfo = application.getPackageManager().getPackageInfo(
            application.getPackageName(), PackageManager.GET_PERMISSIONS);
        packageInfo.requestedPermissions = new String[] {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.MANAGE_EXTERNAL_STORAGE
        };
        Shadows.shadowOf(application.getPackageManager()).installPackage(packageInfo);
        Shadows.shadowOf(application).denyPermissions(LEGACY_PERMISSIONS);
        StorageEnvironment.allFilesAccess = false;
        output = new File(Environment.getExternalStorageDirectory(), "mdterm-report.log");
        Files.createDirectories(output.getParentFile().toPath());
        Files.deleteIfExists(output.toPath());
    }

    @Test
    public void allFilesAccessSavesWithoutLegacyRuntimePermissions() throws Exception {
        StorageEnvironment.allFilesAccess = true;

        ShareUtils.saveTextToFile(application, "report", output.getAbsolutePath(), REPORT, false, -1);

        assertEquals(REPORT, readOutput());
    }

    @Test
    public void existingLegacyPermissionStillAllowsSaving() throws Exception {
        Shadows.shadowOf(application).grantPermissions(LEGACY_PERMISSIONS);

        ShareUtils.saveTextToFile(application, "report", output.getAbsolutePath(), REPORT, false, -1);

        assertEquals(REPORT, readOutput());
    }

    @Test
    public void missingPermissionOpensAllFilesSettingsWithoutWriting() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        ShareUtils.saveTextToFile(activity, "report", output.getAbsolutePath(), REPORT, false,
            ReportActivity.REQUEST_GRANT_STORAGE_PERMISSION_FOR_SAVE_FILE);

        ShadowActivity.IntentForResult request = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(request);
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, request.intent.getAction());
        assertEquals("package:" + application.getPackageName(), request.intent.getDataString());
        assertEquals(ReportActivity.REQUEST_GRANT_STORAGE_PERMISSION_FOR_SAVE_FILE, request.requestCode);
        assertFalse(output.exists());
    }

    @Test
    public void returningFromAllFilesSettingsResumesReportSave() throws Exception {
        try (ActivityController<ReportActivity> controller = createReportActivity()) {
            StorageEnvironment.allFilesAccess = true;

            // The settings screen can return RESULT_CANCELED even after granting access.
            controller.get().onActivityResult(ReportActivity.REQUEST_GRANT_STORAGE_PERMISSION_FOR_SAVE_FILE,
                Activity.RESULT_CANCELED, null);

            assertEquals(REPORT, readOutput());
        }
    }

    @Test
    public void cancellingSettingsDoesNotWriteOrRequestAgain() {
        try (ActivityController<ReportActivity> controller = createReportActivity()) {
            controller.get().onActivityResult(ReportActivity.REQUEST_GRANT_STORAGE_PERMISSION_FOR_SAVE_FILE,
                Activity.RESULT_CANCELED, null);

            assertFalse(output.exists());
            assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivityForResult());
        }
    }

    private ActivityController<ReportActivity> createReportActivity() {
        ReportInfo reportInfo = new ReportInfo("test", "ReportStoragePermissionTest", "Diagnostic report");
        reportInfo.setReportString(REPORT);
        reportInfo.setReportSaveFileLabelAndPath("report", output.getAbsolutePath());
        Intent intent = ReportActivity.newInstance(application, reportInfo).contentIntent;
        ActivityController<ReportActivity> controller = Robolectric.buildActivity(ReportActivity.class, intent);
        controller.get().setTheme(R.style.Theme_MarkdownViewActivity_DayNight);
        return controller.setup();
    }

    // Robolectric returns mode 0 for missing paths. Android reports ENOENT, allowing the
    // real FileUtils write path to distinguish a new file from a special file.
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

    @Implements(Environment.class)
    public static class StorageEnvironment extends ShadowEnvironment {
        static boolean allFilesAccess;

        @Implementation(minSdk = 30)
        protected static boolean isExternalStorageManager() {
            return allFilesAccess;
        }
    }

    private String readOutput() throws Exception {
        assertTrue("Report was not written: " + ShadowLog.getLogs(), output.isFile());
        return new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
    }
}
