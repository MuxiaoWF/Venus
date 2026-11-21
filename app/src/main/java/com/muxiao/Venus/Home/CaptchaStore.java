package com.muxiao.Venus.Home;

import android.content.Context;

import com.google.gson.Gson;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.data.ConfigRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人机验证的跨轮载体，<b>按账号存放</b>：把「后台撞上 1034 时攒下的会话信息」与「用户完成验证后的结果」
 * 落到配置仓储，让等待人工操作不再占用执行线程（挂起-续跑模型，见 docs/module-tasks.md）。
 *
 * <p>两段数据（键都按账号分）：
 * <ul>
 *   <li><b>Pending</b>（{@code captcha_pending_request_<userId>}）：挂起时写。含触发任务 id 列表、gt/challenge、
 *       请求头快照、时间戳与 {@code seen} 标记，供前台复用同一 challenge（重复取 API1 会拿到不匹配的 challenge）。</li>
 *   <li><b>Solution</b>（{@code captcha_solution_<userId>}）：验证成功后写。含 x-rpc-* 三件套与时间戳，
 *       续跑轮把它注入该账号任务的请求头。</li>
 * </ul>
 *
 * <p><b>为什么必须按账号分</b>：极验 challenge 是按请求头（含 Cookie）取的，验证结果只能服务同一账号的会话。
 * 旧实现全局只有一份，多账号串行时后一个账号的挂起会把前一个账号的待验证请求与凭证一起覆盖，表现为
 * 「A 的任务永远停在待验证」。现在各账号各挂各的，互不影响。
 *
 * <p><b>同一账号内仍单飞</b>：该账号多任务并发撞 1034 时在轮末合并成一次验证（见 {@code CaptchaSuspension}）。
 * 跨账号的「一次弹一个、连续验证」由前台的验证队列负责（P1）；本类只保证数据不丢不串。
 *
 * <p>两段数据都带 TTL，过期即视为不存在——极验 challenge 与服务端校验结果本身有时效，久留只会让续跑
 * 带着失效凭证反复 1034。
 */
public final class CaptchaStore {

    private static final String TAG = "VenusCaptcha";
    private static final Gson GSON = new Gson();

    /** 待验证请求有效期：超过即失效（极验 challenge 在服务端同样会过期，久留无意义）。 */
    private static final long PENDING_TTL_MS = 15 * 60 * 1000L;

    /** 验证结果有效期：只需覆盖紧随其后的续跑轮，不必久留。 */
    private static final long SOLUTION_TTL_MS = 10 * 60 * 1000L;

    /** 单账号键前缀：完整键 = 前缀 + userId。 */
    private static final String PREFIX_PENDING = "captcha_pending_request_";
    private static final String PREFIX_SOLUTION = "captcha_solution_";

    /** 旧版全局单键：仅升级后首次读取时迁移一次（其中自带 userId，按其归属搬到新键）。 */
    private static final String LEGACY_KEY_PENDING = "captcha_pending_request";
    private static final String LEGACY_KEY_SOLUTION = "captcha_solution";

    /**
     * 无账号时的兜底后缀。正常链路不会走到（运行入口已拦「无账号」），仅用于避免空后缀键与真实账号冲突。
     */
    private static final String ANONYMOUS = "_anonymous";

    private CaptchaStore() {
    }

    private static String keyOf(String prefix, String userId) {
        return prefix + (userId == null || userId.isEmpty() ? ANONYMOUS : userId);
    }

    // ==================== 待验证请求 ====================

    /**
     * 落库某账号的一次待验证请求。
     *
     * <p>同时清掉<b>该账号</b>的旧 solution：新挂起说明上一份凭证已不足以通过，留着只会让续跑带上失效凭证。
     * 这也是「本轮是否真的验证过」的判据——轮末能读到 solution 就说明这次验证完成了。
     * 注意只清该账号：旧实现清的是全局单键，会把别的账号刚验好的凭证一起废掉。
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
        pending.seen = false;

        ConfigRepository repo = new ConfigRepository(context);
        repo.putString(keyOf(PREFIX_SOLUTION, userId), "");
        repo.putString(keyOf(PREFIX_PENDING, userId), GSON.toJson(pending));
        Logger.debug(TAG, "savePending for " + userId + ": tasks=" + pending.taskIds.size() + ", gt=" + gt);
    }

    /** 读取指定账号的待验证请求；不存在或已过期返回 null（过期时顺手清理，避免反复读到死数据）。 */
    public static Pending getPending(Context context, String userId) {
        ConfigRepository repo = new ConfigRepository(context);
        migrateLegacy(repo);

        String raw = repo.get(keyOf(PREFIX_PENDING, userId), "");
        if (raw == null || raw.isEmpty()) return null;
        Pending pending = parsePending(raw);
        if (pending == null || pending.challenge == null || pending.challenge.isEmpty()) {
            clearPending(context, userId);
            return null;
        }
        if (System.currentTimeMillis() - pending.createdAt > PENDING_TTL_MS) {
            Logger.debug(TAG, "pending expired, dropped for " + userId);
            clearPending(context, userId);
            return null;
        }
        return pending;
    }

