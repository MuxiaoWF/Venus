package com.muxiao.Venus.User;

import static com.muxiao.Venus.common.fixed.getDS;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.common.fixed;
import com.muxiao.Venus.common.tools;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class UserLoginActivity extends AppCompatActivity {
    private TextInputEditText username_input;
    private MaterialButton login_btn;
    private MaterialTextView login_status_text;
    private UserManager user_manager;
    private String username;
    private ScrollView login_scroll_view;
    private ImageView login_qr_code_image;
    // 创建一个ExecutorService用于执行后台登录任务
    private ExecutorService executor_service;
    // 保存ApplicationContext引用
    private Context app_context;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 应用选定的主题
        int selectedTheme = SettingsFragment.getSelectedTheme(this);
        setTheme(selectedTheme);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_user_login);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.user_login_main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 初始化ExecutorService
        executor_service = Executors.newSingleThreadExecutor();
        // 保存ApplicationContext引用
        app_context = getApplicationContext();

        user_manager = new UserManager(this);

        username_input = findViewById(R.id.username_input);
        login_btn = findViewById(R.id.login_btn);
        MaterialButton back_main_btn = findViewById(R.id.back_main_btn);
        login_status_text = findViewById(R.id.login_status_text);
        login_scroll_view = findViewById(R.id.login_scroll_view);
        login_qr_code_image = findViewById(R.id.login_qr_code_image);

        login_btn.setOnClickListener(v -> handleLogin());
        back_main_btn.setOnClickListener(v -> {
            Intent intent = new Intent(UserLoginActivity.this, MainActivity.class);
            startActivity(intent);
        });
    }

    /**
     * 处理登录逻辑
     */
    private void handleLogin() {
        username = Objects.requireNonNull(username_input.getText()).toString().trim();
        if (username.isEmpty()) {
            login_status_text.setText("请输入用户名");
            return;
        }
        Map<String, String> users = user_manager.getUsers();
        // 确保用户名唯一性
        if (users.containsKey(username)) {
            login_status_text.setText("用户名已存在");
            return;
        }
        try {
            if (username_input != null) {
                username_input.setEnabled(false);
                username_input.setFocusable(false);
                username_input.setFocusableInTouchMode(false);
            }
            LoginTask login_task = new LoginTask(app_context);
            executor_service.execute(login_task);
            username_input.setText("");
        } catch (Exception e) {
            String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
            show_error_dialog(this,error_message);
            login_status_text.append("\n登录失败\n");
            login_btn.setEnabled(true);
            if (username_input != null) {
                username_input.setEnabled(true);
                username_input.setFocusable(true);
                username_input.setFocusableInTouchMode(true);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 关闭ExecutorService以释放资源
        if (executor_service != null && !executor_service.isShutdown())
            executor_service.shutdown();
    }


    public class LoginTask implements Runnable {
        private byte[] qr_code_data;
        // 用于跟踪上一个状态，避免重复显示相同状态
        private String last_status = "";
        // 保存ApplicationContext引用
        private final Context context;
        private final fixed fixed_instance;

        public LoginTask(Context context) {
            // 保存ApplicationContext引用
            this.context = context;
            this.fixed_instance = new fixed(context, username);
        }

        @Override
        public void run() {
            try {
                // 确保所有header的fp都已正确初始化（在后台线程中执行）
                this.fixed_instance.getFp();
                // 在主线程更新UI
                runOnUiThread(() -> {
                    login_btn.setEnabled(false);
                    login_status_text.append("\n开始执行登录任务...\n");
                    login_qr_code_image.setVisibility(View.GONE);
                });

                // 创建一个自定义的Notifier来在UI上显示信息
                tools.StatusNotifier notifier = new tools.StatusNotifier();
                notifier.addListener(status -> runOnUiThread(() -> update_login_status(status)));

                // 执行登录流程
                runOnUiThread(() -> login_status_text.append("开始获取二维码...\n"));
                qr_code_data = get_qr_code_data(notifier, context);

                runOnUiThread(() -> {
                    login_status_text.append("二维码已生成，等待扫描...\n");
                    login_status_text.append("请自行截图并使用米游社APP-我-左上角扫描二维码...\n");
                    // 显示二维码
                    if (qr_code_data != null) {
                        Bitmap qr_code_bitmap = BitmapFactory.decodeByteArray(qr_code_data, 0, qr_code_data.length);
                        login_qr_code_image.setImageBitmap(qr_code_bitmap);
                        login_qr_code_image.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    show_error_dialog(context,error_message);
                    login_status_text.append("错误: " + error_message + "\n");
                    login_status_text.append("\n登录失败\n");
                    login_btn.setEnabled(true);
                });
            }
        }

        /**
         * 更新登录状态显示
         *
         * @param status 状态信息
         */
        private void update_login_status(String status) {
            // 如果状态发生变化，则添加新行，否则更新当前行
            if (!status.equals(last_status)) {
                login_status_text.append("当前登录状态: " + status + "\n");
                last_status = status;
            }
            // 滚动到底部
            login_scroll_view.post(() -> login_scroll_view.fullScroll(View.FOCUS_DOWN));
        }

        /**
         * 获取二维码图片，并返回二维码的数组
         *
         * @return 二维码的byte[]
         */
        public byte[] get_qr_code_data(tools.StatusNotifier notifier, Context context) {
            String app_id = "2";
            String device_id = tools.getDeviceId(context);
            Map<String, Object> body = new HashMap<>() {{
                put("app_id", app_id);
                put("device", device_id);
            }};

            try {
                String response = tools.sendPostRequest("https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/panda/qrcode/fetch", new HashMap<>(), body);
                JsonObject result = new Gson().fromJson(response, JsonObject.class);
                int retcode = result.get("retcode").getAsInt();
                if (retcode != 0) {
                    throw new RuntimeException("扫码获取stoken失败-create(RETCODE = " + retcode + "),返回信息为：" + response);
                }
                JsonObject data = result.getAsJsonObject("data");
                String qr_url = data.get("url").getAsString();
                String ticket = qr_url.split("ticket=")[1];
                Thread thread = new Thread(() -> {
                    try {
                        check_login(app_id, ticket, device_id, notifier);
                    } finally {
                        if (notifier != null) {
                            notifier.removeAllListeners();
                        }
                    }
                });
                thread.start();
                QRCodeWriter qr_code_writer = new QRCodeWriter();
                Map<EncodeHintType, Object> hints = new HashMap<>();
                hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
                BitMatrix bit_matrix = qr_code_writer.encode(qr_url, BarcodeFormat.QR_CODE, 300, 300, hints);
                Bitmap bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.RGB_565);
                for (int x = 0; x < 300; x++) {
                    for (int y = 0; y < 300; y++) {
                        bitmap.setPixel(x, y, bit_matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                    }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                return baos.toByteArray();
            } catch (Exception e) {
                if (notifier != null) {
                    notifier.removeAllListeners();
                }
                throw new RuntimeException(e);
            }
        }

        private void check_login(String app_id, String ticket, String device, tools.StatusNotifier notifier) {
            try {
                int times = 0;
                String last_stat = "";
                while (true) {
                    times++;
                    Map<String, Object> body = new HashMap<>() {{
                        put("app_id", app_id);
                        put("ticket", ticket);
                        put("device", device);
                    }};

                    String response = tools.sendPostRequest("https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/panda/qrcode/query", new HashMap<>(), body);
                    JsonObject result = new Gson().fromJson(response, JsonObject.class);
                    int retcode = result.get("retcode").getAsInt();
                    if (retcode != 0) {
                        throw new RuntimeException("扫码获取stoken失败-query(RETCODE = " + retcode + "),返回信息为：" + response);
                    }
                    JsonObject data = result.getAsJsonObject("data");
                    String stat = data.get("stat").getAsString();
                    switch (stat) {
                        case "Init":
                            // 只有状态变化时才通知
                            if (!"Init".equals(last_stat)) {
                                notifier.notifyListeners("等待扫码" + times);
                                last_stat = "Init";
                            }
                            break;
                        case "Scanned":
                            // 只有状态变化时才通知
                            if (!"Scanned".equals(last_stat)) {
                                notifier.notifyListeners("等待确认" + times);
                                last_stat = "Scanned";
                            }
                            break;
                        case "Confirmed":
                            notifier.notifyListeners("登录成功");
                            // 检查 payload 和 raw 是否存在且不为 null
                            if (!data.has("payload") || data.get("payload").isJsonNull()) {
                                throw new RuntimeException("响应中缺少 payload 字段");
                            }
                            JsonObject payload = data.getAsJsonObject("payload");
                            if (!payload.has("raw") || payload.get("raw").isJsonNull()) {
                                throw new RuntimeException("响应中缺少 raw 字段");
                            }
                            JsonElement raw_element = payload.get("raw");
                            if (raw_element.isJsonPrimitive()) {
                                String raw_string = raw_element.getAsString();
                                JsonObject raw = new Gson().fromJson(raw_string, JsonObject.class);
                                String game_token = raw.get("token").getAsString();
                                String uid = raw.get("uid").getAsString();
                                get_stoken_by_game_token(uid, game_token);
                            } else if (raw_element.isJsonObject()) {
                                JsonObject raw = raw_element.getAsJsonObject();
                                String game_token = raw.get("token").getAsString();
                                String uid = raw.get("uid").getAsString();
                                get_stoken_by_game_token(uid, game_token);
                            } else {
                                throw new RuntimeException("raw 字段格式不正确");
                            }
                            // 登录成功后退出循环
                            return;
                        default:
                            notifier.notifyListeners("未知的状态");
                            throw new RuntimeException("未知的状态");
                    }
                    TimeUnit.MILLISECONDS.sleep((int) (Math.random() * 500 + 1500));
                }
            } catch (Exception e) {
                String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    show_error_dialog(context,error_message);
                    login_status_text.append("错误: " + error_message + "\n");
                    login_btn.setEnabled(true);
                });
            } finally {
                if (notifier != null) {
                    notifier.removeAllListeners();
                }
            }
        }

        private void get_stoken_by_game_token(String stuid, String game_token) {
            try {
                JsonObject json = new JsonObject();
                json.addProperty("account_id", Integer.parseInt(stuid));
                json.addProperty("game_token", game_token);
                Map<String, Object> body = new HashMap<>() {{
                    put("account_id", Integer.parseInt(stuid));
                    put("game_token", game_token);
                }};
                String ds = tools.getDS2(json.toString(), fixed_instance.SALT_6X, "");
                Map<String, String> game_token_headers = fixed_instance.gameToken_headers;
                game_token_headers.put("DS", ds);
                String response = tools.sendPostRequest("https://api-takumi.mihoyo.com/account/ma-cn-session/app/getTokenByGameToken", game_token_headers, body);
                JsonObject result = new Gson().fromJson(response, JsonObject.class);
                if (result.get("retcode").getAsInt() != 0) {
                    throw new RuntimeException("扫码获取stoken失败-getTokenByGameToken(RETCODE = " + result.get("retcode").getAsInt() + "),返回信息为：" + response);
                }
                JsonObject data = result.getAsJsonObject("data");
                JsonObject user_info = data.getAsJsonObject("user_info");
                String mid = user_info.get("mid").getAsString();
                JsonObject token = data.getAsJsonObject("token");
                String stoken = token.get("token").getAsString();
                tools.write(context, username, "stoken", stoken);
                tools.write(context, username, "mid", mid);
                tools.write(context, username, "game_token", game_token);
                tools.write(context, username, "stuid", stuid);
                get_ltoken();
            } catch (Exception e) {
                String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    show_error_dialog(context,error_message);
                    login_status_text.append("错误: " + error_message + "\n");
                    login_btn.setEnabled(true);
                });
            }
        }

        private void get_ltoken() {
            try {
                fixed_instance.bbs_headers.put("DS", getDS(fixed_instance.K2));
                Map<String, String> bbs_headers = fixed_instance.bbs_headers;
                bbs_headers.put("Cookie", "stoken=" + tools.read(context, username, "stoken") + ";mid=" + tools.read(context, username, "mid"));
                String response = tools.sendGetRequest("https://passport-api.mihoyo.com/account/auth/api/getLTokenBySToken", bbs_headers, new HashMap<>());
                JsonObject result = new Gson().fromJson(response, JsonObject.class);
                if (result.get("retcode").getAsInt() != 0) {
                    throw new RuntimeException("获取ltoken失败-getLTokenBySToken(RETCODE = " + result.get("retcode").getAsInt() + "),返回信息为：" + response);
                }
                JsonObject data = result.getAsJsonObject("data");
                String ltoken = data.get("ltoken").getAsString();
                tools.write(context, username, "ltoken", ltoken);
                // 登录流程完成，在主线程更新UI
                runOnUiThread(() -> {
                    login_status_text.append("\n登录成功！\n");
                    login_btn.setEnabled(true);
                    // 登录成功后再添加用户并设置为当前用户
                    user_manager.addUser(username);
                    user_manager.setCurrentUser(username);
                    login_status_text.append("用户 " + username + " 添加成功\n");
                    if (username_input != null) {
                        username_input.setEnabled(true);
                        username_input.setFocusable(true);
                        username_input.setFocusableInTouchMode(true);
                    }
                });
            } catch (Exception e) {
                String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    show_error_dialog(context,error_message);
                    login_status_text.append("错误: " + error_message + "\n");
                    login_btn.setEnabled(true);
                });
            }
        }
    }
}
