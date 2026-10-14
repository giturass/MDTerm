package com.termux.view;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewGroup;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalSession;
import com.termux.test.ShadowGhosttyTerminal;
import com.termux.view.textselection.TextSelectionCursorController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Checks Java frame scheduling with terminal/Surface fixtures, without native rendering. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, shadows = TerminalRenderLifecycleTest.RenderTerminalShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalRenderLifecycleTest {
    private ActivityController<Activity> activityController;
    private TerminalView view;
    private ManualExecutor renderTasks;
    private ShadowLooper mainLooper;
    private final List<GhosttyTerminal> terminals = new ArrayList<>();

    @Before
    public void setUp() {
        activityController = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = activityController.get();
        view = new TerminalView(activity, null);
        view.setTerminalViewClient(new TermuxTerminalViewClientBase());
        view.setTextSize(20);
        // Attach View.post() to the main Looper without creating a renderable Surface.
        activity.setContentView(view, new ViewGroup.LayoutParams(0, 0));
        mainLooper = Shadows.shadowOf(Looper.getMainLooper());
        mainLooper.idle();
        assertTrue(view.isAttachedToWindow());
        assertEquals(0, view.getWidth());
        assertEquals(0, view.getHeight());

        ExecutorService original = ReflectionHelpers.getField(view, "mRenderExecutor");
        original.shutdown();
        renderTasks = new ManualExecutor();
        ReflectionHelpers.setField(view, "mRenderExecutor", renderTasks);
        view.attachSession(newSession());
        provideAttachedSurfaceState();
    }

    @After
    public void tearDown() {
        if (view != null) view.surfaceDestroyed(view.getHolder());
        if (activityController != null) activityController.pause().stop().destroy();
        if (renderTasks != null) renderTasks.shutdownNow();
        for (GhosttyTerminal terminal : terminals) terminal.close();
    }

    @Test
    public void selectionSessionSwitchIgnoresOldCompletionAndKeepsRendering() {
        GhosttyTerminal previous = view.getTerminal();
        RenderTerminalShadow previousShadow = Shadow.extract(previous);
        byte[] text = "selected text".getBytes(StandardCharsets.UTF_8);
        previous.feed(text, 0, text.length);
        TextSelectionCursorController selection = new SelectionWithoutToolbar(view);
        ReflectionHelpers.setField(view, "mTextSelectionCursorController", selection);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 15, 10, 0);
        try {
            selection.show(event);
        } finally {
            event.recycle();
        }
        assertTrue(view.isSelectingText());
        assertEquals("selected", view.getSelectedText());

        TerminalSession nextSession = newSession();
        RenderTerminalShadow nextShadow = Shadow.extract(nextSession.getTerminal());
        assertTrue(view.attachSession(nextSession));
        assertFalse(view.isSelectingText());
        assertSame(nextSession.getTerminal(), view.getTerminal());

        // Selection teardown queued an old frame before the render target changed.
        // Complete that work while leaving its posted UI completion pending.
        renderTasks.runAll();
        assertEquals(0, previousShadow.renderedFrames);
        provideAttachedSurfaceState();
        view.onScreenUpdated();
        assertEquals(1, renderTasks.pendingCount());

        // The old completion must not clear the new frame's pending state.
        mainLooper.idle();
        view.onScreenUpdated();
        assertEquals(1, renderTasks.pendingCount());
        renderTasks.runNext();
        assertEquals(1, nextShadow.renderedFrames);

        // The second update is coalesced into a subsequent frame, so rendering continues.
        mainLooper.idleFor(Duration.ofSeconds(1));
        assertEquals(1, renderTasks.pendingCount());
        renderTasks.runNext();
        mainLooper.idle();
        assertEquals(2, nextShadow.renderedFrames);
        assertEquals(0, renderTasks.pendingCount());
    }

    @Test
    public void terminalClosingDuringFrameDoesNotStartRetryLoop() {
        GhosttyTerminal terminal = view.getTerminal();
        RenderTerminalShadow shadow = Shadow.extract(terminal);
        shadow.beforeNextRender = terminal::close;
        view.onScreenUpdated();
        assertEquals(1, renderTasks.pendingCount());

        renderTasks.runNext();
        assertTrue(terminal.isClosed());
        assertEquals(1, shadow.renderedFrames);
        mainLooper.idleFor(Duration.ofSeconds(1));
        assertEquals(0, renderTasks.pendingCount());

        view.surfaceRedrawNeeded(view.getHolder());
        view.onScreenUpdated();
        mainLooper.idleFor(Duration.ofSeconds(1));
        assertEquals(0, renderTasks.pendingCount());
        assertEquals(1, shadow.renderedFrames);
    }

    private TerminalSession newSession() {
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
        GhosttyTerminal terminal = new GhosttyTerminal(session, 20, 4, 10, 20, 100);
        terminals.add(terminal);
        ReflectionHelpers.setField(session, "mTerminal", terminal);
        return session;
    }

    private void provideAttachedSurfaceState() {
        // Supply only the scheduling precondition. This fixture has no Vulkan/native Surface.
        ReflectionHelpers.setField(view, "mSurfaceReady", true);
        ReflectionHelpers.setField(view, "mSurfaceAttached", true);
    }

    /** Use real selection show/hide logic without platform floating-toolbar windows. */
    private static final class SelectionWithoutToolbar extends TextSelectionCursorController {
        SelectionWithoutToolbar(TerminalView view) {
            super(view);
        }

        @Override
        public void setActionModeCallBacks() { }
    }

    /** Count Java requests at the native boundary while retaining the shared terminal fixture. */
    @Implements(value = GhosttyTerminal.class, isInAndroidSdk = false)
    public static class RenderTerminalShadow extends ShadowGhosttyTerminal {
        int renderedFrames;
        Runnable beforeNextRender;

        @Override
        @Implementation
        protected boolean render(boolean cursorVisible) {
            renderedFrames++;
            Runnable action = beforeNextRender;
            beforeNextRender = null;
            if (action != null) action.run();
            return super.render(cursorVisible);
        }
    }

    /** Render work runs only when the test advances it; View.post still uses Android's Looper. */
    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        private boolean shutdown;

        int pendingCount() { return pending.size(); }

        void runNext() {
            assertFalse("Expected queued render work", pending.isEmpty());
            pending.removeFirst().run();
        }

        void runAll() {
            while (!pending.isEmpty()) runNext();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException("Test executor is shut down");
            pending.addLast(command);
        }

        @Override
        public void shutdown() { shutdown = true; }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<>(pending);
            pending.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() { return shutdown; }

        @Override
        public boolean isTerminated() { return shutdown && pending.isEmpty(); }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
