package com.muxiao.Venus.common;

public class Constants {
    public static final int WRITE_PERMISSION_REQUEST_CODE = 10001;
    public static final int NOTIFICATION_ID_WORK = 50628;
    public static final int NOTIFICATION_ID_ERROR = 50629;

    public static class Urls {
        private static final String BASE_URL = "https://api-takumi.mihoyo.com";
        private static final String BBS_BASE_URL = "https://bbs-api.miyoushe.com";
        public static final String WEB_BASE_URL = "https://webstatic.mihoyo.com";
        public static final String ORIGIN_REFERER_URL = "https://act.mihoyo.com";
        public static final String APP_BASE_URL = "https://app.mihoyo.com";

        public static final String COOKIE_TOKEN_STOKEN_URL = BASE_URL + "/auth/api/getCookieAccountInfoBySToken";
        public static final String ACCOUNT_LIST_URL = BASE_URL + "/binding/api/getUserGameRolesByCookie";
        public static final String GAME_ROLES_URL = "https://api-takumi.miyoushe.com/binding/api/getUserGameRolesByStoken";
        public static final String GEN_AUTH_KEY_URL = BASE_URL + "/binding/api/genAuthKey";
        public static final String GEETEST_API1_URL = BBS_BASE_URL + "/misc/api/createVerification?is_high=true";
        public static final String GEETEST_API2_URL = BBS_BASE_URL + "/misc/api/verifyVerification";
        public static final String LOGIN_QR_URL = "https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/panda/qrcode/fetch";
        public static final String LOGIN_CHECK_URL = "https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/panda/qrcode/query";
        public static final String STOKEN_URL = BASE_URL + "/account/ma-cn-session/app/getTokenByGameToken";
        public static final String LTOKEN_URL = "https://passport-api.mihoyo.com/account/auth/api/getLTokenBySToken";
        public static final String FP_URL = "https://public-data-api.mihoyo.com/device-fp/api/getFp";

        public static final String BBS_TASK_URL = BBS_BASE_URL + "/apihub/sapi/getUserMissionsState";
        public static final String BBS_POST_URL = BBS_BASE_URL + "/post/api/getForumPostList";
        public static final String BBS_POST_FULL_URL = BBS_BASE_URL + "/post/api/getPostFull";
        public static final String BBS_SIGN_IN_URL = BBS_BASE_URL + "/apihub/app/api/signIn";
        public static final String BBS_LIKE_URL = BBS_BASE_URL + "/apihub/sapi/upvotePost";
        public static final String BBS_SHARE_URL = BBS_BASE_URL + "/apihub/api/getShareConf";
        public static final String BBS_IMAGE_URL = BBS_BASE_URL + "/post/api/getImagePostList?";
        public static final String BBS_GAME_BH2_REFERER_URL = WEB_BASE_URL + "/bbs/event/signin/bh2/index.html?bbs_auth_required=true&act_id=" + MiHoYoBBSConstants.name_to_act_id("崩坏2") + "&bbs_presentation_style=fullscreen&utm_source=bbs&utm_medium=mys&utm_campaign=icon";
        public static final String BBS_GAME_BH3_REFERER_URL = WEB_BASE_URL + "/bbs/event/signin/bh3/index.html?bbs_auth_required=true&act_id=" + MiHoYoBBSConstants.name_to_act_id("崩坏3") + "&bbs_presentation_style=fullscreen&utm_source=bbs&utm_medium=mys&utm_campaign=icon";
        public static final String BBS_GAME_WD_REFERER_URL = WEB_BASE_URL + "/bbs/event/signin/nxx/index.html?bbs_auth_required=true&bbs_presentation_style=fullscreen&act_id=" + MiHoYoBBSConstants.name_to_act_id("未定事件簿");
        public static final String BBS_GAME_REWARDS_ZZZ_URL = "https://act-nap-api.mihoyo.com/event/luna/zzz/home";
        public static final String BBS_GAME_REWARDS_ZZZ_INFO_URL = "https://act-nap-api.mihoyo.com/event/luna/zzz/info";
        public static final String BBS_GAME_REWARDS_ZZZ_SIGN_URL = "https://act-nap-api.mihoyo.com/event/luna/zzz/sign";
        public static final String BBS_GAME_REWARDS_URL = BASE_URL + "/event/luna/home";
        public static final String BBS_GAME_REWARDS_INFO_URL = BASE_URL + "/event/luna/info";
        public static final String BBS_GAME_REWARDS_SIGN_URL = BASE_URL + "/event/luna/sign";

