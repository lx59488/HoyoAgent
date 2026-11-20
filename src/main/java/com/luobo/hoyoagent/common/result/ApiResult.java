package com.luobo.hoyoagent.common.result;

import com.luobo.hoyoagent.common.exception.ErrorCode;

/**
 * 统一响应结果。
 *
 * <p>200 成功 / 500 兜底失败这两个码只在这里出现一次；其余错误码一律来自 {@link ErrorCode}。
 * （原先还绕了一层 {@code CommonConstants.StatusCode}，和 {@code ErrorCode} 的 400/404/500 重号；
 * 2026-11-20 删掉那个类，两个码收进本类。）
 *
 * <p>类名从 {@code Result} 改成 {@code ApiResult}：{@code common.result.Result} 读起来像
 * "结果的结果"，而且和 {@code MihoyoResult} / {@code CheckinRunResult} 这类业务结果混在一起时，
 * 看不出哪个是 HTTP 响应信封。
 */
public class ApiResult<T> {

    /** 成功码：HTTP 200 之外再嵌一层，前端只看这个字段。 */
    public static final int SUCCESS_CODE = 200;

    /** 兜底失败码：只有 {@link #error(String)} 用它，业务失败请走 {@link ErrorCode}。 */
    public static final int SERVER_ERROR_CODE = 500;

    private final Integer code;
    private final String message;
    private final T data;

    private ApiResult(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ========== 成功响应 ==========

    public static <T> ApiResult<T> success() {
        return new ApiResult<>(SUCCESS_CODE, "success", null);
    }

    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(SUCCESS_CODE, "success", data);
    }

    public static <T> ApiResult<T> success(String message, T data) {
        return new ApiResult<>(SUCCESS_CODE, message, data);
    }

    // ========== 失败响应 ==========

    public static <T> ApiResult<T> error(String message) {
        return new ApiResult<>(SERVER_ERROR_CODE, message, null);
    }

    public static <T> ApiResult<T> error(Integer code, String message) {
        return new ApiResult<>(code, message, null);
    }

    public static <T> ApiResult<T> error(ErrorCode errorCode) {
        return new ApiResult<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> ApiResult<T> error(ErrorCode errorCode, String message) {
        return new ApiResult<>(errorCode.getCode(), message, null);
    }

    // ========== 辅助方法 ==========

    public boolean isSuccess() {
        return code != null && code == SUCCESS_CODE;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
