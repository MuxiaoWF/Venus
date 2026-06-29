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
     * 执行所有任务
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

    private Map<String, String> getBbsHeaders() {
        Map<String, String> bbsHeaders = headerManager.get_bbs_headers();
        bbsHeaders.put("Cookie", cookie);
        return bbsHeaders;
    }

    /**
     * 获取任务列表，判断还有什么任务没有执行Part1
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
     * 获取任务列表，判断还有什么任务没有执行Part2
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
     * 签到米游币
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