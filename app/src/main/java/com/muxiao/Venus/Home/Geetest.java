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
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.Logger;

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
     * 完整验证流程：先 API1 取 gt/challenge，再配置极验 SDK 弹窗并注册结果回调。
     * 若控制器为后台类型，则把 challenge/headers 暂存供前台复用同一 challenge 验证。
     *
     * @param headers  含 Cookie 的请求头（API1 与 API2 共用）
     * @param callback 验证成功/失败的回调
     * @param gt3Controller 前台或后台的极验控制器
     */
    public static void geetest(Context context, Map<String, String> headers, GeetestVerificationCallback callback, GeetestController gt3Controller) {
        Logger.debug("VenusCaptcha", "Geetest.geetest() called, controller=" + gt3Controller.getClass().getSimpleName());
        gt3Controller.createUtils(); // 防止未创建
        Logger.debug("VenusCaptcha", "Calling API1...");
        String response = sendGetRequest(Constants.Urls.GEETEST_API1_URL, headers, null);
        Logger.debug("VenusCaptcha", "API1 response: " + (response != null ? response.substring(0, Math.min(200, response.length())) : "null"));
        if (response == null) {
            callback.onVerificationFailed(context.getString(R.string.geetest_captcha_failed_network));
            return;
        }
        JsonObject data;
        try {
            data = JsonParser.parseString(response).getAsJsonObject();
        } catch (RuntimeException e) {
            // 非 JSON 响应（网关错误页、风控拦截页等）不再让 JsonSyntaxException 冒泡到任务线程
            callback.onVerificationFailed(context.getString(R.string.geetest_captcha_failed_api) + response);
            return;
        }
        JsonObject payload = JsonAccess.object(data, "data");
        String gt = JsonAccess.optString(payload, "gt", null);
        String challenge = JsonAccess.optString(payload, "challenge", null);
        if (JsonAccess.retcode(data) != 0 || gt == null || challenge == null) {
            callback.onVerificationFailed(context.getString(R.string.geetest_captcha_failed_api) + response);
            return;
        }
        // 保存后台任务的 challenge 和 headers，供前台使用同一 challenge 验证
        if (gt3Controller instanceof BackgroundGeetestController) {
            BackgroundGeetestController.savePendingChallenge(gt, challenge);
            BackgroundGeetestController.savePendingHeaders(headers);
            Logger.debug("VenusCaptcha", "Saved pending challenge and headers for background task");
        }
        setupAndCreateButton(context, gt, challenge, headers, callback, gt3Controller);
    }

    /**
     * 使用已有的 gt 和 challenge 进行验证（跳过 API1 调用）。
     * 用于前台复用后台任务的 challenge，避免重复 API1 导致 challenge 不匹配。
     */
    public static void geetestWithChallenge(Context context, String gt, String challenge, Map<String, String> headers,
                                            GeetestVerificationCallback callback, GeetestController gt3Controller) {
        Logger.debug("VenusCaptcha", "Geetest.geetestWithChallenge() called, gt=" + gt);
        gt3Controller.createUtils();
        setupAndCreateButton(context, gt, challenge, headers, callback, gt3Controller);
    }

    /**
     * 配置 GT3ConfigBean 与监听并创建验证按钮。
     * onDialogResult 内做 API2 二次验证并回传 geetCode；onClosed 仅在未成功时视为失败。
     * 后台控制器在 createButton 返回后直接从静态字段读取结果并触发回调。
     */
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
                Logger.debug("VenusCaptcha", "onButtonClick fired, calling getGeetest()");
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
                JsonObject resultObj;
                try {
                    resultObj = GSON.fromJson(result, JsonObject.class);
                } catch (RuntimeException e) {
                    resultObj = null;
                }
                // SDK 回调运行在极验内部线程，此处任何 NPE 都会直接崩溃进程，故全部走安全读取
                String geetestChallenge = JsonAccess.optString(resultObj, "geetest_challenge", "");
                String geetestValidate = JsonAccess.optString(resultObj, "geetest_validate", "");
                String geetestSeccode = JsonAccess.optString(resultObj, "geetest_seccode", "");
                if (geetestChallenge.isEmpty() || geetestValidate.isEmpty() || geetestSeccode.isEmpty()) {
                    callback.onVerificationFailed(context.getString(R.string.geetest_captcha_error, String.valueOf(result)));
                    return;
                }
                Map<String, Object> body = new HashMap<>();
                body.put("geetest_challenge", geetestChallenge);
                body.put("geetest_seccode", geetestSeccode);
                body.put("geetest_validate", geetestValidate);
                AppExecutors.get().io().execute(() -> {
                    try {
                        String checkResponse = sendPostRequest(Constants.Urls.GEETEST_API2_URL, headers, body);
                        if (checkResponse == null) {
                            callback.onVerificationFailed(context.getString(R.string.geetest_second_verify_failed_network));
                            return;
                        }
                        JsonObject check = JsonParser.parseString(checkResponse).getAsJsonObject();
                        JsonObject dataObj = JsonAccess.object(check, "data");
                        String challengeValue = JsonAccess.optString(dataObj, "challenge", null);
                        if (JsonAccess.retcode(check) == 0 && challengeValue != null) {
                            Map<String, String> geetCode = new HashMap<>();
                            geetCode.put("x-rpc-challenge", challengeValue);
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
                });
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
        Logger.debug("VenusCaptcha", "Calling gt3Controller.createButton()");
        gt3Controller.createButton(gt3ConfigBean); // 配置完毕，创建按钮

        // 后台控制器：前台验证完成后，回调链被 HomeFragment 的回调覆盖，
        // CaptchaVerificationHelper 的回调不会触发。直接从静态字段读取结果。
        if (gt3Controller instanceof BackgroundGeetestController) {
            Map<String, String> result = BackgroundGeetestController.getGeetestResult();
            if (result != null) {
                callback.onVerificationSuccess(result);
            } else {
                callback.onVerificationFailed(context.getString(R.string.geetest_wait_timeout));
            }
        }
    }
}
