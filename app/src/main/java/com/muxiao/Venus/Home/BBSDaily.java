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
    private final Map<String, Boolean> taskDo = new HashMap<>() {{
        put("sign", false);
        put("read", false);
        put("like", false);
        put("share", false);
    }};
    private final Map<String, Integer> taskTimes = new HashMap<>() {{
        put("read_num", 3);
        put("like_num", 5);
    }};
    private final List<Map<String, String>> bbs_check_in_list = new ArrayList<>();
    private int todayCanGetCoins; // 当天可获取的米游币
    private int todayHaveGetCoins; // 当天已获取的米游币
    private int haveCoins; // 已获取的米游币
    private List<List<String>> postsList;
    private final tools.StatusNotifier notifier;
    private final GeetestController gt3Controller;
    private final Notification notification;
    private final HeaderManager header_manager;
    private final String cookie;

    public BBSDaily(Context context, String userId, tools.StatusNotifier notifier, GeetestController gt3Controller) {
        this.gt3Controller = gt3Controller;
        this.notification = new Notification(context);
        this.header_manager = new HeaderManager(context);
        this.notifier = notifier;
        String stoken = tools.read(context, userId, "stoken");
        String mid = tools.read(context, userId, "mid");
        String stuid = "ltuid=" + tools.read(context, userId, "stuid");
        if (stoken == null | mid == null | tools.read(context, userId, "stuid") == null) {
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
            bbs_check_in_list.add(MiHoYoBBSConstants.name_to_forum_id(key));
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

        notifier.notifyListeners("今天已经获得" + this.todayHaveGetCoins + "个米游币\n" + "还能获得" + this.todayCanGetCoins + "个米游币\n目前有" + this.haveCoins + "个米游币");
        notifier.notifyListeners("米游币任务执行完毕");
        notification.sendNormalNotification("米游币任务完成", "今日获得" + this.todayHaveGetCoins + "个米游币，目前共有" + this.haveCoins + "个米游币");
    }

    private Map<String, String> get_bbs_headers() {
        Map<String, String> bbs_headers = header_manager.get_bbs_headers();
        bbs_headers.put("Cookie", cookie);
        return bbs_headers;
    }

    /**
     * 获取任务列表，判断还有什么任务没有执行Part1
     */
    private JsonObject checkTasksList() {
        notifier.notifyListeners("正在获取任务列表");
        Map<String, String> bbs_headers = get_bbs_headers();
        String response = tools.sendGetRequest(Constants.Urls.BBS_TASK_URL, bbs_headers, null);
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        JsonObject data = JsonParser.parseString(response).getAsJsonObject().get("data").getAsJsonObject();
        if (res.get("message").getAsString().contains("err") || res.get("retcode").getAsInt() == -100) {
            String errorMsg = "获取任务列表失败，你的cookie可能已过期，请重新设置cookie。";
            notification.sendErrorNotification("任务获取失败", errorMsg);
            throw new RuntimeException(errorMsg);
        }
        this.todayCanGetCoins = data.get("can_get_points").getAsInt();
        this.todayHaveGetCoins = data.get("already_received_points").getAsInt();
        this.haveCoins = data.get("total_points").getAsInt();
        if (this.todayCanGetCoins == 0) { // 完成所有任务
            this.taskDo.put("sign", true);
            this.taskDo.put("read", true);
            this.taskDo.put("like", true);
            this.taskDo.put("share", true);
            return null;
        }
        return data;
    }

    /**
     * 获取任务列表，判断还有什么任务没有执行Part2
     */
    private void getTasksList(JsonObject data) {

        // 任务列表
        Map<Integer, Map<String, String>> tasks = new HashMap<>() {{
            put(58, Map.of("attr", "sign", "done", "is_get_award"));
            put(59, Map.of("attr", "read", "done", "is_get_award", "num_attr", "read_num"));
            put(60, Map.of("attr", "like", "done", "is_get_award", "num_attr", "like_num"));
            put(61, Map.of("attr", "share", "done", "is_get_award"));
        }};
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
                this.taskDo.put(doTask.get("attr"), true);
            } else if (doTask.containsKey("num_attr")) {
                // 这个任务部分完成，获取任务完成次数
                JsonElement happenedTimesElement = missionState.get("happened_times");
                if (happenedTimesElement != null && !happenedTimesElement.isJsonNull()) {
                    Integer num = this.taskTimes.get(doTask.get("num_attr"));
                    int num_attr = (num != null) ? num : 0;
                    this.taskTimes.put(doTask.get("num_attr"), num_attr - happenedTimesElement.getAsInt());
                }
            }
        }
        if (data.get("states").getAsJsonArray().get(0).getAsJsonObject().get("mission_id").getAsInt() >= 62)
            // 新的一天（一个币都没拿）
            notifier.notifyListeners("新的一天，今天可以获得" + this.todayCanGetCoins + "个米游币");
        else // 不是新的一天（只剩部分硬币）
            notifier.notifyListeners("似乎还有任务没完成，今天还能获得" + this.todayCanGetCoins + "个米游币");
    }

    /**
     * 获取帖子列表
     *
     * @return 帖子列表
     */
    private List<List<String>> getList() {
        List<List<String>> tempList = new ArrayList<>();
        notifier.notifyListeners("正在获取帖子列表......");
        Map<String, String> bbs_headers = get_bbs_headers();
        String response = tools.sendGetRequest(Constants.Urls.BBS_POST_URL, bbs_headers,
                Map.of("forum_id", Objects.requireNonNull(bbs_check_in_list.get(0).get("forumId")), "is_good", "false", "is_hot", "false", "page_size", "20", "sort_type", "1"));
        JsonArray list = JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("data").getAsJsonArray("list");
        for (JsonElement postElement : list) {
            JsonObject post = postElement.getAsJsonObject().getAsJsonObject("post");
            tempList.add(List.of(post.get("post_id").getAsString(), post.get("subject").getAsString()));
            notifier.notifyListeners("已获取" + tempList.size() + "个帖子");
            if (tempList.size() >= 5)
                break;
        }
        return tempList;
    }

    /**
     * 签到米游币
     */
    private void signPosts() throws Exception {
        if (Boolean.TRUE.equals(this.taskDo.get("sign"))) {
            notifier.notifyListeners("讨论区任务(每日签到)已经完成过了~");
            return;
        }
        notifier.notifyListeners("正在签到......");
        Map<String, String> header = get_bbs_headers();
        for (Map<String, String> forum : bbs_check_in_list) {
            for (int retryCount = 0; retryCount < 3; retryCount++) {
                // 构建 Map 类型的请求体
                Map<String, Object> postDataMap = new HashMap<>();
                postDataMap.put("gids", Integer.parseInt(Objects.requireNonNull(forum.get("id"))));
                // 生成 DS 签名
                String postDataJson = new Gson().toJson(postDataMap);
                header.put("DS", header_manager.getDS_signIn(postDataJson));
                // 如果是之前进行了人机验证的，添加请求头
                if(geetest_code != null)
                    header.putAll(geetest_code);
                String response = sendPostRequest(Constants.Urls.BBS_SIGN_IN_URL, header, postDataMap);
                JsonObject data = JsonParser.parseString(response).getAsJsonObject();
                if (data.get("retcode").getAsInt() == 1034) {
                    // 当返回码为1034时，触发验证码验证
                    notifier.notifyListeners("需要进行人机验证...");
                    notification.sendErrorNotification("等待进行人机验证", "需要进行人机验证");
                    // 回调验证
                    performVerificationWithCallback(header);
                    synchronized (this) {
                        this.wait();
                    }
                } else if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                    // 签到成功
                    notifier.notifyListeners(forum.get("name") + data.get("message").getAsString());
                    break;
                } else if (data.get("retcode").getAsInt() == -100) {
                    String errorMsg = "签到失败，你的cookie可能已过期，请重新设置cookie。";
                    notifier.notifyListeners(errorMsg);
                    notification.sendErrorNotification("签到失败", errorMsg);
                    throw new RuntimeException(errorMsg);
                } else {
                    String errorMsg = "未知错误: " + response;
                    notifier.notifyListeners(errorMsg);
                    notification.sendErrorNotification("签到异常", errorMsg);
                    if (retryCount < 2)
                        notifier.notifyListeners("正在重试，第" + (retryCount + 2) + "次尝试");
                }
            }
            Thread.sleep(500 + new Random().nextInt(1500));
        }
    }

    /**
     * 看帖任务
     */
    private void readPosts() throws Exception {
        if (Boolean.TRUE.equals(this.taskDo.get("read"))) {
            notifier.notifyListeners("看帖任务已经完成过了~");
            return;
        } else if (postsList == null) {
            notifier.notifyListeners("没有获取到帖子列表");
            notification.sendErrorNotification("看帖任务", "没有获取到帖子列表");
            return;
        }
        notifier.notifyListeners("正在看帖......");
        Integer readNum = this.taskTimes.get("read_num");
        int num = (readNum != null) ? readNum : 3;
        // 循环看帖(数量取需要里和帖子列表里小的)
        for (int i = 0; i < (Math.min(num, postsList.size())); i++) {
            List<String> post = postsList.get(i);
            // 失败重复
            for (int retryCount = 0; retryCount < 3; retryCount++) {
                // 构建 Map 类型的请求体
                Map<String, String> postDataMap = new HashMap<>();
                postDataMap.put("post_id", post.get(0));
                postDataMap.put("gid", bbs_check_in_list.get(0).get("id"));
                Map<String, String> bbs_headers = get_bbs_headers();
                // 如果是之前进行了人机验证的，添加请求头
                if(geetest_code != null)
                    bbs_headers.putAll(geetest_code);
                String response = tools.sendGetRequest(Constants.Urls.BBS_POST_FULL_URL, bbs_headers, postDataMap);
                JsonObject data = JsonParser.parseString(response).getAsJsonObject();
                if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                    notifier.notifyListeners("看帖: " + post.get(1) + " 成功");
                    break;
                } else if (data.get("retcode").getAsInt() == 1034) {
                    // 当返回码为1034时，触发验证码验证
                    notifier.notifyListeners("需要进行人机验证...");
                    notification.sendErrorNotification("等待进行人机验证", "需要进行人机验证");
                    // 回调验证
                    performVerificationWithCallback(bbs_headers);
                    synchronized (this) {
                        this.wait();
                    }
                } else if (data.get("retcode").getAsInt() == -100) {
                    String errorMsg = "看帖失败，你的cookie可能已过期，请重新设置cookie。";
                    notifier.notifyListeners(errorMsg);
                    notification.sendErrorNotification("看帖失败", errorMsg);
                    throw new RuntimeException(errorMsg);
                } else {
                    String errorMsg = "看帖: " + post.get(1) + " 失败,错误信息为: " + response;
                    notifier.notifyListeners(errorMsg);
                    notification.sendErrorNotification("看帖失败", errorMsg);
                    if (retryCount < 2)
                        notifier.notifyListeners("正在重试，第" + (retryCount + 2) + "次尝试");
                }
            }
            //noinspection BusyWait
            Thread.sleep(500 + new Random().nextInt(1500));
        }
    }

    /**
     * 点赞帖子
     */
    private void likePosts() throws Exception {
        if (Boolean.TRUE.equals(this.taskDo.get("like"))) {
            notifier.notifyListeners("点赞任务已经完成过了~");
            return;
        } else if (postsList == null) {
            String msg = "没有获取到帖子列表";
            notifier.notifyListeners(msg);
            notification.sendErrorNotification("点赞任务", msg);
            return;
        }
        notifier.notifyListeners("正在点赞......");
        Integer likeNum = this.taskTimes.get("like_num");
        int num = (likeNum != null) ? likeNum : 5;
        for (int i = 0; i < (Math.min(num, postsList.size())); i++) {
            List<String> post = postsList.get(i);
            for (int retryCount = 0; retryCount < 3; retryCount++) {
                // 构建请求体
                Map<String, Object> postDataMap = new HashMap<>() {{
                    put("post_id", post.get(0));
                    put("is_cancel", false);
                }};
                Map<String, String> bbs_headers = get_bbs_headers();
                // 如果是之前进行了人机验证的，添加请求头
                if(geetest_code != null)
                    bbs_headers.putAll(geetest_code);
                String response = sendPostRequest(Constants.Urls.BBS_LIKE_URL, bbs_headers, postDataMap);
                JsonObject data = JsonParser.parseString(response).getAsJsonObject();
                if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                    notifier.notifyListeners("点赞: " + post.get(1) + " 成功");
                    // 取消点赞
                    postDataMap.put("is_cancel", true);
                    bbs_headers = get_bbs_headers();
                    String cancelResponse = sendPostRequest(Constants.Urls.BBS_LIKE_URL, bbs_headers, postDataMap);
                    JsonObject cancelData = JsonParser.parseString(cancelResponse).getAsJsonObject();
                    if (!cancelData.get("message").getAsString().contains("err") && cancelData.get("retcode").getAsInt() == 0)
                        notifier.notifyListeners("取消点赞: " + post.get(1) + " 成功");
                    break;
                } else if (data.get("retcode").getAsInt() == 1034) {
                    // 当返回码为1034时，触发验证码验证
                    notifier.notifyListeners("需要进行人机验证...");
                    notification.sendErrorNotification("等待进行人机验证", "需要进行人机验证");
                    // 回调验证
                    performVerificationWithCallback(bbs_headers);
                    synchronized (this) {
                        this.wait();
                    }
                } else if (data.get("retcode").getAsInt() == -100) {
                    String errorMsg = "点赞失败，你的cookie可能已过期，请重新设置cookie。";
                    notifier.notifyListeners(errorMsg);
                    notification.sendErrorNotification("点赞失败", errorMsg);
                    throw new RuntimeException(errorMsg);
                } else {
                    String errorMsg = "点赞: " + post.get(1) + " 失败,错误信息为: " + response;
                    notifier.notifyListeners(errorMsg);
                    notification.sendErrorNotification("点赞失败", errorMsg);
                    if (retryCount < 2)
                        notifier.notifyListeners("正在重试，第" + (retryCount + 2) + "次尝试");
                }
            }
            //noinspection BusyWait
            Thread.sleep(500 + new Random().nextInt(1500));
        }
    }

    /**
     * 分享帖子
     */
    private void sharePosts() throws Exception {
        if (Boolean.TRUE.equals(this.taskDo.get("share"))) {
            notifier.notifyListeners("分享任务已经完成过了~");
            return;
        } else if (postsList == null) {
            String msg = "没有获取到帖子列表";
            notifier.notifyListeners(msg);
            notification.sendErrorNotification("分享任务出错", msg);
            return;
        }
        notifier.notifyListeners("正在分享......");
        List<String> post = postsList.get(0);
        for (int retryCount = 0; retryCount < 3; retryCount++) {
            // 构建 Map 类型的请求体
            Map<String, String> postDataMap = new HashMap<>() {{
                put("entity_id", post.get(0));
                put("entity_type", "1");
            }};
            Map<String, String> bbs_header = get_bbs_headers();
            // 如果是之前进行了人机验证的，添加请求头
            if(geetest_code != null)
                bbs_header.putAll(geetest_code);
            String response = tools.sendGetRequest(Constants.Urls.BBS_SHARE_URL, bbs_header, postDataMap);
            JsonObject data = JsonParser.parseString(response).getAsJsonObject();
            if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                notifier.notifyListeners("分享: " + post.get(1) + " 成功");
                break;
            } else if (data.get("retcode").getAsInt() == 1034) {
                // 当返回码为1034时，触发验证码验证
                notifier.notifyListeners("需要进行人机验证...");
                notification.sendErrorNotification("等待进行人机验证", "需要进行人机验证");
                // 回调验证
                performVerificationWithCallback(bbs_header);
                synchronized (this) {
                    this.wait();
                }
            } else if (data.get("retcode").getAsInt() == -100) {
                String errorMsg = "分享失败，你的cookie可能已过期，请重新设置cookie。";
                notifier.notifyListeners(errorMsg);
                notification.sendErrorNotification("分享失败", errorMsg);
                throw new RuntimeException(errorMsg);
            } else {
                String errorMsg = "分享任务执行失败，正在执行第" + (retryCount + 2) + "次，共3次";
                notifier.notifyListeners(errorMsg);
                notification.sendErrorNotification("分享失败", errorMsg);
                if (retryCount < 2)
                    notifier.notifyListeners("正在重试，第" + (retryCount + 2) + "次尝试");
            }
        }
        Thread.sleep(500 + new Random().nextInt(1500));
    }

    private Map<String, String> geetest_code = null;

    /**
     * 回调验证
     *
     * @param headers  请求头
     */
    private void performVerificationWithCallback(Map<String, String> headers) {
        gt3Controller.updateTaskStatusWaring("米游币任务");
        Geetest.geetest(headers, new GeetestVerificationCallback() {
            @Override
            public void onVerificationSuccess(Map<String, String> code) {
                notifier.notifyListeners("人机验证成功，继续执行签到...");
                geetest_code = code;
                gt3Controller.destroyButton(); // 销毁按钮
                gt3Controller.updateTaskStatusInProgress("米游币任务");
                synchronized (BBSDaily.this) {
                    BBSDaily.this.notifyAll();
                }
            }

            @Override
            public void onVerificationFailed(String error) {
                notifier.notifyListeners("人机验证失败: " + error);
                notifier.notifyListeners("验证失败");
                geetest_code = null;
                gt3Controller.destroyButton(); // 销毁按钮
                synchronized (BBSDaily.this) {
                    BBSDaily.this.notifyAll();
                }
                throw new RuntimeException("人机验证失败");
            }
        }, gt3Controller);
    }
}