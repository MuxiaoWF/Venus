package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_COOKIE;

import android.content.Context;

import com.muxiao.Venus.R;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个账号的任务配置快照：由 {@link TaskProfileStore} 的按账号配置组装成不可变列表，供 TaskExecutor 执行。
 *
 * <p>「任务配置按账号」之后本类不再读全局开关：{@link #fromPreferences(Context, String)} 必须给出目标账号，
 * 服务器相关的裁剪（国际服缺米游币/崩坏2/未定/云绝区零）也统一收在这个方法里，见其注释。
 */
public class TaskSettings {
    private final boolean dailyEnabled;
    private final boolean gameDailyEnabled;
    private final boolean sklandArknightsEnabled;
    private final boolean sklandEndfieldEnabled;
    /**
     * 云游戏「总开关」，与 {@link #cloudGames}（具体勾了哪几款）是两回事：总开关开着但一款未勾时
     * 列表里不会有云游戏任务，运行入口据此给出可读提示。
     */
    private final boolean cloudGameEnabled;
    /** 当前服务器类型：决定云游戏 token 的存储键（两服云原神 token 不通用）。 */
    private final boolean isOversea;
    private final String[] dailyForums;
    private final String[] gameDailyGames;
    private final String[] cloudGames;

    private TaskSettings(boolean dailyEnabled, boolean gameDailyEnabled,
                         boolean sklandArknightsEnabled, boolean sklandEndfieldEnabled,
                         boolean cloudGameEnabled, boolean isOversea,
                         String[] dailyForums, String[] gameDailyGames, String[] cloudGames) {
        this.dailyEnabled = dailyEnabled;
        this.gameDailyEnabled = gameDailyEnabled;
        this.sklandArknightsEnabled = sklandArknightsEnabled;
        this.sklandEndfieldEnabled = sklandEndfieldEnabled;
        this.cloudGameEnabled = cloudGameEnabled;
        this.isOversea = isOversea;
        this.dailyForums = dailyForums;
        this.gameDailyGames = gameDailyGames;
        this.cloudGames = cloudGames;
    }

    /**
     * 读取指定账号的任务配置并组装成执行快照。
     *
     * <p>数据源是 {@link TaskProfileStore}（按账号存，见 {@link TaskProfile}），旧版的全局开关只在
     * 「该账号首次继承模板」时被读取一次。profile 存的是原始勾选，<b>服务器相关的裁剪在这里做</b>：
     * 国际服没有米游币 stoken 链路、缺崩坏2/未定事件簿的签到活动、云绝区零没有海外钱包接口——
     * 这些差异按<b>该账号自己的</b>服务器类型（{@link UserManager#isOversea(String)}）统一处理，
     * 配置本身不因换服而丢勾选。
     *
     * <p>刻意不留 {@code fromPreferences(Context)} 重载：「取当前账号」的隐式行为在多账号批跑时
     * 会读到别人的签到计划，而编译期又不会报错。所有调用点都必须显式给出目标账号。
     *
     * @param userId 目标账号；为空时按默认配置返回（不落盘，见 {@link TaskProfileStore#load}）
     */
    public static TaskSettings fromPreferences(Context context, String userId) {
        // 服务器类型按账号取：同一台设备上可以同时存在国服/国际服账号，接口域名与 token 键都不同
        boolean isOversea = new UserManager(context).isOversea(userId);
        TaskProfile profile = TaskProfileStore.load(context, userId);

        // 国际服自动禁用米游币签到（需 stoken，Cookie 登录不提供）
        boolean dailyEnabled = !isOversea && profile.daily;
        boolean gameDailyEnabled = profile.gameDaily;
        // 森空岛：总开关与两款子项都要满足（子项独立保存，关掉总开关不会清掉勾选）
        boolean sklandArknightsEnabled = profile.skland && profile.sklandArknights;
        boolean sklandEndfieldEnabled = profile.skland && profile.sklandEndfield;
        boolean cloudGameEnabled = profile.cloudGame;

        // 输出顺序统一走 *_ORDER：profile 里的顺序取决于用户勾选先后，不能直接当执行/展示顺序
        ArrayList<String> daily = new ArrayList<>();
        for (String game : TaskProfileStore.DAILY_FORUM_ORDER)
            if (profile.dailyForums.contains(game)) daily.add(game);

        ArrayList<String> gameDaily = new ArrayList<>();
        for (String game : TaskProfileStore.GAME_SIGN_ORDER) {
            if (!profile.gameDailyGames.contains(game)) continue;
            if (isOversea && (TaskProfileStore.GAME_HR2.equals(game)
                    || TaskProfileStore.GAME_WEIDING.equals(game))) continue;
            gameDaily.add(game);
        }

        // 云游戏：云绝区零无国际服钱包接口，国服限定；云原神双服均可
        ArrayList<String> cloudGames = new ArrayList<>();
        if (cloudGameEnabled) {
            for (String game : TaskProfileStore.CLOUD_GAME_ORDER) {
                if (!profile.cloudGames.contains(game)) continue;
                if (isOversea && CloudGameDaily.GAME_ZZZ.equals(game)) continue;
                cloudGames.add(game);
            }
        }

        return new TaskSettings(dailyEnabled, gameDailyEnabled,
                sklandArknightsEnabled, sklandEndfieldEnabled, cloudGameEnabled, isOversea,
                daily.toArray(new String[0]), gameDaily.toArray(new String[0]),
                cloudGames.toArray(new String[0]));
    }

    public boolean isDailyEnabled() {
        return dailyEnabled;
    }

    public boolean isGameDailyEnabled() {
        return gameDailyEnabled;
    }

    public boolean isSklandArknightsEnabled() {
        return sklandArknightsEnabled;
    }

    public boolean isSklandEndfieldEnabled() {
        return sklandEndfieldEnabled;
    }

    public String[] getDailyForums() {
        return dailyForums;
    }

    public String[] getGameDailyGames() {
        return gameDailyGames;
    }

    /** 已勾选的云游戏（元素为 {@link CloudGameDaily#GAME_GENSHIN} / {@link CloudGameDaily#GAME_ZZZ}）。 */
    public String[] getCloudGames() {
        return cloudGames;
    }

    /** 云游戏总开关是否开启（与「是否勾选了具体游戏」无关，见 {@link #getCloudGames()}）。 */
    public boolean isCloudGameEnabled() {
        return cloudGameEnabled;
    }

    /**
     * 本快照所属账号的服务器类型。
     *
     * <p>执行层（{@link TaskExecutor} → 各签到模块 → {@code HeaderManager}）一律用它，<b>不要</b>在模块里
     * 再读全局 {@code is_oversea}：多账号跨服批跑时，全局值与「正在跑的这个账号」可能不同。
     * 这里已经是全链路唯一派生点——{@link #fromPreferences(Context, String)} 按账号算好，
     * 具体游戏/板块的裁剪也用它，执行层拿同一个值才不会出现「列表按国际服裁掉、请求按国服发」。
     */
    public boolean isOversea() {
        return isOversea;
    }

    /**
     * 所有任务大类（米游币/游戏签到/云游戏/森空岛）是否全关，用于提示未选择任何任务。
     * 云游戏按「总开关」判定（未勾具体游戏由运行入口另行提示）。
     */
    public boolean hasAnyTaskDisabled() {
        return !dailyEnabled && !gameDailyEnabled && !cloudGameEnabled
                && !sklandArknightsEnabled && !sklandEndfieldEnabled;
    }

    /**
     * 已启用任务列表（稳定 id + 当前语言显示名），顺序即列表显示顺序。
     *
     * <p>id 才是持久化/匹配键（见 {@link TaskIds}）；名称只用于展示，随语言变化不影响状态归属。
     */
    public List<TaskDescriptor> getTasks(Context context) {
        List<TaskDescriptor> tasks = new ArrayList<>();
        if (dailyEnabled)
            tasks.add(new TaskDescriptor(TaskIds.BBS_DAILY, context.getString(R.string.task_name_bbs_daily)));
        if (gameDailyEnabled && gameDailyGames != null)
            for (String g : gameDailyGames)
                tasks.add(new TaskDescriptor(TaskIds.gameSignIn(g),
                        context.getString(R.string.task_name_game_sign_in, MiHoYoBBSConstants.game_to_display_name(context, g))));
        if (cloudGames != null)
            for (String g : cloudGames)
                tasks.add(new TaskDescriptor(TaskIds.cloudGame(g), CloudGameDaily.taskName(context, g)));
        if (sklandArknightsEnabled)
            tasks.add(new TaskDescriptor(TaskIds.SKLAND_ARKNIGHTS, context.getString(R.string.task_name_skland_arknights)));
        if (sklandEndfieldEnabled)
            tasks.add(new TaskDescriptor(TaskIds.SKLAND_ENDFIELD, context.getString(R.string.task_name_skland_endfield)));
        return tasks;
    }

    /**
     * 已启用任务的 id 列表（顺序同 {@link #getTasks(Context)}）。
     * 复位、计数、完成判定都用它——这些动作只关心「哪些任务」，与语言无关，故无需 Context。
     */
    public List<String> getTaskIds() {
        List<String> ids = new ArrayList<>();
        if (dailyEnabled) ids.add(TaskIds.BBS_DAILY);
        if (gameDailyEnabled && gameDailyGames != null)
            for (String g : gameDailyGames) ids.add(TaskIds.gameSignIn(g));
        if (cloudGames != null)
            for (String g : cloudGames) ids.add(TaskIds.cloudGame(g));
        if (sklandArknightsEnabled) ids.add(TaskIds.SKLAND_ARKNIGHTS);
        if (sklandEndfieldEnabled) ids.add(TaskIds.SKLAND_ENDFIELD);
        return ids;
    }

    /**
     * 「任务 id → 是否具备执行条件」，供列表划线提示与执行期跳过。
     * 只覆盖需额外凭证的云游戏 token 与森空岛 token：米游币/游戏签到依赖登录态本身，
     * 未登录时用户列表里没有该账号、也就不会有它的任务，故恒为可执行。
     * 键与 {@link #getTasks(Context)} 的 id 严格同源，未出现在映射里即视为可执行。
     *
     * @param userId 当前选中用户；为空时云游戏与森空岛一律视为未配置
     */
    public Map<String, Boolean> getTaskReadiness(Context context, String userId) {
        Map<String, Boolean> readiness = new HashMap<>();
        boolean hasUser = userId != null && !userId.isEmpty();
        if (cloudGames != null) {
            for (String g : cloudGames) {
                String token = hasUser
                        ? tools.read(context, userId, CloudGameDaily.tokenKey(g, isOversea)) : null;
                readiness.put(TaskIds.cloudGame(g), CloudGameDaily.isValidToken(token));
            }
        }
        if (sklandArknightsEnabled || sklandEndfieldEnabled) {
            String token = hasUser ? tools.read(context, userId, SKLAND_COOKIE) : null;
            boolean ready = SklandDaily.isValidToken(token);
            // 两款游戏共用同一个森空岛 token，配置状态必然一致
            if (sklandArknightsEnabled)
                readiness.put(TaskIds.SKLAND_ARKNIGHTS, ready);
            if (sklandEndfieldEnabled)
                readiness.put(TaskIds.SKLAND_ENDFIELD, ready);
        }
        return readiness;
    }
}
