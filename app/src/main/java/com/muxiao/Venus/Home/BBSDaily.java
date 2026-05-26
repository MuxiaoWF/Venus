package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
    private static final int DEFAULT_READ_COUNT = 3;
    private static final int DEFAULT_LIKE_COUNT = 5;
    private static final int POST_LIST_CAP = 5;
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

    private JsonObject executeWithRetry(ApiCall call, String taskDescription) throws Exception {
        for (int retryCount = 0; retryCount < MAX_RETRIES; retryCount++) {
            JsonObject data = call.execute();
            if (data.get("retcode").getAsInt() == 1034) {
                notifier.notifyListeners("需要进行人机验证...");
                notification.sendErrorNotification("等待进行人机验证", "需要进行人机验证");
                Map<String, String> headers = getBbsHeaders();
                if (geetCode != null) headers.putAll(geetCode);
                performVerificationWithCallback(headers);
                synchronized (this) { while (!verificationComplete) this.wait(); }
            } else if (data.get("retcode").getAsInt() == -100) {
                String errorMsg = taskDescription + "失败，你的cookie可能已过期，请重新设置cookie。";
                notifier.notifyListeners(errorMsg);
                notification.sendErrorNotification(taskDescription + "失败", errorMsg);
                throw new RuntimeException(errorMsg);
            } else if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                return data;
            } else {
                String errorMsg = taskDescription + "失败,错误信息为: " + data;
                notifier.notifyListeners(errorMsg);
                notification.sendErrorNotification(taskDescription + "失败", errorMsg);
                if (retryCount < MAX_RETRIES - 1)
                    notifier.notifyListeners("正在重试，第" + (retryCount + 2) + "次尝试");
            }
        }
        throw new RuntimeException(taskDescription + "重试次数已达上限");
    }

    private final Map<String, Boolean> completedTasks = new HashMap<>();
    private final Map<String, Integer> remainingCounts = new HashMap<>();
    private final List<Map<String, String>> bbsCheckInList = new ArrayList<>();
    private int todayEarnableCoins; // 当天可获取的米游币
    private int todayEarnedCoins; // 当天已获取的米游币
    private int totalCoins; // 已获取的米游币
    private List<List<String>> postsList;
    private final tools.StatusNotifier notifier;
    private final GeetestController gt3Controller;
    private final Notification notification;
    private final HeaderManager headerManager;
    private final String cookie;

    public BBSDaily(Context context, String userId, tools.StatusNotifier notifier, GeetestController gt3Controller) {
        completedTasks.put("sign", false);
        completedTasks.put("read", false);
        completedTasks.put("like", false);
        completedTasks.put("share", false);
        remainingCounts.put("read_num", DEFAULT_READ_COUNT);
        remainingCounts.put("like_num", DEFAULT_LIKE_COUNT);
        this.gt3Controller = gt3Controller;
        this.notification = new Notification(context);
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
        // 添加需要签到的板块信息
        for (String key : name)
            bbsCheckInList.add(MiHoYoBBSConstants.name_to_forum_id(key));
        // 获取任务完成状态
        JsonObject data = checkTasksList();
        if (data != null) {
            // 任务部分未完成，运行完整程序
            getTasksList(data);
            // 获取帖子列表
            this.postsList = getList();
            signPosts();
            readPosts();
            likePosts();
            sharePosts();
            // 重新获取任务刷新签到信息
            checkTasksList();
        } else //任务已经完成
            notifier.notifyListeners("今天已经完成了所有米游币任务，明天再来吧");

        notifier.notifyListeners("今天已经获得" + this.todayEarnedCoins + "个米游币\n" + "还能获得" + this.todayEarnableCoins + "个米游币\n目前有" + this.totalCoins + "个米游币");
        notifier.notifyListeners("米游币任务执行完毕");
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
        notifier.notifyListeners("正在获取任务列表");
        Map<String, String> bbsHeaders = getBbsHeaders();
        String response = tools.sendGetRequest(Constants.Urls.BBS_TASK_URL, bbsHeaders, null);
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        JsonObject data = JsonParser.parseString(response).getAsJsonObject().get("data").getAsJsonObject();
        if (res.get("message").getAsString().contains("err") || res.get("retcode").getAsInt() == -100) {
            String errorMsg = "获取任务列表失败，你的cookie可能已过期，请重新设置cookie。";
            notification.sendErrorNotification("任务获取失败", errorMsg);
            throw new RuntimeException(errorMsg);
        }
        this.todayEarnableCoins = data.get("can_get_points").getAsInt();
        this.todayEarnedCoins = data.get("already_received_points").getAsInt();
        this.totalCoins = data.get("total_points").getAsInt();
        if (this.todayEarnableCoins == 0) { // 完成所有任务
            this.completedTasks.put("sign", true);
            this.completedTasks.put("read", true);
            this.completedTasks.put("like", true);
            this.completedTasks.put("share", true);
            return null;
        }
        return data;
    }

    /**
     * 获取任务列表，判断还有什么任务没有执行Part2
     */
    private void getTasksList(JsonObject data) {

        // 任务列表
        Map<Integer, Map<String, String>> tasks = Map.of(
                58, Map.of("attr", "sign", "done", "is_get_award"),
                59, Map.of("attr", "read", "done", "is_get_award", "num_attr", "read_num"),
                60, Map.of("attr", "like", "done", "is_get_award", "num_attr", "like_num"),
                61, Map.of("attr", "share", "done", "is_get_award")
        );
        // 查找对应id的任务，判断哪些任务完成/未完成
        for (int taskID : tasks.keySet()) {
            JsonObject missionState = null;
            for (JsonElement stateElement : data.get("states").getAsJsonArray()) {
                JsonObject state = stateElement.getAsJsonObject();
                if (state.get("mission_id").getAsInt() == taskID) {
                    missionState = state;
                    break;
                }
            }
            if (missionState == null)
                continue;
            Map<String, String> doTask = tasks.get(taskID);
            if (missionState.get(Objects.requireNonNull(doTask).get("done")).getAsBoolean()) {
                // 这个任务已经完成
                this.completedTasks.put(doTask.get("attr"), true);
            } else if (doTask.containsKey("num_attr")) {
                // 这个任务部分完成，获取任务完成次数
                JsonElement happenedTimesElement = missionState.get("happened_times");
                if (happenedTimesElement != null && !happenedTimesElement.isJsonNull()) {
                    Integer num = this.remainingCounts.get(doTask.get("num_attr"));
                    int numAttr = (num != null) ? num : 0;
                    this.remainingCounts.put(doTask.get("num_attr"), numAttr - happenedTimesElement.getAsInt());
                }
            }
        }
        if (data.get("states").getAsJsonArray().get(0).getAsJsonObject().get("mission_id").getAsInt() >= 62)
            // 新的一天（一个币都没拿）
            notifier.notifyListeners("新的一天，今天可以获得" + this.todayEarnableCoins + "个米游币");
        else // 不是新的一天（只剩部分硬币）
            notifier.notifyListeners("似乎还有任务没完成，今天还能获得" + this.todayEarnableCoins + "个米游币");
    }

    /**
     * 获取帖子列表
     *
     * @return 帖子列表
     */
    private List<List<String>> getList() {
        List<List<String>> tempList = new ArrayList<>();
        notifier.notifyListeners("正在获取帖子列表......");
        Map<String, String> bbsHeaders = getBbsHeaders();
        String response = tools.sendGetRequest(Constants.Urls.BBS_POST_URL, bbsHeaders,
                Map.of("forum_id", Objects.requireNonNull(bbsCheckInList.get(0).get("forumId")), "is_good", "false", "is_hot", "false", "page_size", "20", "sort_type", "1"));
        JsonArray list = JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("data").getAsJsonArray("list");
        for (JsonElement postElement : list) {
            JsonObject post = postElement.getAsJsonObject().getAsJsonObject("post");
            tempList.add(List.of(post.get("post_id").getAsString(), post.get("subject").getAsString()));
            notifier.notifyListeners("已获取" + tempList.size() + "个帖子");
            if (tempList.size() >= POST_LIST_CAP)
                break;
        }
        return tempList;
    }

    /**
     * 签到米游币
     */
    private void signPosts() throws Exception {
        if (Boolean.TRUE.equals(this.completedTasks.get("sign"))) {
            notifier.notifyListeners("讨论区任务(每日签到)已经完成过了~");
            return;
        }
        notifier.notifyListeners("正在签到......");
        for (Map<String, String> forum : bbsCheckInList) {
            Map<String, Object> postDataMap = new HashMap<>();
            postDataMap.put("gids", Integer.parseInt(Objects.requireNonNull(forum.get("id"))));
            String postDataJson = new Gson().toJson(postDataMap);

            JsonObject result = executeWithRetry(() -> {
                Map<String, String> header = getBbsHeaders();
                header.put("DS", headerManager.getDS_signIn(postDataJson));
                if (geetCode != null) header.putAll(geetCode);
                String response = sendPostRequest(Constants.Urls.BBS_SIGN_IN_URL, header, postDataMap);
                return JsonParser.parseString(response).getAsJsonObject();
            }, "签到");

            notifier.notifyListeners(forum.get("name") + result.get("message").getAsString());
            randomDelay();
        }
    }

    private boolean isTaskAlreadyDone(String taskKey, String taskLabel) {
        if (Boolean.TRUE.equals(completedTasks.get(taskKey))) {
            notifier.notifyListeners(taskLabel + "任务已经完成过了~");
            return true;
        }
        if (postsList == null) {
            notifier.notifyListeners("没有获取到帖子列表");
            notification.sendErrorNotification(taskLabel, "没有获取到帖子列表");
            return true;
        }
        return false;
    }

    /**
     * 看帖任务
     */
    private void readPosts() throws Exception {
        if (isTaskAlreadyDone("read", "看帖")) return;
        notifier.notifyListeners("正在看帖......");
        Integer readNum = this.remainingCounts.get("read_num");
        int num = (readNum != null) ? readNum : 3;
        for (int i = 0; i < (Math.min(num, postsList.size())); i++) {
            List<String> post = postsList.get(i);
            Map<String, String> postDataMap = new HashMap<>();
            postDataMap.put("post_id", post.get(0));
            postDataMap.put("gid", bbsCheckInList.get(0).get("id"));

            executeWithRetry(() -> {
                Map<String, String> bbsHeaders = getBbsHeaders();
                if (geetCode != null) bbsHeaders.putAll(geetCode);
                String response = tools.sendGetRequest(Constants.Urls.BBS_POST_FULL_URL, bbsHeaders, postDataMap);
                return JsonParser.parseString(response).getAsJsonObject();
            }, "看帖");

            notifier.notifyListeners("看帖: " + post.get(1) + " 成功");
            randomDelay();
        }
    }

    /**
     * 点赞帖子
     */
    private void likePosts() throws Exception {
        if (isTaskAlreadyDone("like", "点赞")) return;
        notifier.notifyListeners("正在点赞......");
        Integer likeNum = this.remainingCounts.get("like_num");
        int num = (likeNum != null) ? likeNum : 5;
        for (int i = 0; i < (Math.min(num, postsList.size())); i++) {
            List<String> post = postsList.get(i);
            Map<String, Object> postDataMap = new HashMap<>();
            postDataMap.put("post_id", post.get(0));
            postDataMap.put("is_cancel", false);

            executeWithRetry(() -> {
                Map<String, String> bbsHeaders = getBbsHeaders();
                if (geetCode != null) bbsHeaders.putAll(geetCode);
                String response = sendPostRequest(Constants.Urls.BBS_LIKE_URL, bbsHeaders, postDataMap);
                return JsonParser.parseString(response).getAsJsonObject();
            }, "点赞");

            notifier.notifyListeners("点赞: " + post.get(1) + " 成功");
            // 取消点赞
            postDataMap.put("is_cancel", true);
            Map<String, String> cancelHeaders = getBbsHeaders();
            String cancelResponse = sendPostRequest(Constants.Urls.BBS_LIKE_URL, cancelHeaders, postDataMap);
            JsonObject cancelData = JsonParser.parseString(cancelResponse).getAsJsonObject();
            if (!cancelData.get("message").getAsString().contains("err") && cancelData.get("retcode").getAsInt() == 0)
                notifier.notifyListeners("取消点赞: " + post.get(1) + " 成功");
            randomDelay();
        }
    }

    /**
     * 分享帖子
     */
    private void sharePosts() throws Exception {
        if (isTaskAlreadyDone("share", "分享")) return;
        notifier.notifyListeners("正在分享......");
        List<String> post = postsList.get(0);
        Map<String, String> postDataMap = new HashMap<>();
        postDataMap.put("entity_id", post.get(0));
        postDataMap.put("entity_type", "1");

        executeWithRetry(() -> {
            Map<String, String> bbsHeader = getBbsHeaders();
            if (geetCode != null) bbsHeader.putAll(geetCode);
            String response = tools.sendGetRequest(Constants.Urls.BBS_SHARE_URL, bbsHeader, postDataMap);
            return JsonParser.parseString(response).getAsJsonObject();
        }, "分享");

        notifier.notifyListeners("分享: " + post.get(1) + " 成功");
        randomDelay();
    }

    private Map<String, String> geetCode = null;
    private volatile boolean verificationComplete = false;

    private void performVerificationWithCallback(Map<String, String> headers) {
        verificationComplete = false;
        CaptchaVerifier.performVerification(gt3Controller, notifier, "米游币签到", headers,
                new CaptchaVerifier.VerificationCallback() {
                    @Override
                    public void onSuccess(Map<String, String> code) {
                        geetCode = code;
                        synchronized (BBSDaily.this) {
                            verificationComplete = true;
                            BBSDaily.this.notifyAll();
                        }
                    }

                    @Override
                    public void onFailure() {
                        geetCode = null;
                        synchronized (BBSDaily.this) {
                            verificationComplete = true;
                            BBSDaily.this.notifyAll();
                        }
                    }
                });
    }
}