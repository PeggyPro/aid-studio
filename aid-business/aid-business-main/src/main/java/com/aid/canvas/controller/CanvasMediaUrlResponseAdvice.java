package com.aid.canvas.controller;

import com.aid.common.aid.oss.util.MediaPayloadUrlNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 仅在画布 C 端输出阶段解析媒体地址，数据库和分享快照继续保存可迁移的相对路径。 */
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RestControllerAdvice(assignableTypes = CanvasUserController.class)
@RequiredArgsConstructor
public class CanvasMediaUrlResponseAdvice implements ResponseBodyAdvice<Object> {
    private final MediaPayloadUrlNormalizer normalizer;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        return normalizer.normalize(body);
    }
}
