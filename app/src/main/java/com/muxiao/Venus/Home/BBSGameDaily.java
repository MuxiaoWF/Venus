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
 * 游戏每日签到（原神/星铁/绝区零/崩坏3等）。国服：刷新 CookieToken → 取账号列表 → 遍历签到（支持验证码）；
 * 国际服：直接调海外签到 API（无验证码、无账号列表）。
 *
 * <p>响应读取统一走 {@link JsonAccess}（缺字段返回兜底值），避免风控/登录态失效时的残缺 JSON 直接 NPE。
 */
public class BBSGameDaily {

    private static final int MAX_RETRIES = 3;
    private static final int SIGN_DELAY_MIN_MS = 2000;
    private static final int SIGN_DELAY_RANGE_MS = 7000;
    private static final int RATE_LIMIT_COOLDOWN_MS = 10_000;

    /** 接口成功 */
    private static final int RETCODE_OK = 0;
    /** CookieToken 失效，需要刷新后重试 */
    private static final int RETCODE_COOKIE_EXPIRED = -100;
    /** 请求频率限制 */
    private static final int RETCODE_RATE_LIMITED = 429;
    /** 今日已签到 */
    private static final int RETCODE_ALREADY_SIGNED = -5003;

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
     * @param isOversea 目标账号的服务器类型（由 {@code TaskSettings.isOversea()} 经 TaskExecutor 传入）：
     *                  决定活动 id / game_biz 映射表、Cookie 获取方式与请求头域，不能读全局 {@code is_oversea}。
     */
    public BBSGameDaily(Context context, String userId, String gameName,
                        tools.StatusNotifier statusNotifier, boolean isOversea) {
        this.gameName = gameName;
        this.displayName = MiHoYoBBSConstants.game_to_display_name(context, gameName);
        this.context = context;
        this.userId = userId;
        this.statusNotifier = statusNotifier;
        this.notification = new Notification(context);
        // 凭证改从跨轮存储读（挂起-续跑模型），不再持有控制器与等待状态
        this.captchaHelper = new CaptchaVerificationHelper(context, userId);
        this.isOversea = isOversea;
        this.headerManager = new HeaderManager(context, isOversea);
        this.actId = MiHoYoBBSConstants.name_to_act_id(gameName, isOversea);
        String gameId = MiHoYoBBSConstants.name_to_game_id(gameName, isOversea);
        // 国际服映射表不含全部国服游戏，映射缺失时会在后续 Map.of("game_biz", null) 处抛 NPE；
        // 这里提前拦截并给出可读错误。
        if (this.actId == null || gameId == null) {
            String message = context.getString(R.string.game_unsupported, displayName);
            notification.sendErrorNotification(context.getString(R.string.notif_title_game_sign, displayName), message);
            throw new RuntimeException(message);
        }
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

    /** 本条任务在进度流中的任务名（"%s签到"）。 */
    private String taskName() {
        return context.getString(R.string.task_name_game_sign_in, displayName);
    }

    /** 本条任务在系统通知中的标题（"%s 游戏签到"）。 */
    private String notifTitle() {
        return context.getString(R.string.notif_title_game_sign, displayName);
    }

    /**
     * 上报进度：{@code 任务名 + 主体 + 消息}。主体用于区分同一次运行中的多个账号，国际服等无账号概念的场景传空串。
     */
    private void report(String subject, String message) {
        statusNotifier.notifyListeners(taskName() + " " + subject + message);
    }

    /** 构建游戏登录类请求头：基础登录头 + 游戏 Cookie；原神/绝区零额外补 x-rpc-signgame。 */
    private Map<String, String> getGameLoginHeaders() {
        Map<String, String> gameLoginHeaders = headerManager.get_game_login_headers();
        gameLoginHeaders.putAll(cookies);
        if (gameName.equals("原神")) gameLoginHeaders.put("x-rpc-signgame", "hk4e");
        if (gameName.equals("绝区零")) gameLoginHeaders.put("x-rpc-signgame", "zzz");
        return gameLoginHeaders;
    }

    /**
     * 刷新 cookie_token：国服用 stoken 重新换取并写回存储；国际服无法自动刷新，直接抛错提示需重新登录。
     */
    private String updateCookieToken() {
        if (isOversea) {
            // 国际服cookie无法自动刷新，需重新登录
            String msg = context.getString(R.string.game_cookie_token_failed);
            String title = notifTitle();
            statusNotifier.notifyListeners(title + " " + msg);
            notification.sendErrorNotification(title, msg);
            throw new RuntimeException(msg);
        }
        String title = notifTitle();
        statusNotifier.notifyListeners(title + " " + context.getString(R.string.game_cookie_token_expired));
        notification.sendErrorNotification(title, context.getString(R.string.game_cookie_token_expired));
        String newToken = getCookieTokenByStoken();
        report("", context.getString(R.string.game_cookie_token_refreshed));
        tools.write(context, userId, "cookie_token", newToken);
        return newToken;
    }

    /** 刷新 CookieToken 并回写本次会话的 Cookie 头（{@link #updateCookieToken()} 非空或抛异常，无需判空）。 */
    private void refreshCookie() {
        cookies.put("Cookie", buildGameCookie(updateCookieToken()));
    }

    /** 用 stoken 换 cookieToken，成功后写回存储。 */
    public String getCookieTokenByStoken() {
        String stoken = tools.read(context, userId, "stoken");
        String ltoken = tools.read(context, userId, "ltoken");
        String stuid = tools.read(context, userId, "stuid");
        String token = stoken != null ? stoken : ltoken;
        if ((token == null || token.isEmpty()) && (stuid == null || stuid.isEmpty())) {
            notification.sendErrorNotification(notifTitle(), context.getString(R.string.game_stoken_suid_empty));
            throw new RuntimeException(context.getString(R.string.game_stoken_suid_empty));
        }
        Map<String, String> gameLoginHeaders = getGameLoginHeaders();
        String tokenKey = stoken != null ? "stoken" : "ltoken";
        String mid = tools.read(context, userId, "mid");
        gameLoginHeaders.put("Cookie", "stuid=" + stuid + ";" + tokenKey + "=" + token + (mid != null ? ";mid=" + mid : ""));
        String cookieTokenUrl = isOversea ? Constants.Urls.OS_COOKIE_TOKEN_STOKEN_URL : Constants.Urls.COOKIE_TOKEN_STOKEN_URL;
        String response = sendGetRequest(cookieTokenUrl, gameLoginHeaders, null);
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        int retcode = JsonAccess.retcode(res);
        if (retcode != RETCODE_OK) {
            // 仅登录态失效（-100）才断言 stoken 已失效；其余（429 限流 / 风控等）透传 retcode
            // 与 message，避免瞬时故障被误报为「请重新登录」
            String errorMsg = retcode == RETCODE_COOKIE_EXPIRED
                    ? context.getString(R.string.game_cookie_token_failed)
                    : context.getString(R.string.game_cookie_token_get_failed)
                            + " (retcode=" + retcode + ", message=" + JsonAccess.message(res, "") + ")";
            notification.sendErrorNotification(notifTitle(), errorMsg);
            throw new RuntimeException(errorMsg);
        }
        // 显式判空给出可读错误：否则 data.cookie_token 缺失时 getAsString() 直接 NPE
        String cookieToken = JsonAccess.optString(JsonAccess.data(res), "cookie_token", null);
        if (cookieToken == null) {
            notification.sendErrorNotification(notifTitle(), context.getString(R.string.game_cookie_token_failed));
            throw new RuntimeException(context.getString(R.string.game_cookie_token_failed));
        }
        tools.write(context, userId, "cookie_token", cookieToken);
        return cookieToken;
    }

    /**
     * 获取米哈游账号绑定的指定游戏账号列表。
     *
     * @return key 为 region / game_uid / nickname / game_biz
     */
    protected List<Map<String, String>> getAccountList(String gameId) {
        return getAccountList(gameId, false);
    }

    /**
     * 获取账号列表的真实实现（带重试守卫）：CookieToken 失效时递归刷新一次，retried=true 仍失败则抛错，防止无限递归。
     * 返回空列表表示「未获取到任何账号」，由 {@link #runChina()} 统一提示无绑定账号。
     */
    protected List<Map<String, String>> getAccountList(String gameId, boolean retried) {
        report("", context.getString(R.string.snack_loading));
        Map<String, String> headers = getGameLoginHeaders();
        String accountListUrl = isOversea ? Constants.Urls.OS_ACCOUNT_LIST_URL : Constants.Urls.ACCOUNT_LIST_URL;
        String response = sendGetRequest(accountListUrl, headers, Map.of("game_biz", gameId));
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        int retcode = JsonAccess.retcode(data);
        if (retcode == RETCODE_COOKIE_EXPIRED) {
            if (retried) {
                report("", context.getString(R.string.game_cookie_token_failed));
                throw new RuntimeException(context.getString(R.string.game_cookie_token_failed));
            }
            refreshCookie();
            return getAccountList(gameId, true);
        }
        if (retcode != RETCODE_OK) {
            return reportAccountsFailed();
        }
        JsonObject payload = JsonAccess.data(data);
        JsonArray list = payload == null ? null : JsonAccess.array(payload, "list");
        if (list == null) { //结构异常同样按「未获取到账号」处理，避免 NPE
            return reportAccountsFailed();
        }
        List<Map<String, String>> accountList = new ArrayList<>();
        for (JsonElement entry : list) {
            if (entry == null || !entry.isJsonObject()) continue;
            JsonObject account = entry.getAsJsonObject();
            Map<String, String> accountInfo = new HashMap<>();
            accountInfo.put("nickname", JsonAccess.optString(account, "nickname", ""));
            accountInfo.put("game_uid", JsonAccess.optString(account, "game_uid", ""));
            accountInfo.put("region", JsonAccess.optString(account, "region", ""));
            accountInfo.put("game_biz", JsonAccess.optString(account, "game_biz", ""));
            accountList.add(accountInfo);
        }
        tools.write(context, userId, gameId + "_user", new Gson().toJson(accountList));
        report("", context.getResources().getQuantityString(
                R.plurals.game_accounts_found, accountList.size(), accountList.size()));
        return accountList;
    }

    /** 账号列表获取失败：上报进度与通知后返回空列表（调用方据此走「无绑定账号」分支）。 */
    private List<Map<String, String>> reportAccountsFailed() {
        report("", context.getString(R.string.game_get_accounts_failed));
        notification.sendErrorNotification(notifTitle(), context.getString(R.string.game_get_accounts_failed));
        return new ArrayList<>();
    }

    /**
     * 获取签到奖励列表。
     *
     * @return key 为 name（名称）、cnt（数量）
     */
    private List<Map<String, Object>> getCheckinRewards() {
        report("", context.getString(R.string.game_getting_rewards));
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
            if (JsonAccess.retcode(data) == RETCODE_OK) {
                JsonArray awardsArray = JsonAccess.array(JsonAccess.data(data), "awards");
                // 奖励数组缺失时按「本次获取失败」处理，继续重试而不是抛 NPE
                if (awardsArray != null) {
                    List<Map<String, Object>> rewards = new ArrayList<>();
                    for (JsonElement award : awardsArray) {
                        if (award == null || !award.isJsonObject()) continue;
                        JsonObject awardObject = award.getAsJsonObject();
                        Map<String, Object> rewardMap = new HashMap<>();
                        rewardMap.put("name", JsonAccess.optString(awardObject, "name", ""));
                        rewardMap.put("cnt", JsonAccess.optString(awardObject, "cnt", ""));
                        rewards.add(rewardMap);
                    }
                    return rewards;
                }
            }
            report("", context.getString(R.string.game_rewards_retry, i + 1));
            try {
                tools.randomDelay(SIGN_DELAY_MIN_MS, SIGN_DELAY_RANGE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(context.getString(R.string.game_thread_error, e.toString()));
            }
        }
        report("", context.getString(R.string.game_rewards_max_retry));
        return new ArrayList<>();
    }

    /**
     * 获取账号签到信息。
     *
     * @return key 为 is_sign / remain_days / sign_cnt / first_bind（字段缺失则不写入，调用方读到 null）
     */
    private Map<String, Object> isSign(String region, String uid) {
        return isSign(region, uid, false);
    }

    /**
     * 查询签到信息的真实实现（带重试守卫）：CookieToken 失效时刷新一次后重试；retried=true（或国际服）仍失败则抛错。
     */
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
        Map<String, String> params = new HashMap<>();
        params.put("lang", lang);
        params.put("act_id", actId);
        if (!isOversea) {
            params.put("region", region);
            params.put("uid", uid);
        }
        String response = sendGetRequest(isSignApi, gameLoginHeaders, params);
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        int retcode = JsonAccess.retcode(data);
        if (retcode == RETCODE_COOKIE_EXPIRED) {
            if (isOversea || retried) {
                notification.sendErrorNotification(notifTitle(), context.getString(R.string.game_cookie_token_failed));
                throw new RuntimeException(context.getString(R.string.game_cookie_token_failed));
            }
            refreshCookie();
            return isSign(region, uid, true);
        }
        if (retcode != RETCODE_OK) { // 其他错误
            notification.sendErrorNotification(notifTitle(), context.getString(R.string.game_sign_info_failed));
            throw new RuntimeException(context.getString(R.string.game_sign_info_failed) + response);
        }
        Map<String, Object> resultMap = new HashMap<>();
        JsonObject dataObject = JsonAccess.data(data);
        Boolean isSign = JsonAccess.optBooleanOrNull(dataObject, "is_sign");
        Boolean firstBind = JsonAccess.optBooleanOrNull(dataObject, "first_bind");
        Integer totalSignDay = JsonAccess.optTotalSignDay(dataObject);
        // 仅在字段确实存在且非 JsonNull 时写入，保持「缺字段 = 调用方读到 null」的语义
        if (isSign != null) resultMap.put("is_sign", isSign);
        if (totalSignDay != null) resultMap.put("total_sign_day", totalSignDay);
        if (firstBind != null) resultMap.put("first_bind", firstBind);
        return resultMap;
    }

