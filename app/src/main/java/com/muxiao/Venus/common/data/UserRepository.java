package com.muxiao.Venus.common.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.core.Single;

/**
 * 用户数据仓储（单一可信数据源，P0-1 / 阶段 2 DataStore）。
 * <br/>
 * 底层由 RxJava3 Preferences DataStore 承载，替代原 "user_{userId}" SharedPreferences；
 * 对外公开 API（getString / putString / remove / contains）与原 SharedPreferences 实现
 * 签名与语义完全一致 —— 所有调用点（tools.read / tools.write 等）无需任何改动。
 * <br/>
 * 同步热读门面（Write-Through）：
 * - 读：直接返回内存 ConcurrentHashMap 缓存（与原 SP 同步读等价，无异步等待、无主线程阻塞）。
 * - 写：先更新缓存，再异步持久化到 DataStore（updateDataAsync）。
 * - 预热：首次访问某 userId 时，从旧 SP 同步灌入缓存，保证 getString 立即可用（与原行为一致）。
 * <br/>
 * 关于迁移：旧 "user_{userId}" SharedPreferences 仍保留在磁盘，由 seedCacheFromLegacy 在每次进程
 * 冷启动、首次访问该 userId 时同步回灌缓存；新写入只进入 DataStore。该方案刻意不采用自动
 * SharedPreferencesMigration，原因是：若由迁移接管，DataStore 首次 data() 发射前缓存为空，
 * getString 可能瞬时返回 null，破坏原 SP 的「同步读」语义；且迁移需阻塞等待首帧，存在主线程
 * ANR 风险。如后续版本希望回收旧 SP 文件，可改用
 * androidx.datastore.rxjava3.RxSharedPreferencesMigration（builder.addDataMigration(...)）。
 */
public class UserRepository {

    private final Context context;

    // 每个 userId 对应一个 RxDataStore 实例（原 user_{userId}.xml）。
    // 注意：stores / cache / seeded 必须为 static，跨 UserRepository 实例共享。
    // 调用方存在多处 new UserRepository(context)（tools 写读、AppModule 注入等），
    // 若非 static，同一文件会被多个实例各自创建 DataStore，触发
    // "There are multiple DataStores active for the same file" 崩溃。
    private static final ConcurrentHashMap<String, RxDataStore<Preferences>> stores = new ConcurrentHashMap<>();
    // 同步热读缓存：key = userId + ":" + key
    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    // 已同步预热的 userId 集合（避免每次读取都扫一遍旧 SP）
    private static final ConcurrentHashMap<String, Boolean> seeded = new ConcurrentHashMap<>();

    public UserRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    // Composite key 同时作为 DataStore Preferences.Key 名称与内存缓存的 map key，保证读写一致。
    private static String composite(String userId, String key) {
        return userId + ":" + key;
    }

    private RxDataStore<Preferences> storeFor(String userId) {
        RxDataStore<Preferences> ds = stores.get(userId);
        if (ds == null) {
            synchronized (stores) {
                ds = stores.get(userId);
                if (ds == null) {
                    // DataStore 文件名约定为 datastore/user_{userId}.preferences_pb，
                    // 与旧 SP（user_{userId}.xml）一一对应，便于理解迁移关系。
                    ds = new RxPreferenceDataStoreBuilder(context, "user_" + userId).build();
                    // DataStore 数据变化（含后续写入）同步回写缓存
                    ds.data().subscribe(prefs -> {
                        for (Map.Entry<Preferences.Key<?>, Object> e : prefs.asMap().entrySet()) {
                            if (e.getValue() instanceof String) {
                                cache.put(e.getKey().getName(), (String) e.getValue());
                            }
                        }
                    });
                    stores.put(userId, ds);
                }
            }
        }
        seedCacheFromLegacy(userId);
        return ds;
    }

    // 首次访问某 userId 时，从旧 SP 同步灌入缓存（保证 getString 立即可用，与原行为一致）
    private void seedCacheFromLegacy(String userId) {
        if (seeded.putIfAbsent(userId, Boolean.TRUE) == null) {
            SharedPreferences sp = context.getSharedPreferences("user_" + userId, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                if (e.getValue() instanceof String) {
                    cache.put(composite(userId, e.getKey()), (String) e.getValue());
                }
            }
            // 兜底：旧 SP 无数据（如升级用户数据仅存在于 DataStore）时，同步从 DataStore 灌入缓存，
            // 避免 DataStore 异步首帧尚未回灌、getString 在进程首次读取时瞬时返回 null
            //（表现为「stoken/mid 为空，实际存在」）。
            RxDataStore<Preferences> ds = stores.get(userId);
            if (ds != null) {
                try {
                    Preferences prefs = ds.data().firstOrError().blockingGet();
                    for (Map.Entry<Preferences.Key<?>, Object> e : prefs.asMap().entrySet()) {
                        if (e.getValue() instanceof String) {
                            // 仅填充缓存缺失的键：旧 SP / 本次 putString 已写入的键优先，
                            // 避免 re-login 等场景被 DataStore 磁盘上的旧值覆盖回退。
                            cache.putIfAbsent(e.getKey().getName(), (String) e.getValue());
                        }
                    }
                } catch (Exception ignored) {
                    // 首帧读取失败（如文件尚不存在），保留旧 SP 已灌入的缓存
                }
            }
        }
    }

