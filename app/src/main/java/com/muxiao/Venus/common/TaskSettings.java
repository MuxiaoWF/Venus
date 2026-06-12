package com.muxiao.Venus.common;

import static com.muxiao.Venus.common.Constants.Prefs.*;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public class TaskSettings {
    private final boolean dailyEnabled;
    private final boolean gameDailyEnabled;
    private final boolean sklandEnabled;
    private final String[] dailyForums;
    private final String[] gameDailyGames;

    private TaskSettings(boolean dailyEnabled, boolean gameDailyEnabled, boolean sklandEnabled,
                         String[] dailyForums, String[] gameDailyGames) {
        this.dailyEnabled = dailyEnabled;
        this.gameDailyEnabled = gameDailyEnabled;
        this.sklandEnabled = sklandEnabled;
        this.dailyForums = dailyForums;
        this.gameDailyGames = gameDailyGames;
    }

    public static TaskSettings fromPreferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);

        boolean dailyEnabled = prefs.getBoolean(DAILY, true);
        boolean gameDailyEnabled = prefs.getBoolean(GAME_DAILY, true);
        boolean sklandEnabled = prefs.getBoolean(SKLAND_ENABLED, false);

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
        if (prefs.getBoolean(GAME_DAILY_HR2, false)) gameDaily.add("崩坏2");
        if (prefs.getBoolean(GAME_DAILY_WEIDING, false)) gameDaily.add("未定事件簿");

        return new TaskSettings(dailyEnabled, gameDailyEnabled, sklandEnabled,
                daily.toArray(new String[0]), gameDaily.toArray(new String[0]));
    }

    public boolean isDailyEnabled() {
        return dailyEnabled;
    }

    public boolean isGameDailyEnabled() {
        return gameDailyEnabled;
    }

    public boolean isSklandEnabled() {
        return sklandEnabled;
    }

    public String[] getDailyForums() {
        return dailyForums;
    }

    public String[] getGameDailyGames() {
        return gameDailyGames;
    }

    public boolean hasAnyTaskDisabled() {
        return !dailyEnabled && !gameDailyEnabled && !sklandEnabled;
    }

    public List<String> getTaskNames() {
        List<String> names = new ArrayList<>();
        if (dailyEnabled) names.add("米游币签到");
        if (gameDailyEnabled && gameDailyGames != null)
            for (String g : gameDailyGames) names.add(g + "签到");
        if (sklandEnabled) names.add("森空岛签到");
        return names;
    }
}
