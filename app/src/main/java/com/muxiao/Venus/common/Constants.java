package com.muxiao.Venus.common;

/**
 * 全局常量：通知ID、广播Action、米游社/森空岛/抽卡相关URL、SharedPreferences键名。
 * Urls 内部类按国服/国际服分区，Prefs 内部类按功能模块分区。
 */
public class Constants {
    public static final int WRITE_PERMISSION_REQUEST_CODE = 10001;
    public static final int NOTIFICATION_ID_ERROR = 50629;
    public static final int NOTIFICATION_ID_PROGRESS = 50630;

    // 后台人机验证广播 Action
    public static final String ACTION_HANDLE_CAPTCHA = "ACTION_HANDLE_CAPTCHA";

    public static class Urls {
        // 国服 URL
        private static final String BASE_URL = "https://api-takumi.mihoyo.com";
        private static final String BBS_BASE_URL = "https://bbs-api.miyoushe.com";
        public static final String WEB_BASE_URL = "https://webstatic.mihoyo.com";
        public static final String ORIGIN_REFERER_URL = "https://act.mihoyo.com";
        public static final String APP_BASE_URL = "https://app.mihoyo.com";

        // 国际服 URL
        private static final String OS_BASE_URL = "https://api-os-takumi.hoyoverse.com";
        private static final String OS_BBS_BASE_URL = "https://bbs-api-os.hoyolab.com";
        public static final String OS_WEB_BASE_URL = "https://webstatic.hoyoverse.com";
        public static final String OS_ORIGIN_REFERER_URL = "https://act.hoyoverse.com";
        public static final String OS_APP_BASE_URL = "https://app.hoyolab.com";

        public static final String COOKIE_TOKEN_STOKEN_URL = BASE_URL + "/auth/api/getCookieAccountInfoBySToken";
        public static final String ACCOUNT_LIST_URL = BASE_URL + "/binding/api/getUserGameRolesByCookie";
        public static final String GAME_ROLES_URL = "https://api-takumi.miyoushe.com/binding/api/getUserGameRolesByStoken";
        public static final String GEN_AUTH_KEY_URL = BASE_URL + "/binding/api/genAuthKey";
        // 极验验证码：API1获取gt/challenge，API2二次验证
        public static final String GEETEST_API1_URL = BBS_BASE_URL + "/misc/api/createVerification?is_high=true";
        public static final String GEETEST_API2_URL = BBS_BASE_URL + "/misc/api/verifyVerification";
        // 扫码登录：国服已迁到 passport 的 ma-cn-passport（见下方 QR_LOGIN_*），
        // hk4e-sdk 的 combo/panda 旧接口仅国际服仍在用（见 OS_LOGIN_QR_URL / OS_LOGIN_CHECK_URL）
        public static final String STOKEN_URL = BASE_URL + "/account/ma-cn-session/app/getTokenByGameToken";

        // 国际服登录
        public static final String OS_LOGIN_QR_URL = "https://hk4e-sdk.hoyoverse.com/hk4e_global/combo/panda/qrcode/fetch";
        public static final String OS_LOGIN_CHECK_URL = "https://hk4e-sdk.hoyoverse.com/hk4e_global/combo/panda/qrcode/query";
        public static final String OS_STOKEN_URL = OS_BASE_URL + "/account/ma-cn-session/app/getTokenByGameToken";
        public static final String OS_LTOKEN_URL = "https://passport-api.hoyoverse.com/account/auth/api/getLTokenBySToken";
        public static final String LTOKEN_URL = "https://passport-api.mihoyo.com/account/auth/api/getLTokenBySToken";
        // 账号密码登录（国服 passport，返回的 token 即 stoken，user_info 中含 mid）
        public static final String PASSWORD_LOGIN_URL = "https://passport-api.mihoyo.com/account/ma-cn-passport/app/loginByPassword";
        // 扫码登录（国服 passport 版）：createQRLogin 取码，queryQRLoginStatus 轮询。
        // Confirmed 时响应里直接带 tokens[]（stoken）与 user_info.mid/aid，无需再走 getTokenByGameToken。
        public static final String QR_LOGIN_CREATE_URL = "https://passport-api.mihoyo.com/account/ma-cn-passport/app/createQRLogin";
        public static final String QR_LOGIN_QUERY_URL = "https://passport-api.mihoyo.com/account/ma-cn-passport/app/queryQRLoginStatus";
        public static final String FP_URL = "https://public-data-api.mihoyo.com/device-fp/api/getFp";

