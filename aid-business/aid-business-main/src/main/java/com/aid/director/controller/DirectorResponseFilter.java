package com.aid.director.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 配对凭据、场景及轨迹响应禁止浏览器和中间缓存保存。 */
@Component
public class DirectorResponseFilter extends OncePerRequestFilter {
    private final java.util.concurrent.Semaphore sceneWrites;
    private final java.util.concurrent.Semaphore chunkRequests;

    public DirectorResponseFilter(com.aid.director.DirectorProperties properties) {
        sceneWrites = new java.util.concurrent.Semaphore(properties.getMaxSceneWrites());
        chunkRequests = new java.util.concurrent.Semaphore(properties.getMaxChunkRequests());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        java.util.concurrent.Semaphore admission = null;
        if (request.getRequestURI().startsWith("/api/user/studio/director/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            if ("POST".equals(request.getMethod())) {
                boolean scene = request.getRequestURI().endsWith("/scene/save");
                boolean chunk = request.getRequestURI().endsWith("/recording/chunk");
                int limit = scene ? 4 * 1024 * 1024 : chunk ? 256 * 1024 : 16 * 1024;
                if (request.getContentLengthLong() > limit) {
                    response.setStatus(413); response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":413,\"msg\":\"请求内容过大\"}"); return;
                }
                admission = scene ? sceneWrites : chunk ? chunkRequests : null;
                if (admission != null && !admission.tryAcquire()) {
                    response.setStatus(429); response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":429,\"msg\":\"保存繁忙，请稍后\",\"data\":{\"errorCode\":\"REQUEST_CAPACITY_REACHED\",\"retryable\":true}}");
                    return;
                }
                request = bounded(request, limit);
            }
        }
        try { chain.doFilter(request, response); }
        finally { if (admission != null) admission.release(); }
    }

    private HttpServletRequest bounded(HttpServletRequest request, int limit) {
        return new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            private jakarta.servlet.ServletInputStream input;
            @Override public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
                if (input != null) return input;
                var delegate = super.getInputStream();
                input = new jakarta.servlet.ServletInputStream() {
                    private int remaining = limit;
                    private void count(int size) throws IOException {
                        if (size > 0 && (remaining -= size) < 0) throw new IOException("director payload limit");
                    }
                    @Override public int read() throws IOException { int value = delegate.read(); count(value < 0 ? 0 : 1); return value; }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        int size = delegate.read(bytes, offset, Math.min(length, Math.max(1, remaining + 1))); count(size); return size;
                    }
                    @Override public boolean isFinished() { return delegate.isFinished(); }
                    @Override public boolean isReady() { return delegate.isReady(); }
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener) { delegate.setReadListener(listener); }
                };
                return input;
            }
        };
    }
}
