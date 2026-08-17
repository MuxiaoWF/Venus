package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.content.Context;

import com.google.gson.Gson;
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
 * 米游币签到：获取任务列表 → 执行讨论区签到 → 领取奖励。
 * 支持验证码重试（retcode=1034 时触发极验验证），最多重试3次。
 * 仅国服可用（需要stoken），国际服在 TaskSettings 中已禁用。
 */
public class BBSDaily {

    private static final int MAX_RETRIES = 3;
    private static final int DELAY_MIN_MS = 500;
    private static final int DELAY_RANGE_MS = 1500;

    @FunctionalInterface
    private interface ApiCall {
        JsonObject execute() throws Exception;
    }

    /**
     * 带验证码重试地执行一次 API 调用。
     * 最多重试 {@code MAX_RETRIES} 次：retcode=1034 触发极验验证后重试；登录态失效(-100)直接抛错；
     * retcode=0 且 message 不含 err 视为成功；其余情况按剩余次数提示重试，超出则抛异常。
     *
     * @param call 实际发起请求并解析 retcode 的 lambda
     * @return 成功时的响应 JSON
     */
    private JsonObject executeWithRetry(ApiCall call) throws Exception {
        for (int retryCount = 0; retryCount < MAX_RETRIES; retryCount++) {
            JsonObject data = call.execute();
            int retcode = data.get("retcode").getAsInt();
            android.util.Log.e("VenusCaptcha", "BBSDaily: API retcode=" + retcode + ", response=" + data);
            if (retcode == 1034) { // 1034 = 需要人机验证
                android.util.Log.e("VenusCaptcha", "BBSDaily: retcode 1034, starting verification");
                notifier.notifyListeners(context.getString(R.string.bbs_captcha_waiting));
                notification.sendErrorNotification(context.getString(R.string.bbs_captcha_notification), context.getString(R.string.bbs_captcha_needed));
                // 先触发验证
                Map<String, String> headers = getBbsHeaders();
                if (captchaHelper.getGeetCode() != null) headers.putAll(captchaHelper.getGeetCode());
                captchaHelper.performVerificationWithCallback(headers, context.getString(R.string.task_name_bbs_daily));
                android.util.Log.e("VenusCaptcha", "BBSDaily: waiting for verification...");
                captchaHelper.waitForCompletion();
                android.util.Log.e("VenusCaptcha", "BBSDaily: verification complete, geetCode=" + captchaHelper.getGeetCode());
                if (captchaHelper.getGeetCode() == null) {
                    throw new RuntimeException(context.getString(R.string.bbs_captcha_failed));
                }
            } else if (retcode == -100) { // -100 = 登录态失效
                String errorMsg = context.getString(R.string.bbs_cookie_expired);
                notifier.notifyListeners(errorMsg);
                notification.sendErrorNotification(context.getString(R.string.bbs_signin_failed), errorMsg);
                throw new RuntimeException(errorMsg);
            } else if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                return data;
            } else {
                String message = data.has("message") ? data.get("message").getAsString() : context.getString(R.string.bbs_unknown_error);
                String errorMsg = context.getString(R.string.task_name_bbs_daily) + " " + context.getString(R.string.game_sign_failed, message + " (retcode=" + retcode + ")");
                notifier.notifyListeners(errorMsg);
                notification.sendErrorNotification(context.getString(R.string.bbs_signin_failed), errorMsg);
                if (retryCount < MAX_RETRIES - 1)
                    notifier.notifyListeners(context.getString(R.string.bbs_signin_retrying, retryCount + 2));
            }
        }
        throw new RuntimeException(context.getString(R.string.bbs_retry_limit_reached));
    }

    private boolean signCompleted = false;
    private final List<Map<String, String>> bbsCheckInList = new ArrayList<>();
    private int todayEarnableCoins;
    private final tools.StatusNotifier notifier;
    private final Notification notification;
    private final HeaderManager headerManager;
    private final String cookie;
    private final CaptchaVerificationHelper captchaHelper;
    private final boolean isOversea;
    private final Context context;

    public BBSDaily(Context context, String userId, tools.StatusNotifier notifier, GeetestController gt3Controller) {
        this.context = context;
        this.notification = new Notification(context);
        this.captchaHelper = new CaptchaVerificationHelper(context, gt3Controller, notifier, this.notification);
        this.headerManager = new HeaderManager(context);
        this.notifier = notifier;
        this.isOversea = MiHoYoBBSConstants.is_oversea(context);
        String builtCookie = tools.buildUserCookie(context, userId);
        if (builtCookie == null) {
            String errorMsg = context.getString(R.string.bbs_stoken_null);
            notification.sendErrorNotification(context.getString(R.string.bbs_login_error), errorMsg);
            throw new RuntimeException(errorMsg);
        }
        cookie = builtCookie;
    }

    /**
     * 执行米游币社区签到全流程。
     * 先添加待签到板块，拉取任务列表判断是否已全部完成；未完成则遍历板块签到并重新刷新任务状态。
     *
     * @param name  需要社区签到的板块名称（米游币的那个）可填：崩坏2、原神、崩坏3、绝区零、星铁、大别野、崩坏因缘精灵、星布谷地、未定事件簿(获取方式通过MiHoYoBBSConstants的forum_id)
     */
    public void runTask(String[] name) throws Exception {
        notifier.notifyListeners(context.getString(R.string.bbs_start));
        // 添加需要签到的板块信息
        for (String key : name)
            bbsCheckInList.add(MiHoYoBBSConstants.name_to_forum_id(key));
        // 获取任务完成状态
        JsonObject data = checkTasksList();
        if (data != null) {
            // 任务部分未完成，运行完整程序
            getTasksList(data);
            signPosts();
            // 重新获取任务刷新签到信息
            checkTasksList();
        } else //任务已经完成
            notifier.notifyListeners(context.getString(R.string.bbs_all_done_today));

        notifier.notifyListeners(context.getString(R.string.bbs_task_done));
    }

    /** 构建社区签到请求头：基础 BBS 头 + 当前用户的 Cookie。 */
    private Map<String, String> getBbsHeaders() {
        Map<String, String> bbsHeaders = headerManager.get_bbs_headers();
        bbsHeaders.put("Cookie", cookie);
        return bbsHeaders;
    }

    /**
     * 拉取今日任务列表。
     * 通过 can_get_points 判断今日是否已无可得米游币（=0 视为全部完成）并返回 null，
     * 否则返回任务数据供后续解析；retcode 非 0 视为 Cookie 失效并抛错。
     */
    private JsonObject checkTasksList() {
        notifier.notifyListeners(context.getString(R.string.bbs_getting_task_list));
        Map<String, String> bbsHeaders = getBbsHeaders();
        String taskUrl = isOversea ? Constants.Urls.OS_BBS_TASK_URL : Constants.Urls.BBS_TASK_URL;
        String response = tools.sendGetRequest(taskUrl, bbsHeaders, null);
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        if (res.get("retcode").getAsInt() != 0) {
            String errorMsg = context.getString(R.string.bbs_cookie_expired);
            notification.sendErrorNotification(context.getString(R.string.bbs_task_list_failed), errorMsg);
            throw new RuntimeException(errorMsg);
        }
        JsonObject data = res.get("data").getAsJsonObject();
        this.todayEarnableCoins = data.get("can_get_points").getAsInt();
        if (this.todayEarnableCoins == 0) {
            this.signCompleted = true;
            return null;
        }
        return data;
    }

    /**
     * 解析任务状态：从 states 中查找 mission_id=58（社区签到）的 is_get_award，
     * 已领奖则标记 signCompleted=true，并通知今日可获取米游币数量。
     */
    private void getTasksList(JsonObject data) {
        for (JsonElement stateElement : data.get("states").getAsJsonArray()) {
            JsonObject state = stateElement.getAsJsonObject();
            if (state.get("mission_id").getAsInt() == 58) {
                if (state.get("is_get_award").getAsBoolean())
                    this.signCompleted = true;
                break;
            }
        }
        notifier.notifyListeners(context.getString(R.string.bbs_earnable_today, this.todayEarnableCoins));
    }

    /**
     * 遍历待签到板块逐个完成社区签到（含验证码重试）。
     * signCompleted 为 true 时说明今日已全部完成，直接返回。
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
                android.util.Log.e("VenusCaptcha", "BBSDaily signPosts: geetCode=" + captchaHelper.getGeetCode());
                android.util.Log.e("VenusCaptcha", "BBSDaily signPosts: all headers=" + header.keySet());
                String response = sendPostRequest(signInUrl, header, postDataMap);
                return JsonParser.parseString(response).getAsJsonObject();
            });

            notifier.notifyListeners(context.getString(R.string.task_name_bbs_daily) + " " + forum.get("name") + result.get("message").getAsString());
            tools.randomDelay(DELAY_MIN_MS, DELAY_RANGE_MS);
        }
    }

}