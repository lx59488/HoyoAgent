package com.luobo.hoyoagent.mihoyo.error;

import com.luobo.hoyoagent.common.exception.ErrorCode;

public record MihoyoError(int retcode, ErrorKind kind, String message) {

    public static MihoyoError of(int retcode, String message) {
        return new MihoyoError(retcode, kindOf(retcode), message == null ? "" : message);
    }


    /**
     * 给模型/用户看的话。5003 这种没 message 的码，必须在这里补人话。
     */
    public String userMessage() {
        return switch (kind) {
            case AUTH -> "登录状态失效，请重新扫码绑定";
            case SIGN -> "请求签名无效（可能版本/盐不匹配）";
            case RISK -> "触发风控，换网络或稍后再试";
            case RATE_LIMIT -> "请求太频繁，过一会儿再试";
            case NEED_VERIFY -> "需要人机验证，去米游社 App 手动签一次";
            case DATA_PRIVATE -> "战绩未公开，去米游社设置里打开展示";
            case UNKNOWN -> "米游社返回异常 retcode=" + retcode;
        };
    }


    public ErrorCode errorCode() {
        return switch (kind) {
            case AUTH -> ErrorCode.MOYO_AUTH_EXPIRED;
            case SIGN -> ErrorCode.MOYO_SIGN_INVALID;
            case RISK, NEED_VERIFY -> ErrorCode.MOYO_NEED_VERIFY;
            case RATE_LIMIT -> ErrorCode.MOYO_RATE_LIMITED;
            case DATA_PRIVATE -> ErrorCode.MOYO_DATA_PRIVATE;
            case UNKNOWN -> ErrorCode.MOYO_API_ERROR;
        };
    }

    private static ErrorKind kindOf(int retcode) {
        return switch (retcode) {
            case 0 -> ErrorKind.UNKNOWN;
            case -100, 10001 -> ErrorKind.AUTH;
            case -10001 -> ErrorKind.SIGN;
            case -3503, 1034 -> ErrorKind.RISK;
            case -500004, 10101, -5003 -> ErrorKind.RATE_LIMIT;
            case 10102 -> ErrorKind.DATA_PRIVATE;
            default -> ErrorKind.UNKNOWN;
        };
    }
}
