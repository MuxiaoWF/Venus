package com.muxiao.Venus.common;

import android.util.Log;

import com.muxiao.Venus.BuildConfig;

/**
 * 统一日志门面：调试级日志经 BuildConfig.DEBUG 门控（release 下被 R8 常量折叠消除），
 * 仅真实错误走 e/w（始终保留）。R8 只剥离 v/d/i、不剥离 e/w，直接用 Log.e 打调试信息
 * 会让发布包残留日志与验证码噪声。
 */
public final class Logger {

    private static final String DEFAULT_TAG = "Venus";

    private Logger() {
    }

    /** 调试日志：仅在 DEBUG 构建输出（release 自动剔除）。用于验证码/轮询等噪声。 */
    public static void debug(String tag, String msg) {
        if (BuildConfig.DEBUG) Log.d(tag, msg);
    }

    /** 警告：始终保留。 */
    public static void w(String msg) {
        Log.w(DEFAULT_TAG, msg);
    }

    /** 错误：始终保留（仅用于真实异常）。 */
    public static void e(String msg, Throwable t) {
        Log.e(DEFAULT_TAG, msg, t);
    }
}