        public static final String BBS_TASK_URL = BBS_BASE_URL + "/apihub/sapi/getUserMissionsState";
        public static final String BBS_SIGN_IN_URL = BBS_BASE_URL + "/apihub/app/api/signIn";
        public static final String BBS_IMAGE_URL = BBS_BASE_URL + "/post/api/getImagePostList";
        public static final String BBS_GAME_BH2_REFERER_URL = WEB_BASE_URL + "/bbs/event/signin/bh2/index.html?bbs_auth_required=true&act_id=" + MiHoYoBBSConstants.name_to_act_id("崩坏2") + "&bbs_presentation_style=fullscreen&utm_source=bbs&utm_medium=mys&utm_campaign=icon";
        public static final String BBS_GAME_BH3_REFERER_URL = WEB_BASE_URL + "/bbs/event/signin/bh3/index.html?bbs_auth_required=true&act_id=" + MiHoYoBBSConstants.name_to_act_id("崩坏3") + "&bbs_presentation_style=fullscreen&utm_source=bbs&utm_medium=mys&utm_campaign=icon";
        public static final String BBS_GAME_WD_REFERER_URL = WEB_BASE_URL + "/bbs/event/signin/nxx/index.html?bbs_auth_required=true&bbs_presentation_style=fullscreen&act_id=" + MiHoYoBBSConstants.name_to_act_id("未定事件簿");
        public static final String BBS_GAME_REWARDS_ZZZ_URL = "https://act-nap-api.mihoyo.com/event/luna/zzz/home";
        public static final String BBS_GAME_REWARDS_ZZZ_INFO_URL = "https://act-nap-api.mihoyo.com/event/luna/zzz/info";
        public static final String BBS_GAME_REWARDS_ZZZ_SIGN_URL = "https://act-nap-api.mihoyo.com/event/luna/zzz/sign";
        public static final String BBS_GAME_REWARDS_URL = BASE_URL + "/event/luna/home";
        public static final String BBS_GAME_REWARDS_INFO_URL = BASE_URL + "/event/luna/info";
        public static final String BBS_GAME_REWARDS_SIGN_URL = BASE_URL + "/event/luna/sign";
        public static final String BBS_GAME_FORUM = BBS_BASE_URL + "/forum/api/getDiscussionByGame";
        public static final String BBS_GAME_FORUM_POST_LIST_TYPE_URL = BBS_BASE_URL + "/forum/api/getImagePostListType";
        public static final String BBS_GAME_HOT_POST_LIST_URL = BBS_BASE_URL + "/painter/api/getHotForumPostList";

        // 云游戏签到（钱包接口本身即签到：每日首次调用发放免费时长）。
        // 与游戏签到链路完全独立——只需云游戏 token（x-rpc-combo_token），
        // 不依赖 cookie/stoken，也不需要 DS 签名。
        public static final String CLOUD_GAME_GENSHIN_URL = "https://api-cloudgame.mihoyo.com/hk4e_cg_cn/wallet/wallet/get";
        public static final String CLOUD_GAME_ZZZ_URL = "https://cg-nap-api.mihoyo.com/nap_cn/cg/wallet/wallet/get";

        // 国际服 API
        public static final String OS_COOKIE_TOKEN_STOKEN_URL = OS_BASE_URL + "/auth/api/getCookieAccountInfoBySToken";
        public static final String OS_ACCOUNT_LIST_URL = OS_BASE_URL + "/binding/api/getUserGameRolesByCookie";
        public static final String OS_GAME_ROLES_URL = "https://api-os-takumi.hoyoverse.com/binding/api/getUserGameRolesByStoken";
        public static final String OS_GEN_AUTH_KEY_URL = OS_BASE_URL + "/binding/api/genAuthKey";
        public static final String OS_FP_URL = "https://public-data-api.hoyoverse.com/device-fp/api/getFp";

