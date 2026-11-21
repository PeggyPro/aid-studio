package com.aid.media.controller;

import com.aid.common.constant.HttpStatus;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.aid.media.service.MediaPreparationErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** 只把确定的音频前置校验标识呈现给调用方，不把内部异常推断为参数错误。 */
@Order(0)
@RestControllerAdvice(assignableTypes={OfficialAudioController.class,OfficialAudioAdminController.class})
public class OfficialAudioExceptionHandler {
    @ExceptionHandler(ServiceException.class)
    public AjaxResult service(ServiceException failure) {
        MediaPreparationErrorCode code=MediaPreparationErrorCode.read(failure.getDetailMessage());
        if(code==null)return failure.getCode()==null ? AjaxResult.error(failure.getMessage())
                : AjaxResult.error(failure.getCode(),failure.getMessage());
        AjaxResult result=AjaxResult.error(HttpStatus.BAD_REQUEST,failure.getMessage());
        result.put("data",Map.of("errorCode",code.name(),"retryable",false));
        return result;
    }
}
