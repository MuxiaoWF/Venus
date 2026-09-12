package com.muxiao.Venus.User;

import dagger.hilt.android.AndroidEntryPoint;

import com.muxiao.Venus.BaseActivity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;

import androidx.activity.EdgeToEdge;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.muxiao.Venus.R;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.DeviceUtils;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.tools;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 国服用户登录，两种方式并存：
 * <ul>
 *   <li>扫码登录：扫码获取 gameToken → 换取 stoken → 获取 ltoken；</li>
 *   <li>账号密码登录：RSA 加密账号密码 → loginByPassword 直接拿到 stoken 与 mid → 获取 ltoken。</li>
 * </ul>
 * 两条链路最终写入相同的用户凭证（stoken / mid / stuid / ltoken），下游任务无需区分。
 * 支持重新登录模式（清除旧数据后重新登录）。
 */
@AndroidEntryPoint
public class UserLoginActivity extends BaseActivity {
    /** 登录方式：扫码 */
    private static final int MODE_QRCODE = 0;
    /** 登录方式：账号密码 */
    private static final int MODE_PASSWORD = 1;

    private TextInputEditText username_input;
    private TextInputEditText account_input;
    private TextInputEditText password_input;
    private View password_login_group;
    private MaterialButton login_btn;
    private UserManager user_manager;
    private String username;
    private ImageView login_qr_code_image;
    private View login_qr_card;
    private ExecutorService executor_service;
    private tools.StatusNotifier status_notifier;
    private boolean relogin_mode = false; // 是否为重新登录模式
    private String relogin_username = null; // 重新登录的用户名
    private int login_mode = MODE_QRCODE; // 当前登录方式

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 应用选定的主题（含换肤 overlay）
        SettingsFragment.applyAppTheme(this);
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
        account_input = findViewById(R.id.account_input);
        password_input = findViewById(R.id.password_input);
        password_login_group = findViewById(R.id.password_login_group);
        login_btn = findViewById(R.id.login_btn);
        MaterialTextView login_status_text = findViewById(R.id.login_status_text);
        ScrollView login_scroll_view = findViewById(R.id.login_scroll_view);
        login_qr_code_image = findViewById(R.id.login_qr_code_image);
        login_qr_card = findViewById(R.id.login_qr_card);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.user_login_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // 处理状态栏内边距
        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), statusBarHeight,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        // 如果是重新登录模式，自动填充用户名并隐藏输入框
        if (relogin_mode && relogin_username != null) {
            username_input.setText(relogin_username);
            username_input.setEnabled(false);
            username_input.setVisibility(View.GONE);
            login_btn.setText(getString(R.string.login_relogin_button, relogin_username));
        }

        // 登录方式切换：账号密码登录仅国服可用，国际服隐藏切换入口
        // 国服默认使用账号密码登录
        com.google.android.material.tabs.TabLayout login_mode_tabs = findViewById(R.id.login_mode_tabs);
        if (MiHoYoBBSConstants.is_oversea(this)) {
            findViewById(R.id.login_mode_card).setVisibility(View.GONE);
            applyLoginMode(MODE_QRCODE);
        } else {
            login_mode_tabs.addOnTabSelectedListener(
                    new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
                        @Override
                        public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                            applyLoginMode(tab.getPosition());
                        }

                        @Override
                        public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {
                        }

                        @Override
                        public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {
                        }
                    });
            // 默认选中「账号密码」页签，select() 会触发 onTabSelected → applyLoginMode 联动 UI
            Objects.requireNonNull(login_mode_tabs.getTabAt(MODE_PASSWORD)).select();
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
        // 回车开始执行
        username_input.setOnEditorActionListener((v, actionId, event) -> {
            login_status_text.setText("");
            handleLogin();
            return true;
        });
        password_input.setOnEditorActionListener((v, actionId, event) -> {
            login_status_text.setText("");
            handleLogin();
            return true;
        });
    }

    /**
     * 切换登录方式，联动输入区与二维码区的可见性
     */
    private void applyLoginMode(int mode) {
        login_mode = mode;
        boolean isPassword = mode == MODE_PASSWORD;
        password_login_group.setVisibility(isPassword ? View.VISIBLE : View.GONE);
        // 切走扫码模式时收起已生成的二维码卡片，避免残留旧 ticket
        if (isPassword)
            setQrVisible(false);
        if (!relogin_mode)
            login_btn.setText(isPassword ? getString(R.string.login_tab_password) : getString(R.string.login));
    }

    /** 统一控制扫码卡片（含二维码图与提示）的显隐，避免旧二维码残留 */
    private void setQrVisible(boolean visible) {
        if (login_qr_card != null)
            login_qr_card.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (login_qr_code_image != null)
            login_qr_code_image.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * 校验用户名/账号密码与重名冲突，重登录模式先清空旧凭证，
     * 最后按当前登录方式（扫码/密码）提交 LoginTask 执行。
     */
    private void handleLogin() {
        username = Objects.requireNonNull(username_input.getText()).toString().trim();
        boolean isOversea = MiHoYoBBSConstants.is_oversea(this);

        if (username.isEmpty()) {
            status_notifier.notifyListeners(getString(R.string.login_input_username));
            return;
        }
        if (login_mode == MODE_PASSWORD) {
            if (isOversea) {
                status_notifier.notifyListeners(getString(R.string.login_password_only_cn));
                return;
            }
            String account = Objects.requireNonNull(account_input.getText()).toString().trim();
            String password = Objects.requireNonNull(password_input.getText()).toString();
            if (account.isEmpty() || password.isEmpty()) {
                status_notifier.notifyListeners(getString(R.string.login_input_account));
                return;
            }
        }
        if (!relogin_mode && user_manager.getUsers().containsKey(username)) {
            // 检查用户是否属于当前服务器类型
            if (!user_manager.isUserMatchingServerType(username, isOversea)) {
                String userServer = isOversea ? getString(R.string.server_cn) : getString(R.string.server_os);
                status_notifier.notifyListeners(getString(R.string.login_username_exists_in_server, userServer));
                return;
            }
            status_notifier.notifyListeners(getString(R.string.snack_username_exists));
            return;
        }

        // 如果是重新登录模式，先清除旧的token数据（经仓储清除缓存 + 旧 SP + DataStore 三处，
        // 只清旧 SP 时读取仍会命中缓存/DataStore，导致重新登录后继续使用旧凭证）
        if (relogin_mode && relogin_username != null) {
            new com.muxiao.Venus.common.data.UserRepository(this).clear(relogin_username);
        }

        change_component_status(false);
        // 明文账号密码仅在本次任务的内存中传递，不写入任何持久化存储
        String account = login_mode == MODE_PASSWORD
                ? Objects.requireNonNull(account_input.getText()).toString().trim() : null;
        String password = login_mode == MODE_PASSWORD
                ? Objects.requireNonNull(password_input.getText()).toString() : null;
        LoginTask login_task = new LoginTask(this, login_mode, account, password);
        executor_service.execute(login_task);
    }

    /**
     * 登录进行中禁用输入与按钮、完成后恢复，避免重复提交。
     */
    private void change_component_status(Boolean status) {
        login_btn.setEnabled(status);
        username_input.setEnabled(status);
        username_input.setFocusable(status);
        username_input.setFocusableInTouchMode(status);
        account_input.setEnabled(status);
        password_input.setEnabled(status);
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
            setQrVisible(false);
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
        private String device_id;
        private final boolean isOversea;
        private final int mode;
        private final String account;
        private final String password;

        public LoginTask(Context context, int mode, String account, String password) {
            this.context = context;
            this.header_manager = new HeaderManager(context);
            this.isOversea = MiHoYoBBSConstants.is_oversea(context);
            this.mode = mode;
            this.account = account;
            this.password = password;
        }

        @Override
        // 后台线程入口：获取设备 ID 后按登录方式分流到扫码或密码登录
        public void run() {
            try {
                // 先获取设备ID
                DeviceUtils device_utils = new DeviceUtils(context);
                // 等待设备ID获取完成
                this.device_id = device_utils.waitForDeviceId();

                if (mode == MODE_PASSWORD)
                    run_password_login();
                else
                    run_qrcode_login();
            } catch (Exception e) {
                String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                status_notifier.notifyListeners("\n" + getString(R.string.dialog_error) + ": " + error_message + "\n\n" + getString(R.string.login_failed) + "\n");
                runOnUiThread(() -> {
                    change_component_status(true);
                    setQrVisible(false);
                });
            }
        }

        /**
         * 扫码登录：生成二维码 → 轮询状态 → gameToken 换 stoken
         */
        private void run_qrcode_login() throws Exception {
            status_notifier.notifyListeners("\n" + getString(R.string.login_start_task) + "\n");
            byte[] qr_code_data = get_qr_code_data();
            runOnUiThread(() -> {
                // 显示二维码
                Bitmap qr_code_bitmap = BitmapFactory.decodeByteArray(qr_code_data, 0, qr_code_data.length);
                login_qr_code_image.setImageBitmap(qr_code_bitmap);
                setQrVisible(true);
            });
            status_notifier.notifyListeners(getString(R.string.login_qr_generated));
            // 循环检查登录状态
            check_login();
        }

        /**
         * 账号密码登录：loginByPassword 直接返回 stoken 与 mid，再换 ltoken
         */
        private void run_password_login() {
            status_notifier.notifyListeners("\n" + getString(R.string.login_password_start) + "\n");
            PasswordLogin.Result result;
            try {
                result = PasswordLogin.login(context, header_manager, account, password, null);
            } catch (PasswordLogin.AigisRequiredException e) {
                // 触发极验时给出明确指引，而不是静默失败
                throw new RuntimeException(e.getMessage());
            }
            status_notifier.notifyListeners(getString(R.string.login_password_token_ok));

            tools.write(context, username, "stoken", result.stoken);
            tools.write(context, username, "mid", result.mid);
            if (result.aid != null)
                tools.write(context, username, "stuid", result.aid);
            if (result.loginTicket != null && !result.loginTicket.isEmpty())
                tools.write(context, username, "login_ticket", result.loginTicket);
            if (result.realnameRequired || result.needRealperson)
                status_notifier.notifyListeners(getString(R.string.login_password_realname));

            get_ltoken_by_stoken();
            finish_login_success();
        }

        /**
         * 两种登录方式共用的收尾：写入服务器类型、登记用户、恢复界面
         */
        private void finish_login_success() {
            // 存储服务器类型
            tools.write(context, username, "server_type", isOversea ? "1" : "0");
            runOnUiThread(() -> {
                setQrVisible(false);
                // 清空密码输入，避免明文长期驻留界面
                if (password_input != null) password_input.setText("");
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
            status_notifier.notifyListeners("\n" + getString(R.string.login_success, username) + "\n");
        }

        /**
         * 获取二维码图片，并返回二维码的数组
         *
         * @return 二维码的byte[]
         */
        public byte[] get_qr_code_data() throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("app_id", app_id);
            body.put("device", device_id);
            String qrUrl = isOversea ? Constants.Urls.OS_LOGIN_QR_URL : Constants.Urls.LOGIN_QR_URL;
            String response = tools.sendPostRequest(qrUrl, new HashMap<>(), body);
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            int retcode = retcodeOf(result);
            if (retcode != 0)
                throw new RuntimeException(getString(R.string.login_stoken_create_failed, retcode, response));
            // data.url 缺失时不再以 NPE 收场，而是走同一条「获取二维码失败」链路
            JsonObject qrData = result.has("data") && result.get("data").isJsonObject()
                    ? result.getAsJsonObject("data") : null;
            String qr_url = qrData != null && qrData.has("url") && !qrData.get("url").isJsonNull()
                    ? qrData.get("url").getAsString() : null;
            if (qr_url == null)
                throw new RuntimeException(getString(R.string.login_stoken_create_failed, retcode, response));
            String[] ticketParts = qr_url.split("ticket=");
            if (ticketParts.length < 2)
                throw new RuntimeException(getString(R.string.login_stoken_create_failed, retcode, "URL does not contain ticket parameter"));
            this.ticket = ticketParts[1];
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
     * 轮询扫码登录态（Init/Scanned/Confirmed），Confirmed 时取出 game_token 并走共用收尾。
     * 轮询次数受 {@link #MAX_QR_POLL_COUNT} 约束（约 4 分钟），避免二维码过期后线程无限空转。
     */
    private static final int MAX_QR_POLL_COUNT = 120;

    private void check_login() throws Exception {
            int times = 0;
            // 用于跟踪上一个状态，避免重复显示相同状态
            String last_status = "";
            String checkUrl = isOversea ? Constants.Urls.OS_LOGIN_CHECK_URL : Constants.Urls.LOGIN_CHECK_URL;
            while (times < MAX_QR_POLL_COUNT) {
                times++;
                Map<String, Object> body = new HashMap<>();
                body.put("app_id", app_id);
                body.put("ticket", ticket);
                body.put("device", device_id);
                String response = tools.sendPostRequest(checkUrl, null, body);
                JsonObject result = JsonParser.parseString(response).getAsJsonObject();
                int retcode = retcodeOf(result);
                if (retcode != 0)
                    throw new RuntimeException(getString(R.string.login_stoken_query_failed, retcode, response));
                JsonObject data = result.has("data") && result.get("data").isJsonObject()
                        ? result.getAsJsonObject("data") : null;
                if (data == null)
                    throw new RuntimeException(getString(R.string.login_stoken_query_failed, retcode, response));
                String stat = data.has("stat") && !data.get("stat").isJsonNull() ? data.get("stat").getAsString() : "";
                // Init（等待扫码）与 Scanned（等待确认）仅提示文案不同，合并处理；状态不变时不重复通知
                if ("Init".equals(stat) || "Scanned".equals(stat)) {
                    if (!stat.equals(last_status)) {
                        status_notifier.notifyListeners(getString(
                                "Init".equals(stat) ? R.string.login_waiting_scan : R.string.login_waiting_confirm) + times);
                        last_status = stat;
                    }
                } else if ("Confirmed".equals(stat)) {
                    // 检查 payload 和 raw 是否存在且不为 null
                    if (!data.has("payload") || !data.get("payload").isJsonObject())
                        throw new RuntimeException(getString(R.string.login_payload_missing));
                    JsonObject payload = data.getAsJsonObject("payload");
                    if (!payload.has("raw") || payload.get("raw").isJsonNull())
                        throw new RuntimeException(getString(R.string.login_raw_missing));
                    JsonObject raw = JsonParser.parseString(payload.get("raw").getAsString()).getAsJsonObject();
                    if (!raw.has("token") || raw.get("token").isJsonNull()
                            || !raw.has("uid") || raw.get("uid").isJsonNull())
                        throw new RuntimeException(getString(R.string.login_raw_missing));
                    get_stoken_by_game_token(raw.get("uid").getAsString(), raw.get("token").getAsString());
                    // 登录流程完成，走共用收尾
                    finish_login_success();
                    return;
                } else {
                    status_notifier.notifyListeners(getString(R.string.login_unknown_status) + stat + times);
                    throw new RuntimeException(getString(R.string.login_unknown_status) + stat + times);
                }
                TimeUnit.MILLISECONDS.sleep((int) (Math.random() * 500 + 1500));
            }
            throw new RuntimeException(getString(R.string.login_qr_timeout));
        }

    /**
     * 通过game_token获取stoken，通过check_login()方法登录成功获取game_token后调用
     */
        private void get_stoken_by_game_token(String stuid, String game_token) {
            Map<String, Object> body = new HashMap<>();
            body.put("account_id", Long.parseLong(stuid));
            body.put("game_token", game_token);
            Map<String, String> game_token_headers = header_manager.get_game_token_headers();
            String stokenUrl = isOversea ? Constants.Urls.OS_STOKEN_URL : Constants.Urls.STOKEN_URL;
            String response = tools.sendPostRequest(stokenUrl, game_token_headers, body);
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            int retcode = retcodeOf(result);
            if (retcode != 0)
                throw new RuntimeException(getString(R.string.login_stoken_token_failed, retcode, response));
            JsonObject data = JsonParser.parseString(response).getAsJsonObject().has("data")
                    && result.get("data").isJsonObject() ? result.getAsJsonObject("data") : null;
            JsonObject userInfo = data != null && data.has("user_info") && data.get("user_info").isJsonObject()
                    ? data.getAsJsonObject("user_info") : null;
            JsonObject tokenObj = data != null && data.has("token") && data.get("token").isJsonObject()
                    ? data.getAsJsonObject("token") : null;
            String mid = userInfo != null && userInfo.has("mid") && !userInfo.get("mid").isJsonNull()
                    ? userInfo.get("mid").getAsString() : null;
            String stoken = tokenObj != null && tokenObj.has("token") && !tokenObj.get("token").isJsonNull()
                    ? tokenObj.get("token").getAsString() : null;
            if (mid == null || stoken == null)
                throw new RuntimeException(getString(R.string.login_stoken_token_failed, retcode, response));
            tools.write(context, username, "stoken", stoken);
            tools.write(context, username, "mid", mid);
            tools.write(context, username, "game_token", game_token);
            tools.write(context, username, "stuid", stuid);
            get_ltoken_by_stoken();
        }

        /**
         * 通过stoken获取ltoken，扫码登录与账号密码登录拿到 stoken 后共用
         */
        private void get_ltoken_by_stoken() {
            Map<String, String> bbs_headers = header_manager.get_bbs_headers();
            bbs_headers.put("Cookie", "stoken=" + tools.read(context, username, "stoken") + ";mid=" + tools.read(context, username, "mid"));
            String ltokenUrl = isOversea ? Constants.Urls.OS_LTOKEN_URL : Constants.Urls.LTOKEN_URL;
            String response = tools.sendGetRequest(ltokenUrl, bbs_headers, null);
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            int retcode = retcodeOf(result);
            if (retcode != 0)
                throw new RuntimeException(getString(R.string.login_ltoken_failed, retcode, response));
            JsonObject ltokenData = result.has("data") && result.get("data").isJsonObject()
                    ? result.getAsJsonObject("data") : null;
            if (ltokenData == null || !ltokenData.has("ltoken") || ltokenData.get("ltoken").isJsonNull())
                throw new RuntimeException(getString(R.string.login_ltoken_failed, retcode, response));
            String ltoken = ltokenData.get("ltoken").getAsString();
            tools.write(context, username, "ltoken", ltoken);
        }
    }

    /** 安全读取 retcode：缺失、JsonNull 或类型不符时返回哨兵值（不可能是接口真实返回值）。 */
    private static int retcodeOf(JsonObject obj) {
        if (obj == null || !obj.has("retcode") || obj.get("retcode").isJsonNull())
            return Integer.MIN_VALUE;
        try {
            return obj.get("retcode").getAsInt();
        } catch (RuntimeException e) {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        com.muxiao.Venus.common.tools.hideKeyboardOnTouchOutside(this, event);
        return super.dispatchTouchEvent(event);
    }
}