        public static final String OS_BBS_TASK_URL = OS_BBS_BASE_URL + "/apihub/sapi/getUserMissionsState";
        public static final String OS_BBS_SIGN_IN_URL = OS_BBS_BASE_URL + "/apihub/app/api/signIn";
        // 国际服云游戏签到：仅云原神（云绝区零国际服无对应钱包接口）
        public static final String OS_CLOUD_GAME_GENSHIN_URL = "https://sg-cg-api.hoyoverse.com/hk4e_global/cg/wallet/wallet/get";
        public static final String YSH_GACHA_URL = "https://public-operation-hk4e.mihoyo.com/gacha_info/api/getGachaLog?win_mode=fullscreen&authkey_ver=1&sign_type=2&auth_appid=webview_gacha&init_type=301&lang=zh-cn&region=cn_gf01&authkey=";
        public static final String ZZZ_GACHA_URL = "https://public-operation-nap.mihoyo.com/common/gacha_record/api/getGachaLog?authkey_ver=1&sign_type=2&auth_appid=webview_gacha&win_mode=fullscreen&init_log_gacha_type=2001&init_log_gacha_base_type=2&ui_layout=&button_mode=default&plat_type=3&authkey=";

        // 国际服抽卡链接
        public static final String OS_YSH_GACHA_URL = "https://public-operation-hk4e.hoyoverse.com/gacha_info/api/getGachaLog?win_mode=fullscreen&authkey_ver=1&sign_type=2&auth_appid=webview_gacha&init_type=301&lang=en-us&region=os_usa&authkey=";
        public static final String OS_ZZZ_GACHA_URL = "https://public-operation-nap.hoyoverse.com/common/gacha_record/api/getGachaLog?authkey_ver=1&sign_type=2&auth_appid=webview_gacha&win_mode=fullscreen&init_log_gacha_type=2001&init_log_gacha_base_type=2&ui_layout=&button_mode=default&plat_type=3&authkey=";

        // 崩坏：星穹铁道抽卡记录（VPN 抓包链路；B服与国服同 host，由 game_biz 区分渠道）
        public static final String HSR_GACHA_URL_CN = "https://public-operation-hkrpg.mihoyo.com/common/gacha_record/api/getGachaLog?authkey_ver=1&sign_type=2&auth_appid=webview_gacha&lang=zh-cn&authkey=";
        public static final String HSR_GACHA_URL_GLOBAL = "https://public-operation-hkrpg.hoyoverse.com/common/gacha_record/api/getGachaLog?authkey_ver=1&sign_type=2&auth_appid=webview_gacha&lang=en-us&authkey=";

