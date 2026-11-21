package com.aid.media.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** HTTP 响应体有界读取器：达到上限后立即取消订阅，并显式标记正文不完整。 */
public final class BoundedResponseBody {

    private BoundedResponseBody() {
    }

    /** 同步响应按 HTTP 状态选择上限，避免 ofString 在业务校验前完整缓冲。 */
    public static HttpResponse.BodyHandler<Utf8Read> utf8(int successMaxBytes,
                                                           int errorMaxBytes) {
        requirePositive(successMaxBytes);
        requirePositive(errorMaxBytes);
        return response -> new Utf8Subscriber(isSuccess(response.statusCode())
                ? successMaxBytes : errorMaxBytes);
    }

    /** 成功响应保留 InputStream，失败响应在 HTTP 层按字节有界读取。 */
    public static HttpResponse.BodyHandler<Object> inputStreamOrError(int errorMaxBytes) {
        requirePositive(errorMaxBytes);
        return response -> isSuccess(response.statusCode())
                ? HttpResponse.BodySubscribers.mapping(
                        HttpResponse.BodySubscribers.ofInputStream(), body -> (Object) body)
                : HttpResponse.BodySubscribers.mapping(
                        new Utf8Subscriber(errorMaxBytes), body -> (Object) body);
    }

    public static Utf8Read requireRead(Object body) {
        if (body instanceof Utf8Read read) {
            return read;
        }
        throw new IllegalStateException("HTTP错误响应类型无效");
    }

    public static InputStream requireInputStream(Object body) {
        if (body instanceof InputStream input) {
            return input;
        }
        throw new IllegalStateException("HTTP流式响应类型无效");
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static void requirePositive(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("响应上限必须大于0");
        }
    }

    public record Utf8Read(String body, boolean complete) {
        public Utf8Read {
            body = body == null ? "" : body;
        }
    }

    /** 逐字符读取 UTF-8 行，在 String 分配增长前执行单行与累计上限。 */
    public static final class Utf8LineReader implements AutoCloseable {
        private final PushbackReader reader;
        private final long maxTotalChars;
        private final int maxLineChars;
        private long totalChars;

        public Utf8LineReader(InputStream input, long maxTotalChars, int maxLineChars) {
            if (input == null || maxTotalChars <= 0 || maxLineChars <= 0) {
                throw new IllegalArgumentException("流式响应及上限不能为空");
            }
            this.reader = new PushbackReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8), 1);
            this.maxTotalChars = maxTotalChars;
            this.maxLineChars = maxLineChars;
        }

        public String readLine() throws IOException {
            StringBuilder line = new StringBuilder(Math.min(maxLineChars, 8192));
            int value;
            boolean terminated = false;
            while ((value = reader.read()) != -1) {
                if (value == '\n') {
                    terminated = true;
                    break;
                }
                if (value == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.unread(next);
                    }
                    terminated = true;
                    break;
                }
                if (line.length() >= maxLineChars) {
                    throw new LimitExceededException("文本事件超限");
                }
                if (totalChars + line.length() + 1L > maxTotalChars) {
                    throw new LimitExceededException("文本响应超限");
                }
                line.append((char) value);
            }
            if (value == -1 && line.isEmpty()) {
                return null;
            }
            long consumed = line.length() + (terminated ? 1L : 0L);
            if (totalChars + consumed > maxTotalChars) {
                throw new LimitExceededException("文本响应超限");
            }
            totalChars += consumed;
            return line.toString();
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    public static final class LimitExceededException extends IOException {
        public LimitExceededException(String message) {
            super(message);
        }
    }

    private static final class Utf8Subscriber implements HttpResponse.BodySubscriber<Utf8Read> {
        private final int maxBytes;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<Utf8Read> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean done;

        private Utf8Subscriber(int maxBytes) {
            this.maxBytes = maxBytes;
            this.output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        }

        @Override
        public CompletionStage<Utf8Read> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            try {
                for (ByteBuffer buffer : buffers) {
                    int remainingCapacity = maxBytes - output.size();
                    int copyLength = Math.min(remainingCapacity, buffer.remaining());
                    if (copyLength > 0) {
                        byte[] chunk = new byte[copyLength];
                        buffer.get(chunk);
                        output.write(chunk, 0, chunk.length);
                    }
                    if (buffer.hasRemaining()) {
                        finish(false);
                        subscription.cancel();
                        return;
                    }
                }
                subscription.request(1);
            } catch (RuntimeException failure) {
                done = true;
                subscription.cancel();
                body.completeExceptionally(failure);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!done) {
                done = true;
                body.completeExceptionally(throwable);
            }
        }

        @Override
        public void onComplete() {
            finish(true);
        }

        private void finish(boolean complete) {
            if (done) {
                return;
            }
            done = true;
            body.complete(new Utf8Read(output.toString(StandardCharsets.UTF_8), complete));
        }
    }
}
