package com.muxiao.Venus.common.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.core.Single;

/**
 * 米游社配置仓储（单一可信数据源，P0-1 / 阶段 2 DataStore）。
 * 底层由 RxJava3 Preferences DataStore 承载，替代原 "config_prefs" SharedPreferences。
 * 对外 get/put 同步热读（Write-Through），与原 SP 版签名/语义一致。
 *
 * <p>承载的键（config_prefs 的全部键，已完成收口，P0-1 阶段 5）：
 * <ul>
 *   <li>字符串：SALT_6X / SALT_4X / LK2 / K2 / bbs_version / update_time / update_time_local
 *       —— 由 MiHoYoBBSConstants 写入、SettingsFragment 展示；</li>
 *   <li>布尔：captcha_pending —— 由 MainActivity 读写（后台人机验证待触发标记）。</li>
 * </ul>
 *
 * <p>收口说明：自本版本起 config_prefs 不再有任何直接 getSharedPreferences 访问点
 * （唯一例外是本类的 {@link #seedCacheFromLegacy}/{@link #clear()}，用于旧 xml 的回灌与清理），
 * 因此不存在"同一键分属 xml 与 pb 两个文件"的读写分叉。
 *
 * <p>DataStore 按文件应为单例：调用方存在 new ConfigRepository(context) 多次实例化的情况，故 DS / 缓存
 * 均按 prefs 名静态共享，避免"同一文件多 DataStore 实例"异常。
 * 应用为单进程（AndroidManifest 未声明任何 android:process），满足 DataStore 的单进程约束。
 */
public class ConfigRepository {

    private static volatile RxDataStore<Preferences> store;
    /** 同步热读缓存：值类型为 String 或 Boolean。 */
    private static final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();
    private static final Object initLock = new Object();

    private final Context context;

    public ConfigRepository(Context context) {
        this.context = context.getApplicationContext();
        if (store == null) {
            synchronized (initLock) {
                if (store == null) {
                    store = new RxPreferenceDataStoreBuilder(this.context, Constants.Prefs.CONFIG_PREFS_NAME).build();
                    // DataStore 数据变化（含后续写入）同步回写缓存。
                    // 必须显式消费 onError：RxJava 对未处理错误会抛 OnErrorNotImplementedException，
                    // 在 DataStore 读取失败（文件损坏/IO 异常）时直接把异常抛到订阅线程。
                    // 此处降级为「保留既有缓存 + 旧 SP 兜底」，配置读取不因持久层故障而中断。
                    store.data().subscribe(
                            prefs -> {
                                for (Map.Entry<Preferences.Key<?>, Object> e : prefs.asMap().entrySet()) {
                                    Object v = e.getValue();
                                    if (v instanceof String || v instanceof Boolean) {
                                        cache.put(e.getKey().getName(), v);
                                    }
                                }
                            },
                            error -> Logger.debug("VenusConfig", "DataStore observe failed: " + error));
                    // 同步预灌一次，保证首次冷启动 get() 立即可用（订阅为异步，首帧可能来不及）
                    try {
                        Map<Preferences.Key<?>, Object> initial = store.data().firstOrError().blockingGet().asMap();
                        for (Map.Entry<Preferences.Key<?>, Object> e : initial.entrySet()) {
                            Object v = e.getValue();
                            if (v instanceof String || v instanceof Boolean) {
                                cache.put(e.getKey().getName(), v);
                            }
                        }
                    } catch (Exception ignore) {
                        // 读取失败时保持空缓存，后续由 seedCacheFromLegacy 与订阅补全
                    }
                    seedCacheFromLegacy(this.context);
                }
            }
        }
    }

    // 首次访问时从旧 SP 同步灌入缓存（保证 get 立即可用，与原行为一致）
    private static void seedCacheFromLegacy(Context context) {
        SharedPreferences sp = context.getSharedPreferences(Constants.Prefs.CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof String || v instanceof Boolean) {
                cache.put(e.getKey(), v);
            }
        }
    }

    @Nullable
    // 同步热读：仅读内存缓存，未命中或类型不符时返回 defValue，不阻塞也不触发持久化。
    public String get(String key, String defValue) {
        Object v = cache.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    // 同步热读：仅读内存缓存，未命中或类型不符时返回 defValue，不阻塞也不触发持久化。
    public boolean getBoolean(String key, boolean defValue) {
        Object v = cache.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
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

    /**
     * 清空全部配置（等价于原 configPrefs.edit().clear().apply()）。
     * 用于版本升级后回退到内置默认值（见 MainActivity#checkAndUpdateConfig）。
     *
     * <p>同时清理旧 xml：否则下次冷启动 {@link #seedCacheFromLegacy} 会把已废弃的旧值重新灌回缓存，
     * 导致"清空"在重启后失效。
     */
    public void clear() {
        cache.clear();
        context.getSharedPreferences(Constants.Prefs.CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply();
        store.updateDataAsync(prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.clear();
            return Single.just(m);
        }).subscribe();
    }
}
