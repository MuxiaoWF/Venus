package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.fixed.Genshin_act_id;
import static com.muxiao.Venus.common.fixed.Honkai2_act_id;
import static com.muxiao.Venus.common.fixed.Honkai3rd_act_id;
import static com.muxiao.Venus.common.fixed.HonkaiStarRail_act_id;
import static com.muxiao.Venus.common.fixed.TearsOfThemis_act_id;
import static com.muxiao.Venus.common.fixed.ZZZ_act_id;
import static com.muxiao.Venus.common.fixed.game_id_to_name;
import static com.muxiao.Venus.common.fixed.getDS;
import static com.muxiao.Venus.common.tools.sendGetRequest;
import static com.muxiao.Venus.common.tools.sendPostRequest;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Context;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3ErrorBean;
import com.geetest.sdk.GT3Listener;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.fixed;
import com.muxiao.Venus.common.tools;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public class BBSGameDaily {
    private final tools.StatusNotifier statusNotifier;
    protected final Map<String, String> game_login_headers_this;
    private final String game_name;
    private final String act_id;
    private final String player_name;
    private final String game_id;
    private List<Map<String, String>> account_list;
    public String rewards_api;
    public String is_sign_api;
    public String sign_api;
    private List<Map<String, Object>> checkin_rewards;
    private final com.muxiao.Venus.common.fixed fixed;
    private final Context context;
    private final String userId;
    private final HomeFragment.GT3ButtonController gt3Controller;
    private final Notification notification;

    /**
     * @param game_id     游戏id
     * @param game_name   游戏名
     * @param act_id      游戏代码
     * @param player_name 玩家称呼
     */
    public BBSGameDaily(Context context, String userId, String game_id, String game_name, String act_id, String player_name, tools.StatusNotifier statusNotifier, HomeFragment.GT3ButtonController gt3Controller) {
        this.game_name = game_name;
        this.fixed = new fixed(context);
        this.context = context;
        this.userId = userId;
        this.statusNotifier = statusNotifier;
        this.gt3Controller = gt3Controller;
        this.game_login_headers_this = new HashMap<>(fixed.game_login_headers);
        game_login_headers_this.put("DS", getDS(fixed.LK2));
        this.act_id = act_id;
        this.player_name = player_name;
        this.game_id = game_id;
        this.rewards_api = "https://api-takumi.mihoyo.com/event/luna/home";
        this.is_sign_api = "https://api-takumi.mihoyo.com/event/luna/info";
        this.sign_api = "https://api-takumi.mihoyo.com/event/luna/sign";
        this.checkin_rewards = new ArrayList<>();
        this.notification = new Notification(context);
        switch (act_id) {
            case Honkai2_act_id:
                game_login_headers_this.put("Referer", "https://webstatic.mihoyo.com/bbs/event/signin/bh2/index.html?bbs_auth_required=true&act_id=" + Honkai2_act_id + "&bbs_presentation_style=fullscreen&utm_source=bbs&utm_medium=mys&utm_campaign=icon");
                break;
            case Honkai3rd_act_id:
                game_login_headers_this.put("Referer", "https://webstatic.mihoyo.com/bbs/event/signin/bh3/index.html?bbs_auth_required=true&act_id=" + Honkai3rd_act_id + "&bbs_presentation_style=fullscreen&utm_source=bbs&utm_medium=mys&utm_campaign=icon");
                break;
            case HonkaiStarRail_act_id:
                game_login_headers_this.put("Origin", "https://act.mihoyo.com");
                break;
            case Genshin_act_id:
                game_login_headers_this.put("Origin", "https://act.mihoyo.com");
                game_login_headers_this.put("x-rpc-signgame", "hk4e");
                break;
            case ZZZ_act_id:
                game_login_headers_this.put("Referer", "https://act.mihoyo.com");
                game_login_headers_this.put("X-Rpc-Signgame", "zzz");
                break;
            case TearsOfThemis_act_id:
                game_login_headers_this.put("Referer", "https://webstatic.mihoyo.com/bbs/event/signin/nxx/index.html?bbs_auth_required=true&bbs_presentation_style=fullscreen&act_id=" + TearsOfThemis_act_id);
                break;
        }
    }

    private int temp = 0;

    /**
     * 更新cookie_token
     */
    private String updateCookieToken() {
        if (temp < 3) {
            temp++;
            statusNotifier.notifyListeners("CookieToken失效，尝试刷新");
            notification.sendErrorNotification("游戏签到","CookieToken失效，尝试刷新");
            String newToken = getCookieTokenByStoken();
            statusNotifier.notifyListeners("CookieToken刷新成功");
            tools.write(context, userId, "cookie_token", newToken);
            return newToken;
        } else {
            statusNotifier.notifyListeners("CookieToken刷新失败");
            notification.sendErrorNotification("游戏签到出错","CookieToken刷新失败");
            return null;
        }
    }

    /**
     * 通过stoken获取cookie_token
     */
    public String getCookieTokenByStoken() {
        String stoken = tools.read(context, userId, "stoken");
        String stuid = tools.read(context, userId, "stuid");
        if (stoken == null || stoken.isEmpty() && stuid == null || stuid.isEmpty()) {
            notification.sendErrorNotification("游戏签到出错","Stoken和Suid为空，无法自动更新CookieToken");
            throw new RuntimeException("Stoken和Suid为空，无法自动更新CookieToken");
        }
        String cookie = "stuid=" + stuid + ";stoken=" + stoken;
        if (stoken.startsWith("v2_")) {
            if (tools.read(context, userId, "mid") == null) {
                notification.sendErrorNotification("游戏签到出错","v2的stoken获取cookie_token时需要mid");
                throw new RuntimeException("v2的stoken获取cookie_token时需要mid");
            }
            cookie = cookie + ";mid=" + tools.read(context, userId, "mid");
        }
        Map<String, String> game_login_headers = new HashMap<>(fixed.game_login_headers);
        game_login_headers.put("DS", getDS(fixed.LK2));
        game_login_headers.put("cookie", cookie);
        String response = sendGetRequest("https://api-takumi.mihoyo.com/auth/api/getCookieAccountInfoBySToken", game_login_headers, null);
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        if (res.get("retcode").getAsInt() != 0) {
            notification.sendErrorNotification("游戏签到出错","获取CookieToken失败,stoken已失效请重新抓取");
            throw new RuntimeException("获取CookieToken失败,stoken已失效请重新抓取");
        }
        tools.write(context, userId, "cookie_token", res.get("data").getAsJsonObject().get("cookie_token").getAsString());
        return res.get("data").getAsJsonObject().get("cookie_token").getAsString();
    }

    /**
     * 获取米哈游账号绑定的指定游戏账号列表
     *
     * @param game_id 游戏id
     * @param headers 请求头
     * @param update  是否更新cookie_token
     * @return 账号列表List<Map < String, String>>, key为region, uid, nickname.
     */
    protected List<Map<String, String>> getAccountList(String game_id, Map<String, String> headers, Boolean update, tools.StatusNotifier statusNotifier) {
        if (statusNotifier == null)
            statusNotifier = new tools.StatusNotifier();
        String game_name = game_id_to_name.containsKey(game_id) ? game_id_to_name.get(game_id) : game_id;
        if (update) {
            String new_Cookie = updateCookieToken();
            if (new_Cookie == null) {
                statusNotifier.notifyListeners("CookieToken刷新失败，可以重新运行一下每日签到（再次点击运行按钮）");
                throw new RuntimeException("CookieToken刷新失败，可以重新运行一下每日签到（再次点击运行按钮）");
            }
            headers.put("Cookie", "cookie_token=" + new_Cookie + ";ltoken=" + tools.read(context, userId, "ltoken") + ";ltuid=" + tools.read(context, userId, "stuid") + ";account_id=" + tools.read(context, userId, "stuid"));
        }
        statusNotifier.notifyListeners("正在获取米哈游账号绑定的" + game_name + "账号列表...");
        String response = sendGetRequest("https://api-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie", headers, Map.of("game_biz", game_id));
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        if (data.get("retcode").getAsInt() == -100)
            return getAccountList(game_id, headers, true, statusNotifier);
        if (data.get("retcode").getAsInt() != 0) {
            statusNotifier.notifyListeners("获取" + game_name + "账号列表失败！");
            notification.sendErrorNotification("游戏签到","获取" + game_name + "账号列表失败！");
            return new ArrayList<>();
        }
        List<Map<String, String>> account_list = new ArrayList<>();
        for (var entry : data.getAsJsonObject("data").getAsJsonArray("list").asList()) {
            JsonObject account = entry.getAsJsonObject();
            Map<String, String> accountInfo = new HashMap<>();
            accountInfo.put("nickname", account.get("nickname").getAsString());
            accountInfo.put("game_uid", account.get("game_uid").getAsString());
            accountInfo.put("region", account.get("region").getAsString());
            accountInfo.put("game_biz", account.get("game_biz").getAsString());
            account_list.add(accountInfo);
        }
        tools.write(context, userId, game_id + "_user", new Gson().toJson(account_list));
        statusNotifier.notifyListeners("已获取到" + account_list.size() + "个" + game_name + "账号信息");
        return account_list;
    }

    /**
     * 给不同游戏初始化对象方法
     */
    public void init() {
        game_login_headers_this.put("DS", getDS(fixed.LK2));
        game_login_headers_this.put("Cookie", "cookie_token=" + tools.read(context, userId, "cookie_token") + ";ltoken=" + tools.read(context, userId, "ltoken") + ";ltuid=" + tools.read(context, userId, "stuid") + ";account_id=" + tools.read(context, userId, "stuid"));
        this.account_list = getAccountList(game_id, game_login_headers_this, false, statusNotifier);
        if (!account_list.isEmpty())
            this.checkin_rewards = getCheckinRewards();
    }

    /**
     * 获取签到奖励列表
     *
     * @return 签到奖励列表List<Map < String, Object>>, key为name名称, cnt数量.
     */
    private List<Map<String, Object>> getCheckinRewards() {
        statusNotifier.notifyListeners("正在获取签到奖励列表...");
        int max_retry = 3;
        for (int i = 0; i < max_retry; i++) {
            game_login_headers_this.put("DS", getDS(fixed.LK2));
            String response = sendGetRequest(rewards_api, game_login_headers_this, Map.of("lang", "zh-cn", "act_id", act_id));
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
            }
            statusNotifier.notifyListeners("获取签到奖励列表失败，重试次数: " + (i + 1));
            try {
                Thread.sleep(new Random().nextInt(8 - 2 + 1) + 2 * 1000);
            } catch (InterruptedException e) {
                throw new RuntimeException("thread异常" + e);
            }
        }
        statusNotifier.notifyListeners("获取签到奖励列表失败");
        return new ArrayList<>();
    }

    /**
     * 获取账号签到信息
     *
     * @param region 游戏区
     * @param uid    游戏uid
     * @param update 是否更新cookie_token
     * @return 签到信息Map<String, Object>, key为is_sign是否已签到, remain_days剩余天数, sign_cnt已签到天数, first_bind是否首次绑定.
     */
    private Map<String, Object> isSign(String region, String uid, boolean update) {
        String response = sendGetRequest(this.is_sign_api, game_login_headers_this, Map.of("lang", "zh-cn", "act_id", act_id, "region", region, "uid", uid));
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        if (data.get("retcode").getAsInt() != 0) {
            if (!update) {
                String new_cookie = updateCookieToken();
                game_login_headers_this.put("DS", getDS(fixed.LK2));
                game_login_headers_this.put("Referer", "https://act.mihoyo.com/");
                game_login_headers_this.put("Cookie", "cookie_token=" + new_cookie + ";ltoken=" + tools.read(context, userId, "ltoken") + ";ltuid=" + tools.read(context, userId, "stuid") + ";account_id=" + tools.read(context, userId, "stuid"));
                return isSign(region, uid, true);
            }
            if (data.get("retcode").getAsInt() == -100)
                return isSign(region, uid, false);
            notification.sendErrorNotification("游戏签到出错","获取账号签到信息失败！");
            throw new RuntimeException("BBS Cookie Errror" + "获取账号签到信息失败！" + response);
        }
        Map<String, Object> resultMap = new HashMap<>();
        JsonObject dataObject = data.getAsJsonObject("data");
        for (Map.Entry<String, JsonElement> entry : dataObject.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isNumber())
                    resultMap.put(entry.getKey(), value.getAsNumber());
                else if (value.getAsJsonPrimitive().isBoolean())
                    resultMap.put(entry.getKey(), value.getAsBoolean());
                else
                    resultMap.put(entry.getKey(), value.getAsString());
            } else if (value.isJsonObject())
                resultMap.put(entry.getKey(), value.getAsJsonObject().toString());
            else if (value.isJsonArray())
                resultMap.put(entry.getKey(), value.getAsJsonArray().toString());
            else
                resultMap.put(entry.getKey(), value.toString());
        }
        return resultMap;
    }

    /**
     * 签到
     *
     * @param account 账号信息Map<String, String>, key为nickname昵称, game_uid游戏uid, region游戏区.
     * @return 签到结果String
     */
    private String checkIn(Map<String, String> account) {
        Map<String, String> header = new HashMap<>(game_login_headers_this);
        int retries = 3;
        String response = "";
        for (int i = 1; i <= retries; i++) {
            if (i > 1) {
                notification.sendErrorNotification("游戏签到验证码","触发验证码，即将进行第 " + i + " 次重试，最多 " + retries + " 次");
                statusNotifier.notifyListeners("触发验证码，即将进行第 " + i + " 次重试，最多 " + retries + " 次");
            }
            response = sendPostRequest(sign_api, header, Map.of("act_id", act_id, "region", Objects.requireNonNull(account.get("region")), "uid", Objects.requireNonNull(account.get("game_uid"))));
            JsonObject data = JsonParser.parseString(response).getAsJsonObject();
            if (data.get("retcode").getAsInt() == 429) {
                try {
                    Thread.sleep(10000); // 429同ip请求次数过多，尝试sleep10s进行解决
                } catch (InterruptedException e) {
                    notification.sendErrorNotification("游戏签到出错","thread系统错误");
                    throw new RuntimeException("thread系统错误");
                }
                notification.sendErrorNotification("游戏签到出错","429 Too Many Requests ，即将进入下次请求");
                statusNotifier.notifyListeners("429 Too Many Requests ，即将进入下一次请求");
                continue;
            }
            if (data.get("retcode").getAsInt() == 0 && data.getAsJsonObject("data").get("success").getAsInt() != 0 && i < retries) {
                fixed.getFp();
                Map<String, String> record_headers = new HashMap<>(fixed.record_headers);
                record_headers.put("Cookie", "ltuid=" + tools.read(context, userId, "stuid") + ";ltoken=" + tools.read(context, userId, "stoken")
                        + ";ltoken_v2=" + tools.read(context, userId, "stoken") + ";ltuid_v2=" + tools.read(context, userId, "stuid")
                        + ";account_id=" + tools.read(context, userId, "stuid")
                        + ";account_id_v2=" + tools.read(context, userId, "stuid") + ";ltuid=" + tools.read(context, userId, "stuid")
                        + ";account_mid_v2=" + tools.read(context, userId, "mid") + ";cookie_token=" + tools.read(context, userId, "cookie_token")
                        + ";cookie_token_v2=" + tools.read(context, userId, "cookie_token") + ";mi18nLang=zh-cn;login_ticket=" + tools.read(context, userId, "login_ticket"));
                // 触发验证码验证
                notification.sendErrorNotification("游戏签到出错","需要进行人机验证...");
                statusNotifier.notifyListeners("需要进行人机验证...");
                geetest(record_headers);
                // 等待验证完成
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                gt3Controller.cleanUtils();
            } else {
                break;
            }
        }
        return response;
    }

    /**
     * 签到(最开始的)
     */
    public void signAccount() {
        if (account_list.isEmpty()) {
            notification.sendErrorNotification("游戏签到出错","没有绑定任何" + game_name + "账号");
            statusNotifier.notifyListeners("签到失败，并没有绑定任何" + game_name + "账号，请先绑定");
            return;
        }
        statusNotifier.notifyListeners(game_name + ": ");
        try {
            for (Map<String, String> account : account_list) {
                Thread.sleep(new Random().nextInt(8 - 2 + 1) + 2 * 1000);
                Map<String, Object> isData = isSign(account.get("region"), account.get("game_uid"), false);
                if (isData.get("first_bind") != null && (Boolean) Objects.requireNonNull(isData.get("first_bind"))) {
                    notification.sendErrorNotification("游戏签到出错",player_name + account.get("nickname") + "是第一次绑定米游社，请先手动签到一次");
                    statusNotifier.notifyListeners(player_name + account.get("nickname") + "是第一次绑定米游社，请先手动签到一次");
                    continue;
                }
                int signDays = ((Number) Objects.requireNonNull(isData.get("total_sign_day"))).intValue() - 1;
                if ((Boolean) Objects.requireNonNull(isData.get("is_sign"))) {
                    statusNotifier.notifyListeners(player_name + account.get("nickname") + "今天已经签到过了~");
                    signDays += 1;
                } else {
                    Thread.sleep(new Random().nextInt(8 - 2 + 1) + 2 * 1000);
                    String req = checkIn(account);
                    JsonObject data = JsonParser.parseString(req).getAsJsonObject();
                    if (data.get("retcode").getAsInt() != 429) {
                        if (data.get("retcode").getAsInt() == 0 && data.getAsJsonObject("data").get("success").getAsInt() == 0) {
                            statusNotifier.notifyListeners(player_name + account.get("nickname") + "签到成功~");
                            signDays += 2;
                        } else if (data.get("retcode").getAsInt() == -5003) {
                            statusNotifier.notifyListeners(player_name + account.get("nickname") + "今天已经签到过了~");
                        } else {
                            String s = "账号签到失败！\n" + req + "\n";
                            if (!data.get("data").isJsonNull() && data.getAsJsonObject("data").has("success") && data.getAsJsonObject("data").get("success").getAsInt() != 0)
                                s += "原因: 验证码\njson信息:" + req;
                            statusNotifier.notifyListeners(s);
                            notification.sendErrorNotification("游戏签到出错",s);
                            continue;
                        }
                    } else {
                        statusNotifier.notifyListeners(account.get("nickname") + "，本次签到失败");
                        continue;
                    }
                }
                statusNotifier.notifyListeners(account.get("nickname") + "已连续签到" + signDays + "天");
                statusNotifier.notifyListeners("今天获得的奖励是" + checkin_rewards.get(signDays - 1).get("name") + "x" + checkin_rewards.get(signDays - 1).get("cnt"));
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("thread异常" + e);
        }
    }

    private void geetest(Map<String, String> headers) {
        String response = sendGetRequest("https://bbs-api.miyoushe.com/misc/api/createVerification?is_high=true", headers, null);
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        if (data.get("retcode").getAsInt() != 0)
            throw new RuntimeException("获取验证码失败misc/api/createVerification" + response);
        String gt = data.getAsJsonObject("data").get("gt").getAsString();
        String challenge = data.getAsJsonObject("data").get("challenge").getAsString();
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
                    show_error_dialog(context, "极验验证码创建API1 JSON时出错: " + e.getMessage());
                }
            }

            @Override
            public void onReceiveCaptchaCode(int i) {
            }

            @Override
            public void onDialogResult(String result) {
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
                                statusNotifier.notifyListeners("人机验证成功，继续执行签到...");
                                // 存储验证信息，以便在签到时使用
                                headers.put("x-rpc-challenge", check.getAsJsonObject("data").get("challenge").getAsString());
                                headers.put("x-rpc-validate", geetest_validate);
                                headers.put("x-rpc-seccode", geetest_validate + "|jordan");
                                // 通知等待的线程继续执行
                                synchronized (BBSGameDaily.this) {
                                    BBSGameDaily.this.notify();
                                }
                            }
                        } catch (Exception e) {
                            show_error_dialog(context, "极验验证码网络请求错误: " + e.getMessage());
                            // 通知等待的线程继续执行（即使验证失败也要继续）
                            synchronized (BBSGameDaily.this) {
                                BBSGameDaily.this.notify();
                            }
                        }
                    }).start();
                    gt3Controller.getGeetestUtils().showSuccessDialog();
                } catch (JsonSyntaxException e) {
                    show_error_dialog(context, "极验验证码JSON解析错误: " + e.getMessage());
                } catch (Exception e) {
                    show_error_dialog(context, "极验验证码处理验证结果时出错: " + e.getMessage());
                }
            }

            @Override
            public void onStatistics(String s) {}

            @Override
            public void onClosed(int i) {}

            @Override
            public void onSuccess(String result) {}

            @Override
            public void onFailed(GT3ErrorBean errorBean) {}
        });
        gt3Controller.init(gt3ConfigBean);
    }
}