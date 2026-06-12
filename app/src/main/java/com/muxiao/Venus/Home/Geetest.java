package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.sendGetRequest;
import static com.muxiao.Venus.common.tools.sendPostRequest;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3ErrorBean;
import com.geetest.sdk.GT3Listener;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.common.Constants;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class Geetest {
    /**
     * 带回调的验证方法
     */
    public static void geetest(Map<String, String> headers, GeetestVerificationCallback callback, GeetestController gt3Controller) {
        android.util.Log.e("VenusCaptcha", "Geetest.geetest() called, controller=" + gt3Controller.getClass().getSimpleName());
        gt3Controller.createUtils(); // 防止未创建
        android.util.Log.e("VenusCaptcha", "Calling API1...");
        String response = sendGetRequest(Constants.Urls.GEETEST_API1_URL, headers, null);
        android.util.Log.e("VenusCaptcha", "API1 response: " + (response != null ? response.substring(0, Math.min(200, response.length())) : "null"));
        if (response == null) {
            callback.onVerificationFailed("获取验证码失败：网络请求无响应");
            return;
        }
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        if (data.get("retcode").getAsInt() != 0) {
            callback.onVerificationFailed("获取验证码失败misc/api/createVerification" + response);
            return;
        }
        String gt = data.getAsJsonObject("data").get("gt").getAsString();
        String challenge = data.getAsJsonObject("data").get("challenge").getAsString();
        // 保存后台任务的 challenge 和 headers，供前台使用同一 challenge 验证
        if (gt3Controller instanceof BackgroundGeetestController) {
            BackgroundGeetestController.savePendingChallenge(gt, challenge);
            BackgroundGeetestController.savePendingHeaders(headers);
            android.util.Log.e("VenusCaptcha", "Saved pending challenge and headers for background task");
        }
        setupAndCreateButton(gt, challenge, headers, callback, gt3Controller);
    }

    /**
     * 使用已有的 gt 和 challenge 进行验证（跳过 API1 调用）。
     * 用于前台复用后台任务的 challenge，避免重复 API1 导致 challenge 不匹配。
     */
    public static void geetestWithChallenge(String gt, String challenge, Map<String, String> headers,
                                            GeetestVerificationCallback callback, GeetestController gt3Controller) {
        android.util.Log.e("VenusCaptcha", "Geetest.geetestWithChallenge() called, gt=" + gt);
        gt3Controller.createUtils();
        setupAndCreateButton(gt, challenge, headers, callback, gt3Controller);
    }

    private static void setupAndCreateButton(String gt, String challenge, Map<String, String> headers,
                                             GeetestVerificationCallback callback, GeetestController gt3Controller) {
        // 配置bean文件，也可在oncreate初始化
        GT3ConfigBean gt3ConfigBean = new GT3ConfigBean();
        // 设置验证模式，1：bind，2：unbind
        gt3ConfigBean.setPattern(1);
        gt3ConfigBean.setLang("zh");
        // 标记验证是否已成功，防止 showSuccessDialog 触发 onClosed 后误报失败
        final boolean[] verificationSucceeded = {false};
        // 设置回调监听
        gt3ConfigBean.setListener(new GT3Listener() {
            @Override
            public void onButtonClick() {
                android.util.Log.e("VenusCaptcha", "onButtonClick fired, calling getGeetest()");
                // 将参数传递给Geetest SDK
                Map<String, Object> mapData = new HashMap<>();
                mapData.put("gt", gt);
                mapData.put("challenge", challenge);
                mapData.put("success", 1);
                // 设置API1的JSON数据
                gt3ConfigBean.setApi1Json(new JSONObject(mapData));
                gt3Controller.getGeetestUtils().getGeetest();
            }

            @Override
            public void onReceiveCaptchaCode(int i) {
            }

            @Override
            public void onDialogResult(String result) {
                // 使用Gson解析验证结果
                Gson gson = new Gson();
                JsonObject resultObj = gson.fromJson(result, JsonObject.class);
                // 获取验证参数
                String geetestChallenge = resultObj.get("geetest_challenge").getAsString();
                String geetestValidate = resultObj.get("geetest_validate").getAsString();
                String geetestSeccode = resultObj.get("geetest_seccode").getAsString();
                Map<String, Object> body = new HashMap<>();
                body.put("geetest_challenge", geetestChallenge);
                body.put("geetest_seccode", geetestSeccode);
                body.put("geetest_validate", geetestValidate);
                //二次验证
                new Thread(() -> {
                    String checkResponse = sendPostRequest(Constants.Urls.GEETEST_API2_URL, headers, body);
                    if (checkResponse == null) {
                        callback.onVerificationFailed("二次验证失败：网络请求无响应");
                        return;
                    }
                    JsonObject check = JsonParser.parseString(checkResponse).getAsJsonObject();
                    if (check.get("retcode").getAsInt() == 0 && check.getAsJsonObject("data").get("challenge").getAsString() != null) {
                        Map<String, String> geetCode = new HashMap<>();
                        geetCode.put("x-rpc-challenge", check.getAsJsonObject("data").get("challenge").getAsString());
                        geetCode.put("x-rpc-validate", geetestValidate);
                        geetCode.put("x-rpc-seccode", geetestValidate + "|jordan");
                        verificationSucceeded[0] = true;
                        try {
                            gt3Controller.getGeetestUtils().showSuccessDialog();
                        } catch (Exception ignored) {}
                        callback.onVerificationSuccess(geetCode);
                    } else {
                        // 验证失败，调用失败的回调
                        callback.onVerificationFailed("二次验证失败: " + checkResponse);
                    }
                }).start();
            }

            @Override
            public void onStatistics(String s) {
            }

            @Override
            public void onClosed(int i) {
                // showSuccessDialog 动画结束后会触发 onClosed，此时不应视为失败
                if (!verificationSucceeded[0]) {
                    callback.onVerificationFailed("验证码已关闭");
                }
            }

            @Override
            public void onSuccess(String result) {
            }

            @Override
            public void onFailed(GT3ErrorBean errorBean) {
                callback.onVerificationFailed("验证码错误：" + errorBean);
            }
        });
        android.util.Log.e("VenusCaptcha", "Calling gt3Controller.createButton()");
        gt3Controller.createButton(gt3ConfigBean); // 配置完毕，创建按钮
    }
}
