package com.luobo.hoyoagent.common.exception;

import com.luobo.hoyoagent.common.log.ErrorLogSanitizer;
import com.luobo.hoyoagent.common.result.ApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>约定：一律 HTTP 200，业务结果由 {@code ApiResult#code} 区分。
 *
 * <p>2026-11-20：删掉了 RestTemplate/RestClient 与文件上传三组 handler。
 * 它们是在模板项目里继承下来的，本项目没有 {@code RestTemplate}/{@code @RequestPart}/多部分上传，
 * 三条分支永远不会命中；留着只会让人以为 AI 调用失败会走到 7xxx，实际不会。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}", e.getCode());
        return ApiResult.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return ApiResult.error(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 处理绑定异常
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数绑定失败: {}", message);
        return ApiResult.error(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数: {}", ErrorLogSanitizer.summarize(e));
        return ApiResult.error(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    /**
     * 处理 404：静态资源/API 路径不存在
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("资源未找到: {}", e.getResourcePath());
        return ApiResult.error(ErrorCode.NOT_FOUND, "API 接口不存在");
    }

    /**
     * 处理请求方法不支持异常
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> handleHttpRequestMethodNotSupportedException(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {} {}", e.getMethod(), e.getSupportedHttpMethods());
        return ApiResult.error(ErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.getMethod());
    }

    /**
     * 处理其他未知异常：兜底，不把堆栈或内部信息回给调用方
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> handleException(Exception e) {
        log.error("系统异常: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
        return ApiResult.error(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试");
    }
}
