package com.luobo.hoyoagent.mihoyo.transport;

import com.luobo.hoyoagent.mihoyo.error.MihoyoError;
import okhttp3.Headers;
import tools.jackson.databind.JsonNode;

/** 米游社响应的统一形态：不抛异常，把 retcode/headers 都带回来。 */
public record MihoyoResult (int retcode, String message, JsonNode data, Headers headers){
    public boolean ok() {
        return retcode == 0;
    }

    /** data 可能是 missing node（非 0 时），调用方自己判空。 */
    public boolean hasData() {
        return data != null && !data.isMissingNode() && !data.isNull();
    }

    public MihoyoError error() {
        return MihoyoError.of(retcode, message);
    }
}
