package com.muxiao.Venus.common;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 全应用唯一 OkHttpClient 来源（连接池复用、统一超时）。
 * DS 签名按接口使用不同 salt（K2 / LK2 / SALT_6X …），拦截器拿不到 per-request salt，
 * 故 DS 只能由 {@link HeaderManager} 各 header 方法生成，本类不做 DS 拦截器。
 */
public final class ApiClient {

    private static volatile OkHttpClient instance;
    /** 短超时客户端：主源不可达时快速失败让位兜底源，避免调用方长时间等待 */
    private static volatile OkHttpClient fastInstance;

    private ApiClient() {
    }

    /** 共享客户端（懒加载、线程安全）：connect 10s / read 30s / write 30s，跟随 SSL 重定向。 */
    public static OkHttpClient get() {
        if (instance == null) {
            synchronized (ApiClient.class) {
                if (instance == null) {
                    instance = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return instance;
    }

    /**
     * 短超时客户端（connect / read 各 4s），用于「主源 + 兜底源」链路：主源不可达时快速失败，
     * 而不是等满常规超时。经 newBuilder() 派生，共享连接池与调度器。
     */
    public static OkHttpClient getFast() {
        if (fastInstance == null) {
            synchronized (ApiClient.class) {
                if (fastInstance == null) {
                    fastInstance = get().newBuilder()
                            .connectTimeout(4, TimeUnit.SECONDS)
                            .readTimeout(4, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return fastInstance;
    }
}
