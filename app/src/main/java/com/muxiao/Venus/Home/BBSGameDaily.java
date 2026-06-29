package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.sendGetRequest;
import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 游戏每日签到（原神/星铁/绝区零/崩坏3等）。
 * 国服：刷新CookieToken → 获取账号列表 → 遍历签到（支持验证码）。
 * 国际服：直接调用海外签到API（无需验证码，流程更简单）。
 */
public class BBSGameDaily {

    private static final int MAX_RETRIES = 3;
    private static final int SIGN_DELAY_MIN_MS = 2000;
    private static final int SIGN_DELAY_RANGE_MS = 7000;
    private static final int RATE_LIMIT_COOLDOWN_MS = 10_000;

    private final tools.StatusNotifier statusNotifier;
    private final Map<String, String> cookies = new HashMap<>();
    private final String gameName;
    private final String displayName;
    private final String actId;
    private final List<Map<String, String>> accountList;
    private List<Map<String, Object>> checkinRewards = new ArrayList<>();
    private final Context context;
    private final String userId;
    private final Notification notification;
    private final HeaderManager headerManager;
    private final CaptchaVerificationHelper captchaHelper;
    private final boolean isOversea;

    /**
     * @param gameName 游戏名
     */
    public BBSGameDaily(Context context, String userId, String gameName, tools.StatusNotifier statusNotifier, GeetestController gt3Controller) {
        this.gameName = gameName;
        this.displayName = MiHoYoBBSConstants.game_to_display_name(context, gameName);
        this.context = context;
        this.userId = userId;
        this.statusNotifier = statusNotifier;
        this.notification = new Notification(context);
        this.captchaHelper = new CaptchaVerificationHelper(context, gt3Controller, statusNotifier, this.notification);
        this.headerManager = new HeaderManager(context);
        this.isOversea = MiHoYoBBSConstants.is_oversea(context);
        this.actId = MiHoYoBBSConstants.name_to_act_id(gameName, isOversea);
        String gameId = MiHoYoBBSConstants.name_to_game_id(gameName, isOversea);
        if (isOversea) {
            // 国际服直接使用保存的cookie，服务器通过cookie识别账号
            String savedCookie = tools.read(context, userId, "cookie");
            if (savedCookie != null && !savedCookie.isEmpty()) {
                cookies.put("Cookie", savedCookie);
            } else {
                String cookieToken = tools.read(context, userId, "cookie_token");
                if (cookieToken == null) getCookieTokenByStoken();
                cookies.put("Cookie", buildGameCookie(tools.read(context, userId, "cookie_token")));
            }
            // 国际服无需获取账号列表，直接签到
            this.accountList = new ArrayList<>();
            this.checkinRewards = getCheckinRewards();
        } else {
            // 国服设置游戏特定头部
            switch (gameName) {
                case "崩坏2":
                    cookies.put("Referer", Constants.Urls.BBS_GAME_BH2_REFERER_URL);
                    break;
                case "崩坏3":
                    cookies.put("Referer", Constants.Urls.BBS_GAME_BH3_REFERER_URL);
                    break;
                case "星铁":
                    cookies.put("Origin", Constants.Urls.ORIGIN_REFERER_URL);
                    break;
                case "原神":
                    cookies.put("Origin", Constants.Urls.ORIGIN_REFERER_URL);
                    cookies.put("x-rpc-signgame", "hk4e");
                    break;
                case "绝区零":
                    cookies.put("Referer", Constants.Urls.ORIGIN_REFERER_URL);
                    cookies.put("X-Rpc-Signgame", "zzz");
                    break;
                case "未定事件簿":
                    cookies.put("Referer", Constants.Urls.BBS_GAME_WD_REFERER_URL);
                    break;
            }
            String cookieToken = tools.read(context, userId, "cookie_token");
            if (cookieToken == null) getCookieTokenByStoken();
            cookies.put("Cookie", buildGameCookie(tools.read(context, userId, "cookie_token")));
            this.accountList = getAccountList(gameId);
            if (!accountList.isEmpty())
                this.checkinRewards = getCheckinRewards();
        }
    }

