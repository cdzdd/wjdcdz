package com.hengsheng.fund;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DataIntegrityViolationException;

@RestControllerAdvice
public class ApiErrors {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ApiException.class) ResponseEntity<Map<String,Object>> business(ApiException e) {return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage(),"requestId",UUID.randomUUID().toString()));}
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<Map<String,Object>> invalid(Exception e) {return business(ApiException.bad("请求格式或字段无效"));}
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<Map<String,Object>> conflict(Exception e) {return business(ApiException.conflict("请求与既有记录冲突，请检查后重试"));}
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class) ResponseEntity<Map<String,Object>> missing(Exception e) {return business(ApiException.missing());}
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) ResponseEntity<Map<String,Object>> large(Exception e) {return business(new ApiException(413,"FILE_TOO_LARGE","文件最大5MB"));}
    @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> unexpected(Exception e) {String requestId=UUID.randomUUID().toString();log.error("Request {} failed",requestId,e);return ResponseEntity.status(500).body(Map.of("code","INTERNAL_ERROR","message","服务暂时无法完成操作，请联系管理员并提供请求编号","requestId",requestId));}
}
