package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.Constants.Prefs.CLOUD_GAME_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.CLOUD_GAME_GENSHIN;
import static com.muxiao.Venus.common.Constants.Prefs.CLOUD_GAME_ZZZ;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_DABIEYE;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_GENSHIN;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HNA;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HR2;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HR3;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_SRG;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_WEIDING;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_XINGBUGUDI;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_ZZZ;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_GENSHIN;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_HR2;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_HR3;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_SRG;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_WEIDING;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_ZZZ;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_ARKNIGHTS_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_ENDFIELD_ENABLED;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * 「任务配置按账号」的存储与迁移。
 *
 * <p>存储：{@code settings_prefs} 的 {@code task_profile_<userId>}（JSON 化的 {@link TaskProfile}）。
 * 一份配置读一次、写一次都是原子的，后续加字段只需升 {@link TaskProfile#VERSION}，不必新增键。
 * 与凭证（{@code user_{id}}）和任务状态（{@code task_status_bucket_<userId>}）同口径——三处都按账号隔离。
 *
 * <p>为什么不直接复用旧全局键：旧版的 {@code daily_checkbox_*} / {@code game_daily_checkbox_*} 只有一份，
 * 多账号时无法表达「A 只签原神、B 只签星铁」。改造后旧键<b>不再被读取</b>（除升级迁移那一次），
 * 只保留在磁盘上便于回滚。
 *
 * <p>模板（{@code task_profile_template}）：新账号首次加载配置时继承它。模板由升级那一刻的旧全局开关
 * 固化而成——这样老用户升级后行为不变。P1 的「应用到全部账号」会更新模板，形成「显式维护模板」的闭环；
 * 在此之前，新建账号继承的是升级时的快照，而不是「最近一次编辑」。
 */
public final class TaskProfileStore {

    /** 单账号配置键前缀：完整键 = 前缀 + userId。 */
    private static final String KEY_PREFIX = "task_profile_";
    /** 模板键：新账号首次加载时继承。 */
    private static final String KEY_TEMPLATE = "task_profile_template";

    private static final Gson GSON = new Gson();

    /**
     * 米游币讨论区：<b>顺序必须与 Compose 侧 {@code DAILY_CHIP_LABEL_RES} 一致</b>
     * （设置页 chip 的下标即本数组下标，错位会把勾选写到别的讨论区上）。
     */
    public static final String[] DAILY_FORUM_ORDER = {
            "原神", "绝区零", "星铁", "崩坏3", "崩坏2", "未定事件簿",
            "大别野", "崩坏因缘精灵", "星布谷地",
    };
    /** 游戏签到：顺序同上，对应 {@code GAME_CHIP_LABEL_RES}。 */
    public static final String[] GAME_SIGN_ORDER = {
            "原神", "绝区零", "星铁", "崩坏3", "崩坏2", "未定事件簿",
    };
    /** 云游戏：顺序同上，对应 {@code CLOUD_GAME_CHIP_LABEL_RES}。 */
    public static final String[] CLOUD_GAME_ORDER = {
            CloudGameDaily.GAME_GENSHIN, CloudGameDaily.GAME_ZZZ,
    };

    /** 国际服没有签到活动的两款国服专属游戏（{@link TaskSettings} 执行前过滤，设置页据此隐藏 chip）。 */
    public static final String GAME_HR2 = "崩坏2";
    public static final String GAME_WEIDING = "未定事件簿";

    /** 旧版本里唯一默认开启的讨论区。 */
    private static final String DEFAULT_FORUM = "大别野";

    // 旧版全局键，顺序与上面的 ORDER 一一对应；仅升级迁移（legacySnapshot）读取。
    private static final String[] LEGACY_DAILY_KEYS = {
            DAILY_GENSHIN, DAILY_ZZZ, DAILY_SRG, DAILY_HR3,
            DAILY_HR2, DAILY_WEIDING, DAILY_DABIEYE, DAILY_HNA,
            DAILY_XINGBUGUDI,
    };
    /** 旧版各讨论区的默认值：只有「大别野」默认勾选，其余默认关。 */
    private static final boolean[] LEGACY_DAILY_DEFAULTS = {
            false, false, false, false, false, false, true, false, false,
    };
    private static final String[] LEGACY_GAME_KEYS = {
            GAME_DAILY_GENSHIN, GAME_DAILY_ZZZ, GAME_DAILY_SRG,
            GAME_DAILY_HR3, GAME_DAILY_HR2, GAME_DAILY_WEIDING,
    };
    private static final String[] LEGACY_CLOUD_KEYS = {CLOUD_GAME_GENSHIN, CLOUD_GAME_ZZZ};

    private TaskProfileStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 读取指定账号的任务配置。
     *
     * <p>该账号还没有配置时<b>继承模板并落盘</b>（一次性）：此后它在设置页的改动只影响自己。
     * 无账号（userId 为空）返回内存默认值且不落盘——没有账号就没有可归属的配置。
     */
    public static TaskProfile load(Context context, String userId) {
        SharedPreferences prefs = prefs(context);
        if (userId == null || userId.isEmpty()) return defaults();

        TaskProfile stored = tools.fromJsonOrNull(GSON, prefs.getString(KEY_PREFIX + userId, ""), TaskProfile.class);
        if (stored != null) return normalize(stored);

        TaskProfile inherited = template(context);
        save(context, userId, inherited);
        return normalize(inherited);
    }

    /** 写入指定账号的配置。userId 为空时静默跳过（调用方应先拦「无账号」场景）。 */
    public static void save(Context context, String userId, TaskProfile profile) {
        if (userId == null || userId.isEmpty() || profile == null) return;
        profile.v = TaskProfile.VERSION;
        prefs(context).edit().putString(KEY_PREFIX + userId, GSON.toJson(profile)).apply();
    }

    /** 删除指定账号的配置（账号被删除时调用，避免残留成孤儿键）。 */
    public static void delete(Context context, String userId) {
        if (userId == null || userId.isEmpty()) return;
        prefs(context).edit().remove(KEY_PREFIX + userId).apply();
    }

    /**
     * 随账号重命名迁移配置。
     *
     * <p>必须显式迁移：{@code UserManager.removeUser(旧名)} 不会碰这份键，
     * 而重命名是「新增 + 删除」两步——不迁移就等于把旧账号的签到计划丢了。
     * 与 {@code UserRepository.migrate} / {@code AvatarStore.renameAvatar} 同一口径。
     */
    public static void rename(Context context, String oldUserId, String newUserId) {
        if (oldUserId == null || newUserId == null || oldUserId.isEmpty() || newUserId.isEmpty()
                || oldUserId.equals(newUserId)) return;
        TaskProfile moved = tools.fromJsonOrNull(GSON, prefs(context).getString(KEY_PREFIX + oldUserId, ""), TaskProfile.class);
        if (moved == null) return;
        save(context, newUserId, moved);
        delete(context, oldUserId);
    }

    /**
     * 模板：新账号的初始配置。
     *
     * <p>首次调用时由旧版全局开关固化（保证升级用户行为不变），此后稳定存在，直到 P1 的
     * 「应用到全部账号」显式改写它。
     */
    public static TaskProfile template(Context context) {
        SharedPreferences prefs = prefs(context);
        TaskProfile stored = tools.fromJsonOrNull(GSON, prefs.getString(KEY_TEMPLATE, ""), TaskProfile.class);
        if (stored != null) return normalize(stored);

        TaskProfile legacy = legacySnapshot(prefs);
        prefs.edit().putString(KEY_TEMPLATE, GSON.toJson(legacy)).apply();
        return legacy;
    }

    /** 无账号时的兜底配置：与旧版默认值一致（米游币/游戏签到开、只勾大别野、其余关）。 */
    public static TaskProfile defaults() {
        TaskProfile profile = new TaskProfile();
        profile.dailyForums.add(DEFAULT_FORUM);
        return profile;
    }

    /** 显式改写模板（「应用到全部账号」调用），新账号首次加载时继承它。 */
    public static void saveTemplate(Context context, TaskProfile profile) {
        if (profile == null) return;
        profile.v = TaskProfile.VERSION;
        prefs(context).edit().putString(KEY_TEMPLATE, GSON.toJson(profile)).apply();
    }

    /** 把旧版的全局任务开关展开成一份 {@link TaskProfile}（不做服务器类型过滤，见类注释）。 */
    private static TaskProfile legacySnapshot(SharedPreferences prefs) {
        TaskProfile profile = new TaskProfile();
        profile.daily = prefs.getBoolean(DAILY, true);
        profile.dailyForums = collect(prefs, LEGACY_DAILY_KEYS, LEGACY_DAILY_DEFAULTS, DAILY_FORUM_ORDER);
        profile.gameDaily = prefs.getBoolean(GAME_DAILY, true);
        profile.gameDailyGames = collect(prefs, LEGACY_GAME_KEYS, null, GAME_SIGN_ORDER);
        profile.cloudGame = prefs.getBoolean(CLOUD_GAME_ENABLED, false);
        profile.cloudGames = collect(prefs, LEGACY_CLOUD_KEYS, null, CLOUD_GAME_ORDER);
        profile.skland = prefs.getBoolean(SKLAND_ENABLED, false);
        profile.sklandArknights = prefs.getBoolean(SKLAND_ARKNIGHTS_ENABLED, false);
        profile.sklandEndfield = prefs.getBoolean(SKLAND_ENDFIELD_ENABLED, false);
        return profile;
    }

    /**
     * 按 {@code keys} 顺序收集已勾选项，输出顺序即 {@code order} 顺序。
     *
     * @param defaults 逐项默认值；null 表示全部默认 false
     */
    private static List<String> collect(SharedPreferences prefs, String[] keys,
                                        boolean[] defaults, String[] order) {
        List<String> picked = new ArrayList<>(keys.length);
        for (int i = 0; i < keys.length && i < order.length; i++) {
            boolean fallback = defaults != null && i < defaults.length && defaults[i];
            if (prefs.getBoolean(keys[i], fallback)) picked.add(order[i]);
        }
        return picked;
    }


    /** 补齐缺失字段：Gson 反序列化时 null 集合会让调用方 NPE，版本号统一收敛到当前版本。 */
    private static TaskProfile normalize(TaskProfile profile) {
        if (profile.dailyForums == null) profile.dailyForums = new ArrayList<>();
        if (profile.gameDailyGames == null) profile.gameDailyGames = new ArrayList<>();
        if (profile.cloudGames == null) profile.cloudGames = new ArrayList<>();
        if (profile.v > TaskProfile.VERSION) profile.v = TaskProfile.VERSION;
        return profile;
    }
}
