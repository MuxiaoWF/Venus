package com.muxiao.Venus.common;

import static com.muxiao.Venus.common.Constants.Prefs.BBS_VERSION_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.CONFIG_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.K2_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.LK2_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.SALT_4X_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.SALT_6X_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.UPDATE_TIME_LOCAL_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.UPDATE_TIME_PREF;

import android.content.Context;
import android.content.SharedPreferences;

import com.muxiao.Venus.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 米游社常量与配置管理：salt/K2/LK2 签名参数（支持云端更新）、
 * 游戏名称到 act_id/game_biz/forum_id 的映射、国服/国际服判断。
 * 内部游戏标识符使用中文（如"原神"、"星铁"），与 API 返回值一致。
 */
public class MiHoYoBBSConstants {
    /** 不可变快照，volatile 保证跨线程可见性，整体替换保证一致性 */
    public static class ConfigSnapshot {
        public final String SALT_6X, SALT_4X, LK2, K2, bbs_version;
        ConfigSnapshot(String s6x, String s4x, String lk2, String k2, String ver) {
            this.SALT_6X = s6x; this.SALT_4X = s4x; this.LK2 = lk2; this.K2 = k2; this.bbs_version = ver;
        }
    }
    private static volatile ConfigSnapshot snapshot = new ConfigSnapshot(
            "t0qEgfub6cvueAPgR5m9aQWWVciEer7v", "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs",
            "7cb250ce0015057d33ef639b0e30e432", "15f1f47145cb28fe26b18a6789080fe2", "2.108.0");

    // 实例字段：从 snapshot 读取，保持向后兼容
    public String SALT_6X, SALT_4X, LK2, K2, bbs_version;

    public static final String SALT_6X_final = "t0qEgfub6cvueAPgR5m9aQWWVciEer7v";
    public static final String SALT_4X_final = "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs";
    public static final String LK2_final = "7cb250ce0015057d33ef639b0e30e432";
    public static final String K2_final = "15f1f47145cb28fe26b18a6789080fe2";
    public static final String bbs_version_final = "2.108.0";
    public static final String update_time = "2026.06.09";
    public static final String PACKAGE_NAME = "com.mihoyo.hyperion";
    public static final String OS_PACKAGE_NAME = "com.mihoyo.hoyolab";
    private final Context context;

    public MiHoYoBBSConstants(Context context) {
        this.context = context;
        ConfigSnapshot s = snapshot;
        this.SALT_6X = s.SALT_6X; this.SALT_4X = s.SALT_4X;
        this.LK2 = s.LK2; this.K2 = s.K2; this.bbs_version = s.bbs_version;
        updateSalt();
    }

    /**
     * 将内部游戏名称标识符转换为可翻译的显示名称。
     *
     * @param context    上下文
     * @param gameName   内部标识符（如 "原神"、"星铁"）
     * @return 本地化的显示名称
     */
    public static String game_to_display_name(Context context, String gameName) {
        if (gameName == null) return "";
        switch (gameName) {
            case "原神": return context.getString(R.string.genshin_impact);
            case "绝区零": return context.getString(R.string.zenless_zone_zero);
            case "星铁": return context.getString(R.string.star_rail);
            case "崩坏3": return context.getString(R.string.honkai_impact_3);
            case "崩坏2": return context.getString(R.string.honkai_impact_2);
            case "未定事件簿": return context.getString(R.string.tears_of_themis);
            case "大别野": return context.getString(R.string.dabieye);
            case "崩坏因缘精灵": return context.getString(R.string.bengyuanling);
            default: return gameName;
        }
    }

