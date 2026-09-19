package com.muxiao.Venus.common;

/**
 * HTTP 非 2xx 响应失败，携带状态码供上层按类别定制策略（如 429 限流等待更久）。
 * 取代原先无类型的 RuntimeException——靠解析本地化文案区分失败类别过于脆弱。
 * 文案仍由抛出点用现有字符串资源拼装，本类只额外携带结构化的状态码。
 */
public class HttpStatusCodeException extends RuntimeException {

    private final int statusCode;

    public HttpStatusCodeException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
