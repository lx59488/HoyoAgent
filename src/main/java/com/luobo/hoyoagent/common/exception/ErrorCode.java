package com.luobo.hoyoagent.common.exception;

/**
 * 错误码枚举。
 *
 * <p>只保留<b>本项目真的会返回</b>的码：通用 1xxx / AI 7xxx / 米游社 9xxx。
 * 知识库 6xxx、限流 8xxx 这些从模板里继承来的码已删除（2026-11-20）——
 * 没有生产者也没有消费者的枚举项，只会让读代码的人以为系统里还有那些模块。
 */
public enum ErrorCode {

    // ========== 通用错误 1xxx ==========
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // ========== AI 服务错误 7xxx ==========
    AI_SERVICE_ERROR(7003, "AI服务调用失败"),

    // ========== 米游社模块错误 9xxx ==========
    MOYO_NETWORK_ERROR(9001, "米游社服务连接失败"),
    MOYO_API_ERROR(9002, "米游社接口返回异常"),
    MOYO_AUTH_EXPIRED(9003, "米游社登录状态已失效，请重新授权"),
    MOYO_RATE_LIMITED(9004, "米游社请求过于频繁"),
    MOYO_NEED_VERIFY(9005, "米游社要求人机验证"),
    MOYO_SIGN_INVALID(9006, "米游社非法签名"),
    MOYO_DATA_PRIVATE(9007, "战绩未公开"),

    ;

    private final Integer code;
    private final String message;


    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }


    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
