package com.termux.app;

import android.content.Context;

import com.termux.shared.termux.crash.TermuxCrashUtils;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Prevent activity lifecycle tests from consuming the installed app's crash log on Termux. */
@Implements(TermuxCrashUtils.class)
public class TermuxCrashUtilsTestShadow {
    @Implementation
    protected static void notifyAppCrashFromCrashLogFile(Context context, String logTag) {
        // Crash log handling uses fixed installed paths and is outside these UI tests' scope.
    }
}
