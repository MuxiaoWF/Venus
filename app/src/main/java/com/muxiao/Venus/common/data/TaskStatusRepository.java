package com.muxiao.Venus.common.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import com.muxiao.Venus.common.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.core.Single;

/**
 * 任务状态仓储（单一可信数据源，P0-1 / 阶段 2 DataStore）。
 * 底层由 RxJava3 Preferences DataStore 承载，替代原 "task_status_prefs" SharedPreferences；
 * task_status_prefs 当前仅由 TaskStatusManager 经本仓储读写（无其他直接访问点），
 * 故迁移不会造成访问分叉。对外提供 getString/getBoolean/getAll/putString/putBoolean/clear
 * 同步热读（Write-Through），与原 SP 语义一致、调用点零改动。
 * <br/>
 * DataStore 按文件应为单例：调用方存在 new TaskStatusRepository(context) 多次实例化的情况，
 * 故 DS / 缓存均按 prefs 名静态共享，避免“同一文件多 DataStore 实例”异常。
 */
public class TaskStatusRepository {

    private static final String PREFS_NAME = "task_status_prefs";

    private static volatile RxDataStore<Preferences> store;
    private static final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();
    private static final Object initLock = new Object();

    /** 应用级 Context：clear() 需要它来同步清理旧 SP。 */
    private final Context context;

    public TaskStatusRepository(Context context) {
        Context appContext = context.getApplicationContext();
        this.context = appContext;
        if (store == null) {
            synchronized (initLock) {
                if (store == null) {
                    RxDataStore<Preferences> created = new RxPreferenceDataStoreBuilder(appContext, PREFS_NAME).build();
                    store = created;
                    // DataStore 数据变化（含后续写入）同步回写缓存
                    created.data().subscribe(prefs -> {
                        for (Map.Entry<Preferences.Key<?>, Object> e : prefs.asMap().entrySet()) {
                            cache.put(e.getKey().getName(), e.getValue());
                        }
                    });
                    seedCacheFromDataStore(created);
                    seedCacheFromLegacy(appContext);
                }
            }
        }
    }

    /**
     * 同步读取 DataStore 首帧并灌入缓存。
     * <p>
     * 必要性：DataStore 是任务状态的唯一写入源（{@link #putString}/{@link #putBoolean} 均不写旧 SP），
     * 而订阅回写缓存是异步的。若此处不同步灌入，进程冷启动后首次 {@code getString("status_date")}
     * 可能返回默认空串，被 {@code TaskStatusManager.ensureToday()} 误判为「跨天」而触发 clear()，
     * 把当天已持久化的任务状态清空——表现为「杀掉应用重进，今天已签的任务又变回未完成」。
     * <p>
     * 首次读取失败（文件尚不存在等）时静默忽略，保留后续异步订阅与旧 SP 兜底。
     */
    private static void seedCacheFromDataStore(RxDataStore<Preferences> ds) {
        try {
            Preferences prefs = ds.data().firstOrError().blockingGet();
            for (Map.Entry<Preferences.Key<?>, Object> e : prefs.asMap().entrySet()) {
                cache.put(e.getKey().getName(), e.getValue());
            }
        } catch (Exception ignored) {
            // 首帧读取失败：不影响后续异步订阅回写
        }
    }

    /**
     * 旧 SP 只补全 DataStore 中不存在的键（升级用户首次启动的迁移路径）。
     * 使用 putIfAbsent 而非 putAll：旧 SP 自迁移后不再被写入，其内容可能比 DataStore 更旧，
     * 不能反向覆盖权威值（否则同样会触发上文的「跨天误清」）。
     */
    private static void seedCacheFromLegacy(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            cache.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    // 同步热读：仅读内存缓存，未命中或类型不符时返回 def，不阻塞也不触发持久化。
    public String getString(String key, String def) {
        Object v = cache.get(key);
        return v instanceof String ? (String) v : def;
    }

    // 同步热读：仅读内存缓存，未命中或类型不符时返回 def，不阻塞也不触发持久化。
    public boolean getBoolean(String key, boolean def) {
        Object v = cache.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    // 返回缓存的快照副本（防御性拷贝）：拿到的是当前时刻的只读视图，不受后续并发写入影响。
    public Map<String, ?> getAll() {
        return new ConcurrentHashMap<>(cache);
    }

    // 写穿（Write-Through）：先更新内存缓存（写入立即可见），再异步提交 DataStore 持久化。
    public void putString(String key, String value) {
        putAll(java.util.Collections.singletonMap(key, (Object) value));
    }

    // 写穿（Write-Through）：先更新内存缓存（写入立即可见），再异步提交 DataStore 持久化。
    public void putBoolean(String key, boolean value) {
        putAll(java.util.Collections.singletonMap(key, (Object) value));
    }

    /**
     * 批量写穿：一次事务写入多个键（String / Boolean）。
     * <p>
     * DataStore 每次写入都要把整份 preferences 序列化落盘，而任务状态天然成对出现
     * （{@code status_xxx} 与镜像字段 {@code done_xxx}），一次任务运行会产生数十次写入。
     * 合并为单次事务后，写入次数由「键数」降为「调用数」，显著减少主线程/IO 线程的文件 IO。
     */
    public void putAll(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String || v instanceof Boolean) cache.put(e.getKey(), v);
        }
        store.updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            for (Map.Entry<String, ?> e : values.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) m.set(PreferencesKeys.stringKey(e.getKey()), (String) v);
                else if (v instanceof Boolean) m.set(PreferencesKeys.booleanKey(e.getKey()), (Boolean) v);
            }
            return Single.just(m);
        }).subscribe(ignored -> { }, this::logPersistFailure);
    }

    /**
     * 持久化失败兜底。
     * <p>
     * 原先所有写入都以无参 {@code subscribe()} 提交，onError 会落到 RxJavaPlugins 全局处理器，
     * 默认直接抛 {@code OnErrorNotImplementedException} 导致进程崩溃；磁盘写失败本不应崩溃，
     * 此处降级为日志（内存缓存已更新，本次运行内语义不受影响）。
     */
    private void logPersistFailure(Throwable t) {
        Logger.w("TaskStatusRepository: 持久化任务状态失败（内存缓存已生效）: " + t);
    }

    /**
     * 清空内存缓存、DataStore 与旧 SP。
     * 必须一并清掉旧 SP：否则其残留内容会作为「DataStore 中不存在的键」在下次冷启动被
     * {@link #seedCacheFromLegacy} 重新灌入，使清空在重启后失效
     * （与 {@code ConfigRepository#clear()} 的处理保持一致）。
     */
    public void clear() {
        cache.clear();
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        store.updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.clear();
            return Single.just(m);
        }).subscribe(ignored -> { }, this::logPersistFailure);
    }
}
