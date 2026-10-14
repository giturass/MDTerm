package com.termux.terminal;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Ordered PTY writes whose producers never wait for the process to read.
 *
 * A bounded blocking queue here can deadlock a paste or a terminal reply: the
 * process blocks writing its echo, the input reader blocks on the screen queue,
 * and the main thread cannot drain that queue while waiting to submit the write.
 * Only the dedicated output thread waits; submitted byte arrays are copied
 * because both JNI and the keyboard encoder reuse their buffers.
 */
final class PendingWriteQueue {
    private final ArrayDeque<byte[]> mWrites = new ArrayDeque<>();
    private int mHeadOffset;
    private boolean mOpen = true;

    public synchronized boolean write(byte[] data, int offset, int count) {
        if (offset < 0 || count < 0 || offset > data.length - count)
            throw new IndexOutOfBoundsException("Invalid PTY write range");
        if (!mOpen) return false;
        if (count > 0) {
            mWrites.addLast(Arrays.copyOfRange(data, offset, offset + count));
            notifyAll();
        }
        return true;
    }

    public synchronized int read(byte[] buffer, boolean block) {
        if (buffer.length == 0) return 0;
        while (mOpen && mWrites.isEmpty()) {
            if (!block) return 0;
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        if (!mOpen) return -1;

        int copied = 0;
        while (copied < buffer.length && !mWrites.isEmpty()) {
            byte[] head = mWrites.peekFirst();
            int count = Math.min(buffer.length - copied, head.length - mHeadOffset);
            System.arraycopy(head, mHeadOffset, buffer, copied, count);
            copied += count;
            mHeadOffset += count;
            if (mHeadOffset == head.length) {
                mWrites.removeFirst();
                mHeadOffset = 0;
            }
        }
        return copied;
    }

    public synchronized void close() {
        mOpen = false;
        mWrites.clear();
        mHeadOffset = 0;
        notifyAll();
    }
}