    /** 解析签到信息中的「连续签到天数」基准：{@code total_sign_day - 1}，缺失按 0。 */
    private static int signDaysOf(Map<String, Object> isData) {
        Object total = isData.get("total_sign_day");
        return total instanceof Number ? ((Number) total).intValue() - 1 : 0;
    }

    /**
     * 上报签到结果尾部：连续签到天数 + 今日奖励。国服带「角色名+昵称」主体，国际服传空串；奖励表越界时只报天数。
     */
    private void reportSignProgress(String subject, int signDays) {
        report(subject, context.getResources().getQuantityString(
                R.plurals.game_consecutive_days, signDays, signDays));
        if (signDays > 0 && signDays <= checkinRewards.size()) {
            Map<String, Object> reward = checkinRewards.get(signDays - 1);
            report("", context.getString(R.string.game_today_reward, reward.get("name"), reward.get("cnt")));
        }
    }

    /**
     * 执行单个账号的签到（国服），或国际服的整体签到（account 传 null）。国服最多重试 {@code MAX_RETRIES} 次：
     * 429 冷却后重试；retcode=0 且 data.success=1 表示触发极验，验证后继续。
     *
     * @return 签到接口原始响应字符串
     */
    private String checkIn(Map<String, String> account) throws Exception {
        String signApi;
        if (isOversea) {
            signApi = MiHoYoBBSConstants.get_event_base_url(gameName) + "/sign";
            // 国际服：简单签到，无验证码，无重试
            return sendPostRequest(signApi, getGameLoginHeaders(), Map.of("act_id", actId));
        }
        if (gameName.equals("绝区零")) {
            signApi = Constants.Urls.BBS_GAME_REWARDS_ZZZ_SIGN_URL;
        } else {
            signApi = Constants.Urls.BBS_GAME_REWARDS_SIGN_URL;
        }
        String response = "";
        // 限流通知收敛到「本轮重试用尽仍被限流」时发一次：原实现在重试循环内每条 429 都 notify 一次，
        // 多账号 × 多游戏叠加后一轮能连发几十条系统通知——这是「频繁推送/骚扰」类检测的直接来源。
        boolean rateLimited = false;
        for (int i = 1; i <= MAX_RETRIES; i++) {
            Map<String, String> gameLoginHeader = getGameLoginHeaders();
            // 上一轮已验证过则带上 geetCode
            if (captchaHelper.getGeetCode() != null)
                gameLoginHeader.putAll(captchaHelper.getGeetCode());
            response = sendPostRequest(signApi, gameLoginHeader, Map.of("act_id", actId,
                    "region", account.containsKey("region") ? Objects.requireNonNull(account.get("region")) : "",
                    "uid", account.containsKey("game_uid") ? Objects.requireNonNull(account.get("game_uid")) : ""));
            JsonObject data = JsonParser.parseString(response).getAsJsonObject();
            int retcode = JsonAccess.retcode(data);
            if (retcode == RETCODE_RATE_LIMITED) {
                rateLimited = true;
                // 最后一次重试之后不会再有请求，冷却纯属白占线程 10 秒
                if (i < MAX_RETRIES) Thread.sleep(RATE_LIMIT_COOLDOWN_MS);
                report("", context.getString(R.string.game_rate_limited));
                continue;
            }
            // 拿到非 429 响应即视为限流已解除，后续即使走 break/抛验证码也不该再报限流
            rateLimited = false;
            // 触发验证码：retcode=0 但 data.success=1
            if (retcode == RETCODE_OK && JsonAccess.optInt(JsonAccess.data(data), "success", 0) == 1) {
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
                // 挂起本轮：不在任务线程上等人。执行器统一收集后发起一次验证，结果由续跑轮带上
                report("", context.getString(R.string.game_captcha_needed));
                Map<String, String> solved = captchaHelper.getGeetCode();
                if (solved != null) recordHeaders.putAll(solved);
                throw new CaptchaRequiredException(TaskIds.gameSignIn(gameName), recordHeaders);
            } else
                break;
        }
        if (rateLimited)
            notification.sendErrorNotification(notifTitle(), context.getString(R.string.game_rate_limited));
        return response;
    }

