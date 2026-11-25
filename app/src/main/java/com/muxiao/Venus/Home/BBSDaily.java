package com.muxiao.Venus.Home;

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
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.User.TokenExchange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 米游币签到：获取任务列表 → 讨论区签到 → 领取奖励；retcode=1034 时触发极验并重试，最多 3 次。
 * 仅国服可用（需 stoken），国际服在 TaskSettings 中已禁用。
 */
public class BBSDaily {

    private static final int MAX_RETRIES = 3;
    private static final int DELAY_MIN_MS = 500;
    private static final int DELAY_RANGE_MS = 1500;
    /** 需要人机验证 */
    private static final int RETCODE_NEED_CAPTCHA = 1034;
    /** 登录态失效 */
    private static final int RETCODE_LOGIN_EXPIRED = -100;
    /** 讨论区签到任务的任务 ID */
    private static final int MISSION_ID_COMMUNITY_SIGN_IN = 58;

    @FunctionalInterface
    private interface ApiCall {
        JsonObject execute() throws Exception;
    }

    /**
     * 带验证码重试地执行一次 API 调用，最多 {@code MAX_RETRIES} 次：1034 触发极验后重试；-100 直接抛错；
     * retcode=0 且 message 不含 err 视为成功；其余按剩余次数提示重试，超出则抛异常。
     *
     * @param call 实际发起请求并解析 retcode 的 lambda
     */
    private JsonObject executeWithRetry(ApiCall call) throws Exception {
        for (int retryCount = 0; retryCount < MAX_RETRIES; retryCount++) {
            JsonObject data = call.execute();
            int retcode = JsonAccess.retcode(data);
            Logger.debug("VenusCaptcha", "BBSDaily: API retcode=" + retcode + ", response=" + data);

            // 成功：retcode=0 且 message 不含 "err"（接口对部分风控会返回带 err 的 0）
            if (retcode == 0 && !JsonAccess.message(data, "").contains("err")) return data;

            if (retcode == RETCODE_NEED_CAPTCHA) {
                // 挂起本轮：执行器收集后统一发起一次验证，验证结果由下一轮续跑带上（见 CaptchaStore）
                throw captchaRequired();
            }

            String message = JsonAccess.message(data, context.getString(R.string.bbs_unknown_error));
            if (retcode == RETCODE_LOGIN_EXPIRED) {
                // 登录态失效：重试无意义，直接终止
                String errorMsg = context.getString(R.string.bbs_cookie_expired);
                notifier.notifyListeners(errorMsg);
                notification.sendErrorNotification(context.getString(R.string.bbs_signin_failed), errorMsg);
                throw new RuntimeException(errorMsg);
            }

            String errorMsg = context.getString(R.string.task_name_bbs_daily) + " "
                    + context.getString(R.string.game_sign_failed, message + " (retcode=" + retcode + ")");
            notifier.notifyListeners(errorMsg);
            notification.sendErrorNotification(context.getString(R.string.bbs_signin_failed), errorMsg);
            if (retryCount < MAX_RETRIES - 1)
                notifier.notifyListeners(context.getString(R.string.bbs_signin_retrying, retryCount + 2));
        }
        throw new RuntimeException(context.getString(R.string.bbs_retry_limit_reached));
    }

    /**
     * 处理 retcode=1034（需人机验证）：抛出挂起信号交给执行器收尾，**不在任务线程上等人**。
     *
     * <p>旧实现在这里阻塞等待最长 5 分钟：定时链路的 WorkManager 窗口（约 10 分钟）被吃掉一半，
     * 并发任务还会互相踩踏共用的 latch。改为挂起后，本轮立即结束，执行器把任务置「待验证」，
     * 用户完成验证的结果落库，下一轮续跑时 {@link CaptchaStore#getSolution} 自动带进请求头。
     */
    private CaptchaRequiredException captchaRequired() {
        Logger.debug("VenusCaptcha", "BBSDaily: retcode 1034, suspend task for captcha");
        notifier.notifyListeners(context.getString(R.string.bbs_captcha_waiting));
        // 请求头带上本轮已取得的验证结果（若有）：续跑时可直接复用，避免重复验证
        Map<String, String> headers = getBbsHeaders();
        Map<String, String> geetCode = captchaHelper.getGeetCode();
        if (geetCode != null) headers.putAll(geetCode);
        return new CaptchaRequiredException(TaskIds.BBS_DAILY, headers);
    }