    private Map<String, String> getGameLoginHeaders() {
        Map<String, String> gameLoginHeaders = headerManager.get_game_login_headers();
        gameLoginHeaders.putAll(cookies);
        // 原神和绝区零需要 x-rpc-signgame 头
        if (gameName.equals("原神")) gameLoginHeaders.put("x-rpc-signgame", "hk4e");
        if (gameName.equals("绝区零")) gameLoginHeaders.put("x-rpc-signgame", "zzz");
        return gameLoginHeaders;
    }

    /**
     * 更新cookieToken
     */
    private String updateCookieToken() {
        if (isOversea) {
            // 国际服cookie无法自动刷新，需重新登录
            String msg = context.getString(R.string.game_cookie_token_failed);
            String notifTitle = context.getString(R.string.notif_title_game_sign, displayName);
            statusNotifier.notifyListeners(notifTitle + " " + msg);
            notification.sendErrorNotification(notifTitle, msg);
            throw new RuntimeException(msg);
        }
        String notifTitle = context.getString(R.string.notif_title_game_sign, displayName);
        String taskName = context.getString(R.string.task_name_game_sign_in, displayName);
        statusNotifier.notifyListeners(notifTitle + " " + context.getString(R.string.game_cookie_token_expired));
        notification.sendErrorNotification(notifTitle, context.getString(R.string.game_cookie_token_expired));
        String newToken = getCookieTokenByStoken();
        statusNotifier.notifyListeners(taskName + " " + context.getString(R.string.game_cookie_token_refreshed));
        tools.write(context, userId, "cookie_token", newToken);
        return newToken;
    }

