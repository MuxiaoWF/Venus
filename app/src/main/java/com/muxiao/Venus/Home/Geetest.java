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
        gt3Controller.createUtils(); // 防止未创建
        String response = sendGetRequest(Constants.Urls.GEETEST_API1_URL, headers, null);
        JsonObject data = JsonParser.parseString(response).getAsJsonObject();
        if (data.get("retcode").getAsInt() != 0) {
            callback.onVerificationFailed("获取验证码失败misc/api/createVerification" + response);
            return;
        }
        String gt = data.getAsJsonObject("data").get("gt").getAsString();
        String challenge = data.getAsJsonObject("data").get("challenge").getAsString();
        // 配置bean文件，也可在oncreate初始化
        GT3ConfigBean gt3ConfigBean = new GT3ConfigBean();
        // 设置验证模式，1：bind，2：unbind
        gt3ConfigBean.setPattern(1);
        gt3ConfigBean.setLang("zh");
        // 设置回调监听
        gt3ConfigBean.setListener(new GT3Listener() {
            @Override
            public void onButtonClick() {
                // 在这里调用API1获取gt和challenge参数
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
                    JsonObject check = JsonParser.parseString(checkResponse).getAsJsonObject();
                    if (check.get("retcode").getAsInt() == 0 && check.getAsJsonObject("data").get("challenge").getAsString() != null) {
                        Map<String, String> geetCode = new HashMap<>();
                        geetCode.put("x-rpc-challenge", check.getAsJsonObject("data").get("challenge").getAsString());
                        geetCode.put("x-rpc-validate", geetestValidate);
                        geetCode.put("x-rpc-seccode", geetestValidate + "|jordan");
                        gt3Controller.getGeetestUtils().showSuccessDialog();
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
                callback.onVerificationFailed("验证码已关闭");
            }

            @Override
            public void onSuccess(String result) {
            }

            @Override
            public void onFailed(GT3ErrorBean errorBean) {
                callback.onVerificationFailed("验证码错误：" + errorBean);
            }
        });
        gt3Controller.createButton(gt3ConfigBean); // 配置完毕，创建按钮
    }
}
