package com.aid.media.provider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** HttpRequest.timeout bounds headers; this independent deadline also closes a stalled response body. */
final class TextResponseDeadline extends FilterInputStream {
    private static final ScheduledThreadPoolExecutor TIMER;
    static {
        TIMER = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "text-response-deadline"); thread.setDaemon(true); return thread;
        });
        TIMER.setRemoveOnCancelPolicy(true);
    }
    private final ScheduledFuture<?> timeout;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean expired;

    TextResponseDeadline(InputStream input, long millis) {
        super(input);
        if (millis <= 0) throw new IllegalArgumentException("响应截止时间必须为正数");
        timeout = TIMER.schedule(() -> {
            expired = true;
            try { close(); } catch (IOException ignored) { }
        }, millis, TimeUnit.MILLISECONDS);
    }
    private void requireOpen() throws IOException {
        if (expired) throw new java.io.InterruptedIOException("文本响应超时");
        if (closed.get()) throw new IOException("文本响应已关闭");
    }
    @Override public int read() throws IOException { requireOpen(); int value = in.read(); requireOpen(); return value; }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        requireOpen(); int count = in.read(bytes, offset, length); requireOpen(); return count;
    }
    @Override public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            if (timeout != null) timeout.cancel(false);
            in.close();
        }
    }
}
