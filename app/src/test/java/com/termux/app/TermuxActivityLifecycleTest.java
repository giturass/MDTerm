package com.termux.app;

import android.app.Application;
import android.os.Looper;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN")
public class TermuxActivityLifecycleTest {
    @Test
    public void backgroundServiceRestrictionShowsErrorAndSafelyCompletesLifecycle() {
        assertServiceFailureHandled(new SecurityException("app is in background"),
            R.string.error_termux_service_start_failed_bg);
    }

    @Test
    public void serviceFailureWithoutExceptionMessageUsesGeneralError() {
        assertServiceFailureHandled(new SecurityException(),
            R.string.error_termux_service_start_failed_general);
    }

    private void assertServiceFailureHandled(SecurityException failure, int messageId) {
        Application application = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(application).setThrowInBindService(failure);
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        try {
            controller.create().start().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TermuxActivity activity = controller.get();
            assertTrue(ReflectionHelpers.<Boolean>getField(activity, "mIsInvalidState"));
            assertFalse(activity.isVisible());
            assertNull(activity.getTermuxService());
            assertEquals(activity.getString(messageId), ShadowToast.getTextOfLatestToast());
            assertFalse(Shadows.shadowOf(application).getBroadcastIntents().stream().anyMatch(
                intent -> TermuxConstants.BROADCAST_TERMUX_OPENED.equals(intent.getAction())));
            controller.pause().stop().destroy();
        } finally {
            Shadows.shadowOf(application).setThrowInBindService(null);
        }
    }
}