    /**
     * 通过stoken获取cookieToken
     */
    public String getCookieTokenByStoken() {
        String stoken = tools.read(context, userId, "stoken");
        String ltoken = tools.read(context, userId, "ltoken");
        String stuid = tools.read(context, userId, "stuid");
        String token = stoken != null ? stoken : ltoken;
        if ((token == null || token.isEmpty()) && (stuid == null || stuid.isEmpty())) {
            notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), context.getString(R.string.game_stoken_suid_empty));
            throw new RuntimeException(context.getString(R.string.game_stoken_suid_empty));
        }
        Map<String, String> gameLoginHeaders = getGameLoginHeaders();
        String tokenKey = stoken != null ? "stoken" : "ltoken";
        String mid = tools.read(context, userId, "mid");
        gameLoginHeaders.put("Cookie", "stuid=" + stuid + ";" + tokenKey + "=" + token + (mid != null ? ";mid=" + mid : ""));
        String cookieTokenUrl = isOversea ? Constants.Urls.OS_COOKIE_TOKEN_STOKEN_URL : Constants.Urls.COOKIE_TOKEN_STOKEN_URL;
        String response = sendGetRequest(cookieTokenUrl, gameLoginHeaders, null);
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        if (res.get("retcode").getAsInt() != 0) {
            notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), context.getString(R.string.game_cookie_token_failed));
            throw new RuntimeException(context.getString(R.string.game_cookie_token_failed));
        }
        tools.write(context, userId, "cookie_token", res.get("data").getAsJsonObject().get("cookie_token").getAsString());
        return res.get("data").getAsJsonObject().get("cookie_token").getAsString();
    }

    /**
     * 获取米哈游账号绑定的指定游戏账号列表
     *
     * @param gameId 游戏id
     * @return 账号列表List<Map < String, String>>, key为region, uid, nickname.
     */
    protected List<Map<String, String>> getAccountList(String gameId) {
        return getAccountList(gameId, false);
    }

    protected List<Map<String, String>> getAccountList(String gameId, boolean retried) {
        statusNotifier.notifyListeners(context.getString(R.string.notif_title_game_sign, displayName) + " " + context.getString(R.string.snack_loading));
        Map<String, String> headers = getGameLoginHeaders();
        String accountListUrl = isOversea ? Constants.Urls.OS_ACCOUNT_LIST_URL : Constants.Urls.ACCOUNT_LIST_URL;
        String response = sendGetRequest(accountListUrl, headers, Map.of("game_biz", gameId));
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        // CookieToken失效，刷新CookieToken
        if (data.get("retcode").getAsInt() == -100) {
            if (retried) {
                statusNotifier.notifyListeners(context.getString(R.string.notif_title_game_sign, displayName) + " " + context.getString(R.string.game_cookie_token_failed));
                throw new RuntimeException(context.getString(R.string.game_cookie_token_failed));
            }
            String newCookie = updateCookieToken();
            if (newCookie == null) {
                statusNotifier.notifyListeners(context.getString(R.string.notif_title_game_sign, displayName) + " " + context.getString(R.string.game_cookie_token_failed));
                throw new RuntimeException(context.getString(R.string.game_cookie_token_failed));
            }
            cookies.put("Cookie", buildGameCookie(newCookie));
            return getAccountList(gameId, true);
        } else if (data.get("retcode").getAsInt() != 0) { //获取账号列表失败
            statusNotifier.notifyListeners(context.getString(R.string.notif_title_game_sign, displayName) + " " + context.getString(R.string.game_get_accounts_failed));
            notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), context.getString(R.string.game_get_accounts_failed));
            return new ArrayList<>();
        } else {   // 获取结果中的账号列表
            List<Map<String, String>> accountList = new ArrayList<>();
            for (var entry : data.getAsJsonObject("data").getAsJsonArray("list").asList()) {
                JsonObject account = entry.getAsJsonObject();
                Map<String, String> accountInfo = new HashMap<>();
                accountInfo.put("nickname", account.get("nickname").getAsString());
                accountInfo.put("game_uid", account.get("game_uid").getAsString());
                accountInfo.put("region", account.get("region").getAsString());
                accountInfo.put("game_biz", account.get("game_biz").getAsString());
                accountList.add(accountInfo);
            }
            tools.write(context, userId, gameId + "_user", new Gson().toJson(accountList));
            statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_accounts_found, accountList.size()));
            return accountList;
        }
    }

    /**
     * 获取签到奖励列表
     *
     * @return 签到奖励列表List<Map < String, Object>>, key为name名称, cnt数量.
     */
    private List<Map<String, Object>> getCheckinRewards() {
        statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_getting_rewards));
        for (int i = 0; i < MAX_RETRIES; i++) {
            String rewards_api;
            if (isOversea) {
                rewards_api = MiHoYoBBSConstants.get_event_base_url(gameName) + "/home";
            } else if (gameName.equals("绝区零")) {
                rewards_api = Constants.Urls.BBS_GAME_REWARDS_ZZZ_URL;
            } else {
                rewards_api = Constants.Urls.BBS_GAME_REWARDS_URL;
            }
            String lang = isOversea ? "en-us" : "zh-cn";
            String response = sendGetRequest(rewards_api, getGameLoginHeaders(), Map.of("lang", lang, "act_id", actId));
            JsonObject data = JsonParser.parseString(response).getAsJsonObject();
            if (data.get("retcode").getAsInt() == 0) {
                JsonArray awardsArray = data.getAsJsonObject("data").getAsJsonArray("awards");
                List<Map<String, Object>> rewards = new ArrayList<>();
                for (JsonElement award : awardsArray) {
                    JsonObject awardObject = award.getAsJsonObject();
                    Map<String, Object> rewardMap = new HashMap<>();
                    rewardMap.put("name", awardObject.get("name").getAsString());
                    rewardMap.put("cnt", awardObject.get("cnt").getAsString());
                    rewards.add(rewardMap);
                }
                return rewards;
            } else  // 没能成功获取
                statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_rewards_retry, i + 1));
            try {
                tools.randomDelay(SIGN_DELAY_MIN_MS, SIGN_DELAY_RANGE_MS);
            } catch (InterruptedException e) {
                throw new RuntimeException(context.getString(R.string.game_thread_error, e.toString()));
            }
        }
        statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_rewards_max_retry));
        return new ArrayList<>();
    }

    /**
     * 获取账号签到信息
     *
     * @param region 游戏区
     * @param uid    游戏uid
     * @return 签到信息Map<String, Object>, key为is_sign是否已签到, remain_days剩余天数, sign_cnt已签到天数, first_bind是否首次绑定.
     */
    private Map<String, Object> isSign(String region, String uid) {
        return isSign(region, uid, false);
    }

    private Map<String, Object> isSign(String region, String uid, boolean retried) {
        Map<String, String> gameLoginHeaders = getGameLoginHeaders();
        String isSignApi;
        if (isOversea) {
            isSignApi = MiHoYoBBSConstants.get_event_base_url(gameName) + "/info";
        } else if (gameName.equals("绝区零")) {
            isSignApi = Constants.Urls.BBS_GAME_REWARDS_ZZZ_INFO_URL;
        } else {
            isSignApi = Constants.Urls.BBS_GAME_REWARDS_INFO_URL;
        }
        String lang = isOversea ? "en-us" : "zh-cn";
        Map<String, String> params = new java.util.HashMap<>();
        params.put("lang", lang);
        params.put("act_id", actId);
        if (!isOversea) {
            params.put("region", region);
            params.put("uid", uid);
        }
        String response = sendGetRequest(isSignApi, gameLoginHeaders, params);
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        // CookieToken需要刷新（仅国服）
        if (data.get("retcode").getAsInt() == -100) {
            if (isOversea || retried) {
                notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), context.getString(R.string.game_cookie_token_failed));
                throw new RuntimeException(context.getString(R.string.game_cookie_token_failed));
            }
            String newCookie = updateCookieToken();
            cookies.put("Cookie", buildGameCookie(newCookie));
            return isSign(region, uid, true);
        } else if (data.get("retcode").getAsInt() != 0) { // 其他错误
            notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), context.getString(R.string.game_sign_info_failed));
            throw new RuntimeException(context.getString(R.string.game_sign_info_failed) + response);
        }
        Map<String, Object> resultMap = new HashMap<>();
        JsonObject dataObject = data.getAsJsonObject("data");
        if (dataObject.has("is_sign") && !dataObject.get("is_sign").isJsonNull())
            resultMap.put("is_sign", dataObject.get("is_sign").getAsBoolean());
        if (dataObject.has("total_sign_day") && !dataObject.get("total_sign_day").isJsonNull())
            resultMap.put("total_sign_day", dataObject.get("total_sign_day").getAsInt());
        if (dataObject.has("first_bind") && !dataObject.get("first_bind").isJsonNull())
            resultMap.put("first_bind", dataObject.get("first_bind").getAsBoolean());
        return resultMap;
    }

    /**
     * 签到
     *
     * @param account 账号信息Map<String, String>, key为nickname昵称, game_uid游戏uid, region游戏区.
     * @return 签到结果String
     */
    private String checkIn(Map<String, String> account) throws Exception {
        String signApi;
        if (isOversea) {
            signApi = MiHoYoBBSConstants.get_event_base_url(gameName) + "/sign";
        } else if (gameName.equals("绝区零")) {
            signApi = Constants.Urls.BBS_GAME_REWARDS_ZZZ_SIGN_URL;
        } else {
            signApi = Constants.Urls.BBS_GAME_REWARDS_SIGN_URL;
        }
        if (isOversea) {
            // 国际服：简单签到，无验证码，无重试
            return sendPostRequest(signApi, getGameLoginHeaders(), Map.of("act_id", actId));
        }
        // 国服：带验证码和重试的签到流程
        String response = "";
        for (int i = 1; i <= MAX_RETRIES; i++) {
            Map<String, String> gameLoginHeader = getGameLoginHeaders();
            // 如果是之前进行了人机验证的，添加请求头
            if (captchaHelper.getGeetCode() != null)
                gameLoginHeader.putAll(captchaHelper.getGeetCode());
            response = sendPostRequest(signApi, gameLoginHeader, Map.of("act_id", actId, "region", Objects.requireNonNull(account.get("region")), "uid", Objects.requireNonNull(account.get("game_uid"))));
            JsonObject data = JsonParser.parseString(response).getAsJsonObject();
            if (data.get("retcode").getAsInt() == 429) { // 429 = 请求频率限制
                Thread.sleep(RATE_LIMIT_COOLDOWN_MS);
                notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), context.getString(R.string.game_rate_limited));
                statusNotifier.notifyListeners(context.getString(R.string.notif_title_game_sign, displayName) + " " + context.getString(R.string.game_rate_limited));
                continue;
            }
            // 触发验证码
            if (data.get("retcode").getAsInt() == 0 && data.getAsJsonObject("data").get("success").getAsInt() == 1) {
                Map<String, String> recordHeaders = headerManager.get_record_headers();
                String stuid = tools.read(context, userId, "stuid");
                String stoken = tools.read(context, userId, "stoken");
                String mid = tools.read(context, userId, "mid");
                String cookieToken = tools.read(context, userId, "cookie_token");
                String loginTicket = tools.read(context, userId, "login_ticket");
                recordHeaders.put("Cookie", "ltuid=" + stuid + ";ltoken=" + stoken
                        + ";ltoken_v2=" + stoken + ";ltuid_v2=" + stuid
                        + ";account_id=" + stuid
                        + ";account_id_v2=" + stuid
                        + ";account_mid_v2=" + mid + ";cookie_token=" + cookieToken
                        + ";cookie_token_v2=" + cookieToken + ";mi18nLang=zh-cn;login_ticket=" + loginTicket);
                // 触发验证码验证
                notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), context.getString(R.string.game_captcha_needed));
                statusNotifier.notifyListeners(context.getString(R.string.notif_title_game_sign, displayName) + " " + context.getString(R.string.game_captcha_needed));
                captchaHelper.performVerificationWithCallback(recordHeaders, context.getString(R.string.task_name_game_sign_in, displayName));
                captchaHelper.waitForCompletion();
                if (captchaHelper.getGeetCode() == null) {
                    throw new RuntimeException(context.getString(R.string.geetest_captcha_failed_network));
                }
            } else
                break;
        }
        return response;
    }

    /**
     * 签到(最开始的)
     */
    public void run() throws Exception {
        if (isOversea) {
            runOversea();
        } else {
            runChina();
        }
    }

    /**
     * 国际服签到流程：无需账号列表，直接签到
     */
    private void runOversea() throws Exception {
        statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.snack_loading));
        tools.randomDelay(SIGN_DELAY_MIN_MS, SIGN_DELAY_RANGE_MS);
        Map<String, Object> isData = isSign("", "");
        if (isData.get("first_bind") != null && (Boolean) Objects.requireNonNull(isData.get("first_bind"))) {
            notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), context.getString(R.string.game_first_bind));
            statusNotifier.notifyListeners(context.getString(R.string.notif_title_game_sign, displayName) + " " + context.getString(R.string.game_first_bind));
            return;
        }
        int signDays = isData.get("total_sign_day") != null ? ((Number) Objects.requireNonNull(isData.get("total_sign_day"))).intValue() - 1 : 0;
        if (isData.get("is_sign") != null && (Boolean) isData.get("is_sign")) {
            statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_already_signed));
            signDays += 1;
        } else {
            tools.randomDelay(SIGN_DELAY_MIN_MS, SIGN_DELAY_RANGE_MS);
            String req = checkIn(null);
            JsonObject data = JsonParser.parseString(req).getAsJsonObject();
            if (data.get("retcode").getAsInt() == 0) {
                statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_sign_success));
                signDays += 2;
            } else if (data.get("retcode").getAsInt() == -5003) {
                statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_already_signed));
                signDays += 1;
            } else {
                String message = data.has("message") ? data.get("message").getAsString() : context.getString(R.string.bbs_unknown_error);
                statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_sign_failed, message + " (retcode=" + data.get("retcode").getAsInt() + ")"));
                notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), context.getString(R.string.game_sign_failed, message));
                return;
            }
        }
        statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_consecutive_days, signDays));
        if (signDays > 0 && signDays <= checkinRewards.size()) {
            statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_today_reward, checkinRewards.get(signDays - 1).get("name"), checkinRewards.get(signDays - 1).get("cnt")));
        }
    }

    /**
     * 国服签到流程：需要账号列表，支持验证码
     */
    private void runChina() throws Exception {
        if (accountList.isEmpty()) {
            notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), context.getString(R.string.game_no_accounts));
            statusNotifier.notifyListeners(context.getString(R.string.notif_title_game_sign, displayName) + " " + context.getString(R.string.game_no_accounts));
            return;
        }
        String playerName = MiHoYoBBSConstants.game_to_role(gameName);
        statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_start_with_accounts, accountList.size()));
        for (Map<String, String> account : accountList) {
            tools.randomDelay(SIGN_DELAY_MIN_MS, SIGN_DELAY_RANGE_MS);
            Map<String, Object> isData = isSign(account.get("region"), account.get("game_uid"));
            if (isData.get("first_bind") != null && (Boolean) Objects.requireNonNull(isData.get("first_bind"))) {
                notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), account.get("nickname") + context.getString(R.string.game_first_bind));
                statusNotifier.notifyListeners(context.getString(R.string.notif_title_game_sign, displayName) + " " + playerName + account.get("nickname") + context.getString(R.string.game_first_bind));
                continue;
            }
            int signDays = isData.get("total_sign_day") != null ? ((Number) Objects.requireNonNull(isData.get("total_sign_day"))).intValue() - 1 : 0;
            if (isData.get("is_sign") != null && (Boolean) isData.get("is_sign")) {
                statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + playerName + account.get("nickname") + context.getString(R.string.game_already_signed));
                signDays += 1;
            } else {
                tools.randomDelay(SIGN_DELAY_MIN_MS, SIGN_DELAY_RANGE_MS);
                String req = checkIn(account);
                JsonObject data = JsonParser.parseString(req).getAsJsonObject();
                if (data.get("retcode").getAsInt() != 429) {
                    if (data.get("retcode").getAsInt() == 0 && data.getAsJsonObject("data").get("success").getAsInt() == 0) {
                        statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + playerName + account.get("nickname") + context.getString(R.string.game_sign_success));
                        signDays += 2;
                    } else if (data.get("retcode").getAsInt() == -5003) {
                        statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + playerName + account.get("nickname") + context.getString(R.string.game_already_signed));
                        signDays += 1;
                    } else {
                        String message = data.has("message") ? data.get("message").getAsString() : context.getString(R.string.bbs_unknown_error);
                        int retcode = data.get("retcode").getAsInt();
                        boolean needCaptcha = !data.get("data").isJsonNull()
                                && data.getAsJsonObject("data").has("success")
                                && data.getAsJsonObject("data").get("success").getAsInt() != 0;
                        String reason = needCaptcha ? context.getString(R.string.game_sign_captcha_triggered) : message + " (retcode=" + retcode + ")";
                        statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + playerName + account.get("nickname") + context.getString(R.string.game_sign_failed, reason));
                        notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), playerName + account.get("nickname") + context.getString(R.string.game_sign_failed, reason));
                        continue;
                    }
                } else {
                    statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + playerName + account.get("nickname") + context.getString(R.string.game_sign_failed_rate_limit));
                    continue;
                }
            }
            statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + playerName + account.get("nickname") + context.getString(R.string.game_consecutive_days, signDays));
            if (signDays > 0 && signDays <= checkinRewards.size()) {
                statusNotifier.notifyListeners(context.getString(R.string.task_name_game_sign_in, displayName) + " " + context.getString(R.string.game_today_reward, checkinRewards.get(signDays - 1).get("name"), checkinRewards.get(signDays - 1).get("cnt")));
            }
        }
    }

    private String buildGameCookie(String cookieToken) {
        String ltoken = tools.read(context, userId, "ltoken");
        String stuid = tools.read(context, userId, "stuid");
        StringBuilder sb = new StringBuilder();
        sb.append("cookie_token=").append(cookieToken);
        if (ltoken != null) sb.append(";ltoken=").append(ltoken);
        sb.append(";ltuid=").append(stuid);
        sb.append(";account_id=").append(stuid);
        return sb.toString();
    }
}