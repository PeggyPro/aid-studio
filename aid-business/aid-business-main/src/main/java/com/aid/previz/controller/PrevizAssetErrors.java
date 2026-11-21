package com.aid.previz.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Asset upload failures stay separate from generation/charging outcomes. */
@RestControllerAdvice(assignableTypes=PrevizProjectAssetController.class)
public class PrevizAssetErrors {
    @ExceptionHandler(IOException.class)
    public ResponseEntity<AjaxResult> storage(IOException failure) {
        String code=failure.getMessage();
        if(code==null||!code.matches("ASSET_[A-Z_]+"))code="ASSET_STORAGE_UNAVAILABLE";
        int status;String message;
        switch(code) {
            case "ASSET_UPLOAD_CONFLICT", "ASSET_WRITE_CONFLICT" -> {status=409;message="同一上传操作的内容已变化，请使用新的上传操作";}
            case "ASSET_UPLOAD_CANCELLED" -> {status=409;message="这次上传已取消，请重新选择文件开始上传";}
            case "ASSET_FILE_UNAVAILABLE", "ASSET_UPLOAD_NOT_FOUND" -> {status=404;message="资产或上传分块不存在，请核对上传进度";}
            case "ASSET_PROJECT_QUOTA_EXCEEDED", "ASSET_PROJECT_COUNT_EXCEEDED", "ASSET_UPLOAD_RECORD_LIMIT" -> {status=429;message="项目资产容量或上传数量已达到配置限制";}
            case "ASSET_STORAGE_SPACE_LOW" -> {status=507;message="资产存储空间不足，本次上传尚未完成";}
            case "ASSET_DIGEST_MISMATCH", "ASSET_CHUNK_DIGEST_MISMATCH", "ASSET_CHUNK_ENCODING_INVALID",
                 "ASSET_GLB_INVALID", "ASSET_IMAGE_INVALID", "ASSET_IMAGE_FORMAT_MISMATCH", "ASSET_IMAGE_DIMENSIONS_INVALID" -> {status=422;message="资产内容、格式或校验值不符合要求，本次未发布资产";}
            default -> {status=503;message="资产存储暂不可用，请保留当前上传操作后核对重试";}
        }
        AjaxResult body=AjaxResult.error(status,message);body.put("errorCode",code);return ResponseEntity.status(status).body(body);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AjaxResult> parameters(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(AjaxResult.error(400,"资产上传或读取参数无效"));
    }
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<AjaxResult> malformed(Exception failure) {
        return ResponseEntity.badRequest().body(AjaxResult.error(400,"资产请求格式或字段范围无效"));
    }
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<AjaxResult> permission(ServiceException failure) {
        int status=Integer.valueOf(401).equals(failure.getCode())?401:403;
        return ResponseEntity.status(status).body(AjaxResult.error(status,"未登录或没有该项目的资产访问权限"));
    }
}
