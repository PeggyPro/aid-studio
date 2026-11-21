package com.aid.previz.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=PrevizDocumentController.class)
public class PrevizDocumentErrors {
    @ExceptionHandler(IOException.class)
    public ResponseEntity<AjaxResult> storage(IOException failure) {
        String code=failure.getMessage();if(code==null||!code.matches("[A-Z_]+"))code="DOCUMENT_STORAGE_UNAVAILABLE";
        int status;String message;
        switch(code){
            case "WORKSPACE_VERSION_CONFLICT","DOCUMENT_VERSION_CONFLICT" -> {status=409;message="项目源码或场景已更新，请保留本地修改并重新核对版本";}
            case "DOCUMENT_NOT_FOUND" -> {status=404;message="没有可访问的已保存版本";}
            case "DOCUMENT_SIZE_EXCEEDED" -> {status=413;message="项目文档超过配置的大小限制";}
            case "DOCUMENT_HISTORY_LIMIT","DOCUMENT_QUOTA_EXCEEDED" -> {status=429;message="项目历史或存储容量已达到配置限制";}
            default -> {status=503;message="项目存储暂不可用，请保留本地修改；本次未确认保存完成";}
        }
        var body=AjaxResult.error(status,message);body.put("errorCode",code);return ResponseEntity.status(status).body(body);
    }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<AjaxResult> parameters(Exception failure){return ResponseEntity.badRequest().body(AjaxResult.error(400,"项目文档结构、参数或引用无效"));}
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<AjaxResult> permission(ServiceException failure){return ResponseEntity.status(403).body(AjaxResult.error(403,"没有该项目或引用资源的访问权限"));}
}
