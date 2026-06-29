package com.muxiao.Venus.common;

import static com.muxiao.Venus.common.Constants.Prefs.DAILY;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_DABIEYE;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_GENSHIN;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HNA;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HR2;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HR3;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_SRG;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_WEIDING;
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

import com.muxiao.Venus.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 SharedPreferences 读取用户配置的任务开关，组装成不可变的任务列表。
 * 国际服自动禁用米游币签到（需要stoken，Cookie登录不提供）和部分游戏签到。
 */
public class TaskSettings {
    private final boolean dailyEnabled;
    private final boolean gameDailyEnabled;
    private final boolean sklandArknightsEnabled;
    private final boolean sklandEndfieldEnabled;
    private final String[] dailyForums;
    private final String[] gameDailyGames;

    private TaskSettings(boolean dailyEnabled, boolean gameDailyEnabled,
                         boolean sklandArknightsEnabled, boolean sklandEndfieldEnabled,
                         String[] dailyForums, String[] gameDailyGames) {
        this.dailyEnabled = dailyEnabled;
        this.gameDailyEnabled = gameDailyEnabled;
        this.sklandArknightsEnabled = sklandArknightsEnabled;
        this.sklandEndfieldEnabled = sklandEndfieldEnabled;
        this.dailyForums = dailyForums;
        this.gameDailyGames = gameDailyGames;
    }

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

        ArrayList<String> gameDaily = new ArrayList<>();
        if (prefs.getBoolean(GAME_DAILY_GENSHIN, false)) gameDaily.add("原神");
        if (prefs.getBoolean(GAME_DAILY_ZZZ, false)) gameDaily.add("绝区零");
        if (prefs.getBoolean(GAME_DAILY_SRG, false)) gameDaily.add("星铁");
        if (prefs.getBoolean(GAME_DAILY_HR3, false)) gameDaily.add("崩坏3");
        if (!isOversea && prefs.getBoolean(GAME_DAILY_HR2, false)) gameDaily.add("崩坏2");
        if (!isOversea && prefs.getBoolean(GAME_DAILY_WEIDING, false)) gameDaily.add("未定事件簿");

        return new TaskSettings(dailyEnabled, gameDailyEnabled,
                sklandArknightsEnabled, sklandEndfieldEnabled,
                daily.toArray(new String[0]), gameDaily.toArray(new String[0]));
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

    public boolean hasAnyTaskDisabled() {
        return !dailyEnabled && !gameDailyEnabled && !sklandArknightsEnabled && !sklandEndfieldEnabled;
    }

    public List<String> getTaskNames(Context context) {
        List<String> names = new ArrayList<>();
        if (dailyEnabled) names.add(context.getString(R.string.task_name_bbs_daily));
        if (gameDailyEnabled && gameDailyGames != null)
            for (String g : gameDailyGames)
                names.add(context.getString(R.string.task_name_game_sign_in, MiHoYoBBSConstants.game_to_display_name(context, g)));
        if (sklandArknightsEnabled) names.add(context.getString(R.string.task_name_skland_arknights));
        if (sklandEndfieldEnabled) names.add(context.getString(R.string.task_name_skland_endfield));
        return names;
    }
}
