package com.muxiao.Venus.Link;

import static com.muxiao.Venus.common.tools.copyToClipboard;
import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.ContextCompat;

import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.PageController;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.BatteryHelper;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.ui.compose.AdbGuideDialog;
import com.muxiao.Venus.common.ui.compose.BottomBarOverlay;
import com.muxiao.Venus.common.ui.compose.VenusDialogs;
import com.muxiao.Venus.common.ui.compose.LinkRow;
import com.muxiao.Venus.common.ui.compose.LinkScreenKt;
import com.muxiao.Venus.common.ui.compose.LinkScreenView;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
* 抽卡链接页控制器：原神/绝区零为「点行即获取」（结果与复制长在行下）；云游戏为导航行，打开内置 WebView
* 访问米游社抽卡记录页并自动拦截含 authkey 的 URL；VPN 抓包为导航行，启动本地 VPN 后在星铁内打开抽卡记录页，
* 旁路解密 KCP 流量捕获 authkey。
* 界面为 Compose（{@link LinkScreenView}）：本类只保留业务与状态机，控件表现一律由宿主状态派生。
*/
public class LinkFragment implements PageController {

    private final MainActivity activity;

    /** VPN 抓包服务授权回调（MainActivity 注册后注入）。 */
    private ActivityResultLauncher<android.content.Intent> vpnLauncher;

    /**
    * 电池优化豁免授权回调：无论用户是否豁免，返回后都续启抓包流程
    * （未豁免时仅效果打折——挂机熄屏后可能被省电策略掐网，不影响前台使用）。
    */
    private ActivityResultLauncher<android.content.Intent> batteryLauncher;

