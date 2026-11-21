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
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import kotlin.OptIn;
import kotlinx.coroutines.ExperimentalCoroutinesApi;

/**
 * 任务状态仓储（单一可信数据源，底层 RxJava3 Preferences DataStore，替代原 "task_status_prefs" SharedPreferences）。
 * 对外提供 getString/getBoolean/getAll/putString/putBoolean/clear 的同步热读（Write-Through），语义与原 SP 一致、调用点零改动。
 * DataStore 按文件应为单例：调用方存在多次 new 本类的情况，故 DS / 缓存均按 prefs 名静态共享，
 * 避免「同一文件多 DataStore 实例」异常。
 */
@OptIn(markerClass = ExperimentalCoroutinesApi.class)
public class TaskStatusRepository {

    private static final String PREFS_NAME = "task_status_prefs";

    private static volatile RxDataStore<Preferences> store;
    private static final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();
    private static final Object initLock = new Object();
    /** 收集所有 fire-and-forget 订阅（同时满足 Lint CheckResult）；与进程同生命周期，从不 dispose。 */
    private static final CompositeDisposable persistentDisposables = new CompositeDisposable();

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
                    persistentDisposables.add(created.data().subscribe(prefs -> {
                        for (Map.Entry<Preferences.Key<?>, Object> e : prefs.asMap().entrySet()) {
                            cache.put(e.getKey().getName(), e.getValue());
                        }
                    }));
                    seedCacheFromDataStore(created);
                    seedCacheFromLegacy(appContext);
                }
            }
        }
    }

    /**
     * 同步读取 DataStore 首帧并灌入缓存：DataStore 是任务状态的唯一写入源（putString 等均不写旧 SP），
     * 而订阅回写缓存是异步的。若此处不同步灌入，冷启动后首次 {@code getString("status_date")} 可能返回默认
     * 空串，被 {@code TaskStatusManager.ensureToday()} 误判为「跨天」触发 clear()，清掉当天已持久化的状态
     * （表现为「杀掉应用重进，今天已签的任务又变回未完成」）。首帧失败时静默忽略，保留异步订阅与旧 SP 兜底。
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
     * 旧 SP 只补全 DataStore 中不存在的键（升级用户首次启动的迁移路径）。不能用 putAll 反向覆盖：
     * 旧 SP 自迁移后不再写入，内容可能比 DataStore 更旧，覆盖权威值会触发上文的「跨天误清」。
     */
    private static void seedCacheFromLegacy(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            // putIfAbsent 需 API 24，改用 containsKey 判断
            if (!cache.containsKey(e.getKey()))
                cache.put(e.getKey(), e.getValue());
        }
    }

    // 同步热读：仅读内存缓存，未命中或类型不符时返回 def，不阻塞也不触发持久化。
    public String getString(String key, String def) {
        Object v = cache.get(key);
        return v instanceof String ? (String) v : def;
    }

    // 返回缓存的快照副本（防御性拷贝）：拿到的是当前时刻的只读视图，不受后续并发写入影响。
    public Map<String, ?> getAll() {
        return new ConcurrentHashMap<>(cache);
    }

    // 写穿（Write-Through）：先更新内存缓存（写入立即可见），再异步提交 DataStore 持久化。
    public void putString(String key, String value) {
        putAll(java.util.Collections.singletonMap(key, (Object) value));
    }

    /**
     * 批量写穿：一次事务写入多个键（String / Boolean）。DataStore 每次写入都要把整份 preferences 序列化落盘，
     * 而任务状态天然成对出现（{@code status_xxx} 与镜像字段 {@code done_xxx}），一次任务运行会产生数十次写入；
     * 合并为单次事务后写入次数由「键数」降为「调用数」。
     */
    public void putAll(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String || v instanceof Boolean) cache.put(e.getKey(), v);
        }
        persistentDisposables.add(store.updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            for (Map.Entry<String, ?> e : values.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) m.set(PreferencesKeys.stringKey(e.getKey()), (String) v);
                else if (v instanceof Boolean) m.set(PreferencesKeys.booleanKey(e.getKey()), (Boolean) v);
            }
            return Single.just(m);
        }).subscribe(ignored -> { }, this::logPersistFailure));
    }

    /**
     * 持久化失败兜底：原先无参 {@code subscribe()} 的 onError 会落到 RxJavaPlugins 全局处理器，默认抛
     * {@code OnErrorNotImplementedException} 使进程崩溃；磁盘写失败本不应崩溃，故降级为日志（内存缓存已更新）。
     */
    private void logPersistFailure(Throwable t) {
        Logger.w("TaskStatusRepository: 持久化任务状态失败（内存缓存已生效）: " + t);
    }

    /** 删除指定键（内存缓存立即生效，DataStore 异步落盘）。用于重命名/删除账号时清理旧桶。 */
    public void removeKeys(java.util.Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        for (String key : keys) cache.remove(key);
        persistentDisposables.add(store.updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            for (String key : keys) m.remove(PreferencesKeys.stringKey(key));
            return Single.just(m);
        }).subscribe(ignored -> { }, this::logPersistFailure));
    }

    /**
     * 清空内存缓存、DataStore 与旧 SP。旧 SP 必须一并清：否则残留内容会作为「DataStore 中不存在的键」
     * 在下次冷启动被 {@link #seedCacheFromLegacy} 重新灌入，使清空在重启后失效。
     */
    public void clear() {
        cache.clear();
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        persistentDisposables.add(store.updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.clear();
            return Single.just(m);
        }).subscribe(ignored -> { }, this::logPersistFailure));
    }
}
