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
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import kotlin.OptIn;
import kotlinx.coroutines.ExperimentalCoroutinesApi;

/**
 * 用户数据仓储（单一可信数据源，底层 DataStore）：由 RxJava3 Preferences DataStore 承载，
 * 替代原 "user_{userId}" SharedPreferences；对外 API（getString / putString / remove / contains）
 * 与原实现签名与语义一致，所有调用点（tools.read / tools.write 等）无需改动。
 * <p>
 * 同步热读门面（Write-Through）：读直接返回内存 ConcurrentHashMap（无异步等待、不阻塞主线程）；
 * 写先更新缓存再异步持久化（updateDataAsync）；首次访问某 userId 时从旧 SP 同步灌入缓存，
 * 并发首读在灌入期间排队等待，不会读到瞬时空缓存。
 * <p>
 * 刻意不使用 SharedPreferencesMigration：迁移接管时 DataStore 首帧发射前缓存为空，getString 可能瞬时
 * 返回 null，破坏 SP 的同步读语义，且需阻塞等首帧、有 ANR 风险。旧 "user_{userId}" SP 仍留在磁盘，
 * 由 seedCacheFromLegacy 在冷启动首次访问该 userId 时回灌缓存；新写入只进入 DataStore。
 */
@OptIn(markerClass = ExperimentalCoroutinesApi.class)
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
    /**
     * 种子灌入互斥：灌入含旧 SP 全量读 + DataStore 首帧阻塞读（冷盘可达数百毫秒），并发首读必须排队，
     * 否则后来者读到瞬时空缓存、getString 返回 null（表现为「stoken/mid 为空，实际存在」，冷进程并发跑任务必现）。
     * 每用户每进程至多一次，串行化代价可忽略。
     */
    private static final Object SEED_LOCK = new Object();
    /** 收集所有 fire-and-forget 订阅（同时满足 Lint CheckResult）；与进程同生命周期，从不 dispose。 */
    private static final CompositeDisposable persistentDisposables = new CompositeDisposable();

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
                    persistentDisposables.add(ds.data().subscribe(prefs -> {
                        for (Map.Entry<Preferences.Key<?>, Object> e : prefs.asMap().entrySet()) {
                            if (e.getValue() instanceof String) {
                                cache.put(e.getKey().getName(), (String) e.getValue());
                            }
                        }
                    }));
                    stores.put(userId, ds);
                }
            }
        }
        seedCacheFromLegacy(userId);
        return ds;
    }

    // 首次访问某 userId 时，从旧 SP 同步灌入缓存（保证 getString 立即可用，与原行为一致）
    private void seedCacheFromLegacy(String userId) {
        synchronized (SEED_LOCK) {
            if (seeded.containsKey(userId)) return;
            try {
                SharedPreferences sp = context.getSharedPreferences("user_" + userId, Context.MODE_PRIVATE);
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    if (e.getValue() instanceof String) {
                        cache.put(composite(userId, e.getKey()), (String) e.getValue());
                    }
                }
                // 兜底：旧 SP 无数据（如升级用户数据仅在 DataStore）时同步从 DataStore 灌入缓存，
                // 避免异步首帧尚未回灌时 getString 瞬时返回 null。
                RxDataStore<Preferences> ds = stores.get(userId);
                if (ds != null) {
                    try {
                        Preferences prefs = ds.data().firstOrError().blockingGet();
                        for (Map.Entry<Preferences.Key<?>, Object> e : prefs.asMap().entrySet()) {
                            if (e.getValue() instanceof String) {
                                // 仅填充缓存缺失的键：旧 SP / 本次 putString 已写入的键优先，
                                // 避免 re-login 等场景被 DataStore 磁盘上的旧值覆盖回退。
                                // （putIfAbsent 需 API 24，改用 containsKey 判断）
                                String keyName = e.getKey().getName();
                                if (!cache.containsKey(keyName)) {
                                    cache.put(keyName, (String) e.getValue());
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // 首帧读取失败（如文件尚不存在），保留旧 SP 已灌入的缓存
                    }
                }
            } finally {
                // 必须灌入完成后才置位（置位即代表「该用户缓存已可用」）；先置位会让并发读者跳过等待、读到空缓存。
                seeded.put(userId, Boolean.TRUE);
            }
        }
    }

    public void putString(String userId, String key, String value) {
        cache.put(composite(userId, key), value);
        // 双写 legacy SP：否则冷启动时 seedCacheFromLegacy 读到的旧 SP 为空、DataStore 异步订阅尚未回灌，
        // getString 首次读取会瞬时返回 null（表现为"stoken/mid 为空，实际存在"）；双写后总能同步拿到最新值。
        context.getSharedPreferences("user_" + userId, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
        persistentDisposables.add(storeFor(userId).updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.set(PreferencesKeys.stringKey(composite(userId, key)), value);
            return Single.just(m);
        }).subscribe());
    }

    @Nullable
    public String getString(String userId, String key) {
        storeFor(userId); // 触发预热 + DataStore 初始化
        return cache.get(composite(userId, key));
    }

    /**
     * 清空某用户的全部数据（内存缓存 + 旧 SP + DataStore），三处必须同步清，否则 {@link #getString} 会继续
     * 命中缓存返回旧值。先调用 {@link #storeFor} 完成预热再清缓存，否则后续首次预热会把旧值重新灌回。
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
        persistentDisposables.add(storeFor(userId).updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.clear();
            return Single.just(m);
        }).subscribe());
    }

    /**
     * 把 oldUserId 的全部数据整体迁移到 newUserId（用户重命名），完成后清空 oldUserId。
     * 必须走仓储写入路径让缓存/旧 SP/DataStore 三者一致：只复制旧 SP 会漏掉 DataStore 与缓存，
     * 且 newUserId 若已被 seeded 标记便不再回灌，重命名后读到的令牌为空。
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
            if (e.getValue() instanceof String && !moved.containsKey(e.getKey()))
                moved.put(e.getKey(), (String) e.getValue());
        }

        clear(oldUserId);
        for (Map.Entry<String, String> e : moved.entrySet()) {
            putString(newUserId, e.getKey(), e.getValue());
        }
    }
}