    /**
     * 该账号是否有「还没给用户看过」的待验证请求——MainActivity 进前台时是否自动弹框的判据。
     *
     * <p>用 Pending 自带的 {@code seen} 标记替代旧版的全局 {@code captcha_pending} 布尔：那个布尔无法表达
     * 「哪个账号还没弹过」，多账号时任一账号消费掉它，其余账号就再也弹不出来。
     */
    public static boolean hasPendingToShow(Context context, String userId) {
        Pending pending = getPending(context, userId);
        return pending != null && !pending.seen;
    }

    /**
     * 标记该账号的待验证请求「已被用户看过」：保留请求本体（供通知/列表重新唤起同一 challenge），
     * 只是不再自动弹框——用户主动关掉验证框后，不该每次进应用都被再弹一次。
     */
    public static void markPendingSeen(Context context, String userId) {
        ConfigRepository repo = new ConfigRepository(context);
        String raw = repo.get(keyOf(PREFIX_PENDING, userId), "");
        if (raw == null || raw.isEmpty()) return;
        Pending pending = parsePending(raw);
        if (pending == null) return;
        pending.seen = true;
        repo.putString(keyOf(PREFIX_PENDING, userId), GSON.toJson(pending));
    }

    /** 清掉指定账号的待验证请求（验证已结束或被放弃）。 */
    public static void clearPending(Context context, String userId) {
        new ConfigRepository(context).putString(keyOf(PREFIX_PENDING, userId), "");
    }

    // ==================== 验证结果 ====================

    /** 落库指定账号验证成功后拿到的 x-rpc-* 三件套，供紧随其后的续跑轮注入。 */
    public static void saveSolution(Context context, String userId, Map<String, String> code) {
        if (code == null || code.isEmpty()) return;
        Solution solution = new Solution();
        solution.userId = userId;
        solution.code = new LinkedHashMap<>(code);
        solution.createdAt = System.currentTimeMillis();
        new ConfigRepository(context).putString(keyOf(PREFIX_SOLUTION, userId), GSON.toJson(solution));
        Logger.debug(TAG, "saveSolution for user=" + userId);
    }

    /**
     * 读取指定账号的验证结果；不存在、已过期或归属不符时返回 null。
     *
     * <p>键已按账号隔离，这里仍校验一次 {@code solution.userId}：迁移来的旧数据可能带着别的归属。
     */
    public static Map<String, String> getSolution(Context context, String userId) {
        ConfigRepository repo = new ConfigRepository(context);
        migrateLegacy(repo);

        String raw = repo.get(keyOf(PREFIX_SOLUTION, userId), "");
        if (raw == null || raw.isEmpty()) return null;
        Solution solution = parseSolution(raw);
        if (solution == null || solution.code == null || solution.code.isEmpty()) return null;
        if (userId == null || !userId.equals(solution.userId)) return null;
        if (System.currentTimeMillis() - solution.createdAt > SOLUTION_TTL_MS) {
            Logger.debug(TAG, "solution expired, dropped for " + userId);
            clearSolution(context, userId);
            return null;
        }
        return Collections.unmodifiableMap(solution.code);
    }

    /** 清掉指定账号的验证结果（续跑轮已消费或确定失效时调用）。 */
    public static void clearSolution(Context context, String userId) {
        new ConfigRepository(context).putString(keyOf(PREFIX_SOLUTION, userId), "");
    }

    // ==================== 旧数据迁移 ====================

    /**
     * 把旧版全局单键搬到按账号的键上，然后清掉旧键。
     *
     * <p>旧键自带 {@code userId}，所以按「它自己的归属」搬运，与本次读的是哪个账号无关——这样无论谁先读，
     * 数据都落到正确的账号名下，不会出现「A 先读、把 B 的挂起搬到 A 名下」。
     *
     * <p>每次读取都跑一遍：旧键清空后只是两次内存读，开销可忽略；实际搬运只发生一次。
     * 损坏的旧 JSON 无法判定归属，直接作废（留着只会被反复读到、反复解析失败）。
     */
    private static void migrateLegacy(ConfigRepository repo) {
        String legacyPending = repo.get(LEGACY_KEY_PENDING, "");
        if (legacyPending != null && !legacyPending.isEmpty()) {
            Pending moved = parsePending(legacyPending);
            if (moved != null && moved.userId != null && !moved.userId.isEmpty())
                repo.putString(keyOf(PREFIX_PENDING, moved.userId), legacyPending);
            repo.putString(LEGACY_KEY_PENDING, "");
        }

        String legacySolution = repo.get(LEGACY_KEY_SOLUTION, "");
        if (legacySolution != null && !legacySolution.isEmpty()) {
            Solution moved = parseSolution(legacySolution);
            if (moved != null && moved.userId != null && !moved.userId.isEmpty())
                repo.putString(keyOf(PREFIX_SOLUTION, moved.userId), legacySolution);
            repo.putString(LEGACY_KEY_SOLUTION, "");
        }
    }

    private static Pending parsePending(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return GSON.fromJson(raw, Pending.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Solution parseSolution(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return GSON.fromJson(raw, Solution.class);
        } catch (RuntimeException e) {
            return null;
        }
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
        /**
         * 是否已被用户看到（自动弹框消费过）：false = 进前台自动弹；true = 只留作通知/列表入口。
         * 旧版 JSON 没有该字段，反序列化为 false，升级后首次进前台会自动弹一次（与旧行为一致）。
         */
        public boolean seen;

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
