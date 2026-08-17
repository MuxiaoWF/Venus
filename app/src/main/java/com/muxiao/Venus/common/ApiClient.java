package com.muxiao.Venus.common;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 应用级共享 HTTP 客户端（Now in Android：集中式网络源，P1-3）。
 * 作为全应用唯一 OkHttpClient 来源：
 * - 复用连接池，避免每个请求新建连接（原行为一致）；
 * - 统一连接/读/写超时与 SSL 重定向跟随。
 * 说明（设计决策）：DS 签名因"每个接口使用不同 salt（K2/LK2/SALT_6X）"而必须在构造请求头时按接口计算，
 * 无法用通用 OkHttp Interceptor 注入（拦截器拿不到 per-request salt）。因此 DS 仍由
 * {@link HeaderManager} 各 header 构造方法权威生成，本类只负责"客户端集中化"这一层，
 * 不重复造 DS 拦截器。后续若 miHoYo 接口收敛为统一 salt，可再评估迁移到拦截器。
 */
public final class ApiClient {

    private static volatile OkHttpClient instance;

    private ApiClient() {
    }

    /**
     * 返回共享 OkHttpClient（懒加载、单例、线程安全）。
     * 配置与原 tools.getSharedClient() 完全一致：connect 10s / read 30s / write 30s，跟随 SSL 重定向。
     */
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
}
