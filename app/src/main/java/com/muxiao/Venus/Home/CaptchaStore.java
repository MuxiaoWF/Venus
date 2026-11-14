package com.muxiao.Venus.Home;

import android.content.Context;

import com.google.gson.Gson;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.data.ConfigRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人机验证的跨轮载体：把「后台撞上 1034 时攒下的会话信息」与「用户完成验证后的结果」落到配置仓储，
 * 让等待人工操作不再占用执行线程（挂起-续跑模型，见 docs/module-tasks.md）。
 *
 * <p>两段数据：
 * <ul>
 *   <li><b>Pending</b>：后台本轮挂起时写。含触发任务 id 列表、gt/challenge、请求头快照与时间戳，
 *       供前台「点通知 / 进应用」时直接复用同一 challenge（重复取 API1 会拿到不匹配的 challenge）。</li>
 *   <li><b>Solution</b>：用户验证成功后写。含 x-rpc-* 三件套与时间戳，下一轮续跑时注入所有任务的请求头。</li>
 * </ul>
 *
 * <p>全局只保留一份：同一时刻只允许一个验证在途（单飞），多任务并发撞 1034 会在轮末合并成一次验证。
 * 两段数据都带 TTL，过期即视为不存在——极验 challenge 与服务端校验结果本身有时效，久留只会让续跑
 * 带着失效凭证反复 1034。
 */
public final class CaptchaStore {

    private static final String TAG = "VenusCaptcha";
    private static final Gson GSON = new Gson();

    /** 待验证请求有效期：超过即失效（极验 challenge 在服务端同样会过期，久留无意义）。 */
    private static final long PENDING_TTL_MS = 15 * 60 * 1000L;

    /** 验证结果有效期：只需覆盖紧随其后的续跑轮，不必久留。 */
    private static final long SOLUTION_TTL_MS = 10 * 60 * 1000L;

    private static final String KEY_PENDING = "captcha_pending_request";
    private static final String KEY_SOLUTION = "captcha_solution";

    private CaptchaStore() {
    }

    // ==================== 待验证请求 ====================

    /**
     * 落库一次待验证请求。同时把 {@code captcha_pending} 置真——MainActivity 用它做「有没有待验证」
     * 的快速判断，避免每次进应用都反序列化 JSON。
     */
    public static void savePending(Context context, String userId, Collection<String> taskIds,
                                   String gt, String challenge, Map<String, String> headers) {
        Pending pending = new Pending();
        pending.userId = userId;
        pending.taskIds = taskIds == null ? new ArrayList<>() : new ArrayList<>(taskIds);
        pending.gt = gt;
        pending.challenge = challenge;
        pending.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        pending.createdAt = System.currentTimeMillis();

        ConfigRepository repo = new ConfigRepository(context);
        // 新挂起 = 上一份验证结果已不足以让任务通过（否则不会再次 1034），留着只会让续跑带上失效凭证。
        // 同时也是「本轮是否真的验证过」的判据：轮末能读到 solution 就说明这次验证完成了。
        repo.putString(KEY_SOLUTION, "");
        repo.putString(KEY_PENDING, GSON.toJson(pending));
        repo.putBoolean(Constants.Prefs.PREF_CAPTCHA_PENDING, true);
        Logger.debug(TAG, "savePending: tasks=" + pending.taskIds.size() + ", gt=" + gt);
    }

    /** 读取待验证请求；不存在或已过期返回 null（过期时顺手清理，避免反复读到死数据）。 */
    public static Pending getPending(Context context) {
        ConfigRepository repo = new ConfigRepository(context);
        String raw = repo.get(KEY_PENDING, "");
        if (raw == null || raw.isEmpty()) return null;
        Pending pending;
        try {
            pending = GSON.fromJson(raw, Pending.class);
        } catch (RuntimeException e) {
            clearPending(context);
            return null;
        }
        if (pending == null || pending.challenge == null || pending.challenge.isEmpty()) {
            clearPending(context);
            return null;
        }
        if (System.currentTimeMillis() - pending.createdAt > PENDING_TTL_MS) {
            Logger.debug(TAG, "pending expired, dropped");
            clearPending(context);
            return null;
        }
        return pending;
    }

    /** 清掉待验证请求与「待验证」标记（验证已结束或被放弃）。 */
    public static void clearPending(Context context) {
        ConfigRepository repo = new ConfigRepository(context);
        repo.putString(KEY_PENDING, "");
        repo.putBoolean(Constants.Prefs.PREF_CAPTCHA_PENDING, false);
    }

    /**
     * 只清「待验证」标记、保留请求本身：用户关掉验证框或本轮验证失败时用——下次点通知仍可拿同一
     * challenge 重试，而不是被反复自动弹出。
     */
    public static void dismissPendingFlag(Context context) {
        new ConfigRepository(context).putBoolean(Constants.Prefs.PREF_CAPTCHA_PENDING, false);
    }

    // ==================== 验证结果 ====================

    /** 落库验证成功后拿到的 x-rpc-* 三件套，供紧随其后的续跑轮注入。 */
    public static void saveSolution(Context context, String userId, Map<String, String> code) {
        if (code == null || code.isEmpty()) return;
        Solution solution = new Solution();
        solution.userId = userId;
        solution.code = new LinkedHashMap<>(code);
        solution.createdAt = System.currentTimeMillis();
        new ConfigRepository(context).putString(KEY_SOLUTION, GSON.toJson(solution));
        Logger.debug(TAG, "saveSolution for user=" + userId);
    }

    /**
     * 读取指定用户的验证结果；不存在、已过期或属于其他用户时返回 null。
     * 按用户隔离：后台运行 A 期间切到 B 完成验证，不能把 A 的凭证注进 B 的请求。
     */
    public static Map<String, String> getSolution(Context context, String userId) {
        String raw = new ConfigRepository(context).get(KEY_SOLUTION, "");
        if (raw == null || raw.isEmpty()) return null;
        Solution solution;
        try {
            solution = GSON.fromJson(raw, Solution.class);
        } catch (RuntimeException e) {
            clearSolution(context);
            return null;
        }
        if (solution == null || solution.code == null || solution.code.isEmpty()) return null;
        if (userId == null || !userId.equals(solution.userId)) return null;
        if (System.currentTimeMillis() - solution.createdAt > SOLUTION_TTL_MS) {
            Logger.debug(TAG, "solution expired, dropped");
            clearSolution(context);
            return null;
        }
        return Collections.unmodifiableMap(solution.code);
    }

    /** 清掉验证结果（续跑轮已消费或确定失效时调用）。 */
    public static void clearSolution(Context context) {
        new ConfigRepository(context).putString(KEY_SOLUTION, "");
    }

    // ==================== 数据模型 ====================

    /** 挂起中的验证请求。字段为 public：Gson 直读直写，与 TaskStatusManager.Bucket 同风格。 */
    public static final class Pending {
        public String userId;
        public List<String> taskIds;
        public String gt;
        public String challenge;
        public Map<String, String> headers;
        public long createdAt;

        /** 触发验证的任务 id 列表（非 null 视图）。 */
        public List<String> taskIds() {
            return taskIds == null ? Collections.emptyList() : taskIds;
        }
    }

    /** 验证成功的凭证。 */
    public static final class Solution {
        public String userId;
        public Map<String, String> code;
        public long createdAt;
    }
}
