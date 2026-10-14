package com.termux.terminal;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class PendingWriteQueueTest {
    @Test(timeout = 2000)
    public void largePasteDoesNotWaitForTheProcessToRead() {
        PendingWriteQueue queue = new PendingWriteQueue();
        byte[] paste = new byte[512 * 1024];
        for (int i = 0; i < paste.length; i++) paste[i] = (byte) i;

        // No output consumer is running. A blocking ring buffer would stall
        // the UI before it can drain the echo that lets the process read again.
        assertTrue(queue.write(paste, 0, paste.length));
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = queue.read(buffer, false)) > 0)
            received.write(buffer, 0, count);
        assertArrayEquals(paste, received.toByteArray());
    }

    @Test
    public void reusedBuffersAndPartialReadsKeepInputAndRepliesInOrder() {
        PendingWriteQueue queue = new PendingWriteQueue();
        byte[] reused = new byte[]{0, 1, 2, 3, 4};
        queue.write(reused, 1, 3);
        Arrays.fill(reused, (byte) 9);
        queue.write(reused, 0, 2);
        byte[] pair = new byte[2];
        assertEquals(2, queue.read(pair, false));
        assertArrayEquals(new byte[]{1, 2}, pair);
        assertEquals(2, queue.read(pair, false));
        assertArrayEquals(new byte[]{3, 9}, pair);
        assertEquals(1, queue.read(pair, false));
        assertEquals(9, pair[0]);
        assertEquals(0, queue.read(pair, false));
    }

    @Test
    public void closingSessionReleasesWaitingWriterAndRejectsLaterInput() throws Exception {
        PendingWriteQueue queue = new PendingWriteQueue();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<Integer> writer = executor.submit(() -> {
                started.countDown();
                return queue.read(new byte[16], true);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            queue.close();
            assertEquals(-1, writer.get(1, TimeUnit.SECONDS).intValue());
            assertFalse(queue.write(new byte[]{1}, 0, 1));
            assertEquals(-1, queue.read(new byte[16], false));
        } finally {
            queue.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void invalidRangesDoNotQueuePartialInput() {
        PendingWriteQueue queue = new PendingWriteQueue();
        assertThrows(IndexOutOfBoundsException.class,
            () -> queue.write(new byte[4], 1, Integer.MAX_VALUE));
        assertThrows(IndexOutOfBoundsException.class,
            () -> queue.write(new byte[4], -1, 1));
        assertEquals(0, queue.read(new byte[16], false));
    }
}