    /**
     * 从云端获取最新配置并更新本地 SharedPreferences。
     * 应在后台线程调用。
     *
     * @return true 如果获取并更新成功
     */
    public static boolean update_config_from_web(Context context) {
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("User-Agent", "Venus/1.0");
            String response = tools.sendGetRequest(Constants.Urls.MUXIAO_MINE_UPDATE_SALT_URL, headers, null);
            if (response == null || response.isEmpty()) return false;

            com.google.gson.JsonObject data = com.google.gson.JsonParser.parseString(response).getAsJsonObject();
            if (data == null) return false;

            SharedPreferences configPrefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = configPrefs.edit();
            editor.putString(SALT_6X_PREF, getDataOrDefault(data, SALT_6X_PREF, SALT_6X_final));
            editor.putString(SALT_4X_PREF, getDataOrDefault(data, SALT_4X_PREF, SALT_4X_final));
            editor.putString(LK2_PREF, getDataOrDefault(data, LK2_PREF, LK2_final));
            editor.putString(K2_PREF, getDataOrDefault(data, K2_PREF, K2_final));
            editor.putString(BBS_VERSION_PREF, getDataOrDefault(data, BBS_VERSION_PREF, bbs_version_final));
            editor.putString(UPDATE_TIME_PREF, getDataOrDefault(data, UPDATE_TIME_PREF, update_time));
            editor.putString(UPDATE_TIME_LOCAL_PREF, getDataOrDefault(data, UPDATE_TIME_LOCAL_PREF, update_time));
            editor.apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getDataOrDefault(com.google.gson.JsonObject data, String key, String defaultValue) {
        if (data != null && data.has(key) && !data.get(key).isJsonNull())
            return data.get(key).getAsString();
        return defaultValue;
    }

    private void updateSalt() {
        SharedPreferences configPrefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        String s6x = configPrefs.getString(SALT_6X_PREF, SALT_6X_final);
        String s4x = configPrefs.getString(SALT_4X_PREF, SALT_4X_final);
        String lk2 = configPrefs.getString(LK2_PREF, LK2_final);
        String k2 = configPrefs.getString(K2_PREF, K2_final);
        String ver = configPrefs.getString(BBS_VERSION_PREF, bbs_version_final);
        // 原子替换快照，保证所有字段一致性
        snapshot = new ConfigSnapshot(s6x, s4x, lk2, k2, ver);
        // 同步实例字段（兼容直接字段访问）
        this.SALT_6X = s6x; this.SALT_4X = s4x; this.LK2 = lk2; this.K2 = k2; this.bbs_version = ver;
    }

    private static final String Honkai2_act_id = "e202203291431091";
    private static final String Honkai3rd_act_id = "e202306201626331";
    private static final String HonkaiStarRail_act_id = "e202304121516551";
    private static final String Genshin_act_id = "e202311201442471";
    private static final String TearsOfThemis_act_id = "e202202251749321";
    private static final String ZZZ_act_id = "e202406242138391";

    // 国际服 act_id
    private static final String OS_Honkai2_act_id = "e202203291431091";
    private static final String OS_Honkai3rd_act_id = "e202110291205111";
    private static final String OS_HonkaiStarRail_act_id = "e202303301540311";
    private static final String OS_Genshin_act_id = "e202102251931481";
    private static final String OS_TearsOfThemis_act_id = "e202202281857121";
    private static final String OS_ZZZ_act_id = "e202406031448091";

    /**
     * 获取游戏签到id
     *
     * @param name 游戏名称，可输入崩坏2、原神、崩坏3、绝区零、星铁、未定事件簿
     * @return 游戏id, 如无对应id则返回null
     */
    public static String name_to_act_id(String name) {
        return name_to_act_id(name, false);
    }

    /**
     * 获取游戏签到id
     *
     * @param name 游戏名称，可输入崩坏2、原神、崩坏3、绝区零、星铁、未定事件簿
     * @param isOversea 是否为国际服
     * @return 游戏id, 如无对应id则返回null
     */
    public static String name_to_act_id(String name, boolean isOversea) {
        if (isOversea) {
            switch (name) {
                case "崩坏2":
                    return OS_Honkai2_act_id;
                case "崩坏3":
                    return OS_Honkai3rd_act_id;
                case "星铁":
                    return OS_HonkaiStarRail_act_id;
                case "原神":
                    return OS_Genshin_act_id;
                case "未定事件簿":
                    return OS_TearsOfThemis_act_id;
                case "绝区零":
                    return OS_ZZZ_act_id;
                default:
                    return null;
            }
        }
        switch (name) {
            case "崩坏2":
                return Honkai2_act_id;
            case "崩坏3":
                return Honkai3rd_act_id;
            case "星铁":
                return HonkaiStarRail_act_id;
            case "原神":
                return Genshin_act_id;
            case "未定事件簿":
                return TearsOfThemis_act_id;
            case "绝区零":
                return ZZZ_act_id;
            default:
                return null;
        }
    }

    private static final List<Map<String, String>> bbs_list = new ArrayList<>(Arrays.asList(
            new HashMap<>() {{
                put("id", "1");
                put("forumId", "1");
                put("name", "崩坏3");
            }},
            new HashMap<>() {{
                put("id", "2");
                put("forumId", "26");
                put("name", "原神");
            }}, new HashMap<>() {{
                put("id", "3");
                put("forumId", "30");
                put("name", "崩坏2");
            }}, new HashMap<>() {{
                put("id", "4");
                put("forumId", "37");
                put("name", "未定事件簿");
            }}, new HashMap<>() {{
                put("id", "5");
                put("forumId", "34");
                put("name", "大别野");
            }}, new HashMap<>() {{
                put("id", "6");
                put("forumId", "52");
                put("name", "星铁");
            }}, new HashMap<>() {{
                put("id", "8");
                put("forumId", "57");
                put("name", "绝区零");
            }}, new HashMap<>() {{
                put("id", "9");
                put("forumId", "948");
                put("name", "崩坏因缘精灵");
            }}, new HashMap<>() {{
                put("id", "10");
                put("forumId", "950");
                put("name", "星布谷地");
            }}));

    private static final Map<String, String> name_to_game_id = new HashMap<>() {{
        put("崩坏2", "bh2_cn");
        put("崩坏3", "bh3_cn");
        put("未定事件簿", "nxx_cn");
        put("原神", "hk4e_cn");
        put("星铁", "hkrpg_cn");
        put("绝区零", "nap_cn");
        put("崩坏因缘精灵", "hna_cn");
    }};
    private static final Map<String, String> name_to_game_id_os = new HashMap<>() {{
        put("崩坏2", "bh2_os");
        put("崩坏3", "bh3_os");
        put("未定事件簿", "nxx_os");
        put("原神", "hk4e_global");
        put("星铁", "hkrpg_global");
        put("绝区零", "nap_global");
    }};


    /**
     * 获取游戏id（game_biz），可输入崩坏2、原神、崩坏3、绝区零、星铁、崩坏因缘精灵、未定事件簿
     *
     * @param isOversea 是否为国际服
     * @return game_biz
     */
    public static String name_to_game_id(String game_name, boolean isOversea) {
        if (isOversea) {
            return name_to_game_id_os.get(game_name);
        }
        return name_to_game_id.get(game_name);
    }

    /**
     * 获取游戏论坛id，可输入崩坏2、原神、崩坏3、绝区零、星铁、大别野、崩坏因缘精灵、星布谷地、未定事件簿
     *
     * @return id、forumID、名字的map组合
     */
    public static Map<String, String> name_to_forum_id(String name) {
        for (Map<String, String> map : bbs_list) {
            if (name.equals(map.get("name")))
                return map;
        }
        return null;
    }

    public static String game_to_role(String game) {
        switch (game) {
            case "原神":
                return "旅行者";
            case "崩坏3":
            case "崩坏2":
                return "舰长";
            case "星铁":
                return "开拓者";
            case "未定事件簿":
                return "律师";
            case "绝区零":
            default:
                return "绳匠";
        }
    }

    // 缓存 is_oversea 结果，避免每次调用都读 SharedPreferences
    private static volatile Boolean cachedIsOversea = null;

    public static boolean is_oversea(Context context) {
        if (cachedIsOversea != null) return cachedIsOversea;
        SharedPreferences prefs = context.getSharedPreferences(Constants.Prefs.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        cachedIsOversea = prefs.getInt(Constants.Prefs.SERVER_TYPE, 0) == 1;
        return cachedIsOversea;
    }

    /** 当用户切换服务器类型时调用，清除缓存 */
    public static void clearOverseaCache() {
        cachedIsOversea = null;
    }

    /**
     * 获取国际服游戏签到事件API的完整基础URL（含路径前缀）
     * 不同游戏使用不同的域名和路径：
     * 原神: sg-hk4e-api.hoyolab.com/event/sol
     * 星铁: sg-public-api.hoyolab.com/event/luna/os
     * 崩坏3: sg-public-api.hoyolab.com/event/mani
     * 绝区零: sg-act-nap-api.hoyolab.com/event/luna/zzz/os
     *
     * @param gameName 游戏名称
     * @return 完整的事件API基础URL，末尾不含斜杠
     */
    public static String get_event_base_url(String gameName) {
        switch (gameName) {
            case "原神":
                return "https://sg-hk4e-api.hoyolab.com/event/sol";
            case "崩坏3":
                return "https://sg-public-api.hoyolab.com/event/mani";
            case "绝区零":
                return "https://sg-act-nap-api.hoyolab.com/event/luna/zzz/os";
            case "星铁":
            default:
                return "https://sg-public-api.hoyolab.com/event/luna/os";
        }
    }
}
