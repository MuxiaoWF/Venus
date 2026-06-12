package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Random;

public class BBSDaily {

    private static final int MAX_RETRIES = 3;
    private static final int DELAY_MIN_MS = 500;
    private static final int DELAY_RANGE_MS = 1500;
    private static final Random RANDOM = new Random();

    private void randomDelay() throws InterruptedException {
        Thread.sleep(DELAY_MIN_MS + RANDOM.nextInt(DELAY_RANGE_MS));
    }

    @FunctionalInterface
    private interface ApiCall {
        JsonObject execute() throws Exception;
    }

    private JsonObject executeWithRetry(ApiCall call) throws Exception {
        for (int retryCount = 0; retryCount < MAX_RETRIES; retryCount++) {
            JsonObject data = call.execute();
            int retcode = data.get("retcode").getAsInt();
            android.util.Log.e("VenusCaptcha", "BBSDaily: API retcode=" + retcode + ", response=" + data);
            if (retcode == 1034) {
                android.util.Log.e("VenusCaptcha", "BBSDaily: retcode 1034, starting verification");
                notifier.notifyListeners("米游币签到 需要进行人机验证...");
                notification.sendErrorNotification("米游币签到 等待进行人机验证", "需要进行人机验证");
                // 先触发验证
                Map<String, String> headers = getBbsHeaders();
                if (captchaHelper.getGeetCode() != null) headers.putAll(captchaHelper.getGeetCode());
                captchaHelper.performVerificationWithCallback(headers, "米游币签到");
                android.util.Log.e("VenusCaptcha", "BBSDaily: waiting for verification...");
                captchaHelper.waitForCompletion();
                android.util.Log.e("VenusCaptcha", "BBSDaily: verification complete, geetCode=" + captchaHelper.getGeetCode());
                if (captchaHelper.getGeetCode() == null) {
                    throw new RuntimeException("人机验证失败，未获取到验证结果");
                }
            } else if (retcode == -100) {
                String errorMsg = "米游币签到 失败，cookie可能已过期，请重新设置cookie";
                notifier.notifyListeners(errorMsg);
                notification.sendErrorNotification("米游币签到 失败", errorMsg);
                throw new RuntimeException(errorMsg);
            } else if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                return data;
            } else {
                String message = data.has("message") ? data.get("message").getAsString() : "未知错误";
                String errorMsg = "米游币签到 签到失败: " + message + " (retcode=" + retcode + ")";
                notifier.notifyListeners(errorMsg);
                notification.sendErrorNotification("米游币签到 失败", errorMsg);
                if (retryCount < MAX_RETRIES - 1)
                    notifier.notifyListeners("米游币签到 正在重试，第" + (retryCount + 2) + "次尝试");
            }
        }
        throw new RuntimeException("米游币签到 重试次数已达上限");
    }

    private final Map<String, Boolean> completedTasks = new HashMap<>();
    private final List<Map<String, String>> bbsCheckInList = new ArrayList<>();
    private int todayEarnableCoins; // 当天可获取的米游币
    private int todayEarnedCoins; // 当天已获取的米游币
    private int totalCoins; // 已获取的米游币
    private final tools.StatusNotifier notifier;
    private final Notification notification;
    private final HeaderManager headerManager;
    private final String cookie;
    private final CaptchaVerificationHelper captchaHelper;

    public BBSDaily(Context context, String userId, tools.StatusNotifier notifier, GeetestController gt3Controller) {
        completedTasks.put("sign", false);
        this.notification = new Notification(context);
        this.captchaHelper = new CaptchaVerificationHelper(gt3Controller, notifier, this.notification);
        this.headerManager = new HeaderManager(context);
        this.notifier = notifier;
        String stoken = tools.read(context, userId, "stoken");
        String mid = tools.read(context, userId, "mid");
        String stuid = "ltuid=" + tools.read(context, userId, "stuid");
        if (stoken == null || mid == null || tools.read(context, userId, "stuid") == null) {
            String errorMsg = "stoken/mid/stuid为null,请尝试重新登陆";
            notification.sendErrorNotification("登录错误", errorMsg);
            throw new RuntimeException(errorMsg);
        }
        cookie = "stoken=" + stoken + "; mid=" + mid + "; stuid=" + stuid + ";";
    }

    /**
     * 执行所有任务
     *
     * @param name  需要社区签到的板块名称（米游币的那个）可填：崩坏2、原神、崩坏3、绝区零、星铁、大别野、崩坏因缘精灵、星布谷地、未定事件簿(获取方式通过MiHoYoBBSConstants的forum_id)
     */
    public void runTask(String[] name) throws Exception {
        notifier.notifyListeners("米游币签到 开始执行");
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
            notifier.notifyListeners("米游币签到 今日任务已全部完成");

        notifier.notifyListeners("米游币签到 任务完成，已获得" + this.todayEarnedCoins + "个，还能获得" + this.todayEarnableCoins + "个，目前共有" + this.totalCoins + "个米游币");
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
        notifier.notifyListeners("米游币签到 正在获取任务列表");
        Map<String, String> bbsHeaders = getBbsHeaders();
        String response = tools.sendGetRequest(Constants.Urls.BBS_TASK_URL, bbsHeaders, null);
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        JsonObject data = JsonParser.parseString(response).getAsJsonObject().get("data").getAsJsonObject();
        if (res.get("message").getAsString().contains("err") || res.get("retcode").getAsInt() == -100) {
            String errorMsg = "米游币签到 获取任务列表失败，cookie可能已过期，请重新设置cookie";
            notification.sendErrorNotification("米游币签到 任务获取失败", errorMsg);
            throw new RuntimeException(errorMsg);
        }
        this.todayEarnableCoins = data.get("can_get_points").getAsInt();
        this.todayEarnedCoins = data.get("already_received_points").getAsInt();
        this.totalCoins = data.get("total_points").getAsInt();
        if (this.todayEarnableCoins == 0) { // 完成所有任务
            this.completedTasks.put("sign", true);
            return null;
        }
        return data;
    }

    /**
     * 获取任务列表，判断还有什么任务没有执行Part2
     */
    private void getTasksList(JsonObject data) {
        // 查找签到任务(mission_id=58)的完成状态
        for (JsonElement stateElement : data.get("states").getAsJsonArray()) {
            JsonObject state = stateElement.getAsJsonObject();
            if (state.get("mission_id").getAsInt() == 58) {
                if (state.get("is_get_award").getAsBoolean())
                    this.completedTasks.put("sign", true);
                break;
            }
        }
        notifier.notifyListeners("米游币签到 今日还可获得" + this.todayEarnableCoins + "个米游币");
    }

    /**
     * 签到米游币
     */
    private void signPosts() throws Exception {
        if (Boolean.TRUE.equals(this.completedTasks.get("sign"))) {
            notifier.notifyListeners("米游币签到 讨论区每日签到已完成");
            return;
        }
        notifier.notifyListeners("米游币签到 正在执行讨论区签到...");
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
                String response = sendPostRequest(Constants.Urls.BBS_SIGN_IN_URL, header, postDataMap);
                return JsonParser.parseString(response).getAsJsonObject();
            });

            notifier.notifyListeners("米游币签到 " + forum.get("name") + result.get("message").getAsString());
            randomDelay();
        }
    }

}