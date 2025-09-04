package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.fixed.bbs_list;
import static com.muxiao.Venus.common.fixed.getDS;
import static com.muxiao.Venus.common.tools.sendGetRequest;
import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.content.Context;
import android.util.Log;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3ErrorBean;
import com.geetest.sdk.GT3Listener;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.muxiao.Venus.common.fixed;
import com.muxiao.Venus.common.tools;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public class Daily {
    private final Map<String, Boolean> taskDo = new HashMap<>();
    private final Map<String, Integer> taskTimes = new HashMap<>();
    private final List<Map<String, String>> this_bbsList;
    private int todayGetCoins;
    private int todayHaveGetCoins;
    private int haveCoins;
    private List<List<String>> postsList;
    private final tools.StatusNotifier notifier;
    private final Context context;
    private final String userId;
    private final com.muxiao.Venus.common.fixed fixed;
    private final HomeFragment.GT3ButtonController gt3Controller;

    public Daily(Context context, String userId, tools.StatusNotifier notifier, HomeFragment.GT3ButtonController gt3Controller) {
        this.context = context;
        this.userId = userId;
        this.fixed = new fixed(context, userId);
        this.gt3Controller = gt3Controller;

        taskDo.put("sign", false);
        taskDo.put("read", false);
        taskDo.put("like", false);
        taskDo.put("share", false);

        taskTimes.put("read_num", 3);
        taskTimes.put("like_num", 5);

        this_bbsList = new ArrayList<>();
        this.notifier = notifier;
    }

    /**
     * 米游社任务执行
     *
     * @param name 需要社区签到的板块名称（米游币的那个）可填：崩坏3、原神、崩坏2、未定事件簿、大别野、星铁、绝区零
     */
    public void initBbsTask(String[] name) {
        String stoken = tools.read(context, userId, "stoken");
        String mid = tools.read(context, userId, "mid");
        String stuid = "ltuid=" + tools.read(context, userId, "stuid");

        if (stoken == null)
            throw new RuntimeException("stoken为null,请尝试重新登陆");
        else if (mid == null)
            throw new RuntimeException("mid为null,请尝试重新登陆");
        else if (tools.read(context, userId, "stuid") == null)
            throw new RuntimeException("stuid为null,请尝试重新登陆");
        for (String key : name) {
            for (Map<String, String> map : bbs_list) {
                if (key.equals(map.get("name"))) {
                    this_bbsList.add(map);
                    break;
                }
            }
        }
        fixed.bbs_headers.put("DS", getDS(fixed.K2));
        fixed.bbs_headers.put("cookie", "stoken=" + stoken + "; mid=" + mid + "; stuid=" + stuid + ";");
        getTasksList();
        this.postsList = getList();
    }

    /**
     * 获取任务列表，判断还有什么任务没有执行
     */
    private void getTasksList() {
        notifier.notifyListeners("正在获取任务列表");
        Map<String, String> bbs_headers = new HashMap<>(fixed.bbs_headers);
        bbs_headers.put("DS", getDS(fixed.K2));
        String response = tools.sendGetRequest("https://bbs-api.miyoushe.com/apihub/sapi/getUserMissionsState", bbs_headers, null);
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        JsonObject data = JsonParser.parseString(response).getAsJsonObject().get("data").getAsJsonObject();
        if (res.get("message").getAsString().contains("err") || res.get("retcode").getAsInt() == -100) {
            throw new RuntimeException("获取任务列表失败，你的cookie可能已过期，请重新设置cookie。");
        }
        this.todayGetCoins = data.get("can_get_points").getAsInt();
        this.todayHaveGetCoins = data.get("already_received_points").getAsInt();
        this.haveCoins = data.get("total_points").getAsInt();
        Map<Integer, Map<String, String>> tasks = new HashMap<>();
        tasks.put(58, Map.of("attr", "sign", "done", "is_get_award"));
        tasks.put(59, Map.of("attr", "read", "done", "is_get_award", "num_attr", "read_num"));
        tasks.put(60, Map.of("attr", "like", "done", "is_get_award", "num_attr", "like_num"));
        tasks.put(61, Map.of("attr", "share", "done", "is_get_award"));
        if (this.todayGetCoins == -1) {
            this.taskDo.put("sign", true);
            this.taskDo.put("read", true);
            this.taskDo.put("like", true);
            this.taskDo.put("share", true);
        } else {
            for (int task : tasks.keySet()) {
                JsonObject missionState = null;
                for (JsonElement stateElement : data.get("states").getAsJsonArray()) {
                    JsonObject state = stateElement.getAsJsonObject();
                    if (state.get("mission_id").getAsInt() == task) {
                        missionState = state;
                        break;
                    }
                }
                if (missionState == null)
                    continue;
                Map<String, String> doTask = tasks.get(task);
                if (missionState.get(Objects.requireNonNull(doTask).get("done")).getAsBoolean()) {
                    this.taskDo.put(doTask.get("attr"), true);
                } else if (doTask.containsKey("num_attr")) {
                    JsonElement happenedTimesElement = missionState.get("happened_times");
                    if (happenedTimesElement != null && !happenedTimesElement.isJsonNull()) {
                        Integer num = this.taskTimes.get(doTask.get("num_attr"));
                        int num_attr = (num != null) ? num : 0;
                        this.taskTimes.put(doTask.get("num_attr"), num_attr - happenedTimesElement.getAsInt());
                    }
                }
            }
        }
        if (data.get("can_get_points").getAsInt() != 0) {
            boolean newDay = data.get("states").getAsJsonArray().get(0).getAsJsonObject().get("mission_id").getAsInt() >= 62;
            if (newDay)
                notifier.notifyListeners("新的一天，今天可以获得" + this.todayGetCoins + "个米游币");
            else
                notifier.notifyListeners("似乎还有任务没完成，今天还能获得" + this.todayGetCoins + "个米游币");
        } else notifier.notifyListeners("今天已经完成了所有米游币任务，明天再来吧");
    }

    /**
     * 获取帖子列表
     *
     * @return 帖子列表
     */
    private List<List<String>> getList() {
        if (todayGetCoins == 0)
            return null;
        List<List<String>> tempList = new ArrayList<>();
        notifier.notifyListeners("正在获取帖子列表......");
        Map<String, String> bbs_headers = new HashMap<>(fixed.bbs_headers);
        bbs_headers.put("DS", getDS(fixed.K2));
        String response = tools.sendGetRequest("https://bbs-api.miyoushe.com/post/api/getForumPostList", bbs_headers,
                Map.of("forum_id", Objects.requireNonNull(this_bbsList.get(0).get("forumId")), "is_good", "false", "is_hot", "false", "page_size", "20", "sort_type", "1"));
        JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();
        JsonObject data = jsonObject.getAsJsonObject("data");
        JsonArray list = data.getAsJsonArray("list");
        for (JsonElement postElement : list) {
            JsonObject post = postElement.getAsJsonObject().getAsJsonObject("post");
            tempList.add(List.of(post.get("post_id").getAsString(), post.get("subject").getAsString()));
            if (tempList.size() >= 5) {
                break;
            }
            notifier.notifyListeners("已获取" + tempList.size() + "个帖子");
        }
        return tempList;
    }

    /**
     * 执行所有任务
     *
     * @param sign  是否执行签到
     * @param read  是否执行阅读
     * @param like  是否执行点赞
     * @param share 是否执行分享
     */
    public void runTask(boolean sign, boolean read, boolean like, boolean share) {
        if (sign)
            signPosts();
        if (read)
            readPosts();
        if (like)
            likePosts();
        if (share)
            sharePosts();
        getTasksList();
        notifier.notifyListeners("今天已经获得" + this.todayHaveGetCoins + "个米游币\n" + "还能获得" + this.todayGetCoins + "个米游币\n目前有" + this.haveCoins + "个米游币");
        notifier.notifyListeners("米游币任务执行完毕");
    }

    /**
     * 签到米游币
     */
    private void signPosts() {
        if (Boolean.TRUE.equals(this.taskDo.get("sign"))) {
            notifier.notifyListeners("讨论区任务(每日签到)已经完成过了~");
            return;
        }
        notifier.notifyListeners("正在签到......");
        Map<String, String> header = new HashMap<>(fixed.bbs_headers);
        for (Map<String, String> forum : this_bbsList) {
            for (int retryCount = 0; retryCount < 3; retryCount++) {
                // 构建 Map 类型的请求体
                Map<String, Object> postDataMap = new HashMap<>();
                postDataMap.put("gids", Integer.parseInt(Objects.requireNonNull(forum.get("id"))));
                // 生成 DS 签名
                String postDataJson = new Gson().toJson(postDataMap);
                header.put("DS", tools.getDS2(postDataJson, fixed.SALT_6X, ""));
                String response = sendPostRequest("https://bbs-api.miyoushe.com/apihub/app/api/signIn", header, postDataMap);
                JsonObject data = JsonParser.parseString(response).getAsJsonObject();
                if (data.get("retcode").getAsInt() == 1034) {
                    // 当返回码为1034时，触发验证码验证
                    notifier.notifyListeners("需要进行人机验证...");
                    geetest(header);
                    // 等待验证完成
                    synchronized (this) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    gt3Controller.Gone();
                    gt3Controller.cleanUtils();
                } else if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                    notifier.notifyListeners(forum.get("name") + data.get("message").getAsString());
                    wait2();
                    break;
                } else if (data.get("retcode").getAsInt() == -100) {
                    throw new RuntimeException("签到失败，你的cookie可能已过期，请重新设置cookie。");
                } else {
                    notifier.notifyListeners("未知错误: " + response);
                }
            }
        }
    }

    /**
     * 看帖任务
     */
    private void readPosts() {
        if (Boolean.TRUE.equals(this.taskDo.get("read"))) {
            notifier.notifyListeners("看帖任务已经完成过了~");
            return;
        }
        if (postsList == null) {
            notifier.notifyListeners("没有获取到帖子列表");
            return;
        }
        notifier.notifyListeners("正在看帖......");
        Map<String, String> bbs_headers = new HashMap<>(fixed.bbs_headers);
        Integer readNum = this.taskTimes.get("read_num");
        int num = (readNum != null) ? readNum : 5;
        for (int i = 0; i < (Math.min(num, postsList.size())); i++) {
            List<String> post = postsList.get(i);
            for (int retryCount = 0; retryCount < 3; retryCount++) {
                // 构建 Map 类型的请求体
                Map<String, String> postDataMap = new HashMap<>();
                postDataMap.put("post_id", post.get(0));
                postDataMap.put("gid", this_bbsList.get(0).get("id"));
                bbs_headers.put("DS", getDS(fixed.K2));
                String response = tools.sendGetRequest("https://bbs-api.miyoushe.com/post/api/getPostFull", bbs_headers, postDataMap);
                JsonObject data = JsonParser.parseString(response).getAsJsonObject();
                if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                    notifier.notifyListeners("看帖: " + post.get(1) + " 成功");
                    break;
                } else if (data.get("retcode").getAsInt() == 1034) {
                    // 当返回码为1034时，触发验证码验证
                    notifier.notifyListeners("需要进行人机验证...");
                    geetest(bbs_headers);
                    // 等待验证完成
                    synchronized (this) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    // 重新尝试
                    retryCount--;
                } else if (data.get("retcode").getAsInt() == -100) {
                    throw new RuntimeException("看帖失败，你的cookie可能已过期，请重新设置cookie。");
                } else {
                    notifier.notifyListeners("看帖: " + post.get(1) + " 失败,错误信息为: " + response);
                    if (retryCount < 2) {
                        notifier.notifyListeners("正在重试，第" + (retryCount + 2) + "次尝试");
                    }
                }
            }
            wait2();
        }
    }

    /**
     * 点赞帖子
     */
    private void likePosts() {
        if (Boolean.TRUE.equals(this.taskDo.get("like"))) {
            notifier.notifyListeners("点赞任务已经完成过了~");
            return;
        }
        if (postsList == null) {
            notifier.notifyListeners("没有获取到帖子列表");
            return;
        }
        notifier.notifyListeners("正在点赞......");
        Map<String, String> bbs_headers = fixed.bbs_headers;
        Map<String, String> header = new HashMap<>(bbs_headers);
        Integer likeNum = this.taskTimes.get("like_num");
        int num = (likeNum != null) ? likeNum : 5;
        for (int i = 0; i < (Math.min(num, postsList.size())); i++) {
            List<String> post = postsList.get(i);
            for (int retryCount = 0; retryCount < 3; retryCount++) {
                // 构建 Map 类型的请求体
                Map<String, Object> postDataMap = new HashMap<>();
                postDataMap.put("post_id", post.get(0));
                postDataMap.put("is_cancel", false);
                bbs_headers.put("DS", getDS(fixed.K2));
                String response = sendPostRequest("https://bbs-api.miyoushe.com/apihub/sapi/upvotePost", header, postDataMap);
                JsonObject data = JsonParser.parseString(response).getAsJsonObject();
                if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                    notifier.notifyListeners("点赞: " + post.get(1) + " 成功");
                    wait2();

                    // 取消点赞
                    postDataMap.put("is_cancel", true);
                    bbs_headers.put("DS", getDS(fixed.K2));
                    String cancelResponse = sendPostRequest("https://bbs-api.miyoushe.com/apihub/sapi/upvotePost", header, postDataMap);
                    JsonObject cancelData = JsonParser.parseString(cancelResponse).getAsJsonObject();
                    if (!cancelData.get("message").getAsString().contains("err") && cancelData.get("retcode").getAsInt() == 0) {
                        notifier.notifyListeners("取消点赞: " + post.get(1) + " 成功");
                    }
                    break;
                } else if (data.get("retcode").getAsInt() == 1034) {
                    // 当返回码为1034时，触发验证码验证
                    notifier.notifyListeners("需要进行人机验证...");
                    geetest(header);
                    // 等待验证完成
                    synchronized (this) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    // 重新尝试
                    retryCount--;
                } else if (data.get("retcode").getAsInt() == -100) {
                    throw new RuntimeException("点赞失败，你的cookie可能已过期，请重新设置cookie。");
                } else {
                    notifier.notifyListeners("点赞: " + post.get(1) + " 失败,错误信息为: " + response);
                    if (retryCount < 2) {
                        notifier.notifyListeners("正在重试，第" + (retryCount + 2) + "次尝试");
                    }
                }
            }
            wait2();
        }
    }

    /**
     * 分享帖子
     */
    private void sharePosts() {
        if (Boolean.TRUE.equals(this.taskDo.get("share"))) {
            notifier.notifyListeners("分享任务已经完成过了~");
            return;
        }
        if (postsList == null) {
            notifier.notifyListeners("没有获取到帖子列表");
            return;
        }
        notifier.notifyListeners("正在分享......");
        Map<String, String> header = new HashMap<>(fixed.bbs_headers);
        List<String> post = postsList.get(0);
        for (int retryCount = 0; retryCount < 3; retryCount++) {
            // 构建 Map 类型的请求体
            Map<String, String> postDataMap = new HashMap<>();
            postDataMap.put("entity_id", post.get(0));
            postDataMap.put("entity_type", "1");
            header.put("DS", getDS(fixed.K2));
            String response = tools.sendGetRequest("https://bbs-api.miyoushe.com/apihub/api/getShareConf", header, postDataMap);
            JsonObject data = JsonParser.parseString(response).getAsJsonObject();
            if (!data.get("message").getAsString().contains("err") && data.get("retcode").getAsInt() == 0) {
                notifier.notifyListeners("分享: " + post.get(1) + " 成功");
                break;
            } else if (data.get("retcode").getAsInt() == 1034) {
                // 当返回码为1034时，触发验证码验证
                notifier.notifyListeners("需要进行人机验证...");
                geetest(header);
                // 等待验证完成
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                // 重新尝试
                retryCount--;
            } else if (data.get("retcode").getAsInt() == -100) {
                throw new RuntimeException("分享失败，你的cookie可能已过期，请重新设置cookie。");
            } else {
                notifier.notifyListeners("分享任务执行失败，正在执行第" + (retryCount + 2) + "次，共3次");
            }
        }
        wait2();
    }

    private void wait2() {
        try {
            Thread.sleep(1000 + new Random().nextInt(3000));
        } catch (InterruptedException ignored) {
        }
    }

    private void geetest(Map<String, String> headers) {
        String response = sendGetRequest("https://bbs-api.miyoushe.com/misc/api/createVerification?is_high=true", headers, null);
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        if (data.get("retcode").getAsInt() != 0) {
            throw new RuntimeException("获取验证码失败misc/api/createVerification" + response);
        }
        String gt = data.getAsJsonObject("data").get("gt").getAsString();
        String challenge = data.getAsJsonObject("data").get("challenge").getAsString();
        gt3Controller.Visible();
        // 配置bean文件，也可在oncreate初始化
        GT3ConfigBean gt3ConfigBean = new GT3ConfigBean();
        // 设置验证模式，1：bind，2：unbind
        gt3ConfigBean.setPattern(1);
        // 设置点击灰色区域是否消失，默认不消息
        gt3ConfigBean.setCanceledOnTouchOutside(false);
        gt3ConfigBean.setLang("zh");
        // 设置加载webview超时时间，单位毫秒，默认10000，仅且webview加载静态文件超时，不包括之前的http请求
        gt3ConfigBean.setTimeout(10000);
        // 设置webview请求超时(用户点选或滑动完成，前端请求后端接口)，单位毫秒，默认10000
        gt3ConfigBean.setWebviewTimeout(10000);
        // 设置回调监听
        gt3ConfigBean.setListener(new GT3Listener() {
            @Override
            public void onButtonClick() {
                // 在这里调用API1获取gt和challenge参数
                // 可以通过网络请求从你的服务器获取这些参数
                // 将参数传递给Geetest SDK
                try {
                    // 使用JSONObject创建JSON对象
                    JSONObject api1Json = new JSONObject();
                    api1Json.put("gt", gt);
                    api1Json.put("challenge", challenge);
                    api1Json.put("success", 1);

                    // 设置API1的JSON数据
                    gt3ConfigBean.setApi1Json(api1Json);
                    gt3Controller.getGeetestUtils().getGeetest();
                } catch (Exception e) {
                    Log.e("gt3", "创建API1 JSON时出错: " + e.getMessage());
                }
            }

            @Override
            public void onReceiveCaptchaCode(int i) {
            }

            @Override
            public void onDialogResult(String result) {
                Log.e("gt3", "GT3BaseListener-->onDialogResult-->" + result);
                // 使用Gson解析验证结果
                try {
                    Gson gson = new Gson();
                    JsonObject resultObj = gson.fromJson(result, JsonObject.class);
                    // 获取验证参数
                    String geetest_challenge = resultObj.get("geetest_challenge").getAsString();
                    String geetest_validate = resultObj.get("geetest_validate").getAsString();
                    String geetest_seccode = resultObj.get("geetest_seccode").getAsString();
                    Map<String, Object> body = new HashMap<>();
                    body.put("geetest_challenge", geetest_challenge);
                    body.put("geetest_seccode", geetest_seccode);
                    body.put("geetest_validate", geetest_validate);
                    
                    // 在后台线程执行网络请求
                    new Thread(() -> {
                        try {
                            //二次验证
                            String checkResponse = sendPostRequest("https://bbs-api.miyoushe.com/misc/api/verifyVerification", headers, body);
                            JsonObject check = JsonParser.parseString(checkResponse).getAsJsonObject();
                            if (check.get("retcode").getAsInt() == 0 && check.getAsJsonObject("data").get("challenge").getAsString() != null) {
                                // 验证成功后，继续执行签到逻辑
                                notifier.notifyListeners("人机验证成功，继续执行签到...");
                                // 存储验证信息，以便在签到时使用
                                headers.put("x-rpc-challenge", check.getAsJsonObject("data").get("challenge").getAsString());
                                // 通知等待的线程继续执行
                                synchronized (Daily.this) {
                                    Daily.this.notify();
                                }
                            }
                        } catch (Exception e) {
                            Log.e("gt3", "网络请求错误: " + e.getMessage());
                            // 通知等待的线程继续执行（即使验证失败也要继续）
                            synchronized (Daily.this) {
                                Daily.this.notify();
                            }
                        }
                    }).start();
                    gt3Controller.getGeetestUtils().showSuccessDialog();
                } catch (JsonSyntaxException e) {
                    Log.e("gt3", "JSON解析错误: " + e.getMessage());
                } catch (Exception e) {
                    Log.e("gt3", "处理验证结果时出错: " + e.getMessage());
                }
            }

            @Override
            public void onStatistics(String s) {
            }

            @Override
            public void onClosed(int i) {
            }

            @Override
            public void onSuccess(String result) {
            }

            @Override
            public void onFailed(GT3ErrorBean errorBean) {
            }
        });
        gt3Controller.init(gt3ConfigBean);
    }
}