    private boolean signCompleted = false;
    private final List<Map<String, String>> bbsCheckInList = new ArrayList<>();
    private int todayEarnableCoins;
    private final tools.StatusNotifier notifier;
    private final Notification notification;
    private final HeaderManager headerManager;
    private String cookie;
    private final String userId;
    private final CaptchaVerificationHelper captchaHelper;
    private final boolean isOversea;
    private final Context context;

    /**
     * @param isOversea 目标账号的服务器类型（由 {@code TaskSettings.isOversea()} 经 TaskExecutor 传入）。
     *                  国服/国际服的接口域名、appId、Referer 与 DS 头都不同，故由调用方给定，
     *                  不在构造里读全局 {@code is_oversea}——多账号跨服批跑时全局值可能是别人的服。
     */
    public BBSDaily(Context context, String userId, tools.StatusNotifier notifier, boolean isOversea) {
        this.context = context;
        this.userId = userId;
        this.notification = new Notification(context);
        // 凭证改从跨轮存储读（挂起-续跑模型），不再持有控制器与等待状态
        this.captchaHelper = new CaptchaVerificationHelper(context, userId);
        this.isOversea = isOversea;
        this.headerManager = new HeaderManager(context, isOversea);
        this.notifier = notifier;
        String builtCookie = tools.buildUserCookie(context, userId);
        if (builtCookie == null) {
            String errorMsg = context.getString(R.string.bbs_stoken_null);
            notification.sendErrorNotification(context.getString(R.string.bbs_login_error), errorMsg);
            throw new RuntimeException(errorMsg);
        }
        cookie = builtCookie;
    }

    /**
     * 执行米游币社区签到全流程：添加待签到板块 → 拉任务列表判断是否已完成 → 遍历板块签到 → 刷新任务状态。
     *
     * @param name 需社区签到的板块名（经 MiHoYoBBSConstants 的 forum_id 解析：崩坏2、原神、崩坏3、绝区零、星铁、
     *             大别野、崩坏因缘精灵、星布谷地、未定事件簿）
     */
    public void runTask(String[] name) throws Exception {
        notifier.notifyListeners(context.getString(R.string.bbs_start));
        resolveCheckInForums(name);
        JsonObject data = checkTasksList();
        if (data != null) {
            getTasksList(data);
            signPosts();
            // 重新获取任务刷新签到信息
            checkTasksList();
        } else //任务已经完成
            notifier.notifyListeners(context.getString(R.string.bbs_all_done_today));

        notifier.notifyListeners(context.getString(R.string.bbs_task_done));
    }

    /**
     * 把板块名解析为板块信息并去重。名称非法时前置校验并抛出可读错误：否则写入 null 后要到
     * {@code signPosts()} 取 forum.get("id") 才以 NPE 暴露，错误点与原因相距很远。
     */
    private void resolveCheckInForums(String[] names) {
        bbsCheckInList.clear();
        if (names == null || names.length == 0)
            throw new RuntimeException(context.getString(R.string.task_bbs_daily_no_forum));
        for (String key : names) {
            Map<String, String> forum = key != null ? MiHoYoBBSConstants.name_to_forum_id(key) : null;
            if (forum == null)
                throw new RuntimeException(context.getString(R.string.task_bbs_daily_no_forum) + " (" + key + ")");
            if (!bbsCheckInList.contains(forum))
                bbsCheckInList.add(forum);
        }
    }

    /** 构建社区签到请求头：基础 BBS 头 + 当前用户的 Cookie。 */
    private Map<String, String> getBbsHeaders() {
        Map<String, String> bbsHeaders = headerManager.get_bbs_headers();
        bbsHeaders.put("Cookie", cookie);
        return bbsHeaders;
    }

