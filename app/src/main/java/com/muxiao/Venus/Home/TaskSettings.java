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
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_COOKIE;
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_ENDFIELD_ENABLED;

import android.content.Context;
import android.content.SharedPreferences;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 SharedPreferences 读取用户配置的任务开关，组装成不可变的任务列表。
 * 国际服自动禁用米游币签到（需要stoken，Cookie登录不提供）和部分游戏签到。
 */
public class TaskSettings {
    private final boolean dailyEnabled;
    private final boolean gameDailyEnabled;
    private final boolean sklandArknightsEnabled;
    private final boolean sklandEndfieldEnabled;
    /**
     * 云游戏「总开关」状态，与 {@link #cloudGames}（具体勾选了哪几款）是两回事：
     * 总开关开着但一款都没勾时，列表里不会有云游戏任务，运行入口会据此给出可读提示。
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
     * 从 SharedPreferences 组装不可变任务配置；国际服自动禁用米游币签到与部分国服专属游戏签到。
     */
    public static TaskSettings fromPreferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);

        // 国际服禁用米游币签到（需要stoken，Cookie登录不提供）
        boolean isOversea = MiHoYoBBSConstants.is_oversea(context);
        boolean dailyEnabled = !isOversea && prefs.getBoolean(DAILY, true);
        boolean gameDailyEnabled = prefs.getBoolean(GAME_DAILY, true);
        boolean sklandMasterEnabled = prefs.getBoolean(SKLAND_ENABLED, false);
        boolean sklandArknightsEnabled = sklandMasterEnabled && prefs.getBoolean(SKLAND_ARKNIGHTS_ENABLED, false);
        boolean sklandEndfieldEnabled = sklandMasterEnabled && prefs.getBoolean(SKLAND_ENDFIELD_ENABLED, false);

        ArrayList<String> daily = new ArrayList<>();
        if (prefs.getBoolean(DAILY_GENSHIN, false)) daily.add("原神");
        if (prefs.getBoolean(DAILY_ZZZ, false)) daily.add("绝区零");
        if (prefs.getBoolean(DAILY_SRG, false)) daily.add("星铁");
        if (prefs.getBoolean(DAILY_HR3, false)) daily.add("崩坏3");
        if (prefs.getBoolean(DAILY_HR2, false)) daily.add("崩坏2");
        if (prefs.getBoolean(DAILY_WEIDING, false)) daily.add("未定事件簿");
        if (prefs.getBoolean(DAILY_DABIEYE, true)) daily.add("大别野");
        if (prefs.getBoolean(DAILY_HNA, false)) daily.add("崩坏因缘精灵");
        if (prefs.getBoolean(DAILY_XINGBUGUDI, false)) daily.add("星布谷地");

        ArrayList<String> gameDaily = new ArrayList<>();
        if (prefs.getBoolean(GAME_DAILY_GENSHIN, false)) gameDaily.add("原神");
        if (prefs.getBoolean(GAME_DAILY_ZZZ, false)) gameDaily.add("绝区零");
        if (prefs.getBoolean(GAME_DAILY_SRG, false)) gameDaily.add("星铁");
        if (prefs.getBoolean(GAME_DAILY_HR3, false)) gameDaily.add("崩坏3");
        if (!isOversea && prefs.getBoolean(GAME_DAILY_HR2, false)) gameDaily.add("崩坏2");
        if (!isOversea && prefs.getBoolean(GAME_DAILY_WEIDING, false)) gameDaily.add("未定事件簿");

        // 云游戏：云绝区零无国际服钱包接口，国服限定；云原神双服均可
        boolean cloudGameEnabled = prefs.getBoolean(CLOUD_GAME_ENABLED, false);
        ArrayList<String> cloudGames = new ArrayList<>();
        if (cloudGameEnabled) {
            if (prefs.getBoolean(CLOUD_GAME_GENSHIN, false)) cloudGames.add(CloudGameDaily.GAME_GENSHIN);
            if (!isOversea && prefs.getBoolean(CLOUD_GAME_ZZZ, false)) cloudGames.add(CloudGameDaily.GAME_ZZZ);
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
     * 是否所有任务大类（米游币/游戏签到/云游戏/森空岛）均被关闭，用于提示用户未选择任何任务。
     * 云游戏按「总开关」判定：开了总开关即视为用户已选择该大类（未勾具体游戏由运行入口另行提示）。
     */
    public boolean hasAnyTaskDisabled() {
        return !dailyEnabled && !gameDailyEnabled && !cloudGameEnabled
                && !sklandArknightsEnabled && !sklandEndfieldEnabled;
    }

    /**
     * 返回已启用任务的本地化名称列表，用于通知与日志展示。
     */
    public List<String> getTaskNames(Context context) {
        List<String> names = new ArrayList<>();
        if (dailyEnabled) names.add(context.getString(R.string.task_name_bbs_daily));
        if (gameDailyEnabled && gameDailyGames != null)
            for (String g : gameDailyGames)
                names.add(context.getString(R.string.task_name_game_sign_in, MiHoYoBBSConstants.game_to_display_name(context, g)));
        if (cloudGames != null)
            for (String g : cloudGames)
                names.add(CloudGameDaily.taskName(context, g));
        if (sklandArknightsEnabled) names.add(context.getString(R.string.task_name_skland_arknights));
        if (sklandEndfieldEnabled) names.add(context.getString(R.string.task_name_skland_endfield));
        return names;
    }

    /**
     * 返回「任务名 → 是否具备执行条件」映射，供任务列表划线提示与执行期跳过使用。
     *
     * <p>只覆盖<b>需要额外凭证</b>的两类：云游戏 token、森空岛 token。米游币签到与游戏签到
     * 依赖的是账号登录态本身——未登录时用户列表里没有该账号、也就不会有它的任务，故恒为可执行。
     *
     * <p>键为本地化任务名，与 {@link #getTaskNames(Context)} 严格同源（同一批 getString 口径），
     * 调用方按名字查即可；未出现在映射里的任务视为可执行。
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
                readiness.put(CloudGameDaily.taskName(context, g), CloudGameDaily.isValidToken(token));
            }
        }
        if (sklandArknightsEnabled || sklandEndfieldEnabled) {
            String token = hasUser ? tools.read(context, userId, SKLAND_COOKIE) : null;
            boolean ready = SklandDaily.isValidToken(token);
            // 两款游戏共用同一个森空岛 token，配置状态必然一致
            if (sklandArknightsEnabled)
                readiness.put(context.getString(R.string.task_name_skland_arknights), ready);
            if (sklandEndfieldEnabled)
                readiness.put(context.getString(R.string.task_name_skland_endfield), ready);
        }
        return readiness;
    }
}
