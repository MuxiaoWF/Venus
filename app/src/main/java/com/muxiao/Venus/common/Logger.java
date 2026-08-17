package com.muxiao.Venus.common;

import android.util.Log;

import com.muxiao.Venus.BuildConfig;

/**
 * 统一日志门面（P2-4）。
 * 痛点：原代码大量使用 Log.e 输出调试信息，而 R8 仅剥离 v/d/i，不剥离 e/w，
 * 导致发布包残留调试日志与验证码噪声。
 * 方案：调试级日志经 BuildConfig.DEBUG 门控（release 下被 R8 常量折叠消除）；
 * 仅真实错误使用 e/w（始终保留）。
 */
public final class Logger {

    private static final String DEFAULT_TAG = "Venus";

    private Logger() {
    }

    /** 调试日志：仅在 DEBUG 构建输出（release 自动剔除）。用于验证码/轮询等噪声。 */
    public static void debug(String tag, String msg) {
        if (BuildConfig.DEBUG) Log.d(tag, msg);
    }

    public static void debug(String msg) {
        if (BuildConfig.DEBUG) Log.d(DEFAULT_TAG, msg);
    }

    public static void d(String msg) {
        if (BuildConfig.DEBUG) Log.d(DEFAULT_TAG, msg);
    }

    public static void i(String msg) {
        if (BuildConfig.DEBUG) Log.i(DEFAULT_TAG, msg);
    }

    /** 警告：始终保留。 */
    public static void w(String msg) {
        Log.w(DEFAULT_TAG, msg);
    }

    /** 错误：始终保留（仅用于真实异常）。 */
    public static void e(String msg) {
        Log.e(DEFAULT_TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(DEFAULT_TAG, msg, t);
    }
}
