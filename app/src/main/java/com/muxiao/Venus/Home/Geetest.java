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
                Map<String, Object> mapData = new HashMap<>() {{
                    put("gt", gt);
                    put("challenge", challenge);
                    put("success", 1);
                }};
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
                String geetest_challenge = resultObj.get("geetest_challenge").getAsString();
                String geetest_validate = resultObj.get("geetest_validate").getAsString();
                String geetest_seccode = resultObj.get("geetest_seccode").getAsString();
                Map<String, Object> body = new HashMap<>();
                body.put("geetest_challenge", geetest_challenge);
                body.put("geetest_seccode", geetest_seccode);
                body.put("geetest_validate", geetest_validate);
                //二次验证
                new Thread(() -> {
                    String checkResponse = sendPostRequest(Constants.Urls.GEETEST_API2_URL, headers, body);
                    JsonObject check = JsonParser.parseString(checkResponse).getAsJsonObject();
                    if (check.get("retcode").getAsInt() == 0 && check.getAsJsonObject("data").get("challenge").getAsString() != null) {
                        // 存储验证信息，以便在签到时使用
                        Map<String, String> geetest_code = new HashMap<>() {{
                            put("x-rpc-challenge", check.getAsJsonObject("data").get("challenge").getAsString());
                            put("x-rpc-validate", geetest_validate);
                            put("x-rpc-seccode", geetest_validate + "|jordan");
                        }};
                        gt3Controller.getGeetestUtils().showSuccessDialog();
                        // 调用成功的回调
                        callback.onVerificationSuccess(geetest_code);
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
