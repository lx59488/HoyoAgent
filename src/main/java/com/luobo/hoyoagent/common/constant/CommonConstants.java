package com.luobo.hoyoagent.common.constant;

/**
 * 通用常量定义
 */
public final class CommonConstants {
    
    private CommonConstants() {}
    
    /**
     * 状态码
     */
    public static final class StatusCode {
        public static final int SUCCESS = 200;
        public static final int BAD_REQUEST = 400;
        public static final int UNAUTHORIZED = 401;
        public static final int FORBIDDEN = 403;
        public static final int NOT_FOUND = 404;
        public static final int SERVER_ERROR = 500;
        
        private StatusCode() {}
    }

}
