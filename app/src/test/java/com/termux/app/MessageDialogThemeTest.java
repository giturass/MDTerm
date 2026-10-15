package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.termux.R;
import com.termux.shared.interact.MessageDialogUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "en-rUS-w360dp-h640dp-notnight-mdpi")
public class MessageDialogThemeTest {
    @Test
    public void lightMessagesAndInstallerKeepTheCurrentPalette() {
        checkDialogs(false);
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-night-mdpi")
    public void darkMessagesAndInstallerKeepTheCurrentPalette() {
        checkDialogs(true);
    }

    @Test
    public void simpleMessageKeepsDefaultActionAndDismissCallback() {
        try (ActivityController<AppCompatActivity> controller = host()) {
            AtomicInteger dismissed = new AtomicInteger();
            MessageDialogUtils.showMessage(controller.get(), null, "Installation failed",
                dialog -> dismissed.incrementAndGet());
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals("Installation failed", ((TextView) dialog.findViewById(android.R.id.message)).getText().toString());
            assertEquals(controller.get().getString(android.R.string.ok),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertFalse(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getVisibility() == View.VISIBLE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse(dialog.isShowing());
            assertEquals(1, dismissed.get());
        }
    }

    private static void checkDialogs(boolean dark) {
        try (ActivityController<AppCompatActivity> controller = host()) {
            AppCompatActivity activity = controller.get();
            for (int action : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                List<Integer> actions = new ArrayList<>();
                AtomicInteger dismissed = new AtomicInteger();
                MessageDialogUtils.showMessage(activity, "Report an issue",
                    "Visit https://example.com or email help@example.com", "Continue",
                    (dialog, which) -> actions.add(which), "Cancel",
                    (dialog, which) -> actions.add(which), dialog -> dismissed.incrementAndGet());
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                int background = checkMessageColors(dialog, dark);
                TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
                assertEquals("Report an issue", title.getText().toString());
                assertReadable(title.getCurrentTextColor(), background);
                TextView message = dialog.findViewById(android.R.id.message);
                assertTrue(message.getMovementMethod() instanceof LinkMovementMethod);
                Spanned linked = (Spanned) message.getText();
                Set<String> links = new HashSet<>();
                for (URLSpan link : linked.getSpans(0, linked.length(), URLSpan.class)) links.add(link.getURL());
                assertEquals(new HashSet<>(Arrays.asList("https://example.com", "mailto:help@example.com")), links);
                assertEquals(color(activity, com.google.android.material.R.attr.colorPrimary),
                    message.getLinkTextColors().getDefaultColor());
                assertReadable(message.getLinkTextColors().getDefaultColor(), background);
                for (int button : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                    assertEquals(color(activity, com.google.android.material.R.attr.colorPrimary),
                        dialog.getButton(button).getCurrentTextColor());
                    assertReadable(dialog.getButton(button).getCurrentTextColor(), background);
                }
                dialog.getButton(action).performClick();
                ShadowLooper.idleMainLooper();
                assertEquals(Collections.singletonList(action), actions);
                assertEquals(1, dismissed.get());
                assertFalse(dialog.isShowing());
            }

            AlertDialog progress = TermuxInstaller.showBootstrapProgressDialog(activity);
            try {
                checkMessageColors(progress, dark);
                assertEquals(activity.getString(R.string.bootstrap_installer_body),
                    ((TextView) progress.findViewById(android.R.id.message)).getText().toString());
                ProgressBar spinner = findSpinner(progress.getWindow().getDecorView());
                assertNotNull(spinner);
                assertTrue(spinner.isIndeterminate());
                assertEquals(color(progress.getContext(), com.google.android.material.R.attr.colorPrimary),
                    color(spinner.getContext(), com.google.android.material.R.attr.colorPrimary));
                progress.onBackPressed();
                ShadowLooper.idleMainLooper();
                assertTrue("Installation must stay visible until the worker finishes", progress.isShowing());
            } finally {
                progress.dismiss();
            }
        }
    }

    private static int checkMessageColors(AlertDialog dialog, boolean dark) {
        Drawable background = dialog.getWindow().getDecorView().getBackground();
        assertTrue(background instanceof InsetDrawable);
        Drawable surface = ((InsetDrawable) background).getDrawable();
        assertTrue(surface instanceof MaterialShapeDrawable);
        int container = ((MaterialShapeDrawable) surface).getFillColor().getDefaultColor();
        assertEquals(!dark, MaterialColors.isColorLight(container));
        TextView message = dialog.findViewById(android.R.id.message);
        assertNotNull(message);
        assertReadable(message.getCurrentTextColor(), container);
        return container;
    }

    private static void assertReadable(int foreground, int background) {
        double contrast = ColorUtils.calculateContrast(foreground, background);
        assertTrue("Dialog text contrast must reach 4.5:1; was " + contrast, contrast >= 4.5);
    }

    private static int color(Context context, int attribute) {
        return MaterialColors.getColor(context, attribute, "MessageDialogThemeTest");
    }

    private static ProgressBar findSpinner(View view) {
        if (view instanceof ProgressBar) return (ProgressBar) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ProgressBar spinner = findSpinner(group.getChildAt(i));
                if (spinner != null) return spinner;
            }
        }
        return null;
    }

    private static ActivityController<AppCompatActivity> host() {
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_TermuxApp_DayNight_NoActionBar);
        return controller.setup();
    }
}
