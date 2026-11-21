package com.aid.previz.controller;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=PrevizMembershipController.class)
public class PrevizMembershipErrors {
 @ExceptionHandler(ServiceException.class) public ResponseEntity<AjaxResult> service(ServiceException failure){
  Integer code=failure.getCode();int status=code!=null&&java.util.Set.of(400,401,403,409).contains(code)?code:403;
  return ResponseEntity.status(status).body(AjaxResult.error(status,failure.getMessage()));
 }
 @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
 public ResponseEntity<AjaxResult> invalid(Exception failure){return ResponseEntity.badRequest().body(AjaxResult.error(400,"项目或成员参数无效"));}
}