        public static final String YSH_GACHA_URL = "https://public-operation-hk4e.mihoyo.com/gacha_info/api/getGachaLog?win_mode=fullscreen&authkey_ver=1&sign_type=2&auth_appid=webview_gacha&init_type=301&lang=zh-cn&region=cn_gf01&authkey=";
        public static final String ZZZ_GACHA_URL = "https://public-operation-nap.mihoyo.com/common/gacha_record/api/getGachaLog?authkey_ver=1&sign_type=2&auth_appid=webview_gacha&win_mode=fullscreen&init_log_gacha_type=2001&init_log_gacha_base_type=2&ui_layout=&button_mode=default&plat_type=3&authkey=";

        public static final String MUXIAO_MINE_UPDATE_SALT_URL = "https://muxiaowf.dpdns.org/api/salt";
        public static final String MUXIAO_MINE_UPDATE_LANZOU_URL = "https://wwzq.lanzouq.com/b00wn0dtfe";
        public static final String MUXIAO_MINE_UPDATE_URL = "https://api.github.com/repos/MuxiaoWF/Venus/releases/latest";
        public static final String MUXIAO_MINE_GITHUB_URL = "https://github.com/MuxiaoWF/Venus";
        public static final String MUXIAO_MINE_BLOG_URL = "https://muxiaowf.dpdns.org/";

        public static final String SKLAND_LOGIN_URL = "https://www.skland.com/login";
        public static final String SKLAND_COOKIE_URL = "https://web-api.skland.com/account/info/hg";
    }

    public static class Prefs {
        public static final String SETTINGS_PREFS_NAME = "settings_prefs"; // 设置prefs
        // 米游币和游戏签到
        public static final String DAILY = "daily_switch_button";
        public static final String DAILY_GENSHIN = "daily_checkbox_genshin";
        public static final String DAILY_ZZZ = "daily_checkbox_zzz";
        public static final String DAILY_SRG = "daily_checkbox_srg";
        public static final String DAILY_HR3 = "daily_checkbox_hr3";
        public static final String DAILY_HR2 = "daily_checkbox_hr2";
        public static final String DAILY_WEIDING = "daily_checkbox_weiding";
        public static final String DAILY_DABIEYE = "daily_checkbox_dabieye";
        public static final String DAILY_HNA = "daily_checkbox_hna";
        public static final String GAME_DAILY = "game_daily_switch_button";
        public static final String GAME_DAILY_GENSHIN = "game_daily_checkbox_genshin";
        public static final String GAME_DAILY_ZZZ = "game_daily_checkbox_zzz";
        public static final String GAME_DAILY_SRG = "game_daily_checkbox_srg";
        public static final String GAME_DAILY_HR3 = "game_daily_checkbox_hr3";
        public static final String GAME_DAILY_HR2 = "game_daily_checkbox_hr2";
        public static final String GAME_DAILY_WEIDING = "game_daily_checkbox_weiding";
        public static final String NOTIFICATION = "notification_switch"; // 通知
        public static final String AUTO_UPDATE_ENABLED = "auto_update_enabled"; // 自动更新
        public static final String SKLAND_ENABLED = "skland_enabled";
        public static final String SKLAND_COOKIE = "skland_cookie";

        // 主题
        public static final String THEME_PREFS_NAME = "theme_prefs";
        public static final String SELECTED_THEME_VARIANT = "selected_theme_variant";
        public static final String SELECTED_THEME = "selected_theme";

        // 米游社配置
        public static final String CONFIG_PREFS_NAME = "config_prefs";
        public static final String SALT_6X_PREF = "SALT_6X";
        public static final String SALT_4X_PREF = "SALT_4X";
        public static final String LK2_PREF = "LK2";
        public static final String K2_PREF = "K2";
        public static final String BBS_VERSION_PREF = "bbs_version";
        public static final String UPDATE_TIME_PREF = "update_time";
        public static final String UPDATE_TIME_LOCAL_PREF = "update_time_local";

        // 背景
        public static final String BACKGROUND_PREFS_NAME = "background_prefs";
        public static final String BACKGROUND_IMAGE_URI = "background_image_uri";
        public static final String BACKGROUND_ALPHA = "background_alpha";

        // Venus应用信息
        public static final String APP_INFO_PREFS_NAME = "app_info";
        public static final String LAST_VERSION = "last_version";
    }
}