    /**
     * 登录态自动续期：用 SToken 换发 CookieToken，并把会话 Cookie 切成 {@code cookie_token} 形态
     * （米游币接口同样接受，游戏签到侧长期使用）。能救回的典型场景：{@code stoken=} 形态被判失效但 SToken 本身仍有效。
     * <p>
     * 国服走通行证 V2 换票（{@code ma-cn-session/app/exchange}，dst_token_type=4）；旧
     * {@code getCookieAccountInfoBySToken} 已无法正常返回。国际服无 exchange 端点，仍用旧接口。
     *
     * @return {@code true} 已换到可用 Cookie，可带新 Cookie 重试；失败（凭证缺失/SToken 真失效/网络异常）返回 false
     */
    private boolean refreshCookie() {
        String stoken = tools.read(context, userId, "stoken");
        String ltoken = tools.read(context, userId, "ltoken");
        String stuid = tools.read(context, userId, "stuid");
        String mid = tools.read(context, userId, "mid");
        boolean hasStoken = stoken != null && !stoken.isEmpty();
        String token = hasStoken ? stoken : ltoken;
        String tokenKey = hasStoken ? "stoken" : "ltoken";
        if (token == null || token.isEmpty() || stuid == null || stuid.isEmpty()) return false;

        try {
            String cookieToken;
            if (isOversea) {
                Map<String, String> headers = headerManager.get_bbs_headers();
                headers.put("Cookie", "stuid=" + stuid + ";" + tokenKey + "=" + token
                        + (mid != null ? ";mid=" + mid : ""));
                String response = tools.sendGetRequest(Constants.Urls.OS_COOKIE_TOKEN_STOKEN_URL, headers, null);
                JsonObject res = JsonParser.parseString(response).getAsJsonObject();
                if (JsonAccess.retcode(res) != 0) return false;
                cookieToken = JsonAccess.optString(JsonAccess.data(res), "cookie_token", null);
            } else {
                // V2 换票必须用 SToken；只有 ltoken 时无法续期
                if (!hasStoken) return false;
                cookieToken = TokenExchange.exchange(headerManager, stoken, mid, TokenExchange.TARGET_COOKIE_TOKEN);
            }
            if (cookieToken == null) return false;
            // 回写存储供游戏签到等链路复用，避免各自重复换发
            tools.write(context, userId, "cookie_token", cookieToken);
            cookie = tools.buildCookie(
                    "cookie_token", cookieToken, "cookie_token_v2", cookieToken,
                    "ltoken", ltoken, "ltoken_v2", ltoken,
                    "ltuid", stuid, "ltuid_v2", stuid,
                    "account_id", stuid, "account_id_v2", stuid);
            return true;
        } catch (RuntimeException e) {
            // 网络异常 / 换票失败：续期只是尽力而为，失败不改变原有错误语义
            Logger.debug("VenusBBS", "BBSDaily: cookie 续期失败 " + e);
            return false;
        }
    }

    /**
     * 拉取今日任务列表：{@code can_get_points == 0} 视为今日已全部完成并返回 null，否则返回任务数据。
     * <p>
     * 登录态失效（-100）先尝试一次自动续期（V2 换票取 cookie_token 并切 Cookie 形态）后重试，
     * 续期失败才按「Cookie 失效」上报，避免把可自愈的过期误报成需要重新登录。
     */
    private JsonObject checkTasksList() {
        return checkTasksList(false);
    }

