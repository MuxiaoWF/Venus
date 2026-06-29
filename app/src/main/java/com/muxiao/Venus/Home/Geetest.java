package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.sendGetRequest;
import static com.muxiao.Venus.common.tools.sendPostRequest;

import android.content.Context;

import com.geetest.sdk.GT3ConfigBean;
import com.muxiao.Venus.R;
import com.geetest.sdk.GT3ErrorBean;
import com.geetest.sdk.GT3Listener;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.common.Constants;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 极验验证码封装：调用API1获取gt/challenge → SDK弹出验证 → API2二次验证。
 * 支持前台（HomeFragment）和后台（BackgroundGeetestController）两种模式。
 */
public class Geetest {
    private static final Gson GSON = new Gson();

    /**
     * 带回调的验证方法
     */
    public static void geetest(Context context, Map<String, String> headers, GeetestVerificationCallback callback, GeetestController gt3Controller) {
        android.util.Log.e("VenusCaptcha", "Geetest.geetest() called, controller=" + gt3Controller.getClass().getSimpleName());
        gt3Controller.createUtils(); // 防止未创建
        android.util.Log.e("VenusCaptcha", "Calling API1...");
        String response = sendGetRequest(Constants.Urls.GEETEST_API1_URL, headers, null);
        android.util.Log.e("VenusCaptcha", "API1 response: " + (response != null ? response.substring(0, Math.min(200, response.length())) : "null"));
        if (response == null) {
            callback.onVerificationFailed(context.getString(R.string.geetest_captcha_failed_network));
            return;
        }
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        if (data.get("retcode").getAsInt() != 0) {
            callback.onVerificationFailed(context.getString(R.string.geetest_captcha_failed_api) + response);
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
        setupAndCreateButton(context, gt, challenge, headers, callback, gt3Controller);
    }

    /**
     * 使用已有的 gt 和 challenge 进行验证（跳过 API1 调用）。
     * 用于前台复用后台任务的 challenge，避免重复 API1 导致 challenge 不匹配。
     */
    public static void geetestWithChallenge(Context context, String gt, String challenge, Map<String, String> headers,
                                            GeetestVerificationCallback callback, GeetestController gt3Controller) {
        android.util.Log.e("VenusCaptcha", "Geetest.geetestWithChallenge() called, gt=" + gt);
        gt3Controller.createUtils();
        setupAndCreateButton(context, gt, challenge, headers, callback, gt3Controller);
    }

    private static void setupAndCreateButton(Context context, String gt, String challenge, Map<String, String> headers,
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
                JsonObject resultObj = GSON.fromJson(result, JsonObject.class);
                String geetestChallenge = resultObj.get("geetest_challenge").getAsString();
                String geetestValidate = resultObj.get("geetest_validate").getAsString();
                String geetestSeccode = resultObj.get("geetest_seccode").getAsString();
                Map<String, Object> body = new HashMap<>();
                body.put("geetest_challenge", geetestChallenge);
                body.put("geetest_seccode", geetestSeccode);
                body.put("geetest_validate", geetestValidate);
                new Thread(() -> {
                    try {
                        String checkResponse = sendPostRequest(Constants.Urls.GEETEST_API2_URL, headers, body);
                        if (checkResponse == null) {
                            callback.onVerificationFailed(context.getString(R.string.geetest_second_verify_failed_network));
                            return;
                        }
                        JsonObject check = JsonParser.parseString(checkResponse).getAsJsonObject();
                        JsonObject dataObj = check.getAsJsonObject("data");
                        if (check.get("retcode").getAsInt() == 0 && dataObj.has("challenge") && !dataObj.get("challenge").isJsonNull()) {
                            Map<String, String> geetCode = new HashMap<>();
                            geetCode.put("x-rpc-challenge", dataObj.get("challenge").getAsString());
                            geetCode.put("x-rpc-validate", geetestValidate);
                            geetCode.put("x-rpc-seccode", geetestValidate + "|jordan");
                            verificationSucceeded[0] = true;
                            callback.onVerificationSuccess(geetCode);
                            try { gt3Controller.getGeetestUtils().showSuccessDialog(); } catch (Exception ignored) {}
                        } else {
                            callback.onVerificationFailed(context.getString(R.string.geetest_second_verify_failed, checkResponse));
                        }
                    } catch (Exception e) {
                        callback.onVerificationFailed(context.getString(R.string.geetest_captcha_error, e.getMessage()));
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
                    callback.onVerificationFailed(context.getString(R.string.geetest_captcha_closed));
                }
            }

            @Override
            public void onSuccess(String result) {
            }

            @Override
            public void onFailed(GT3ErrorBean errorBean) {
                callback.onVerificationFailed(context.getString(R.string.geetest_captcha_error, errorBean));
            }
        });
        android.util.Log.e("VenusCaptcha", "Calling gt3Controller.createButton()");
        gt3Controller.createButton(gt3ConfigBean); // 配置完毕，创建按钮

        // 后台控制器：前台验证完成后，回调链被 HomeFragment 的回调覆盖，
        // CaptchaVerificationHelper 的回调不会触发。直接从静态字段读取结果。
        if (gt3Controller instanceof BackgroundGeetestController) {
            Map<String, String> result = BackgroundGeetestController.getGeetestResult();
            if (result != null) {
                callback.onVerificationSuccess(result);
            } else {
                callback.onVerificationFailed("Verification failed or timed out");
            }
        }
    }
}
