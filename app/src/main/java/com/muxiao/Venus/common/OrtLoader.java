package com.muxiao.Venus.common;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;

import java.io.File;

/**
 * ORT native 库手动加载器：构建期 .so 被剥离，首次推理前由九宫格识别模块调 {@link #load} 加载。
 * 加载顺序：onnxruntime → onnxruntime4j_jni（后者 dlopen 依赖前者）。
 */
public final class OrtLoader {

    private static final String LIB_RUNTIME = "libonnxruntime.so";
    // 注意拼写：AAR 里的实际文件名是 4j_jni（带下划线）
    private static final String LIB_JNI = "libonnxruntime4j_jni.so";

    private static volatile boolean loaded;

    private OrtLoader() {
    }

    /** 组件根目录：filesDir/geetest_solver/（.so 与模型权重都在这里，按子目录分置）。 */
    public static File baseDir(Context context) {
        return new File(context.getFilesDir(), "geetest_solver");
    }

    /** 当前设备首选 ABI 对应的库目录。 */
    public static File nativeDir(Context context) {
        return new File(baseDir(context), primaryAbi());
    }

    /** 模型权重目录。 */
    public static File modelDir(Context context) {
        return new File(baseDir(context), "models");
    }

    /** 设备首选 ABI（SUPPORTED_ABIS 首位即系统安装时选用的 ABI 顺序）。 */
    public static String primaryAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        return abis.length > 0 ? abis[0] : "arm64-v8a";
    }

    /** 两个 native 库是否都已下载就位（不校验完整性——校验在下载器做）。 */
    public static boolean isReady(Context context) {
        File dir = nativeDir(context);
        return new File(dir, LIB_RUNTIME).isFile() && new File(dir, LIB_JNI).isFile();
    }

    /**
     * 加载两个 native 库（幂等）。失败抛 UnsatisfiedLinkError/IOException，调用方据此回落人工验证。
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    public static synchronized void load(Context context) {
        if (loaded) return;
        File dir = nativeDir(context);
        File runtime = new File(dir, LIB_RUNTIME);
        File jni = new File(dir, LIB_JNI);
        if (!runtime.isFile() || !jni.isFile())
            throw new IllegalStateException("solver native libs missing in " + dir);
        // 跳过 ORT 官方自动加载器，手动按依赖顺序加载
        System.setProperty("onnxruntime.native.onnxruntime.skip", "true");
        System.setProperty("onnxruntime.native.onnxruntime4j_jni.skip", "true");
        System.load(runtime.getAbsolutePath());
        System.load(jni.getAbsolutePath());
        loaded = true;
        Logger.debug("VenusCaptcha", "ORT native libs loaded from " + dir);
    }
}