    /**
    * 捕获结果广播接收器（CaptureService/PacketAnalyzer 发出，均带包名限制）：
    * UPDATING = 缺键、云端密钥表更新中（抓包继续，重放机制衔接）；
    * GET_AUTHKEY = 捕获成功，按判定渠道拼抽卡记录 URL 复制并提示；
    * CAPTURE_FAILED = 补拉落定后仍缺键（网络失败或服务端未收录），行内提示原因。
    * 三条路径都同步 status_vpn 行文案——触发时用户多半在游戏里、Snackbar 一闪即逝，
    * 行内持久状态是切回应用后唯一可见的反馈（show 也会兜底回显）。
    */
    private final BroadcastReceiver authkeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            View root = linkPane;
            if (CaptureService.ACTION_CAPTURE_UPDATING.equals(intent.getAction())) {
                if (root == null) return;
                setStatusVpn(activity.getString(R.string.vpn_capture_updating), VpnStatus.WARN);
                tools.showToast(root, context.getApplicationContext(),
                        activity.getString(R.string.vpn_capture_updating));
                return;
            }
            if (CaptureService.ACTION_CAPTURE_FAILED.equals(intent.getAction())) {
                if (root == null) return;
                setStatusVpn(activity.getString(R.string.vpn_capture_failed_key), VpnStatus.WARN);
                notifyCaptureIssue(activity.getString(R.string.vpn_capture_failed_key));
                return;
            }
            if (!CaptureService.ACTION_GET_AUTHKEY.equals(intent.getAction())) return;
            String authkey = intent.getStringExtra("authkey");
            if (authkey == null || authkey.isEmpty()) return;
            String channel = intent.getStringExtra("channel");
            if (root == null) return;
            lastCapturedChannel = channel;
            lastCapturedUrl = buildHsrUrl(authkey, channel);
            copyToClipboard(root, context.getApplicationContext(), lastCapturedUrl);
            setStatusVpn(activity.getString(R.string.vpn_authkey_captured, channelLabel(channel)),
                    VpnStatus.SUCCESS);
            renderVpnResult();
            tools.showToast(root, context.getApplicationContext(),
                    activity.getString(R.string.vpn_authkey_captured, channelLabel(channel)));
        }
    };

    /** ADB 广播接收：STATUS 同步副标题；GET_AUTHKEY 命中即复制并常驻结果区。 */
    private final BroadcastReceiver adbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            View root = linkPane;
            if (AdbCaptureService.ACTION_ADB_STATUS.equals(intent.getAction())) {
                if (root == null) return;
                String message = intent.getStringExtra(AdbCaptureService.EXTRA_MESSAGE);
                if (message == null) return;
                setStatusAdb(message, intent.getIntExtra(AdbCaptureService.EXTRA_STATE,
                        LinkScreenKt.VPN_STATUS_DEFAULT));
                return;
            }
            if (!AdbCaptureService.ACTION_ADB_GET_AUTHKEY.equals(intent.getAction())) return;
            String url = intent.getStringExtra(AdbCaptureService.EXTRA_URL);
            if (url == null || url.isEmpty() || root == null) return;
            lastAdbUrl = url;
            copyToClipboard(root, context.getApplicationContext(), url);
            setStatusAdb(activity.getString(R.string.adb_status_captured, adbGameLabel(url)),
                    LinkScreenKt.VPN_STATUS_SUCCESS);
            renderAdbResult();
            tools.showToast(root, context.getApplicationContext(),
                    activity.getString(R.string.adb_status_captured, adbGameLabel(url)));
        }
    };

    /** VPN 行语义状态：着色让用户切回应用后一眼分清运行中/已捕获/异常 */
    private enum VpnStatus {DEFAULT, RUNNING, SUCCESS, WARN}

    /** 最近一次捕获的渠道与完整 URL：常驻结果区的数据源（进程内记忆，重启抓包不丢） */
    private String lastCapturedChannel;
    private String lastCapturedUrl;

    /** 最近一次 ADB 捕获的 URL。 */
    private String lastAdbUrl;
    /** 首次解压 libadb.so 进行中，避免重复点击起多个线程。 */
    private boolean adbPreparing = false;

    /** Compose 版页面宿主：页眉 / 账号行 / 来源列表 / 结果区全部由它绘制。 */
    private LinkScreenView linkPane;

    /**
    * 底栏覆盖高度变化 → 通知 Compose 宿主重算正文底部留白（Compose 侧没有滚动 View，只能把高度作为状态喂给组合）。
    */
    private final Runnable bottomBarListener = () -> {
        if (linkPane != null) linkPane.setBottomClearance(BottomBarOverlay.get());
    };

    /** 按语义状态更新 VPN 行文案与文字色（DEFAULT 恢复默认次要色）。 */
    private void setStatusVpn(CharSequence text, VpnStatus state) {
        if (linkPane == null) return;
        int uiStatus;
        switch (state) {
            case RUNNING:
                uiStatus = LinkScreenKt.VPN_STATUS_RUNNING;
                break;
            case SUCCESS:
                uiStatus = LinkScreenKt.VPN_STATUS_SUCCESS;
                break;
            case WARN:
                uiStatus = LinkScreenKt.VPN_STATUS_WARN;
                break;
            default:
                uiStatus = LinkScreenKt.VPN_STATUS_DEFAULT;
                break;
        }
        linkPane.setVpnStatus(text.toString(), uiStatus);
    }

    /** VPN 抓包结果区：捕获成功后常驻渲染链接行（切页/切后台回来仍在，可重复复制）。 */
    private void renderVpnResult() {
        if (linkPane == null) return;
        if (lastCapturedUrl == null) {
            linkPane.setVpnResult(null, null);
            return;
        }
        linkPane.setVpnResult(lastCapturedChannel == null
                ? activity.getString(R.string.server_cn)
                : channelLabel(lastCapturedChannel), lastCapturedUrl);
    }

    /** 按渠道拼星铁抽卡记录 URL：B服与国服同 host（game_biz 区分），国际服换 hoyoverse host + 英文 */
    private String buildHsrUrl(String authkey, String channel) {
        boolean global = CaptureService.CHANNEL_GLOBAL.equals(channel);
        String base = global ? com.muxiao.Venus.common.Constants.Urls.HSR_GACHA_URL_GLOBAL
                : com.muxiao.Venus.common.Constants.Urls.HSR_GACHA_URL_CN;
        String biz = CaptureService.CHANNEL_BILI.equals(channel) ? "hkrpg_bilibili"
                : global ? "hkrpg_global" : "hkrpg_cn";
        return base + android.net.Uri.encode(authkey)
                + "&game_biz=" + biz + "&page=1&size=20&gacha_type=11&end_id=0";
    }

    private String channelLabel(String channel) {
        if (CaptureService.CHANNEL_BILI.equals(channel)) return activity.getString(R.string.capture_channel_bili);
        if (CaptureService.CHANNEL_GLOBAL.equals(channel)) return activity.getString(R.string.server_os);
        return activity.getString(R.string.server_cn);
    }

    private String currentUserId;
    private UserManager userManager;
    /** 当前服务器可用用户列表，供下拉项与选中下标计算（updateDropdown 维护）。 */
    private List<String> currentUsers;

    // 按 gameType -> userId -> (uid -> link) 存储（0=原神, 1=绝区零）
    private final Map<Integer, Map<String, Map<Integer, String>>> tabResults = new HashMap<>();

    /** 捕获结果广播是否已注册（Activity 经 Class.newInstance 构造时 base Context 尚未挂载，
     *  构造器里不能访问 getApplicationContext——注册延迟到 createView，幂等）。 */
    private boolean receiverRegistered = false;

    public LinkFragment(MainActivity activity) {
        this.activity = activity;
    }

    /** 注入 MainActivity 注册的启动器（VPN 授权 + 电池优化豁免）。 */
    public void setLaunchers(ActivityResultLauncher<android.content.Intent> vpn,
                             ActivityResultLauncher<android.content.Intent> battery) {
        this.vpnLauncher = vpn;
        this.batteryLauncher = battery;
    }

    /** VPN 授权页返回回调（MainActivity 的 launcher 转发）。 */
    public void onVpnPermissionResult(boolean granted) {
        if (granted) {
            CaptureService.start(activity.getApplicationContext());
            setStatusVpn(activity.getString(R.string.vpn_capture_running_hint), VpnStatus.RUNNING);
            if (linkPane != null)
                tools.showCustomSnackbar(linkPane, activity.getApplicationContext(),
                        activity.getString(R.string.vpn_capture_started));
        } else {
            if (linkPane != null)
                tools.showCustomSnackbar(linkPane, activity.getApplicationContext(),
                        activity.getString(R.string.vpn_capture_permission_denied));
        }
    }

    // ============================== ADB 抓取 ==============================

    /** ADB 行点击：运行中则停止，否则先展示图文引导，确认后再进入配对流程。 */
    private void toggleAdbCapture() {
        android.content.Context appContext = activity.getApplicationContext();
        if (AdbCaptureService.running) {
            AdbCaptureService.stop(appContext);
            setStatusAdb(activity.getString(R.string.adb_capture_hint), LinkScreenKt.VPN_STATUS_DEFAULT);
            return;
        }
        AdbGuideDialog.show(activity, () -> startAdbFlow(appContext));
    }

    /** 引导确认后的实际启动流程：确保 adb 就绪 → 检查通知权限 → 进入配对。 */
    private void startAdbFlow(Context appContext) {
        if (adbPreparing) return;
        if (!AdbGachaCapture.isAdbReady(appContext)) {
            adbPreparing = true;
            setStatusAdb(activity.getString(R.string.adb_status_preparing), LinkScreenKt.VPN_STATUS_RUNNING);
            AdbGachaCapture.ensureAdb(appContext,
                    () -> {
                        adbPreparing = false;
                        startAdbPairing(appContext);
                    },
                    () -> {
                        adbPreparing = false;
                        showAdbMissing();
                    });
            return;
        }
        startAdbPairing(appContext);
    }

    private void showAdbMissing() {
        setStatusAdb(activity.getString(R.string.adb_error_missing_binary), LinkScreenKt.VPN_STATUS_WARN);
        notifyCaptureIssue(activity.getString(R.string.adb_error_missing_binary));
    }

    /** 抓包失败：状态行已同步，再补一条不依赖页面上屏的通知。 */
    private void notifyCaptureIssue(String message) {
        new Notification(activity.getApplicationContext())
                .sendForcedErrorNotification(activity.getString(R.string.app_name), message);
    }

    /** 配对码只能经通知输入：Android 13+ 未给通知权限时整个流程不可用，先引导开启。 */
    private void startAdbPairing(Context appContext) {
        Notification notification = new Notification(appContext);
        if (notification.areNotificationsDisabled()) {
            VenusDialogs.confirm(
                    activity,
                    activity.getString(R.string.dialog_need_notification_permission),
                    activity.getString(R.string.msg_need_notification_for_background),
                    activity.getString(R.string.btn_go_to_settings),
                    () -> activity.startActivity(notification.getNotificationSettingsIntent()),
                    activity.getString(R.string.btn_cancel));
            return;
        }
        AdbCaptureService.startPairing(appContext);
        setStatusAdb(activity.getString(R.string.adb_status_pairing), LinkScreenKt.VPN_STATUS_RUNNING);
    }

    /** ADB 行状态（复用 VPN_STATUS_* 四态）。 */
    private void setStatusAdb(CharSequence text, int state) {
        if (linkPane == null) return;
        linkPane.setAdbStatus(text.toString(), state);
    }

    /** ADB 结果区常驻渲染（切页回来仍在，可重复复制）。 */
    private void renderAdbResult() {
        if (linkPane == null) return;
        if (lastAdbUrl == null) {
            linkPane.setAdbResult(null, null);
            return;
        }
        linkPane.setAdbResult(adbGameLabel(lastAdbUrl), lastAdbUrl);
    }

    /** 按 game_biz 标注结果行归属。 */
    private String adbGameLabel(String url) {
        if (url.contains("game_biz=hk4e"))
            return activity.getString(R.string.genshin_impact);
        if (url.contains("game_biz=hkrpg"))
            return activity.getString(R.string.star_rail);
        if (url.contains("game_biz=nap"))
            return activity.getString(R.string.zenless_zone_zero);
        return "ADB";
    }

    /** 构建页面 UI（外壳首次组合时调用一次）：绑定回调、初始化账号下拉与底栏订阅。 */
    public View createView(Context context) {
        if (linkPane != null) return linkPane; // 幂等：外壳重组不会重复装配
        LinkScreenView root = new LinkScreenView(context);
        linkPane = root;

        userManager = new UserManager(context);

        // 注册捕获结果广播（应用内广播，not exported；更新中 + 失败 + 成功三个 action）。
        // 广播接收器与控制器同生命周期：Activity 销毁（含 recreate）时经 release() 注销。
        if (!receiverRegistered) {
            IntentFilter captureFilter = new IntentFilter(CaptureService.ACTION_GET_AUTHKEY);
            captureFilter.addAction(CaptureService.ACTION_CAPTURE_UPDATING);
            captureFilter.addAction(CaptureService.ACTION_CAPTURE_FAILED);
            ContextCompat.registerReceiver(activity.getApplicationContext(), authkeyReceiver,
                    captureFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            IntentFilter adbFilter = new IntentFilter(AdbCaptureService.ACTION_ADB_STATUS);
            adbFilter.addAction(AdbCaptureService.ACTION_ADB_GET_AUTHKEY);
            ContextCompat.registerReceiver(activity.getApplicationContext(), adbReceiver,
                    adbFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }

        // 原神 / 绝区零：点行或刷新图标即获取
        root.setOnSourceClickListener(this::fetchGame);
        root.setOnRefreshClickListener(this::fetchGame);
        // 云游戏获取：打开独立 WebView 页面（导航行）
        root.setOnCloudClickListener(() ->
                activity.startActivity(new Intent(context, CloudGachaActivity.class)));
        // VPN 抓包获取：启动/停止本地 VPN 抓包服务（导航行，点击切换）
        root.setOnVpnClickListener(this::toggleVpnCapture);
        // ADB 抓取：进入配对模式或停止监听
        root.setOnAdbClickListener(this::toggleAdbCapture);
        // 账号下拉选中；无用户时整行进引导（跳转用户管理页）
        root.setOnUserSelectedListener(this::onUserSelected);
        root.setOnEmptyUserClickListener(this::jumpToUserManagement);
        // 结果行的复制按钮：剪贴板与 Snackbar 仍走 Java 侧（锚点为页面根视图）
        root.setOnCopyListener(url ->
                copyToClipboard(linkPane, context.getApplicationContext(), url));

        // 底栏覆盖高度：首帧先取当前值，后续变化经监听器回灌
        root.setBottomClearance(BottomBarOverlay.get());
        BottomBarOverlay.addListener(bottomBarListener);

        updateDropdown();

        return root;
    }

    /** 下拉选中回调：切换用户后按新用户重绘两游戏的行内结果与账号行头像。 */
    private void onUserSelected(int index) {
        if (currentUsers == null || index < 0 || index >= currentUsers.size()) return;
        String selectedUser = currentUsers.get(index);
        userManager.setCurrentUser(selectedUser);
        currentUserId = selectedUser;
        linkPane.setSelectedUserIndex(index);
        renderGameResult(0);
        renderGameResult(1);
        loadUserAvatar();
    }

    /** 无用户空态：整行点击跳到用户管理页。 */
    private void jumpToUserManagement() {
        activity.navigateToPage(1);
    }

    /** VPN 抓包导航行：运行中点击停止（本次未捕获时给出针对性引导）；未运行先过电池优化豁免门控再启动服务。 */
    private void toggleVpnCapture() {
        android.content.Context appContext =
                activity.getApplicationContext();
        View root = linkPane;
        if (CaptureService.running) {
            // 先读后停：stopCapture 会清 capturedChannel，停止后即无从判断本次是否有结果
            boolean captured = CaptureService.capturedChannel != null;
            CaptureService.stop(appContext);
            setStatusVpn(activity.getString(R.string.vpn_capture_hint), VpnStatus.DEFAULT);
            // 本次没抓到时给出区别于普通停止的明确提示（缺键场景另有 CAPTURE_FAILED 广播提示）
            tools.showCustomSnackbar(root, appContext, activity.getString(captured
                    ? R.string.vpn_capture_stopped : R.string.vpn_capture_no_result));
        } else if (!BatteryHelper.isIgnoringBatteryOptimizations(appContext)) {
            // 未豁免电池优化：实验证实厂商省电策略会在熄屏/切后台后掐断抓包网络，
            // 导致游戏断连甚至闪退——先引导豁免（不强制，可「仍然启动」）。
            // 走系统设置列表页让用户手动勾选（无需权限、无 Play 政策风险），
            // 对话框文案需告知操作路径：切「所有应用」→ 找到本应用 → 允许
            VenusDialogs.confirm(
                    activity,
                    activity.getString(R.string.dialog_need_battery_optimization),
                    activity.getString(R.string.msg_capture_battery_hint),
                    activity.getString(R.string.battery_request_positive),
                    () -> {
                        try {
                            batteryLauncher.launch(
                                    BatteryHelper.buildBatteryOptimizationSettingsIntent(appContext));
                        } catch (Exception e) {
                            // 机型不支持该设置页：退回应用详情页，返回后手动再点一次
                            BatteryHelper.openVendorBatterySettings(appContext);
                        }
                    },
                    activity.getString(R.string.battery_start_anyway),
                this::startCaptureAfterChecks);
        } else {
            startCaptureAfterChecks();
        }
    }

    /** 电池优化门控通过后的续启流程：VPN 未授权走系统授权页，授权后启动抓包（MainActivity 的 launcher 转发入口）。 */
    public void startCaptureAfterChecks() {
        android.content.Context appContext = activity.getApplicationContext();
        android.content.Intent prepare = VpnService.prepare(appContext);
        if (prepare == null) {
            CaptureService.start(appContext);
            setStatusVpn(activity.getString(R.string.vpn_capture_running_hint), VpnStatus.RUNNING);
            if (linkPane != null)
                tools.showCustomSnackbar(linkPane, appContext, activity.getString(R.string.vpn_capture_started));
        } else {
            vpnLauncher.launch(prepare);
        }
    }

    /** 按 gameType（0=原神, 1=绝区零）拉取抽卡链接：成功行内展示结果，失败则在同一行内就地报错。 */
    private void fetchGame(int gameType) {
        if (currentUserId == null || currentUserId.isEmpty()) {
            showInlineError(gameType, activity.getString(R.string.msg_select_user_first));
            return;
        }
        // 重绘结果区，顺带清掉上一次的行内错误；再进入获取态
        renderGameResult(gameType);
        setChipState(gameType, ChipState.FETCHING);

        // 预先在 UI 线程取出上下文与用户快照：后台线程只使用不可变引用
        final android.content.Context appContext = activity.getApplicationContext();
        final String userId = currentUserId;
        com.muxiao.Venus.common.AppExecutors.get().execute(() -> {
            Map<Integer, String> result;
            GachaLink gachaLink = new GachaLink(appContext, userId);
            try {
                result = gameType == 0 ? gachaLink.genshin() : gachaLink.zzz();
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    showInlineError(gameType, tools.describeError(e));
                    setChipState(gameType, ChipState.PENDING);
                });
                return;
            }
            final Map<Integer, String> fetched = result;
            activity.runOnUiThread(() -> {
                // computeIfAbsent 需 API 24，这里手动 get + put（调用处在 UI 线程，无并发问题）
                Map<String, Map<Integer, String>> perUser = tabResults.get(gameType);
                if (perUser == null) {
                    perUser = new HashMap<>();
                    tabResults.put(gameType, perUser);
                }
                perUser.put(userId, new HashMap<>(fetched));
                View root = linkPane;
                if (root == null) return;
                if (fetched.isEmpty()) {
                    // 无角色 / 未取到链接：直接把本行结果区替换成错误提示
                    renderGameResult(gameType);
                    showInlineError(gameType, activity.getString(R.string.msg_no_game_role_or_error));
                    setChipState(gameType, ChipState.PENDING);
                } else {
                    renderGameResult(gameType);
                    copyToClipboard(root, appContext, fetched.values().iterator().next());
                    showCustomSnackbar(root, appContext, activity.getString(R.string.snack_link_copied));
                }
            });
        });
    }

    /** 行内渲染某游戏的全部链接（uid 分组，每条带复制按钮）。 */
    private void renderGameResult(int gameType) {
        if (linkPane == null) return;
        Map<String, Map<Integer, String>> perUser = tabResults.get(gameType);
        Map<Integer, String> links = perUser == null ? null : perUser.get(currentUserId);

        hideInlineError(gameType);
        if (links == null || links.isEmpty()) {
            linkPane.setSourceResult(gameType, Collections.emptyList());
            setChipState(gameType, ChipState.PENDING);
            return;
        }
        List<LinkRow> rows = new ArrayList<>(links.size());
        for (Map.Entry<Integer, String> e : links.entrySet())
            rows.add(new LinkRow(activity.getString(R.string.gacha_uid_label, e.getKey()), e.getValue()));
        linkPane.setSourceResult(gameType, rows);
        setChipState(gameType, ChipState.FETCHED);
    }

    /** 来源行状态（对齐设计稿 chip 语义：待获取/获取中 = 中性 chip，已获取 = 语义成功 chip）。 */
    private enum ChipState {PENDING, FETCHING, FETCHED}

    /**
    * 同步某游戏行的状态：这里只下发语义状态，视觉（chip 文案/配色/图标圆/旋转/淡入）全部由 Compose 依状态派生，
    * 术语与 [LinkScreenKt] 的 LINK_CHIP_* 常量一一对应。
    */
    private void setChipState(int gameType, ChipState state) {
        if (linkPane == null) return;
        int uiState;
        switch (state) {
            case FETCHING:
                uiState = LinkScreenKt.LINK_CHIP_FETCHING;
                break;
            case FETCHED:
                uiState = LinkScreenKt.LINK_CHIP_FETCHED;
                break;
            default:
                uiState = LinkScreenKt.LINK_CHIP_PENDING;
                break;
        }
        linkPane.setSourceState(gameType, uiState);
    }

    /**
    * 就地错误：错误文案写进该来源行的错误位并收起结果行——错误「顶替」结果的位置出现，不在页面下方另起独立提示卡，
    * 两个来源互不干扰。
    */
    private void showInlineError(int gameType, String message) {
        if (linkPane == null) return;
        linkPane.setSourceResult(gameType, Collections.emptyList());
        linkPane.setSourceError(gameType, message != null && !message.isEmpty()
                ? message
                : activity.getString(R.string.msg_no_game_role_or_error));
    }

    /** 收起某来源行的就地错误（重绘结果时调用）。 */
    private void hideInlineError(int gameType) {
        if (linkPane != null) linkPane.setSourceError(gameType, null);
    }

    /**
    * 按当前服务器类型填充用户列表并选定当前/首个用户；无用户则清空选择。
    * 行内展示带服务器后缀（如「t [国服]」）由 Compose 依 oversea 标志拼接，下拉展开项仍是原始用户名。
    */
    private void updateDropdown() {
        // 跨服全量，展示文案逐账号带服务器后缀
        List<String> usernames = userManager.getAllUsernames();
        currentUsers = usernames;
        List<String> labels = new ArrayList<>(usernames.size());
        for (String username : usernames) {
            boolean oversea = userManager.isOversea(username);
            labels.add(userManager.displayName(username) + " [" + activity.getString(
                    oversea ? R.string.server_os : R.string.server_cn) + "]");
        }
        int selectedIndex = -1;
        String currentUser = userManager.getCurrentUser();
        if (currentUser != null && !currentUser.isEmpty() && usernames.contains(currentUser)) {
            currentUserId = currentUser;
            selectedIndex = usernames.indexOf(currentUser);
        } else if (!usernames.isEmpty()) {
            // 如果没有设置当前用户但有用户存在，默认选择第一个
            String firstUser = usernames.get(0);
            userManager.setCurrentUser(firstUser);
            currentUserId = firstUser;
            selectedIndex = 0;
        } else {
            // 没有任何账号，清空选择（Compose 侧自动切到「前往添加」引导）
            currentUserId = null;
        }
        // 后缀兜底标记按选中账号自己的服别：labels 逐账号已带后缀，仅在缺失时生效，
        // 不再读全局 is_oversea（跨服全量列表里全局值对单个账号没有意义）
        boolean oversea = userManager.isOversea(currentUserId != null ? currentUserId : "");
        if (linkPane != null) linkPane.setUsers(usernames, labels, selectedIndex, oversea);
        // 账号行头像随当前用户刷新（复用账号体系自定义头像，见 AvatarStore）
        loadUserAvatar();
    }

    /** 账号行头像：有自定义图则圆形加载，否则恢复默认人像图标（与主页账号行同一套）。 */
    private void loadUserAvatar() {
        if (linkPane != null) linkPane.setAvatarUsername(currentUserId);
    }

    /**
    * 头像文件被替换/重置后强制重载：头像按用户名定址、换头像时原地覆盖文件，用户名与路径都没变，
    * 只调 {@link #loadUserAvatar()} 不会触发重组，Glide 也会命中旧缓存。
    */
    public void refreshAvatar() {
        if (linkPane != null) linkPane.refreshAvatar();
    }

    /** 页面成为当前选中页 / Activity 恢复：更新下拉框并同步 VPN 行状态与常驻结果区。 */
    @Override
    public void show() {
        updateDropdown();
        // 同步 VPN 抓包行状态（服务可能在其他界面被停止；已捕获时回显最近结果）
        if (linkPane != null) {
            if (CaptureService.capturedChannel != null) {
                // 成功反馈发生在游戏后台、Snackbar 早已消失，切回应用时靠这行确认已复制
                setStatusVpn(activity.getString(R.string.vpn_authkey_captured,
                        channelLabel(CaptureService.capturedChannel)), VpnStatus.SUCCESS);
            } else if (CaptureService.running) {
                setStatusVpn(activity.getString(R.string.vpn_capture_running_hint), VpnStatus.RUNNING);
            } else {
                setStatusVpn(activity.getString(R.string.vpn_capture_hint), VpnStatus.DEFAULT);
            }
            // 恢复常驻结果区：捕获过的链接切页/切后台回来仍在，可随时重复复制
            renderVpnResult();
            // ADB 结果同样常驻回显
            renderAdbResult();
            renderGameResult(0);
            renderGameResult(1);
        }
    }

    /** 页面失去选中 / Activity 暂停（无逐帧资源，无操作）。 */
    @Override
    public void hide() {
    }

    /** 销毁：解除底栏订阅与捕获结果广播（Activity 销毁时由 MainActivity 调用）。 */
    public void release() {
        BottomBarOverlay.removeListener(bottomBarListener);
        if (receiverRegistered) {
            activity.getApplicationContext().unregisterReceiver(authkeyReceiver);
            activity.getApplicationContext().unregisterReceiver(adbReceiver);
            receiverRegistered = false;
        }
        linkPane = null;
    }
}
