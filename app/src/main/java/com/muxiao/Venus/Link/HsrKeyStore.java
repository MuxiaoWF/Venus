package com.muxiao.Venus.Link;

import static com.muxiao.Venus.common.Constants.Prefs.HSR_KEYS_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.HSR_KEYS_UPDATE_TIME_PREF;

import android.content.Context;
import android.util.Base64;

import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.data.ConfigRepository;
import com.muxiao.Venus.common.tools;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 星铁 KCP 初始密钥表仓储：assets/hsr.json 内置表 + 云端更新（模式同 MiHoYoBBSConstants 的 K2/LK2）。
 * 云端源链：主源 stardb-exporter（GitHub raw，海外快、国内常不可达，走短超时快速让位）→
 * 兜底自建服务（Constants.Urls.HSR_KEYS_UPDATE_URL），两者均为 {版本号: base64密钥} 同构 JSON；
 * CaptureService 每次启动抓包时后台调 checkForUpdateAsync 检查更新。
 * 合并语义：assets 为底、云端逐键覆盖（仅接受解码后为 4096 字节的有效条目），
 * 持久化只存「与运行时表的差量」到 ConfigRepository（键 hsr_keys）——
 * 冷启动按 assets ∪ 差量重建，应用内置 assets 补新键后也不会被旧差量顶掉。
 *
 * <p>请求节流（尽量少打远程接口）：
 * <ul>
 *   <li>常规检查（每次启动抓包触发）：距上次<b>成功</b>拉取不足 {@link #PROACTIVE_INTERVAL_MS} 直接跳过
 *       ——密钥表平时不变化，正常使用下每 24h 至多 1 次请求；</li>
 *   <li>缺键补拉（分析器遇到未知版本号触发）：绕过常规节流立即拉取（游戏上新版后服务端一有键即可用），
 *       但 {@link #MISS_RETRY_MS} 内至多 1 次，且全程单飞（进行中不重复发起）——
 *       防止服务端也没这个键时每个 KCP 包都打请求；</li>
 * </ul>
 */
public final class HsrKeyStore {
    private static final String TAG = "HsrKeyStore";
    /** 每个初始密钥 base64 解码后的固定长度（与 PacketAnalyzer.createXorPad 的 pad 长度一致） */
    private static final int KEY_BYTES = 4096;
    /** 常规检查节流：距上次成功拉取不足该时长，启动抓包不再请求 */
    private static final long PROACTIVE_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    /** 缺键补拉节流：两次补拉尝试的最小间隔（服务端暂缺新键时避免刷请求） */
    private static final long MISS_RETRY_MS = 10 * 60 * 1000L;
    /** 云端源链（按序尝试）：主源短超时快速失败让位兜底源，某源有效即止 */
    private static final String[] SOURCE_URLS = {
            Constants.Urls.HSR_KEYS_UPDATE_URL_PRIMARY,
            Constants.Urls.HSR_KEYS_UPDATE_URL
    };

    private static final Object initLock = new Object();
    /** 运行时合并表（assets ∪ 持久化差量 ∪ 本次会话云端拉取），volatile 整体替换 */
    private static volatile JSONObject table;
    /** 单飞闸：同一时刻至多一个拉取在途 */
    private static final AtomicBoolean fetching = new AtomicBoolean(false);
    /** 上次缺键补拉尝试时刻（仅本进程内存，会话结束即失效——缺键只可能发生在抓包会话内） */
    private static volatile long lastMissRequestMs;

    private HsrKeyStore() {
    }

    /** 按版本号查初始密钥；懒加载运行时表，无该版本返回 null（调用方按缺键处理） */
    public static String getKey(Context context, String version) {
        if (table == null) {
            synchronized (initLock) {
                if (table == null)
                    table = loadRuntimeTable(context);
            }
        }
        if (table.isNull(version)) return null;
        return table.optString(version);
    }

    /**
     * 异步检查云端密钥表更新（内部已在后台线程池执行，任意线程可调）。
     *
     * @param force false=常规检查（启动抓包时）：距上次成功拉取不足 24h 跳过；
     *              true=缺键补拉（分析器遇到未知版本时）：绕过常规节流，仅受 10min 重试间隔与单飞闸约束
     */
    public static void checkForUpdateAsync(Context context, boolean force) {
        checkForUpdateAsync(context, force, null);
    }

    /**
     * 同上，可附带拉取落定回调。
     *
     * @param onSettled 本次**真实发起**的拉取结束（无论成败）后在后台线程回调；
     *                  因节流跳过或已有拉取在途（未发起新请求）时不回调
     */
    public static void checkForUpdateAsync(Context context, boolean force, Runnable onSettled) {
        long now = System.currentTimeMillis();
        if (force) {
            if (now - lastMissRequestMs < MISS_RETRY_MS) return;
            lastMissRequestMs = now;
        } else if (now - getLastSuccessMs(context) < PROACTIVE_INTERVAL_MS) {
            return;
        }
        if (!fetching.compareAndSet(false, true)) return;
        AppExecutors.get().execute(() -> {
            try {
                updateFromWeb(context);
            } finally {
                fetching.set(false);
                if (onSettled != null) onSettled.run();
            }
        });
    }

    /**
     * 按顺序尝试各云端源拉取密钥表并合并进运行时表（必须在后台线程调用）。
     * 主源（stardb-exporter，海外快、国内常不可达）用短超时客户端，失败/无效立即让位兜底源；
     * 某源返回任何有效条目即成功，跨源不叠加。全部失败时静默保留现有表（抓包仍可用内置表兜底）。
     */
    private static void updateFromWeb(Context context) {
        for (int i = 0; i < SOURCE_URLS.length; i++) {
            // 主源可能被墙：短超时快速失败；兜底源走常规超时
            boolean fast = i < SOURCE_URLS.length - 1;
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "Venus/1.0");
                String response = tools.sendGetRequest(SOURCE_URLS[i], headers, null, fast);
                if (response == null || response.isEmpty()) continue;
                JSONObject valid = filterValid(new JSONObject(response));
                if (valid.length() == 0) {
                    Logger.debug(TAG, "remote hsr keys invalid: " + SOURCE_URLS[i]);
                    continue;
                }
                mergeAndPersist(context, valid, SOURCE_URLS[i]);
                return;
            } catch (Exception e) {
                Logger.debug(TAG, "fetch hsr keys failed from " + SOURCE_URLS[i] + ": " + e);
            }
        }
    }

    /** 合并有效条目进运行时表，差量写盘并刷新成功时刻基准（JSON 异常由调用方按源失败处理） */
    private static void mergeAndPersist(Context context, JSONObject valid, String source) throws Exception {
        synchronized (initLock) {
            if (table == null)
                table = loadRuntimeTable(context);
            Iterator<String> it = valid.keys();
            while (it.hasNext()) {
                String version = it.next();
                table.put(version, valid.getString(version));
            }
            ConfigRepository configRepo = new ConfigRepository(context);
            String snapshot = buildDiffJson(context).toString();
            if (!snapshot.equals(configRepo.get(HSR_KEYS_PREF, ""))) {
                configRepo.putString(HSR_KEYS_PREF, snapshot);
                Logger.debug(TAG, "hsr keys persisted from " + source);
            }
            // 成功即刷新常规节流基准（与差量无关，空表也算「该源已确认无更新」）
            configRepo.putString(HSR_KEYS_UPDATE_TIME_PREF, String.valueOf(System.currentTimeMillis()));
        }
    }

    /**
     * 运行时表相对 assets 的差量快照（assets 里没有、或值已被云端覆盖的条目）。
     *
     * <p>注意必须写「相对 assets 的全量差量」，不能只写本次拉取新增的条目：运行时表
     * = assets ∪ 已落盘差量，只写本次新增会把此前落盘、assets 里没有的键从磁盘抹掉；
     * 一旦随后 24h 常规节流窗口内重启进程（常规检查会跳过拉取），应用就会退回缺键状态，
     * 表现为 VPN 抓包"流程都对但抓不到链接"。
     */
    private static JSONObject buildDiffJson(Context context) throws Exception {
        JSONObject assets = loadAssets(context);
        JSONObject diff = new JSONObject();
        Iterator<String> it = table.keys();
        while (it.hasNext()) {
            String version = it.next();
            String value = table.optString(version);
            String assetValue = assets.optString(version);
            if (value.equals(assetValue))
                continue;
            diff.put(version, value);
        }
        return diff;
    }

    private static long getLastSuccessMs(Context context) {
        try {
            return Long.parseLong(
                Objects.requireNonNull(new ConfigRepository(context).get(HSR_KEYS_UPDATE_TIME_PREF, "0")));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 运行时表 = assets/hsr.json ⊕ 持久化差量（assets 缺失/损坏时差量仍可独立成表） */
    private static JSONObject loadRuntimeTable(Context context) {
        JSONObject merged = loadAssets(context);
        JSONObject persisted = filterValid(loadPersisted(context));
        Iterator<String> it = persisted.keys();
        while (it.hasNext()) {
            String version = it.next();
            try {
                merged.put(version, persisted.getString(version));
            } catch (Exception ignored) {
            }
        }
        return merged;
    }

    private static JSONObject loadAssets(Context context) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(context.getAssets().open("hsr.json"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null)
                    sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Logger.debug(TAG, "load assets hsr.json failed: " + e);
            return new JSONObject();
        }
    }

    private static JSONObject loadPersisted(Context context) {
        try {
            String raw = new ConfigRepository(context).get(HSR_KEYS_PREF, "");
            if (raw == null || raw.isEmpty()) return new JSONObject();
            return new JSONObject(raw);
        } catch (Exception e) {
            Logger.debug(TAG, "load persisted hsr keys failed: " + e);
            return new JSONObject();
        }
    }

    /** 过滤无效条目：键须为纯数字版本号，值须 base64 解码为固定长度密钥 */
    private static JSONObject filterValid(JSONObject input) {
        JSONObject out = new JSONObject();
        Iterator<String> it = input.keys();
        while (it.hasNext()) {
            String version = it.next();
            try {
                if (!version.matches("\\d{1,10}")) continue;
                String value = input.getString(version);
                if (Base64.decode(value, Base64.DEFAULT).length != KEY_BYTES) continue;
                out.put(version, value);
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