    /** 签到入口：按服务器类型分发到国际服/国服流程。 */
    public void run() throws Exception {
        if (isOversea) {
            runOversea();
        } else {
            runChina();
        }
    }

    /** 国际服签到流程：无需账号列表，直接签到。 */
    private void runOversea() throws Exception {
        report("", context.getString(R.string.snack_loading));
        tools.randomDelay(SIGN_DELAY_MIN_MS, SIGN_DELAY_RANGE_MS);
        Map<String, Object> isData = isSign("", "");
        if (Boolean.TRUE.equals(isData.get("first_bind"))) {
            notification.sendErrorNotification(notifTitle(), context.getString(R.string.game_first_bind));
            statusNotifier.notifyListeners(notifTitle() + " " + context.getString(R.string.game_first_bind));
            return;
        }
        int signDays = signDaysOf(isData);
        if (Boolean.TRUE.equals(isData.get("is_sign"))) {
            report("", context.getString(R.string.game_already_signed));
            signDays += 1;
        } else {
            tools.randomDelay(SIGN_DELAY_MIN_MS, SIGN_DELAY_RANGE_MS);
            JsonObject data = JsonParser.parseString(checkIn(null)).getAsJsonObject();
            int retcode = JsonAccess.retcode(data);
            if (retcode == RETCODE_OK) {
                report("", context.getString(R.string.game_sign_success));
                signDays += 2;
            } else if (retcode == RETCODE_ALREADY_SIGNED) {
                report("", context.getString(R.string.game_already_signed));
                signDays += 1;
            } else {
                String message = JsonAccess.message(data, context.getString(R.string.bbs_unknown_error));
                report("", context.getString(R.string.game_sign_failed, message + " (retcode=" + retcode + ")"));
                notification.sendErrorNotification(notifTitle(), context.getString(R.string.game_sign_failed, message));
                return;
            }
        }
        reportSignProgress("", signDays);
    }

