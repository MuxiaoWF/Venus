package com.muxiao.Venus.common;

import static com.muxiao.Venus.common.Constants.Prefs.*;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MiHoYoBBSConstants {
    public String SALT_6X;
    public String SALT_4X;
    public String LK2;
    public String K2;
    public String bbs_version;
    public static final String SALT_6X_final = "t0qEgfub6cvueAPgR5m9aQWWVciEer7v";
    public static final String SALT_4X_final = "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs";
    public static final String LK2_final = "DlOUwIupfU6YespEUWDJmXtutuXV6owG";
    public static final String K2_final = "b0EofkfMKq2saWV9fwux18J5vzcFTlex";
    public static final String bbs_version_final = "2.99.1";
    public static final String update_time = "2026.01.12";
    public static final String PACKAGE_NAME = "com.mihoyo.hyperion";
    private final Context context;

    public MiHoYoBBSConstants(Context context) {
        this.context = context;
        updateSalt();
    }

    private void updateSalt() {
        // 尝试从配置中获取值
        SharedPreferences configPrefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        SALT_6X = configPrefs.getString(SALT_6X_PREF, SALT_6X_final);
        SALT_4X = configPrefs.getString(SALT_4X_PREF, SALT_4X_final);
        LK2 = configPrefs.getString(LK2_PREF, LK2_final);
        K2 = configPrefs.getString(K2_PREF, K2_final);
        bbs_version = configPrefs.getString(BBS_VERSION_PREF, bbs_version_final);
    }

    private static final String Honkai2_act_id = "e202203291431091";
    private static final String Honkai3rd_act_id = "e202306201626331";
    private static final String HonkaiStarRail_act_id = "e202304121516551";
    private static final String Genshin_act_id = "e202311201442471";
    private static final String TearsOfThemis_act_id = "e202202251749321";
    private static final String ZZZ_act_id = "e202406242138391";

    /**
     * 获取游戏签到id
     *
     * @param name 游戏名称，可输入崩坏2、原神、崩坏3、绝区零、星铁、未定事件簿
     * @return 游戏id, 如无对应id则返回null
     */
    public static String name_to_act_id(String name) {
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
            default:
                return ZZZ_act_id;

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

    private static final Map<String, String> game_id_to_name = new HashMap<>() {{
        put("bh2_cn", "崩坏2");
        put("bh3_cn", "崩坏3");
        put("nxx_cn", "未定事件簿");
        put("hk4e_cn", "原神");
        put("hkrpg_cn", "星铁");
        put("nap_cn", "绝区零");
    }};
    private static final Map<String, String> name_to_game_id = new HashMap<>() {{
        put("崩坏2", "bh2_cn");
        put("崩坏3", "bh3_cn");
        put("未定事件簿", "nxx_cn");
        put("原神", "hk4e_cn");
        put("星铁", "hkrpg_cn");
        put("绝区零", "nap_cn");
        put("崩坏因缘精灵", "hna_cn");
    }};

    /**
     * 获取游戏id（game_biz），可输入崩坏2、原神、崩坏3、绝区零、星铁、崩坏因缘精灵、未定事件簿
     *
     * @return game_biz
     */
    public static String name_to_game_id(String game_name) {
        return name_to_game_id.get(game_name);
    }

    /**
     * 获取游戏名称，可输入game_biz
     *
     * @return 游戏名字：崩坏2、原神、崩坏3、绝区零、星铁、崩坏因缘精灵、未定事件簿。若不存在返回输入值
     */
    public static String game_id_to_name(String game_id) {
        return game_id_to_name.containsKey(game_id) ? game_id_to_name.get(game_id) : game_id;
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

}
