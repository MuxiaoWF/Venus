package com.muxiao.Venus.common;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一线程模型（Now in Android 推荐：结构化并发 + 生命周期感知 Executor）。
 * 替代散落的 new Thread / Executors.newFixedThreadPool(3) / runOnUiThread。
 * - io()：后台任务线程池（网络、文件、加解密等）。
 * - main()：主线程 Executor，用于把结果切回 UI。
 * 注意：本类为进程级单例线程池，禁止 shutdown；任务取消请使用 Future.cancel(true)。
 */
public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    private final ExecutorService io;
    private final Executor main;

    private AppExecutors() {
        int cores = Runtime.getRuntime().availableProcessors();
        // 至少 4 个线程，覆盖「米游币/游戏/森空岛×2」并行上限，并预留余量。
        this.io = Executors.newFixedThreadPool(Math.max(4, cores * 2));
        this.main = new MainThreadExecutor();
    }

    public static AppExecutors get() {
        return INSTANCE;
    }

    public ExecutorService io() {
        return io;
    }

    public Executor main() {
        return main;
    }

    private static final class MainThreadExecutor implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable command) {
            handler.post(command);
        }
    }
}