    /** 国服签到流程：需要账号列表，支持验证码。 */
    private void runChina() throws Exception {
        if (accountList.isEmpty()) {
            notification.sendErrorNotification(notifTitle(), context.getString(R.string.game_no_accounts));
            statusNotifier.notifyListeners(notifTitle() + " " + context.getString(R.string.game_no_accounts));
            return;
        }
        String playerName = MiHoYoBBSConstants.game_to_role(gameName);
        report("", context.getResources().getQuantityString(
                R.plurals.game_start_with_accounts, accountList.size(), accountList.size()));
        for (Map<String, String> account : accountList) {
            tools.randomDelay(SIGN_DELAY_MIN_MS, SIGN_DELAY_RANGE_MS);
            String subject = playerName + account.get("nickname");
            Map<String, Object> isData = isSign(account.get("region"), account.get("game_uid"));
            if (Boolean.TRUE.equals(isData.get("first_bind"))) {
                notification.sendErrorNotification(notifTitle(), account.get("nickname") + context.getString(R.string.game_first_bind));
                statusNotifier.notifyListeners(notifTitle() + " " + subject + context.getString(R.string.game_first_bind));
                continue;
            }
            int signDays = signDaysOf(isData);
            if (Boolean.TRUE.equals(isData.get("is_sign"))) {
                report(subject, context.getString(R.string.game_already_signed));
                signDays += 1;
            } else {
                tools.randomDelay(SIGN_DELAY_MIN_MS, SIGN_DELAY_RANGE_MS);
                JsonObject data = JsonParser.parseString(checkIn(account)).getAsJsonObject();
                int retcode = JsonAccess.retcode(data);
                if (retcode == RETCODE_RATE_LIMITED) {
                    report(subject, context.getString(R.string.game_sign_failed_rate_limit));
                    continue;
                }
                if (retcode == RETCODE_OK && JsonAccess.optInt(JsonAccess.data(data), "success", 0) == 0) {
                    report(subject, context.getString(R.string.game_sign_success));
                    signDays += 2;
                } else if (retcode == RETCODE_ALREADY_SIGNED) {
                    report(subject, context.getString(R.string.game_already_signed));
                    signDays += 1;
                } else {
                    String message = JsonAccess.message(data, context.getString(R.string.bbs_unknown_error));
                    boolean needCaptcha = JsonAccess.optInt(JsonAccess.data(data), "success", 0) != 0;
                    String reason = needCaptcha ? context.getString(R.string.game_sign_captcha_triggered)
                            : message + " (retcode=" + retcode + ")";
                    report(subject, context.getString(R.string.game_sign_failed, reason));
                    notification.sendErrorNotification(notifTitle(), subject + context.getString(R.string.game_sign_failed, reason));
                    continue;
                }
            }
            reportSignProgress(subject, signDays);
        }
    }

    /** 用 cookie_token + ltoken/stuid 拼出游戏签到所需的 Cookie 字符串。 */
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
