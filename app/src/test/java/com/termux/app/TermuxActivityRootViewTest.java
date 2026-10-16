package com.termux.app;

import android.app.Application;
import android.view.View;
import android.widget.FrameLayout;

import com.termux.app.terminal.TermuxActivityRootView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class TermuxActivityRootViewTest {
    @Test
    public void multiWindowClearsExistingAndPendingKeyboardMargins() {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        activity.mTermuxActivityBottomSpaceView = new View(activity);
        Shadows.shadowOf(activity).setInMultiWindowMode(true);
        TermuxActivityRootView root = new TermuxActivityRootView(activity);
        root.setActivity(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(320, 640);
        params.bottomMargin = 176;
        root.setLayoutParams(params);
        root.marginBottom = 240;
        root.lastMarginBottom = 176;

        root.onGlobalLayout();

        assertEquals(0, params.bottomMargin);
        assertNull(root.marginBottom);
        assertNull(root.lastMarginBottom);
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        root.onGlobalLayout();
        assertEquals("Relayout must not restore the cached keyboard margin", 0, params.bottomMargin);
    }

    @Test
    public void multiWindowClearsCachedMarginsEvenIfNoMarginIsCurrentlyApplied() {
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        activity.mTermuxActivityBottomSpaceView = new View(activity);
        Shadows.shadowOf(activity).setInMultiWindowMode(true);
        TermuxActivityRootView root = new TermuxActivityRootView(activity);
        root.setActivity(activity);
        root.setLayoutParams(new FrameLayout.LayoutParams(320, 640));
        root.marginBottom = 176;
        root.lastMarginBottom = 176;

        root.onGlobalLayout();

        assertNull(root.marginBottom);
        assertNull(root.lastMarginBottom);
        assertEquals(0, ((FrameLayout.LayoutParams) root.getLayoutParams()).bottomMargin);
    }
}