    public void putString(String userId, String key, String value) {
        cache.put(composite(userId, key), value);
        // 双写 legacy SP：putString 原先只写 cache + DataStore，导致冷启动时
        // seedCacheFromLegacy 读到的旧 SP 为空、而 DataStore 异步订阅尚未回灌 cache，
        // getString 在进程首次读取会瞬时返回 null（表现为"stoken/mid 为空，实际存在"）。
        // 双写后 seedCacheFromLegacy 总能同步拿到最新值，恢复原 SP 的同步读语义。
        context.getSharedPreferences("user_" + userId, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
        storeFor(userId).updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.set(PreferencesKeys.stringKey(composite(userId, key)), value);
            return Single.just(m);
        }).subscribe();
    }

    @Nullable
    public String getString(String userId, String key) {
        storeFor(userId); // 触发预热 + DataStore 初始化
        return cache.get(composite(userId, key));
    }

    public void remove(String userId, String key) {
        cache.remove(composite(userId, key));
        // 同步从 legacy SP 移除，保持与缓存/DataStore 三者一致
        context.getSharedPreferences("user_" + userId, Context.MODE_PRIVATE)
                .edit().remove(key).apply();
        storeFor(userId).updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.remove(PreferencesKeys.stringKey(composite(userId, key)));
            return Single.just(m);
        }).subscribe();
    }

    // 判断缓存中是否存在该键；先调用 storeFor 触发旧 SP 预热与 DataStore 初始化（有副作用）。
    public boolean contains(String userId, String key) {
        storeFor(userId);
        return cache.containsKey(composite(userId, key));
    }

    /**
     * 清空某用户的全部数据（内存缓存 + 旧 SP + DataStore），三处必须同步清，
     * 否则 {@link #getString} 会继续命中缓存返回旧值。
     * <p>
     * 用于「重新登录」：原实现只清旧 SP，而读取优先命中缓存，导致重新登录后
     * 仍可能拿到上一次的 stoken/mid/ltoken。
     * <p>
     * 注意先调用 {@link #storeFor} 完成预热/初始化，再清缓存——否则后续首次预热
     * 会把旧值从 DataStore/旧 SP 重新灌回缓存。
     */
    public void clear(String userId) {
        if (userId == null || userId.isEmpty()) return;
        storeFor(userId); // 先完成预热，避免清空后又被 seed 回来

        String prefix = userId + ":";
        for (String cacheKey : cache.keySet()) {
            if (cacheKey.startsWith(prefix)) cache.remove(cacheKey);
        }
        context.getSharedPreferences("user_" + userId, Context.MODE_PRIVATE).edit().clear().apply();
        // DataStore 文件本身按用户隔离（user_{userId}），整体清空即等价于清空该用户
        storeFor(userId).updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.clear();
            return Single.just(m);
        }).subscribe();
    }

    /**
     * 把 oldUserId 的全部数据整体迁移到 newUserId（用于用户重命名），
     * 迁移完成后清空 oldUserId。
     * <p>
     * 原实现只复制旧 SP 文件，遗漏了 DataStore 与内存缓存，且 newUserId 若此前已被
     * {@code seeded} 标记过便不会再从旧 SP 回灌，导致重命名后读取到的令牌为空。
     * 统一走仓储写入路径后，缓存/旧 SP/DataStore 三者保持一致。
     */
    public void migrate(String oldUserId, String newUserId) {
        if (oldUserId == null || newUserId == null
                || oldUserId.isEmpty() || newUserId.isEmpty()
                || oldUserId.equals(newUserId)) return;
        storeFor(oldUserId);
        storeFor(newUserId);

        // 以内存缓存为主，旧 SP 仅补全缓存缺失的键（缓存可能尚未覆盖历史键）
        Map<String, String> moved = new HashMap<>();
        String oldPrefix = oldUserId + ":";
        for (Map.Entry<String, String> e : cache.entrySet()) {
            if (e.getKey().startsWith(oldPrefix))
                moved.put(e.getKey().substring(oldPrefix.length()), e.getValue());
        }
        for (Map.Entry<String, ?> e : context.getSharedPreferences("user_" + oldUserId, Context.MODE_PRIVATE)
                .getAll().entrySet()) {
            if (e.getValue() instanceof String)
                moved.putIfAbsent(e.getKey(), (String) e.getValue());
        }

        clear(oldUserId);
        for (Map.Entry<String, String> e : moved.entrySet()) {
            putString(newUserId, e.getKey(), e.getValue());
        }
    }
}