        public static final String MUXIAO_MINE_UPDATE_SALT_URL = "https://muxiaowf.top/api/salt";
        /** 星铁 KCP 初始密钥表（HSR KeyStore）主源：stardb-exporter 社区维护（海外快、国内常不可达，短超时快速让位） */
        public static final String HSR_KEYS_UPDATE_URL_PRIMARY =
                "https://raw.githubusercontent.com/juliuskreutz/stardb-exporter/refs/heads/master/keys/hsr.json";
        /** 星铁 KCP 初始密钥表（HSR KeyStore）兜底源：自建服务，返回与 assets/hsr.json 同构的 {版本号: base64密钥} JSON */
        public static final String HSR_KEYS_UPDATE_URL = "https://muxiaowf.top/api/hsr";
        public static final String MUXIAO_MINE_UPDATE_LANZOU_URL = "https://wwzq.lanzouq.com/b00wn0dtfe";
        public static final String MUXIAO_MINE_UPDATE_URL = "https://api.github.com/repos/MuxiaoWF/Venus/releases/latest";
        public static final String MUXIAO_MINE_GITHUB_URL = "https://github.com/MuxiaoWF/Venus";
        public static final String MUXIAO_MINE_BLOG_URL = "https://muxiaowf.top/";

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
        public static final String DAILY_XINGBUGUDI = "daily_checkbox_xingbugudi";
        public static final String GAME_DAILY = "game_daily_switch_button";
        public static final String GAME_DAILY_GENSHIN = "game_daily_checkbox_genshin";
        public static final String GAME_DAILY_ZZZ = "game_daily_checkbox_zzz";
        public static final String GAME_DAILY_SRG = "game_daily_checkbox_srg";
        public static final String GAME_DAILY_HR3 = "game_daily_checkbox_hr3";
        public static final String GAME_DAILY_HR2 = "game_daily_checkbox_hr2";
        public static final String GAME_DAILY_WEIDING = "game_daily_checkbox_weiding";
        // 云游戏签到（云原神 / 云绝区零），token 按用户存于 user_{id}，键见 CloudGameDaily.tokenKey
        public static final String CLOUD_GAME_ENABLED = "cloud_game_switch_button";
        public static final String CLOUD_GAME_GENSHIN = "cloud_game_checkbox_genshin";
        public static final String CLOUD_GAME_ZZZ = "cloud_game_checkbox_zzz";
        public static final String NOTIFICATION = "notification_switch"; // 通知
        public static final String BACKGROUND_TASK_ENABLED = "background_task_enabled"; // 后台运行
        public static final String SCHEDULED_TASK_ENABLED = "scheduled_task_enabled"; // 定时签到
        public static final String SCHEDULED_TASK_TIME = "scheduled_task_time"; // 定时签到执行时间 HH:mm
        public static final String AUTO_UPDATE_ENABLED = "auto_update_enabled"; // 自动更新
        public static final String SKLAND_ENABLED = "skland_enabled";
        public static final String SKLAND_ARKNIGHTS_ENABLED = "skland_arknights_enabled";
        public static final String SKLAND_ENDFIELD_ENABLED = "skland_endfield_enabled";
        public static final String SKLAND_COOKIE = "skland_cookie";

        // 主题
        public static final String THEME_PREFS_NAME = "theme_prefs";
        public static final String SELECTED_THEME_VARIANT = "selected_theme_variant";
        public static final String SELECTED_THEME = "selected_theme";

        // 语言
        public static final String LANGUAGE_PREFS_NAME = "language_prefs";
        public static final String SELECTED_LANGUAGE = "selected_language";

        // 米游社配置
        public static final String CONFIG_PREFS_NAME = "config_prefs";
        public static final String SALT_6X_PREF = "SALT_6X";
        public static final String SALT_4X_PREF = "SALT_4X";
        public static final String LK2_PREF = "LK2";
        public static final String K2_PREF = "K2";
        public static final String SALT_PASSPORT_PREF = "SALT_PASSPORT";
        public static final String BBS_VERSION_PREF = "bbs_version";
        public static final String UPDATE_TIME_PREF = "update_time";
        public static final String UPDATE_TIME_LOCAL_PREF = "update_time_local";
        /** 星铁 KCP 初始密钥表云端更新差量（JSON：{版本号: base64密钥}），HsrKeyStore 写入 */
        public static final String HSR_KEYS_PREF = "hsr_keys";
        /** 星铁初始密钥表上次成功云端拉取的时间（epoch 毫秒字符串），HsrKeyStore 写入 */
        public static final String HSR_KEYS_UPDATE_TIME_PREF = "hsr_keys_update_time";

        // 背景
        public static final String BACKGROUND_PREFS_NAME = "background_prefs";
        public static final String BACKGROUND_IMAGE_URI = "background_image_uri";
        public static final String BACKGROUND_ALPHA = "background_alpha";
        // 背景淡化风格预设档（0=轻盈 1=标准 2=浓郁），控制图像预处理的饱和度/亮度参数
        public static final String BACKGROUND_PRESET = "background_preset";

        // Venus应用信息
        public static final String APP_INFO_PREFS_NAME = "app_info";
        public static final String LAST_VERSION = "last_version";

        // 后台人机验证标记
        public static final String PREF_CAPTCHA_PENDING = "captcha_pending";

        // 服务器类型
        public static final String SERVER_TYPE = "server_type"; // 0=国服, 1=国际服
    }
}