    /**
     * @param retried 是否已经走过一次自动续期（防止刷新后仍失败时无限递归）
     */
    private JsonObject checkTasksList(boolean retried) {
        notifier.notifyListeners(context.getString(R.string.bbs_getting_task_list));
        Map<String, String> bbsHeaders = getBbsHeaders();
        // 续跑轮已持有验证凭证：任务列表接口同样可能要求验证，不带就是再吃一次 1034
        Map<String, String> geetCode = captchaHelper.getGeetCode();
        if (geetCode != null) bbsHeaders.putAll(geetCode);
        String taskUrl = isOversea ? Constants.Urls.OS_BBS_TASK_URL : Constants.Urls.BBS_TASK_URL;
        String response = tools.sendGetRequest(taskUrl, bbsHeaders, null);
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        int retcode = JsonAccess.retcode(data);
        if (retcode != 0) {
            // 登录态失效：先自动续期一次，续期成功则带新 Cookie 重试
            if (retcode == RETCODE_LOGIN_EXPIRED && !retried && refreshCookie()) {
                notifier.notifyListeners(context.getString(R.string.bbs_cookie_refreshed));
                return checkTasksList(true);
            }
            // 需要人机验证：与签到接口同口径挂起（旧实现直接抛普通异常记 ERROR，
            // 用户看到的是「任务列表获取失败」而不是「待验证」，也就没有验证入口）
            if (retcode == RETCODE_NEED_CAPTCHA) throw captchaRequired();
            // 仅登录态失效（-100）才提示重设 cookie；其余（1034 人机验证 / 429 限流 / 风控等）原样透传
            // retcode 与 message——瞬时失败重跑即可恢复，报成「cookie 过期」会误导用户重新登录
            String errorMsg = retcode == RETCODE_LOGIN_EXPIRED
                    ? context.getString(R.string.bbs_cookie_expired)
                    : context.getString(R.string.bbs_task_list_failed)
                            + " (retcode=" + retcode + ", message=" + JsonAccess.message(data, "") + ")";
            notification.sendErrorNotification(context.getString(R.string.bbs_task_list_failed), errorMsg);
            throw new RuntimeException(errorMsg);
        }
        JsonObject payload = JsonAccess.data(data);
        if (payload == null) throw new RuntimeException(context.getString(R.string.bbs_task_list_failed));
        // can_get_points 缺失时按 0 处理：接口语义为「今日无可获取米游币」，即任务已全部完成
        this.todayEarnableCoins = JsonAccess.optInt(payload, "can_get_points", 0);
        if (this.todayEarnableCoins == 0) {
            this.signCompleted = true;
            return null;
        }
        return payload;
    }

    /**
     * 解析任务状态：从 states 找 mission_id=58（社区签到）的 is_get_award，已领奖则置 signCompleted，
     * 并通知今日可获取米游币数量。
     */
    private void getTasksList(JsonObject data) {
        JsonArray states = JsonAccess.array(data, "states");
        if (states != null) {
            for (JsonElement stateElement : states) {
                JsonObject state = stateElement != null && stateElement.isJsonObject()
                        ? stateElement.getAsJsonObject() : null;
                if (state == null) continue;
                if (JsonAccess.optInt(state, "mission_id", -1) != MISSION_ID_COMMUNITY_SIGN_IN) continue;
                if (JsonAccess.optIsGetAward(state)) this.signCompleted = true;
                break;
            }
        }
        notifier.notifyListeners(context.getResources().getQuantityString(
                R.plurals.bbs_earnable_today, this.todayEarnableCoins, this.todayEarnableCoins));
    }

    /**
     * 遍历待签到板块逐个签到（含验证码重试）；{@code signCompleted} 为 true 说明今日已全部完成，直接返回。
     */
    private void signPosts() throws Exception {
        if (signCompleted) {
            notifier.notifyListeners(context.getString(R.string.bbs_forum_sign_done));
            return;
        }
        notifier.notifyListeners(context.getString(R.string.bbs_forum_signing));
        String signInUrl = isOversea ? Constants.Urls.OS_BBS_SIGN_IN_URL : Constants.Urls.BBS_SIGN_IN_URL;
        for (Map<String, String> forum : bbsCheckInList) {
            Map<String, Object> postDataMap = new HashMap<>();
            postDataMap.put("gids", Integer.parseInt(Objects.requireNonNull(forum.get("id"))));
            String postDataJson = new Gson().toJson(postDataMap);

            JsonObject result = executeWithRetry(() -> {
                Map<String, String> header = getBbsHeaders();
                header.put("DS", headerManager.getDS_signIn(postDataJson));
                if (captchaHelper.getGeetCode() != null) header.putAll(captchaHelper.getGeetCode());
                Logger.debug("VenusCaptcha", "BBSDaily signPosts: geetCode=" + captchaHelper.getGeetCode());
                String response = sendPostRequest(signInUrl, header, postDataMap);
                return JsonParser.parseString(response).getAsJsonObject();
            });

            notifier.notifyListeners(context.getString(R.string.task_name_bbs_daily) + " "
                    + forum.get("name") + JsonAccess.message(result, ""));
            tools.randomDelay(DELAY_MIN_MS, DELAY_RANGE_MS);
        }
    }

}
