package com.muxiao.Venus.common.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

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

    public TaskStatusRepository(Context context) {
        Context context1 = context.getApplicationContext();
        if (store == null) {
            synchronized (initLock) {
                if (store == null) {
                    store = new RxPreferenceDataStoreBuilder(context1, PREFS_NAME).build();
                    // DataStore 数据变化（含后续写入）同步回写缓存
                    store.data().subscribe(prefs -> {
                        for (Map.Entry<Preferences.Key<?>, Object> e : prefs.asMap().entrySet()) {
                            cache.put(e.getKey().getName(), e.getValue());
                        }
                    });
                    seedCacheFromLegacy(context1);
                }
            }
        }
    }

    // 首次访问时从旧 SP 同步灌入缓存（保证 getXXX/all 立即可用，与原行为一致）
    private static void seedCacheFromLegacy(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        cache.putAll(sp.getAll());
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
        cache.put(key, value);
        store.updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.set(PreferencesKeys.stringKey(key), value);
            return Single.just(m);
        }).subscribe();
    }

    // 写穿（Write-Through）：先更新内存缓存（写入立即可见），再异步提交 DataStore 持久化。
    public void putBoolean(String key, boolean value) {
        cache.put(key, value);
        store.updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.set(PreferencesKeys.booleanKey(key), value);
            return Single.just(m);
        }).subscribe();
    }

    // 清空内存缓存与 DataStore；注意旧 SP 仍残留磁盘，进程冷重启后会被 seedCacheFromLegacy 重新灌入。
    public void clear() {
        cache.clear();
        store.updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.clear();
            return Single.just(m);
        }).subscribe();
    }
}
