package com.termux.app;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.os.Looper;

import com.termux.app.event.SystemEventReceiver;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.TermuxShellUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalSession;
import com.termux.test.ShadowGhosttyTerminal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises pressure dispatch and service lifetime, without native compression or a PTY. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, shadows = {
    ScrollbackCompressionServiceTest.CompressionTerminalShadow.class,
    ScrollbackCompressionServiceTest.ShellCleanupShadow.class
})
@LooperMode(LooperMode.Mode.PAUSED)
public class ScrollbackCompressionServiceTest {
    private TermuxService service;
    private TermuxShellManager manager;
    private ManualExecutor worker;
    private ShadowLooper mainLooper;
    private boolean destroyed;
    private final List<GhosttyTerminal> terminals = new ArrayList<>();

    @Before
    public void setUp() {
        service = Robolectric.buildService(TermuxService.class).get();
        manager = new TermuxShellManager(service);
        ReflectionHelpers.setField(service, "mShellManager", manager);
        ScheduledExecutorService original = ReflectionHelpers.getField(service, "mCompressionExecutor");
        original.shutdownNow();
        worker = new ManualExecutor();
        ReflectionHelpers.setField(service, "mCompressionExecutor", worker);
        mainLooper = Shadows.shadowOf(Looper.getMainLooper());
        SystemEventReceiver.registerPackageUpdateEvents(service);
        // No real processes were started, and shutdown must not try to kill them.
        service.mWantsToStop = true;
    }

    @After
    public void tearDown() {
        destroyService();
        mainLooper.idle();
        for (GhosttyTerminal terminal : terminals) terminal.close();
    }

    @Test
    public void pressureEventsCoalesceAndNativeWorkWaitsForWorker() {
        CompressionTerminalShadow terminal = addTerminal();
        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE);
        mainLooper.idle();
        assertTrue(worker.pending.isEmpty());

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
        service.onLowMemory();
        mainLooper.idle();
        assertEquals(1, worker.pending.size());
        assertEquals(0, terminal.queries);
        assertEquals(0, terminal.steps);

        worker.runNext();
        assertEquals(1, terminal.queries);
        assertEquals(1, terminal.steps);
        assertTrue(worker.pending.isEmpty());

