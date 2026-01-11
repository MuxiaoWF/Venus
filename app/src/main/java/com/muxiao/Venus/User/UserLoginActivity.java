package com.muxiao.Venus.User;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.DeviceUtils;
import com.muxiao.Venus.common.HeaderManager;
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
    private UserManager user_manager;
    private String username;
    private ImageView login_qr_code_image;
    private ExecutorService executor_service;
    private tools.StatusNotifier status_notifier;
    private boolean relogin_mode = false; // 是否为重新登录模式
    private String relogin_username = null; // 重新登录的用户名

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 应用选定的主题
        int selectedTheme = SettingsFragment.getSelectedTheme(this);
        setTheme(selectedTheme);
        setContentView(R.layout.activity_user_login);

        // 设置状态栏
        EdgeToEdge.enable(this);

        // 单线程池
        executor_service = Executors.newSingleThreadExecutor();
        user_manager = new UserManager(this);

        // 检查是否为重新登录模式
        Intent intent = getIntent();
        relogin_mode = intent.getBooleanExtra("RELOGIN_MODE", false);
        relogin_username = intent.getStringExtra("USERNAME");

        username_input = findViewById(R.id.username_input);
        login_btn = findViewById(R.id.login_btn);
        MaterialButton back_main_btn = findViewById(R.id.back_main_btn);
        MaterialTextView login_status_text = findViewById(R.id.login_status_text);
        ScrollView login_scroll_view = findViewById(R.id.login_scroll_view);
        login_qr_code_image = findViewById(R.id.login_qr_code_image);

        // 如果是重新登录模式，自动填充用户名并隐藏输入框
        if (relogin_mode && relogin_username != null) {
            username_input.setText(relogin_username);
            username_input.setEnabled(false);
            username_input.setVisibility(View.GONE);
            login_btn.setText(String.format("重新登录%s", relogin_username));
        }

        // Notifier更新信息
        status_notifier = new tools.StatusNotifier();
        status_notifier.addListener(status -> runOnUiThread(() -> {
            login_status_text.append(status);
            // 滚动到底部
            login_scroll_view.post(() -> login_scroll_view.fullScroll(View.FOCUS_DOWN));
        }));
        login_btn.setOnClickListener(v -> {
            login_status_text.setText("");
            handleLogin();
        });
        back_main_btn.setOnClickListener(v -> {
            Intent intentB = new Intent(UserLoginActivity.this, MainActivity.class);
            intentB.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intentB);
            finish();
        });
        // 回车开始执行
        username_input.setOnEditorActionListener((v, actionId, event) -> {
            login_status_text.setText("");
            handleLogin();
            return true;
        });
    }

    /**
     * 处理登录逻辑
     */
    private void handleLogin() {
        username = Objects.requireNonNull(username_input.getText()).toString().trim();
        if (username.isEmpty()) {
            status_notifier.notifyListeners("请输入用户名");
            return;
        } else if (!relogin_mode && user_manager.getUsers().containsKey(username)) {// 确保用户名唯一性，仅在非重新登录模式下检查
            status_notifier.notifyListeners("用户名已存在");
            return;
        }

        // 如果是重新登录模式，先清除旧的token数据
        if (relogin_mode && relogin_username != null) {
            // 获取SharedPreferences并清除其中的数据
            android.content.SharedPreferences sharedPreferences = getSharedPreferences("user_" + relogin_username, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.clear(); // 清空所有token数据
            editor.apply();
        }

        change_component_status(false);
        LoginTask login_task = new LoginTask(this);
        executor_service.execute(login_task);
    }

    /**
     * 更改组件状态
     */
    private void change_component_status(Boolean status) {
        login_btn.setEnabled(status);
        username_input.setEnabled(status);
        username_input.setFocusable(status);
        username_input.setFocusableInTouchMode(status);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 关闭ExecutorService
        if (executor_service != null && !executor_service.isShutdown())
            executor_service.shutdown();
        // 移除监听器
        if (status_notifier != null)
            status_notifier.removeAllListeners();
        // 清理二维码图片资源
        if (login_qr_code_image != null) {
            BitmapDrawable drawable = (BitmapDrawable) login_qr_code_image.getDrawable();
            if (drawable != null) {
                Bitmap bitmap = drawable.getBitmap();
                if (bitmap != null && !bitmap.isRecycled())
                    bitmap.recycle();
            }
        }
    }


    public class LoginTask implements Runnable {
        private final Context context;
        private final HeaderManager header_manager;
        private final String app_id = "2";
        private String ticket;
        private final String device_id;

        public LoginTask(Context context) {
            this.context = context;
            this.header_manager = new HeaderManager(context);
            DeviceUtils device_utils = new DeviceUtils(context);
            this.device_id = device_utils.generateDeviceId();
        }

        @Override
        public void run() {
            try {
                status_notifier.notifyListeners("\n开始执行登录任务...\n开始获取二维码...\n");
                byte[] qr_code_data = get_qr_code_data();
                runOnUiThread(() -> {
                    // 显示二维码
                    Bitmap qr_code_bitmap = BitmapFactory.decodeByteArray(qr_code_data, 0, qr_code_data.length);
                    login_qr_code_image.setImageBitmap(qr_code_bitmap);
                    login_qr_code_image.setVisibility(View.VISIBLE);
                });
                status_notifier.notifyListeners("二维码已生成，等待扫描...\n请自行截图并使用米游社APP-我-左上角扫描二维码...\n");
                // 循环检查登录状态
                check_login();
            } catch (Exception e) {
                String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                status_notifier.notifyListeners("\n错误: " + error_message + "\n\n登录失败\n");
                runOnUiThread(() -> {
                    change_component_status(true);
                    login_qr_code_image.setVisibility(View.GONE);
                });
            }
        }

        /**
         * 获取二维码图片，并返回二维码的数组
         *
         * @return 二维码的byte[]
         */
        public byte[] get_qr_code_data() throws Exception {
            Map<String, Object> body = new HashMap<>() {{
                put("app_id", app_id);
                put("device", device_id);
            }};
            String response = tools.sendPostRequest(Constants.Urls.LOGIN_QR_URL, new HashMap<>(), body);
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            int retcode = result.get("retcode").getAsInt();
            if (retcode != 0)
                throw new RuntimeException("扫码获取stoken失败-create(RETCODE = " + retcode + "),返回信息为：" + response);
            String qr_url = result.getAsJsonObject("data").get("url").getAsString();
            this.ticket = qr_url.split("ticket=")[1];
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            // 根据屏幕密度调整二维码大小
            int qrSize = (int) (getResources().getDisplayMetrics().density * 200);
            BitMatrix bitMatrix = qrCodeWriter.encode(qr_url, BarcodeFormat.QR_CODE, Math.max(qrSize, 300), Math.max(qrSize, 300), hints);
            Bitmap bitmap = Bitmap.createBitmap(bitMatrix.getWidth(), bitMatrix.getHeight(), Bitmap.Config.RGB_565);
            for (int x = 0; x < bitMatrix.getWidth(); x++)
                for (int y = 0; y < bitMatrix.getHeight(); y++)
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            // 回收Bitmap资源
            bitmap.recycle();
            return baos.toByteArray();
        }

        /**
         * 检查登录状态
         */
        private void check_login() throws Exception {
            int times = 0;
            // 用于跟踪上一个状态，避免重复显示相同状态
            String last_status = "";
            while (true) {
                times++;
                Map<String, Object> body = new HashMap<>() {{
                    put("app_id", app_id);
                    put("ticket", ticket);
                    put("device", device_id);
                }};
                String response = tools.sendPostRequest(Constants.Urls.LOGIN_CHECK_URL, null, body);
                JsonObject result = JsonParser.parseString(response).getAsJsonObject();
                int retcode = result.get("retcode").getAsInt();
                if (retcode != 0)
                    throw new RuntimeException("扫码获取stoken失败-query(RETCODE = " + retcode + "),返回信息为：" + response);
                JsonObject data = result.getAsJsonObject("data");
                String stat = data.get("stat").getAsString();
                switch (stat) {
                    case "Init":
                        // 只有状态变化时才通知
                        if (!"Init".equals(last_status)) {
                            status_notifier.notifyListeners("等待扫码" + times);
                            last_status = "Init";
                        }
                        break;
                    case "Scanned":
                        if (!"Scanned".equals(last_status)) {
                            status_notifier.notifyListeners("等待确认" + times);
                            last_status = "Scanned";
                        }
                        break;
                    case "Confirmed":
                        // 检查 payload 和 raw 是否存在且不为 null
                        if (!data.has("payload") || data.get("payload").isJsonNull())
                            throw new RuntimeException("响应中缺少 payload 字段");
                        JsonObject payload = data.getAsJsonObject("payload");
                        if (!payload.has("raw") || payload.get("raw").isJsonNull())
                            throw new RuntimeException("响应中缺少 raw 字段");
                        String raw_string = payload.get("raw").getAsString();
                        JsonObject raw = JsonParser.parseString(raw_string).getAsJsonObject();
                        String game_token = raw.get("token").getAsString();
                        String uid = raw.get("uid").getAsString();
                        get_stoken_by_game_token(uid, game_token);
                        // 登录流程完成，在主线程更新UI
                        runOnUiThread(() -> {
                            login_qr_code_image.setVisibility(View.GONE);
                            // 如果是重新登录模式，则更新现有用户的token
                            if (relogin_mode && relogin_username != null) {
                                // 设置当前用户为重新登录的用户
                                user_manager.setCurrentUser(relogin_username);
                            } else {
                                // 登录成功后再添加用户并设置为当前用户
                                user_manager.addUser(username);
                                user_manager.setCurrentUser(username);
                            }
                            change_component_status(true);
                        });
                        status_notifier.notifyListeners("\n登录成功！\n用户 " + username + " 添加成功\n");
                        return;
                    default:
                        status_notifier.notifyListeners("未知的状态" + stat + times);
                        throw new RuntimeException("未知的状态" + stat + times);
                }
                TimeUnit.MILLISECONDS.sleep((int) (Math.random() * 500 + 1500));
            }
        }

        /**
         * 通过game_token获取stoken，通过check_login()方法登录成功获取game_token后调用
         */
        private void get_stoken_by_game_token(String stuid, String game_token) {
            JsonObject json = new JsonObject();
            json.addProperty("account_id", Integer.parseInt(stuid));
            json.addProperty("game_token", game_token);
            Map<String, Object> body = new HashMap<>() {{
                put("account_id", Integer.parseInt(stuid));
                put("game_token", game_token);
            }};
            Map<String, String> game_token_headers = header_manager.get_game_token_headers();
            String response = tools.sendPostRequest(Constants.Urls.STOKEN_URL, game_token_headers, body);
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            if (result.get("retcode").getAsInt() != 0)
                throw new RuntimeException("扫码获取stoken失败-getTokenByGameToken(RETCODE = " + result.get("retcode").getAsInt() + "),返回信息为：" + response);
            JsonObject data = result.getAsJsonObject("data");
            String mid = data.getAsJsonObject("user_info").get("mid").getAsString();
            String stoken = data.getAsJsonObject("token").get("token").getAsString();
            tools.write(context, username, "stoken", stoken);
            tools.write(context, username, "mid", mid);
            tools.write(context, username, "game_token", game_token);
            tools.write(context, username, "stuid", stuid);
            get_ltoken_by_stoken();
        }

        /**
         * 通过stoken获取ltoken，通过get_stoken_by_game_token()方法获取stoken后调用
         */
        private void get_ltoken_by_stoken() {
            Map<String, String> bbs_headers = header_manager.get_bbs_headers();
            bbs_headers.put("Cookie", "stoken=" + tools.read(context, username, "stoken") + ";mid=" + tools.read(context, username, "mid"));
            String response = tools.sendGetRequest(Constants.Urls.LTOKEN_URL, bbs_headers, null);
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            if (result.get("retcode").getAsInt() != 0)
                throw new RuntimeException("获取ltoken失败-getLTokenBySToken(RETCODE = " + result.get("retcode").getAsInt() + "),返回信息为：" + response);
            String ltoken = result.getAsJsonObject("data").get("ltoken").getAsString();
            tools.write(context, username, "ltoken", ltoken);
        }
    }
}
