package com.aid.director.controller;

import com.aid.director.DirectorException;
import com.aid.common.core.domain.AjaxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import java.util.Map;

/** 机位接口稳定错误码与脱敏响应。 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {DirectorController.class, DirectorGuestController.class})
public class DirectorErrorAdvice {
    @ExceptionHandler(DirectorException.class)
    public ResponseEntity<AjaxResult> business(DirectorException error) {
        log.info("虚拟相机请求拒绝 code={} status={}", error.getErrorCode(), error.getStatus());
        AjaxResult result = new AjaxResult(error.getStatus(), error.getMessage(), Map.of("errorCode", error.getErrorCode(),
                "retryable", error.getStatus() == 429 || error.getStatus() == 503));
        return ResponseEntity.status(error.getStatus()).header("Cache-Control", "no-store").body(result);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class,
            com.alibaba.fastjson2.JSONException.class, ClassCastException.class})
    public ResponseEntity<AjaxResult> invalid(Exception ignored) { return business(DirectorException.invalid()); }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<AjaxResult> duplicate() { return business(DirectorException.conflict("IDEMPOTENCY_CONFLICT")); }

    @ExceptionHandler(com.aid.common.exception.ServiceException.class)
    public ResponseEntity<AjaxResult> permission() { return business(DirectorException.denied()); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AjaxResult> unavailable(Exception error) {
        // 不记录异常正文或SQL参数，避免票据、用户数据进入通用错误日志。
        log.error("虚拟相机依赖异常 type={}", error.getClass().getSimpleName());
        return business(new DirectorException("DEPENDENCY_UNAVAILABLE", 503, "服务暂不可用"));
    }
}
