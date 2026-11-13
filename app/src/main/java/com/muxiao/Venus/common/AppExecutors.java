package com.muxiao.Venus.common;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 统一线程模型：进程级单例线程池，后台任务（网络 / 文件 / 加解密）统一走这里。
 * <p>
 * 禁止 shutdown，任务取消请用 Future.cancel(true)。不对外暴露 {@link ExecutorService}：
 * compileSdk 34+ 起它实现 AutoCloseable，链式调用会触发 Lint
 * 「AutoCloseable used without try-with-resources」误报，且从类型上杜绝误 shutdown 共享池。
 */
public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    private final ExecutorService io;

    private AppExecutors() {
        int cores = Runtime.getRuntime().availableProcessors();
        // 至少 4 个线程，覆盖「米游币/游戏/森空岛×2」并行上限，并预留余量。
        this.io = Executors.newFixedThreadPool(Math.max(4, cores * 2));
    }

    public static AppExecutors get() {
        return INSTANCE;
    }

    /** 在 IO 线程池执行后台任务（fire-and-forget）。 */
    public void execute(Runnable command) {
        io.execute(command);
    }

    /** 在 IO 线程池提交后台任务，返回可 {@code cancel(true)} 的 Future。 */
    public Future<?> submit(Runnable task) {
        return io.submit(task);
    }
}