        service.onLowMemory();
        mainLooper.idle();
        worker.runNext();
        assertEquals(2, terminal.queries);
        assertEquals(2, terminal.steps);
    }

    @Test
    public void pendingWorkYieldsBetweenBatchesWithoutRepeatingMemoryQueries() {
        CompressionTerminalShadow terminal = addTerminal();
        terminal.stepsUntilComplete = 3;
        service.onLowMemory();
        mainLooper.idle();
        worker.runNext();
        assertEquals(2, terminal.steps);
        assertEquals(1, terminal.queries);
        assertEquals(1, worker.pending.size());
        assertEquals(100, worker.lastDelayMillis);

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
        mainLooper.idle();
        assertEquals(1, worker.pending.size());
        worker.runNext();
        assertEquals(3, terminal.steps);
        assertEquals(1, terminal.queries);
        assertTrue(worker.pending.isEmpty());
    }

    @Test
    public void destructionBeforeMainThreadSnapshotSubmitsNoWork() {
        CompressionTerminalShadow terminal = addTerminal();
        service.onLowMemory();
        destroyService();
        mainLooper.idle();
        service.onLowMemory();
        mainLooper.idle();
        assertTrue(worker.isShutdown());
        assertTrue(worker.pending.isEmpty());
        assertEquals(0, terminal.queries);
    }

    @Test
    public void workerAlreadyDispatchedAtDestructionDoesNotAccessTerminals() {
        CompressionTerminalShadow terminal = addTerminal();
        service.onLowMemory();
        mainLooper.idle();
        Runnable dispatched = worker.pending.removeFirst();
        destroyService();
        dispatched.run();
        assertEquals(0, terminal.queries);
        assertEquals(0, terminal.steps);
        assertTrue(worker.pending.isEmpty());
    }

    @Test
    public void destructionStopsAnAlreadyDispatchedContinuation() {
        CompressionTerminalShadow terminal = addTerminal();
        terminal.stepsUntilComplete = 3;
        service.onLowMemory();
        mainLooper.idle();
        worker.runNext();
        Runnable dispatched = worker.pending.removeFirst();
        destroyService();
        dispatched.run();
        assertEquals(2, terminal.steps);
        assertTrue(worker.isShutdown());
        assertTrue(worker.pending.isEmpty());
    }

    @Test
    public void rejectionAndQueryFailureDoNotBlockTheNextPressureEvent() {
        CompressionTerminalShadow terminal = addTerminal();
        worker.reject = true;
        service.onLowMemory();
        mainLooper.idle();
        assertTrue(worker.pending.isEmpty());

        worker.reject = false;
        terminal.failNextQuery = true;
        service.onLowMemory();
        mainLooper.idle();
        worker.runNext();
        assertEquals(1, terminal.queries);
        assertEquals(0, terminal.steps);

        service.onLowMemory();
        mainLooper.idle();
        worker.runNext();
        assertEquals(2, terminal.queries);
        assertEquals(1, terminal.steps);
    }

    @Test
    public void sessionClosedAfterSnapshotIsSkipped() {
        CompressionTerminalShadow terminal = addTerminal();
        service.onLowMemory();
        mainLooper.idle();
        terminals.get(0).close();
        manager.mTermuxSessions.clear();
        worker.runNext();
        assertEquals(1, terminal.queries);
        assertEquals(0, terminal.steps);
        assertTrue(worker.pending.isEmpty());
    }

    private CompressionTerminalShadow addTerminal() {
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase());
        GhosttyTerminal terminal = new GhosttyTerminal(session, 80, 24, 10, 20, 100);
        ReflectionHelpers.setField(session, "mTerminal", terminal);
        TermuxSession wrapped = ReflectionHelpers.callConstructor(TermuxSession.class,
            ClassParameter.from(TerminalSession.class, session),
            ClassParameter.from(ExecutionCommand.class, new ExecutionCommand()),
            ClassParameter.from(TermuxSession.TermuxSessionClient.class, null),
            ClassParameter.from(boolean.class, false));
        manager.mTermuxSessions.add(wrapped);
        terminals.add(terminal);
        return Shadow.extract(terminal);
    }

    private void destroyService() {
        if (destroyed) return;
        destroyed = true;
        service.onDestroy();
    }

    @Implements(value = GhosttyTerminal.class, isInAndroidSdk = false)
    public static class CompressionTerminalShadow extends ShadowGhosttyTerminal {
        int queries;
        int steps;
        int stepsUntilComplete = 1;
        boolean failNextQuery;

        @Override
        @Implementation
        protected long[] getMemoryUsageForCompression() {
            queries++;
            if (failNextQuery) {
                failNextQuery = false;
                throw new IllegalStateException("Terminal unavailable during memory query");
            }
            return isClosed() ? null : new long[]{1024, 1};
        }

        @Override
        @Implementation
        protected int compressScrollbackIncrementally() {
            if (isClosed()) return 0;
            return ++steps < stepsUntilComplete ? 1 : 2;
        }
    }

    /** Service destruction should not touch a host's actual Termux temporary directory. */
    @Implements(value = TermuxShellUtils.class, isInAndroidSdk = false)
    public static class ShellCleanupShadow {
        @Implementation
        protected static void clearTermuxTMPDIR(boolean onlyIfExists) { }
    }

    /** Real Handler callbacks, with worker tasks advanced explicitly to exercise shutdown races. */
    private static final class ManualExecutor extends ScheduledThreadPoolExecutor {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        long lastDelayMillis;
        boolean reject;

        ManualExecutor() { super(1); }

        void runNext() {
            assertFalse("Expected queued compression work", pending.isEmpty());
            pending.removeFirst().run();
        }

        @Override
        public void execute(Runnable command) {
            if (reject || isShutdown()) throw new RejectedExecutionException();
            pending.addLast(command);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            lastDelayMillis = unit.toMillis(delay);
            ScheduledTask task = new ScheduledTask(command, lastDelayMillis);
            execute(task);
            return task;
        }

        @Override
        public List<Runnable> shutdownNow() {
            List<Runnable> remaining = new ArrayList<>(pending);
            pending.clear();
            super.shutdownNow();
            return remaining;
        }
    }

    private static final class ScheduledTask extends FutureTask<Void> implements ScheduledFuture<Void> {
        final long delayMillis;

        ScheduledTask(Runnable command, long delayMillis) {
            super(command, null);
            this.delayMillis = delayMillis;
        }

        @Override public long getDelay(TimeUnit unit) {
            return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override public int compareTo(Delayed other) {
            return Long.compare(delayMillis, other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
