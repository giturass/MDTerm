package com.termux.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import com.termux.app.terminal.SessionActivityIndicator;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SessionActivityIndicatorTest {

    @Test
    public void rippleStopsForHiddenDetachedAndFinishedSessions() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        FrameLayout card = new FrameLayout(activity);
        activity.setContentView(card);
        SessionActivityIndicator indicator = new SessionActivityIndicator(activity, null);
        card.addView(indicator, new FrameLayout.LayoutParams(20, 20));
        ValueAnimator animator = ReflectionHelpers.getField(indicator, "mAnimator");

        indicator.setActive(true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(animator.isStarted());
        card.setVisibility(View.INVISIBLE);
        assertFalse(animator.isStarted());
        card.setVisibility(View.VISIBLE);
        assertTrue(animator.isStarted());

        indicator.setActive(false);
        assertEquals(View.GONE, indicator.getVisibility());
        assertFalse(animator.isStarted());

        indicator.setActive(true);
        assertTrue(animator.isStarted());
        card.removeView(indicator);
        assertFalse(animator.isStarted());
        card.addView(indicator);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(animator.isStarted());

        controller.pause().stop().destroy();
        assertFalse(animator.isStarted());
    }
}
